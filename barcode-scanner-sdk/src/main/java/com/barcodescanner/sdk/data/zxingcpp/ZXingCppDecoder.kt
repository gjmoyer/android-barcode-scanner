package com.barcodescanner.sdk.data.zxingcpp

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
import zxingcpp.BarcodeReader

/**
 * Fallback decoder backed by the prebuilt zxing-cpp Android AAR
 * (`io.github.zxing-cpp:android`, see `gradle/libs.versions.toml`) via its
 * public [BarcodeReader] API — no in-repo JNI/CMake source.
 *
 * Coverage: critically **GS1 DataBar (Omnidirectional / Stacked / Limited /
 * Expanded / Expanded Stacked)** — the one family ML Kit cannot read — plus an
 * UNKNOWN passthrough for forward-compat formats (Telepen/MaxiCode/MicroQR/...)
 * when the host explicitly enables it. ML Kit owns every native symbology;
 * this engine never second-guesses them (no duplicate inference on frames ML
 * Kit already resolves).
 *
 * MSI Plessey is deliberately EXCLUDED (zxing-cpp has no MSI reader) and is
 * handled by [com.barcodescanner.sdk.data.msi.MsiNativeDecoder].
 *
 * Format filtering uses the wrapper's [BarcodeReader.Format] set: an empty set
 * is the wrapper default and means "scan all formats" (same as the old
 * empty-filter fallback), which is how the UNKNOWN-only passthrough works.
 */
