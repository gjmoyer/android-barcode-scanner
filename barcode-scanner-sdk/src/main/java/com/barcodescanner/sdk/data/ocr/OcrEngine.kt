package com.barcodescanner.sdk.data.ocr

import android.graphics.Bitmap
import android.graphics.Rect
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean

/**
 * One OCR text line with its bounding box in the source bitmap coordinates
 * (null box = unknown location; line is then ineligible for proximity scoring
 * but still usable by checksum/length rules).
 */
data class OcrLine(val text: String, val box: Rect?)

/**
 * Text-recognition seam. ML Kit cannot run in JVM unit tests, so the decoder
 * depends on this interface: production uses [MlKitOcrEngine], tests inject a
 * fake returning canned lines (the decoder logic — band anchoring, digit
 * filtering, checksum gating — is fully unit-testable without the model).
 */
interface OcrEngine {
    suspend fun recognize(bitmap: Bitmap, rotationDegrees: Int): List<OcrLine>
    fun close()
}

/**
 * Bundled Latin-script ML Kit text recognition (offline, no Play Services
 * dependency — same distribution choice as the barcode model). Thread-safe and
 * re-entrant; the underlying client is created lazily and shared.
 */
class MlKitOcrEngine : OcrEngine {
    private val lock = Any()
    private var client: com.google.mlkit.vision.text.TextRecognizer? = null
    private val closed = AtomicBoolean(false)

    private fun client(): com.google.mlkit.vision.text.TextRecognizer {
        if (closed.get()) throw IllegalStateException("MlKitOcrEngine is closed")
        synchronized(lock) {
            if (closed.get()) throw IllegalStateException("MlKitOcrEngine is closed")
            return client ?: TextRecognition
                .getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
                .also { client = it }
        }
    }

    override suspend fun recognize(bitmap: Bitmap, rotationDegrees: Int): List<OcrLine> =
        withContext(Dispatchers.Default) {
            try {
                val image = InputImage.fromBitmap(bitmap, rotationDegrees)
                val text = client().process(image).await()
                val out = mutableListOf<OcrLine>()
                for (block in text.textBlocks) {
                    for (line in block.lines) {
                        val t = line.text
                        if (t.isNotBlank()) {
                            out += OcrLine(
                                text = t,
                                box = line.boundingBox?.let { Rect(it) },
                            )
                        }
                    }
                }
                out
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                // Model missing/corrupt on device: behave as "no text", never crash a scan.
                emptyList()
            }
        }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        synchronized(lock) {
            runCatching { client?.close() }
            client = null
        }
    }
}
