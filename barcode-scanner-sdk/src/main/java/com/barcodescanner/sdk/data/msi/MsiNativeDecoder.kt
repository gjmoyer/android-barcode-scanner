package com.barcodescanner.sdk.data.msi

import com.barcodescanner.sdk.data.frame.LumaFrame
import com.barcodescanner.sdk.data.frame.NativeFrame
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
import java.nio.ByteBuffer

/**
 * JNI wrapper for the clean-room C++ MSI Plessey decoder
 * (`src/main/cpp/msi_decoder.cpp`).
 *
 * Replaces the pure-Kotlin [MsiPlesseyDecoder]: the C++ engine uses
 * signal-template correlation instead of run-length thresholding, which
 * survives the blur that defeats width-based classification. Validated 6/6
 * on the shelf-tag benchmark set (3–7ms per frame).
 *
 * Checksum contract matches [MsiPlesseyDecoder]: [checksumPolicy] is pushed
 * into the native engine (only strings valid under it compete for the
 * frame's winner; NONE lets it try MOD10/MOD11/MOD1010/MOD1110), then
 * re-validated here; [stripChecksum] (default true) strips the check
 * digit(s) from the emitted payload.
 */
class MsiNativeDecoder(
    private val stripChecksum: Boolean = true,
    private val minPayloadDigits: Int = MsiCodeTable.MIN_DIGITS,
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default,
    /**
     * Checksum scheme the host requires (wired from
     * [ScannerConfig.msiChecksumPolicy] by [ScannerContainer][com.barcodescanner.sdk.di.ScannerContainer]).
     * Pushed into native candidate selection (see [nativePolicyCode]): a
     * post-hoc filter alone let a Mod10-valid truncation of a Mod1010 tag
     * outvote the true read and then be rejected or, under a looser policy,
     * emitted. The reported digits are still re-validated strictly under
     * this policy via [MsiChecksumValidator] — the exact contract the
     * pre-native Kotlin decoder enforced. Revalidation (not the reported
     * scheme name) decides: every Mod1010/Mod1110 codeword also validates as
     * Mod10 by construction, so name-matching would mis-route double-check
     * labels. NONE accepts anything. Grocery shelf tags are 9-digit Mod1010
     * (7-digit item + two check digits): use MOD_10_10 for them.
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
    /**
     * Accepted symbol lengths, check digits included; null accepts any
     * (wired from [ScannerConfig.msiSymbolLengths]). Pushed into native
     * candidate selection before scanline voting, then re-checked on the
     * reported codeword here.
     */
    private val symbolLengths: IntRange? = null,
) : BarcodeDecoder {

    override val name: String = NAME
    override val supportedSymbologies: Set<Symbology> = setOf(Symbology.MSI_PLESSEY)

    private external fun nativeDecodeLuma(
        gray: ByteBuffer,
        width: Int,
        height: Int,
        maxOrientations: Int,
        scanTopFraction: Float,
        requiredPolicy: Int,
        minDigits: Int,
        maxDigits: Int,
        stretchRetry: Boolean,
    ): String?

    private val requiredNativePolicy: Int = nativePolicyCode(checksumPolicy)

    // Native digit bounds; 0 leaves a side open (JNI contract, msi_jni.cpp).
    private val nativeMinDigits: Int = symbolLengths?.first ?: 0
    private val nativeMaxDigits: Int = symbolLengths?.last ?: 0

    // Single-path temporal voting state (see [MsiVoteGate]; synchronized).
    private val voteGate = MsiVoteGate(requireConsecutiveFrames)

    /**
     * Standalone bitmap decode (hosts using the engine directly; the facade
     * never calls this). The bitmap is converted to gray natively
     * ([NativeFrame.prepareStill]: no rotation — the pixel layout
     * as given, as this entry point always decoded) and read with one
     * orientation and the full-frame band window. A read is reported upside
     * down when [ScanFrame.isUpsideDownCandidate].
     */
    override suspend fun decode(frame: ScanFrame): DecodeOutcome = withContext(dispatcher) {
        try {
            if (!nativeAvailable || !NativeFrame.available) {
                return@withContext DecodeOutcome.NotFound("MSI native library not loaded")
            }
            val luma = NativeFrame.prepareStill(
                frame.bitmap,
                rotationDegrees = 0,
                maxOrientations = 1,
                scanTopFraction = FULL_FRAME_SCAN_TOP,
            ) ?: return@withContext DecodeOutcome.NotFound("MSI native: image too small")
            decodeLumaRaw(luma, upsideDown = frame.isUpsideDownCandidate)
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            // Rare path: log the class so on-device forensics can tell
            // OOM/recycled-bitmap/JNI apart.
            android.util.Log.w(NAME, "decode failed: ${t.javaClass.simpleName}: ${t.message}")
            DecodeOutcome.Error(DecoderException("MSI native decode failed", t), recoverable = true)
        }
    }

    /**
     * Facade path (live and still): decodes the shared prepared [LumaFrame]
     * in place (no pixel copy, no Kotlin conversion). Orientation attempts
     * and the band-search window come from the frame; the winning relative
     * rotation reports 180 as [DecodedBarcode.isUpsideDown]. Same validation,
     * vote gate and payload contract as [decode].
     */
    internal suspend fun decodeLuma(frame: LumaFrame): DecodeOutcome = withContext(dispatcher) {
        try {
            if (!nativeAvailable) {
                return@withContext DecodeOutcome.NotFound("MSI native library not loaded")
            }
            decodeLumaRaw(frame, upsideDown = false)
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            android.util.Log.w(NAME, "decodeLuma failed: ${t.javaClass.simpleName}: ${t.message}")
            DecodeOutcome.Error(DecoderException("MSI native decode failed", t), recoverable = true)
        }
    }

    /** One native read of [frame] in place, then [acceptNativeRead]. */
    private fun decodeLumaRaw(frame: LumaFrame, upsideDown: Boolean): DecodeOutcome {
        val raw = try {
            nativeDecodeLuma(
                frame.buffer,
                frame.width,
                frame.height,
                frame.maxOrientations,
                frame.scanTopFraction,
                requiredNativePolicy,
                nativeMinDigits,
                nativeMaxDigits,
                frame.stretchRetry,
            )
        } catch (e: UnsatisfiedLinkError) {
            return DecodeOutcome.Error(
                DecoderException("MSI native library not loaded", e),
                recoverable = false,
            )
        }
        return acceptNativeRead(raw, upsideDown)
    }

    /**
     * Shared acceptance tail. [raw] is `digits|policy|votes|relRot`; null is
     * a native miss. [upsideDown] is the orientation of the buffer handed to
     * native (false for upright luma frames); a relRot 180 read flips it.
     * Every rejection resets the vote gate.
     */
    internal fun acceptNativeRead(raw: String?, upsideDown: Boolean): DecodeOutcome {
        if (raw == null) {
            // Native miss: reset temporal voting.
            voteGate.reset()
            return DecodeOutcome.NotFound("MSI native: no decode")
        }
        val parts = raw.split("|")
        if (parts.size != 4) {
            voteGate.reset()
            return DecodeOutcome.NotFound("MSI native: bad result format")
        }
        val full = parts[0]
        val nativePolicy = parts[1]
        val isUpsideDown = upsideDown != (parts[3] == "180")
        if (full.length < minPayloadDigits) {
            voteGate.reset()
            return DecodeOutcome.NotFound("MSI native: payload too short")
        }
        if (symbolLengths != null && full.length !in symbolLengths) {
            voteGate.reset()
            return DecodeOutcome.NotFound("MSI native: symbol length ${full.length} outside $symbolLengths")
        }
        // Host-policy enforcement: re-validate the reported digits under
        // the configured scheme (see [checksumPolicy]). Rejects foreign
        // schemes; double-check codewords still pass their own config.
        val validation = MsiChecksumValidator.validate(full, checksumPolicy)
        if (!validation.valid) {
            voteGate.reset()
            return DecodeOutcome.NotFound(
                "MSI native: rejected under $checksumPolicy (native: $nativePolicy)",
            )
        }
        val validated = validation.payloadWithoutChecksum
        // Degenerate reads (blank/shadow areas decode as all-same-digit
        // windows that still checksum-validate, e.g. all-zeros) are
        // withheld: a real payload is never a single repeated digit.
        // Documented tradeoff — a hypothetical all-same-digit label would
        // miss — vastly preferable to emitting zeros from empty areas.
        if (validated.toSet().size < 2) {
            voteGate.reset()
            return DecodeOutcome.NotFound("MSI native: degenerate payload")
        }
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
            return DecodeOutcome.NotFound("MSI native: stripped payload too short")
        }
        // Temporal voting: require the same payload across consecutive
        // observations before emitting. Transient false positives
        // (motion-blur phantoms) rarely repeat; true barcodes do.
        // The gate auto-resets on confirmation, so an unchanged value
        // does not re-emit until it changes and returns.
        if (!voteGate.observe(payload)) {
            val (_, n) = voteGate.progress()
            return DecodeOutcome.NotFound(
                "MSI native: awaiting confirmation ($n/${voteGate.required})",
            )
        }
        return DecodeOutcome.Success(
            listOf(
                DecodedBarcode(
                    rawValue = payload,
                    symbology = Symbology.MSI_PLESSEY,
                    confidence = 1.0f,
                    engineName = NAME,
                    checksumStripped = stripped,
                    // Same convention as ML Kit / zxing-cpp: a read off a
                    // 180°-relative orientation was decoded after 180°
                    // re-orientation.
                    isUpsideDown = isUpsideDown,
                ),
            ),
        )
    }

    companion object {
        const val NAME = "MsiNative"

        /** Band-search skip for whole images (see `msi::DecodeOptions`). */
        internal const val FULL_FRAME_SCAN_TOP = 0.25f

        /**
         * Stable JNI code for the required scheme; must mirror
         * `toRequiredPolicy` in `msi_jni.cpp`. -1 = no requirement (NONE).
         */
        internal fun nativePolicyCode(policy: ScannerConfig.MsiChecksumPolicy): Int = when (policy) {
            ScannerConfig.MsiChecksumPolicy.MOD_10 -> 0
            ScannerConfig.MsiChecksumPolicy.MOD_11 -> 1
            ScannerConfig.MsiChecksumPolicy.MOD_10_10 -> 2
            ScannerConfig.MsiChecksumPolicy.MOD_10_11 -> 3
            ScannerConfig.MsiChecksumPolicy.NONE -> -1
        }

        /**
         * True when the native library loaded. False on ABIs we don't ship
         * (32-bit x86, see the SDK/sample abiFilters) yields NotFound instead
         * of crashing. The load is guarded (a bare
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
