package com.barcodescanner.sdk.data.mlkit

import android.graphics.Bitmap
import com.barcodescanner.sdk.domain.decoder.BarcodeDecoder
import com.barcodescanner.sdk.domain.decoder.DecodeOutcome
import com.barcodescanner.sdk.domain.decoder.DecoderException
import com.barcodescanner.sdk.domain.model.DecodedBarcode
import com.barcodescanner.sdk.domain.model.ScanFrame
import com.barcodescanner.sdk.domain.model.Symbology
import com.google.mlkit.vision.barcode.BarcodeScanner
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.common.InputImage
import com.google.android.gms.tasks.CancellationTokenSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Primary decoder backed by Google ML Kit.
 *
 * Handles all [Symbology.mlKitNatives]. DataBar/MSI are never attempted here —
 * [supportedSymbologies] excludes them so fusion routes those frames straight
 * to fallback engines.
 *
 * Rotation: [ScanFrame.bitmap] for orientation candidates is ALREADY physically
 * rotated and [ScanFrame.effectiveRotation] compensates (sensor minus attempt),
 * so the hint passed here is correct for both base and rotated frames.
 * Bounding boxes from rotated candidates are nulled (they would otherwise be
 * reported in pre-rotated coordinates and mislead host overlays).
 *
 * Threading: ML Kit clients are thread-safe for `process()`; this decoder is
 * re-entrant. Decode runs on [Dispatchers.Default] so callers never block Main.
 */
class MLKitDecoder(
    enabledSymbologies: Set<Symbology>,
    private val clientProvider: (BarcodeScannerOptions) -> BarcodeScanner = {
        BarcodeScanning.getClient(it)
    },
    /** Seam for unit tests: ML Kit's InputImage needs an initialized MlKitContext. */
    private val imageProvider: (Bitmap, Int) -> InputImage = { bitmap, rotation ->
        InputImage.fromBitmap(bitmap, rotation)
    },
) : BarcodeDecoder {

    override val name: String = NAME

    /** ML Kit normalizes the view via the rotation hint; any candidate is the same image. */
    override val resolvesOrientationInternally: Boolean = true

    override val supportedSymbologies: Set<Symbology> =
        enabledSymbologies.intersect(Symbology.mlKitNatives)

    private val options: BarcodeScannerOptions by lazy {
        val format = MlKitSymbologyMapper.optionsFormat(supportedSymbologies)
        if (format != null) {
            BarcodeScannerOptions.Builder().setBarcodeFormats(format).build()
        } else {
            BarcodeScannerOptions.Builder().build()
        }
    }

    @Volatile
    private var clientRef: BarcodeScanner? = null
    private val clientLock = Any()
    private val closed = AtomicBoolean(false)

    private fun client(): BarcodeScanner {
        if (closed.get()) throw DecoderException("MLKitDecoder is closed")
        synchronized(clientLock) {
            if (closed.get()) throw DecoderException("MLKitDecoder is closed")
            return clientRef ?: clientProvider(options).also { clientRef = it }
        }
    }

    override suspend fun decode(frame: ScanFrame): DecodeOutcome = withContext(Dispatchers.Default) {
        if (closed.get()) {
            return@withContext DecodeOutcome.Error(DecoderException("MLKitDecoder is closed"), false)
        }
        if (supportedSymbologies.isEmpty()) {
            return@withContext DecodeOutcome.NotFound("no ML Kit symbologies enabled")
        }
        try {
            val image = imageProvider(frame.bitmap, frame.effectiveRotation)
            // CancellationTokenSource rides coroutine cancellation into the ML Kit
            // Task (plain await() leaves the Task running after timeout). Cancelling
            // the token in `finally` guarantees a timed-out frame never leaks
            // inference that keeps reading the bitmap fusion is trying to recycle.
            val cts = CancellationTokenSource()
            val barcodes: List<com.google.mlkit.vision.barcode.common.Barcode> = try {
                client().process(image).await(cts)
            } finally {
                runCatching { cts.cancel() }
            }
            if (barcodes.isEmpty()) {
                DecodeOutcome.NotFound("ML Kit found no barcode")
            } else {
                val mapped = barcodes.mapNotNull { b ->
                    // Blank counts as missing: potential/undecoded barcodes carry
                    // null OR "" (an "" with a mapped format would otherwise hit
                    // DecodedBarcode's non-empty require and turn a filtered-out
                    // miss into an Error outcome).
                    val raw = b.rawValue?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
                    val symbology = MlKitSymbologyMapper.fromMlKit(b.format)
                    if (symbology !in supportedSymbologies) return@mapNotNull null
                    // Rotated candidates: box coords are in pre-rotated space -> null them
                    // rather than reporting misleading overlays.
                    val rotated = frame.attemptRotation != 0
                    // Copy the box: ML Kit reuses the Rect object and Rect is
                    // mutable, so storing the reference would alias engine
                    // state into an immutable result (also lets hosts mutate
                    // our cached box through the result).
                    val box = if (rotated) null else b.boundingBox?.let { android.graphics.Rect(it) }
                    DecodedBarcode(
                        rawValue = raw,
                        symbology = symbology,
                        // ML Kit gives no per-barcode confidence; fixed 0.95 documents
                        // the fusion coupling (minConfidence > 0.95 disables ML Kit).
                        confidence = 0.95f,
                        boundingBox = box,
                        cornerPoints = if (rotated) null else b.cornerPoints?.toList(),
                        engineName = NAME,
                        isUpsideDown = frame.isUpsideDownCandidate,
                    )
                }
                if (mapped.isEmpty()) DecodeOutcome.NotFound("ML Kit results filtered out")
                else DecodeOutcome.Success(mapped)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: DecoderException) {
            DecodeOutcome.Error(e, recoverable = false)
        } catch (t: Throwable) {
            DecodeOutcome.Error(DecoderException("ML Kit decode failed", t), recoverable = true)
        }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        synchronized(clientLock) {
            runCatching { clientRef?.close() }
            clientRef = null
        }
    }

    companion object {
        const val NAME = "MLKit"
    }
}
