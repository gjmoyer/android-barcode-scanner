package com.barcodescanner.sdk.data.msi

import com.barcodescanner.sdk.data.mlkit.MlKitRegionLocalizer
import com.barcodescanner.sdk.domain.decoder.BarcodeDecoder
import com.barcodescanner.sdk.domain.decoder.DecodeOutcome
import com.barcodescanner.sdk.domain.decoder.DecoderException
import com.barcodescanner.sdk.domain.model.DecodedBarcode
import com.barcodescanner.sdk.domain.model.ScanFrame
import com.barcodescanner.sdk.domain.model.Symbology
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * MSI-via-ROI decoder: ML Kit isolates, our Plessey decoder decodes.
 *
 * Split pipeline: [MlKitRegionLocalizer] finds candidate 1D regions (ML Kit
 * cannot decode MSI — it is not a supported format — but
 * `enableAllPotentialBarcodes()` still returns boxes for barcode-looking
 * strips), [MsiRegionCropper] pads + deskews each strip, and the shared
 * [MsiPlesseyDecoder] decodes the rectified crop. A miss here is cheap and
 * explicit ([DecodeOutcome.NotFound]) so fusion falls through to full-frame MSI.
 *
 * Routing notes:
 * - [resolvesOrientationInternally] is true: deskew handles arbitrary tilt, so a
 *   physically rotated candidate is duplicate work — fusion feeds only the
 *   primary candidate. Crop coordinates are bitmap space on every candidate.
 * - already-decoded regions are attempted ANYWAY: ML Kit classifies MSI strips
 *   as a nearby 1D family (or unknown) with a value MLKitDecoder must drop, so
 *   "decoded" usually means misclassified-MSI, not a competing symbology. The
 *   MSI checksum + ≥2-vote gate rejects true foreign strips, making the attempt
 *   safe; skipping them starved ROI on every real shelf label observed.
 * - regions are largest-first, capped at [MAX_REGIONS]; tiny boxes that cannot
 *   hold modules are skipped. Every skip/hit is logged under [TAG] so a device
 *   run (`adb logcat -s MsiRoi`) reports the localizer hit-rate directly.
 *
 * Confidence is 1.0 like plain MSI: the inner decoder only emits checksum-gated
 * (≥2 agreeing observations) hits; this wrapper only retags the engine name so
 * the sample UI can show which path won.
 */
class MsiRegionAssistDecoder(
    private val localizer: MlKitRegionLocalizer,
    private val msi: BarcodeDecoder,
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default,
) : BarcodeDecoder {

    override val name: String = NAME
    override val supportedSymbologies: Set<Symbology> = setOf(Symbology.MSI_PLESSEY)
    override val resolvesOrientationInternally: Boolean = true

    override suspend fun decode(frame: ScanFrame): DecodeOutcome = withContext(dispatcher) {
        try {
            val t0 = System.nanoTime()
            val regions = localizer.localize(frame)
            val locMs = (System.nanoTime() - t0) / 1_000_000
            if (regions.isEmpty()) {
                android.util.Log.d(TAG, "localize regions=0 locMs=${locMs} -> NotFound")
                return@withContext DecodeOutcome.NotFound("MSI-ROI: no candidate regions")
            }
            val candidates = regions
                .filter { (it.boundingBox?.width() ?: 0) >= MIN_BOX_W }
                .filter { (it.boundingBox?.height() ?: 0) >= MIN_BOX_H }
                .sortedByDescending { (it.boundingBox?.width() ?: 0) * (it.boundingBox?.height() ?: 0) }
                .take(MAX_REGIONS)
            android.util.Log.d(
                TAG,
                "localize regions=${regions.size} usable=${candidates.size} " +
                    "decoded=${regions.count { it.decoded }} locMs=${locMs}",
            )
            if (candidates.isEmpty()) {
                return@withContext DecodeOutcome.NotFound("MSI-ROI: no usable regions")
            }
            val hits = mutableListOf<DecodedBarcode>()
            for ((index, region) in candidates.withIndex()) {
                val box = region.boundingBox ?: continue
                val tCrop = System.nanoTime()
                val crop = MsiRegionCropper.cropAndDeskew(frame.bitmap, box, region.cornerPoints)
                if (crop == null) {
                    android.util.Log.d(TAG, "region#$index box=$box crop=null")
                    continue
                }
                try {
                    val cropFrame = frame.copy(bitmap = crop.bitmap)
                    when (val outcome = msi.decode(cropFrame)) {
                        is DecodeOutcome.Success -> {
                            val ms = (System.nanoTime() - tCrop) / 1_000_000
                            for (b in outcome.barcodes) {
                                android.util.Log.d(
                                    TAG,
                                    "region#$index HIT value=${b.rawValue} deskew=${crop.angleApplied} " +
                                        "cropMs=${ms} box=$box",
                                )
                                hits += b.copy(engineName = NAME)
                            }
                        }
                        is DecodeOutcome.NotFound ->
                            android.util.Log.d(
                                TAG,
                                "region#$index miss deskew=${crop.angleApplied} box=$box",
                            )
                        is DecodeOutcome.Error ->
                            android.util.Log.d(
                                TAG,
                                "region#$index error(recoverable=${outcome.recoverable})",
                            )
                    }
                } finally {
                    runCatching { crop.bitmap.recycle() }
                }
                if (hits.isNotEmpty()) break
            }
            if (hits.isNotEmpty()) {
                return@withContext DecodeOutcome.Success(hits)
            }
            return@withContext DecodeOutcome.NotFound("MSI-ROI: no region decoded")
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            DecodeOutcome.Error(DecoderException("MSI-ROI assist failed", t), recoverable = true)
        }
    }

    override fun close() {
        runCatching { localizer.close() }
    }

    companion object {
        const val NAME = "MsiRoi"
        /** Logcat tag for per-region forensics; filter with `adb logcat -s MsiRoi`. */
        const val TAG = "MsiRoi"
        /** Largest-first cap: bounds added ML Kit + crop + MSI latency per frame. */
        const val MAX_REGIONS = 3
        /** Boxes below this cannot hold MSI modules at any scale; skip early. */
        const val MIN_BOX_W = 64
        const val MIN_BOX_H = 16
    }
}
