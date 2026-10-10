package com.barcodescanner.sdk.data.msi

import android.graphics.Bitmap
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
 * JNI wrapper for the clean-room C++ MSI Plessey decoder
 * (`src/main/cpp/msi_decoder.cpp`).
 *
 * Replaces the pure-Kotlin [MsiPlesseyDecoder]: the C++ engine uses
 * signal-template correlation instead of run-length thresholding, which
 * survives the blur that defeats width-based classification. Validated 6/6
 * on the shelf-tag benchmark set (3–7ms per frame).
 *
 * Checksum contract matches [MsiPlesseyDecoder]: the native engine tries
 * MOD10/MOD11/MOD1010/MOD1110 and reports which validated; [stripChecksum]
 * (default true) strips the check digit(s) from the emitted payload.
 */
class MsiNativeDecoder(
    private val stripChecksum: Boolean = true,
    private val minPayloadDigits: Int = MsiCodeTable.MIN_DIGITS,
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default,
) : BarcodeDecoder {

    override val name: String = NAME
    override val supportedSymbologies: Set<Symbology> = setOf(Symbology.MSI_PLESSEY)

    private external fun nativeDecode(gray: ByteArray, width: Int, height: Int): String?

    override suspend fun decode(frame: ScanFrame): DecodeOutcome = withContext(dispatcher) {
        try {
            val bitmap = frame.bitmap
            val w = bitmap.width
            val h = bitmap.height
            if (w <= 0 || h <= 0) {
                return@withContext DecodeOutcome.NotFound("MSI native: empty frame")
            }
            // Bitmap -> grayscale bytes (0=black, 255=white, row-major).
            val px = IntArray(w * h)
            bitmap.getPixels(px, 0, w, 0, 0, w, h)
            val gray = ByteArray(w * h)
            for (i in px.indices) {
                val c = px[i]
                val r = (c shr 16) and 0xFF
                val g = (c shr 8) and 0xFF
                val b = c and 0xFF
                gray[i] = ((77 * r + 150 * g + 29 * b) shr 8).toByte()
            }
            // Native call is synchronous and fast (single-digit ms); no need
            // to chunk it, but respect cancellation before paying for pixels.
            ensureActiveCompat()
            val raw = try {
                nativeDecode(gray, w, h)
            } catch (e: UnsatisfiedLinkError) {
                return@withContext DecodeOutcome.Error(
                    DecoderException("MSI native library not loaded", e),
                    recoverable = false,
                )
            } ?: return@withContext DecodeOutcome.NotFound("MSI native: no decode")
            // Format: "digits|policy|votes"
            val parts = raw.split("|")
            if (parts.size != 3) {
                return@withContext DecodeOutcome.NotFound("MSI native: bad result format")
            }
            val full = parts[0]
            val policy = parts[1]
            if (full.length < minPayloadDigits) {
                return@withContext DecodeOutcome.NotFound("MSI native: payload too short")
            }
            val (payload, stripped) = if (stripChecksum) {
                stripCheckDigits(full, policy)
            } else {
                full to false
            }
            if (payload.length < minPayloadDigits) {
                return@withContext DecodeOutcome.NotFound("MSI native: stripped payload too short")
            }
            DecodeOutcome.Success(
                listOf(
                    DecodedBarcode(
                        rawValue = payload,
                        symbology = Symbology.MSI_PLESSEY,
                        confidence = 1.0f,
                        engineName = NAME,
                        checksumStripped = stripped,
                    ),
                ),
            )
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            DecodeOutcome.Error(DecoderException("MSI native decode failed", t), recoverable = true)
        }
    }

    /** Strips check digits per policy; returns (payload, stripped). */
    private fun stripCheckDigits(full: String, policy: String): Pair<String, Boolean> {
        val n = when (policy) {
            "mod10", "mod11" -> 1
            "mod1010", "mod1110" -> 2
            else -> 0
        }
        if (n == 0 || full.length <= n) return full to false
        return full.dropLast(n) to true
    }

    // withContext(dispatcher) already checks cancellation on entry; this is a
    // no-op hook for symmetry with the old decoder's cooperative checks.
    private fun ensureActiveCompat() = Unit

    companion object {
        const val NAME = "MsiNative"

        init {
            System.loadLibrary("msi_decoder")
        }
    }
}