class ZXingCppDecoder(
    enabledSymbologies: Set<Symbology>,
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default,
    /**
     * Maps to [BarcodeReader.Options.tryHarder]. Wired from
     * `ScannerConfig.zxingTryHarder` in [ScannerContainer]: null is thorough
     * (fast only when a host opts out explicitly). Rotation/inversion retries
     * (tryRotate/tryInvert) stay on in both modes.
     */
    private val thorough: Boolean = true,
    /**
     * Injectable reader construction (tests supply fakes and avoid loading the
     * native lib on the JVM). Production passes the real [BarcodeReader].
     */
    private val readerFactory: (BarcodeReader.Options) -> BarcodeReader =
        { options -> BarcodeReader(options) },
) : BarcodeDecoder {

    override val name: String = NAME

    /** Native TryRotate already sweeps 0/90/180/270 inside one call. */
    override val resolvesOrientationInternally: Boolean = true

    override val supportedSymbologies: Set<Symbology> = buildSet {
        if (Symbology.DATA_BAR in enabledSymbologies) add(Symbology.DATA_BAR)
        if (Symbology.DATA_BAR_EXPANDED in enabledSymbologies) add(Symbology.DATA_BAR_EXPANDED)
        if (Symbology.DATA_BAR_LIMITED in enabledSymbologies) add(Symbology.DATA_BAR_LIMITED)
        // UNKNOWN passthrough only: toFormats() emits no names for it, so an
        // UNKNOWN-only set becomes the wrapper default (scan-all) and anything
        // unmapped surfaces as UNKNOWN (at reduced confidence — see parse).
        if (Symbology.UNKNOWN in enabledSymbologies) add(Symbology.UNKNOWN)
    }

    override suspend fun decode(frame: ScanFrame): DecodeOutcome = withContext(dispatcher) {
        if (supportedSymbologies.isEmpty()) {
            return@withContext DecodeOutcome.NotFound("no zxing symbologies enabled")
        }
        try {
            val options = BarcodeReader.Options(
                formats = toFormats(),
                tryRotate = true,
                tryInvert = true,
                tryHarder = thorough,
            )
            val reader = try {
                readerFactory(options)
            } catch (e: UnsatisfiedLinkError) {
                return@withContext DecodeOutcome.NotFound("zxing native lib unavailable")
            }
            val results = try {
                reader.read(frame.bitmap)
            } catch (e: UnsatisfiedLinkError) {
                return@withContext DecodeOutcome.NotFound("zxing native lib unavailable")
            }
            parse(results, frame)
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            // Rare path: log the class so on-device forensics can tell a
            // missing/broken native lib (ULE family) from wrapper misuse.
            android.util.Log.w(NAME, "decode failed: ${t.javaClass.simpleName}: ${t.message}")
            DecodeOutcome.Error(DecoderException("zxing-cpp decode failed", t), recoverable = true)
        }
    }

    /**
     * Native format filter using the wrapper [BarcodeReader.Format] set. DataBar
     * is split by family so a host enabling only LIMITED does not pay for (or
     * receive) omni scans. Natives are deliberately never listed — ML Kit owns
     * them. An empty result (UNKNOWN-only enabled set) falls back to the wrapper
     * default scan-all pass.
     */
    internal fun toFormats(): Set<BarcodeReader.Format> {
        val formats = mutableSetOf<BarcodeReader.Format>()
        if (Symbology.DATA_BAR in supportedSymbologies) {
            formats += listOf(
                BarcodeReader.Format.DATA_BAR_OMNI,
                BarcodeReader.Format.DATA_BAR_STK,
                BarcodeReader.Format.DATA_BAR_STK_OMNI,
            )
        }
        if (Symbology.DATA_BAR_EXPANDED in supportedSymbologies) {
            formats += listOf(
                BarcodeReader.Format.DATA_BAR_EXP,
                BarcodeReader.Format.DATA_BAR_EXP_STK,
            )
        }
        if (Symbology.DATA_BAR_LIMITED in supportedSymbologies) {
            formats += BarcodeReader.Format.DATA_BAR_LTD
        }
        return formats
    }

    /** Maps prebuilt wrapper results into SDK barcodes (internal for tests). */
    internal fun parse(results: List<BarcodeReader.Result>, frame: ScanFrame): DecodeOutcome {
        if (results.isEmpty()) return DecodeOutcome.NotFound("zxing found no barcode")
        val out = mutableListOf<DecodedBarcode>()
        // Dedup native duplicates on (value, symbology), keep first.
        val seen = HashSet<String>()
        for (r in results) {
            val text = r.text.orEmpty()
            if (text.isEmpty()) continue
            val symbology = mapFormat(r.format)
            // UNKNOWN only passes when the host explicitly enabled it, at reduced
            // confidence: Telepen/MaxiCode/MicroQR/add-ons must never outrank a
            // real symbology hit or trip the 0.95 early-exit, and hosts can filter
            // them with minConfidence > 0.6.
            if (symbology == Symbology.UNKNOWN && Symbology.UNKNOWN !in supportedSymbologies) continue
            if (symbology != Symbology.UNKNOWN && symbology !in supportedSymbologies) continue
            if (!seen.add("$symbology|$text")) continue
            val confidence = if (symbology == Symbology.UNKNOWN) 0.6f else 0.9f
            out += DecodedBarcode(
                rawValue = text,
                symbology = symbology,
                // Fixed 0.9 documents the fusion coupling (minConfidence > 0.9 disables ZXing).
                confidence = confidence,
                engineName = NAME,
                isUpsideDown = frame.isUpsideDownCandidate,
            )
        }
        return if (out.isEmpty()) DecodeOutcome.NotFound("zxing results filtered out")
        else DecodeOutcome.Success(out)
    }

    /**
     * Maps the prebuilt wrapper [BarcodeReader.Format] enum to SDK symbologies.
     * Formats with no SDK counterpart (add-ons, Telepen, MaxiCode, Micro/rMQR,
     * DXFilmEdge, ISBN, group pseudo-formats) map to UNKNOWN.
     */
    internal fun mapFormat(format: BarcodeReader.Format): Symbology {
        return when (format) {
            BarcodeReader.Format.DATA_BAR,
            BarcodeReader.Format.DATA_BAR_OMNI,
            BarcodeReader.Format.DATA_BAR_STK,
            BarcodeReader.Format.DATA_BAR_STK_OMNI,
            -> Symbology.DATA_BAR
            BarcodeReader.Format.DATA_BAR_EXP,
            BarcodeReader.Format.DATA_BAR_EXP_STK,
            -> Symbology.DATA_BAR_EXPANDED
            BarcodeReader.Format.DATA_BAR_LTD -> Symbology.DATA_BAR_LIMITED
            BarcodeReader.Format.EAN_8 -> Symbology.EAN_8
            BarcodeReader.Format.EAN_13 -> Symbology.EAN_13
            BarcodeReader.Format.UPC_A -> Symbology.UPC_A
            BarcodeReader.Format.UPC_E -> Symbology.UPC_E
            BarcodeReader.Format.CODE_39,
            BarcodeReader.Format.CODE_39_STD,
            BarcodeReader.Format.CODE_39_EXT,
            BarcodeReader.Format.CODE_32,
            BarcodeReader.Format.PZN,
            -> Symbology.CODE_39
            BarcodeReader.Format.CODE_93 -> Symbology.CODE_93
            BarcodeReader.Format.CODE_128 -> Symbology.CODE_128
            BarcodeReader.Format.ITF,
            BarcodeReader.Format.ITF_14,
            -> Symbology.ITF
            BarcodeReader.Format.CODABAR -> Symbology.CODABAR
            BarcodeReader.Format.QR_CODE,
            BarcodeReader.Format.QR_CODE_MODEL_1,
            BarcodeReader.Format.QR_CODE_MODEL_2,
            -> Symbology.QR_CODE
            BarcodeReader.Format.AZTEC,
            BarcodeReader.Format.AZTEC_CODE,
            BarcodeReader.Format.AZTEC_RUNE,
            -> Symbology.AZTEC
            BarcodeReader.Format.PDF_417,
            BarcodeReader.Format.COMPACT_PDF_417,
            BarcodeReader.Format.MICRO_PDF_417,
            -> Symbology.PDF_417
            BarcodeReader.Format.DATA_MATRIX -> Symbology.DATA_MATRIX
            else -> Symbology.UNKNOWN
        }
    }

    companion object {
        const val NAME = "ZXingCpp"

        @Volatile
        private var cachedAvailability: Boolean? = null

        /**
         * True if the prebuilt zxing-cpp native library loads on this device.
         * Cached after the first probe. Unit tests on the JVM return false so
         * fusion can continue to the next engine instead of crashing.
         */
        val isAvailable: Boolean
            get() {
                if (cachedAvailability == null) {
                    cachedAvailability = runCatching {
                        // init loads "zxingcpp_android"; failure means no native lib
                        // (JVM unit tests, missing ABI) — decode() then yields NotFound.
                        BarcodeReader(BarcodeReader.Options())
                        true
                    }.onFailure {
                        android.util.Log.w("ZXingCppDecoder", "zxing-cpp native lib unavailable", it)
                    }.getOrDefault(false)
                }
                return cachedAvailability == true
            }
    }
}
