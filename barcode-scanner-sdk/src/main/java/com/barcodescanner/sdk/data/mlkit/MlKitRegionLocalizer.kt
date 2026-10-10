package com.barcodescanner.sdk.data.mlkit

import android.graphics.Point
import android.graphics.Rect
import com.barcodescanner.sdk.domain.model.ScanFrame
import com.google.android.gms.tasks.CancellationTokenSource
import com.google.mlkit.vision.barcode.BarcodeScanner
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean

/**
 * POC: ML Kit as a *localizer* for the custom MSI decoder — not as an MSI decoder.
 *
 * MSI/Plessey is not in ML Kit's supported-format list, so [Barcode.rawValue] is
 * expected to be null for MSI symbols. What ML Kit CAN still return (bundled
 * model >= 17.1.0, unbundled >= 18.2.0, via
 * [BarcodeScannerOptions.Builder.enableAllPotentialBarcodes]) is a candidate
 * region — [Barcode.boundingBox] plus [Barcode.cornerPoints] — for anything that
 * looks enough like a 1D barcode (MSI often resembles ITF/Code 39 to the model).
 *
 * Intended follow-up (see `MsiRegionCropper`): pad the region for quiet zone,
 * deskew via the corner quad, then run [com.barcodescanner.sdk.data.msi.MsiNativeDecoder]
 * on the rectified strip and confirm with a checksum + multi-frame stability gate.
 *
 * Best-effort contract (do NOT rely on this alone):
 * - the model is trained on supported families, not MSI: dense/short/low-contrast
 *   MSI can miss, and `enableAllPotentialBarcodes` can also return noise boxes;
 * - coordinates are in [InputImage] space, which here is [ScanFrame.bitmap]
 *   space (the image is built from the frame bitmap): always valid for
 *   cropping, on every orientation candidate. They are NOT valid for host
 *   overlays without mapping — consumers must not draw them directly;
 * - callers must fall back to full-frame MSI decode when no region validates,
 *   and should require a stable read across frames before accepting an ROI hit.
 *
 * 1D-only format mask: MSI is a linear symbology, so only linear formats are
 * requested. This narrows the candidate pool (fewer QR/Aztec boxes to sift) while
 * keeping every family MSI can masquerade as.
 */
class MlKitRegionLocalizer(
    private val clientProvider: (BarcodeScannerOptions) -> BarcodeScanner = {
        BarcodeScanning.getClient(it)
    },
    /** Seam for unit tests: ML Kit's InputImage needs an initialized MlKitContext. */
    private val imageProvider: (android.graphics.Bitmap, Int) -> InputImage =
        { bitmap, rotation -> InputImage.fromBitmap(bitmap, rotation) },
) {
    /**
     * Candidate barcode region. [decoded] is false for the MSI path of interest
     * (rawValue null); [format] is [Barcode.FORMAT_UNKNOWN] or a nearby-family
     * guess in that case — useful for logging, never for symbology routing.
     */
    data class BarcodeRegion(
        val boundingBox: Rect?,
        val cornerPoints: List<Point>?,
        val format: Int,
        val decoded: Boolean,
    )

    private val options: BarcodeScannerOptions by lazy {
        BarcodeScannerOptions.Builder()
            .setBarcodeFormats(
                Barcode.FORMAT_CODABAR,
                Barcode.FORMAT_CODE_39,
                Barcode.FORMAT_CODE_93,
                Barcode.FORMAT_CODE_128,
                Barcode.FORMAT_EAN_8,
                Barcode.FORMAT_EAN_13,
                Barcode.FORMAT_ITF,
                Barcode.FORMAT_UPC_A,
                Barcode.FORMAT_UPC_E,
            )
            .enableAllPotentialBarcodes()
            .build()
    }

    @Volatile
    private var clientRef: BarcodeScanner? = null
    private val clientLock = Any()
    private val closed = AtomicBoolean(false)

    private fun client(): BarcodeScanner {
        if (closed.get()) throw IllegalStateException("MlKitRegionLocalizer is closed")
        synchronized(clientLock) {
            if (closed.get()) throw IllegalStateException("MlKitRegionLocalizer is closed")
            return clientRef ?: clientProvider(options).also { clientRef = it }
        }
    }

    /**
     * Returns candidate regions, INCLUDING undecoded ones (the MSI case).
     * Empty list = no candidate (caller falls back to full-frame MSI decode).
     */
    suspend fun localize(frame: ScanFrame): List<BarcodeRegion> =
        withContext(Dispatchers.Default) {
            if (closed.get()) return@withContext emptyList()
            try {
                val image = imageProvider(frame.bitmap, frame.effectiveRotation)
                val cts = CancellationTokenSource()
                val barcodes: List<Barcode> = try {
                    client().process(image).await(cts)
                } finally {
                    runCatching { cts.cancel() }
                }
                // NOTE: no attemptRotation filtering (unlike MLKitDecoder): boxes
                // are consumed by cropping frame.bitmap, which shares InputImage
                // space on every candidate. Only overlay use would need mapping.
                return@withContext barcodes.mapNotNull { b ->
                    val box = b.boundingBox ?: return@mapNotNull null
                    val region = BarcodeRegion(
                        boundingBox = Rect(box),
                        cornerPoints = b.cornerPoints?.toList(),
                        format = b.format,
                        // Empty string counts as undecoded: potential barcodes
                        // arrive with null OR "" values (observed: format=-1
                        // with "" on every real MSI strip).
                        decoded = !b.rawValue.isNullOrEmpty(),
                    )
                    // Forensics: which formats ML Kit reports on MSI labels (it
                    // decodes the strips as SOMETHING with a value, but in a
                    // format outside the supported map — that is why MLKitDecoder
                    // misses the same frames). Decoded-ness never gates ROI:
                    // a misclassified MSI strip is still our best crop.
                    // NOTE: never log rawValue here — this fires per region per
                    // frame on the live path and values are host PII.
                    android.util.Log.d(
                        TAG,
                        "region box=$box decoded=${region.decoded} " +
                            "format=${region.format}",
                    )
                    region
                }
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                android.util.Log.d(TAG, "localize missed: ${t.javaClass.simpleName}")
                emptyList()
            }
        }

    fun close() {
        if (!closed.compareAndSet(false, true)) return
        synchronized(clientLock) {
            runCatching { clientRef?.close() }
            clientRef = null
        }
    }

    companion object {
        const val TAG = "MlKitRegionLocalizer"
    }
}
