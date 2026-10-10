package com.barcodescanner.sdk.data.msi

import android.graphics.Bitmap
import com.barcodescanner.sdk.domain.decoder.BarcodeDecoder
import com.barcodescanner.sdk.domain.decoder.DecodeOutcome
import com.barcodescanner.sdk.domain.decoder.DecoderException
import com.barcodescanner.sdk.domain.model.DecodedBarcode
import com.barcodescanner.sdk.domain.model.ScanFrame
import com.barcodescanner.sdk.domain.model.ScannerConfig
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
    /**
     * Checksum scheme the host requires (wired from
     * [ScannerConfig.msiChecksumPolicy] by [ScannerContainer][com.barcodescanner.sdk.di.ScannerContainer]).
     * The native engine detects digit strings under whichever scheme
     * validates; the reported digits are then re-validated strictly under
     * this policy via [MsiChecksumValidator] — the exact contract the
     * pre-native Kotlin decoder enforced. Revalidation (not the reported
     * scheme name) decides: every Mod1010/Mod1110 codeword also validates as
     * Mod10 by construction, so name-matching would mis-route double-check
     * labels. NONE accepts anything.
     */
    private val checksumPolicy: ScannerConfig.MsiChecksumPolicy =
        ScannerConfig.MsiChecksumPolicy.MOD_10,
    /**
     * Consecutive-frame agreement required before emitting. Live camera
     * produces transient false positives (motion blur phantoms that pass
     * checksum by luck); requiring the same value twice kills them.
     * Set to 1 to disable (single-frame emit, as in static-image tests).
     *
     * Each decoder instance owns its [MsiVoteGate]: never share one instance
     * across decode paths (ROI crops vs full-frame) — a miss on one path
     * must not reset the count the other path is building.
     */
    requireConsecutiveFrames: Int = 2,
) : BarcodeDecoder {

    override val name: String = NAME
    override val supportedSymbologies: Set<Symbology> = setOf(Symbology.MSI_PLESSEY)

    private external fun nativeDecode(gray: ByteArray, width: Int, height: Int): String?

    // Single-path temporal voting state (see [MsiVoteGate]; synchronized).
    private val voteGate = MsiVoteGate(requireConsecutiveFrames)

    override suspend fun decode(frame: ScanFrame): DecodeOutcome = withContext(dispatcher) {
        try {
            if (!nativeAvailable) {
                return@withContext DecodeOutcome.NotFound("MSI native library not loaded")
            }
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
            } ?: run {
                // Native miss: reset temporal voting.
                voteGate.reset()
                return@withContext DecodeOutcome.NotFound("MSI native: no decode")
            }
            // Format: "digits|policy|votes"
            val parts = raw.split("|")
            if (parts.size != 3) {
                voteGate.reset()
                return@withContext DecodeOutcome.NotFound("MSI native: bad result format")
            }
            val full = parts[0]
            val nativePolicy = parts[1]
            if (full.length < minPayloadDigits) {
                voteGate.reset()
                return@withContext DecodeOutcome.NotFound("MSI native: payload too short")
            }
            // Host-policy enforcement: re-validate the reported digits under
            // the configured scheme (see [checksumPolicy]). Rejects foreign
            // schemes; double-check codewords still pass their own config.
            val validation = MsiChecksumValidator.validate(full, checksumPolicy)
            if (!validation.valid) {
                voteGate.reset()
                return@withContext DecodeOutcome.NotFound(
                    "MSI native: rejected under $checksumPolicy (native: $nativePolicy)",
                )
            }
            val validated = validation.payloadWithoutChecksum
            // stripChecksum=false still validates under the policy (precision
            // gate stays) but emits the full codeword WITH check digits and
            // reports checksumStripped=false; length floors then count codeword
            // digits (see ScannerConfig.msiStripChecksum).
            val (payload, stripped) = if (stripChecksum) {
                validated to (validated.length != full.length)
            } else {
                full to false
            }
            if (payload.length < minPayloadDigits) {
                voteGate.reset()
                return@withContext DecodeOutcome.NotFound("MSI native: stripped payload too short")
            }
            // Temporal voting: require the same payload across consecutive
            // observations before emitting. Transient false positives
            // (motion-blur phantoms) rarely repeat; true barcodes do.
            // The gate auto-resets on confirmation, so an unchanged value
            // does not re-emit until it changes and returns.
            if (!voteGate.observe(payload)) {
                val (_, n) = voteGate.progress()
                return@withContext DecodeOutcome.NotFound(
                    "MSI native: awaiting confirmation ($n/${voteGate.required})",
                )
            }
            DecodeOutcome.Success(
                listOf(
                    DecodedBarcode(
                        rawValue = payload,
                        symbology = Symbology.MSI_PLESSEY,
                        confidence = 1.0f,
                        engineName = NAME,
                        checksumStripped = stripped,
                        // Same convention as ML Kit / zxing-cpp: a read off a
                        // 180°-relative candidate was decoded after 180°
                        // re-orientation. (Previously always false for MSI.)
                        isUpsideDown = frame.isUpsideDownCandidate,
                    ),
                ),
            )
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            DecodeOutcome.Error(DecoderException("MSI native decode failed", t), recoverable = true)
        }
    }

    // withContext(dispatcher) already checks cancellation on entry; this is a
    // no-op hook for symmetry with the old decoder's cooperative checks.
    private fun ensureActiveCompat() = Unit

    companion object {
        const val NAME = "MsiNative"

        /**
         * True when the native library loaded. False on ABIs we don't ship
         * (e.g. x86 32-bit emulators) and on JVM unit tests — [decode] then
         * yields NotFound instead of crashing. The load is guarded (a bare
         * `System.loadLibrary` in class init throws
         * `ExceptionInInitializerError`, an Error that fusion's
         * `catch (Exception)` cannot contain and that would crash the first
         * scan on an unsupported device).
         */
        @Volatile
        var nativeAvailable: Boolean = false
            private set

        init {
            nativeAvailable = runCatching { System.loadLibrary("msi_decoder") }.isSuccess
            if (!nativeAvailable) {
                android.util.Log.w(NAME, "msi_decoder native lib unavailable; MSI decodes as NotFound")
            }
        }
    }
}
