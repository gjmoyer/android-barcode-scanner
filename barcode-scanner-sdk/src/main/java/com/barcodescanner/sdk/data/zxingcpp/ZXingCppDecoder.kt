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
import org.json.JSONArray

/**
 * Fallback decoder backed by zxing-cpp via JNI (pinned v3.1.1, see CMakeLists.txt).
 *
 * Coverage: critically **GS1 DataBar (Omnidirectional / Stacked / Limited /
 * Expanded / Expanded Stacked)** plus second-opinion 1D/2D coverage for rotated
 * or inverted frames ML Kit missed.
 *
 * MSI Plessey is deliberately EXCLUDED (zxing-cpp has no MSI reader) and is
 * handled by [com.barcodescanner.sdk.data.msi.MsiPlesseyDecoder].
 *
 * v3.x notes: native `ToString(format)` returns the human-readable name
 * ("DataBar Expanded", "QR Code", "Data Matrix", ...), and
 * `BarcodeFormatFromString` is case-insensitive ignoring " -_/" (and throws on
 * unknown names — the native side skips those). Filter + mapping below use the
 * v3.1.1 identifier set (DataBarOmni/DataBarStk/DataBarLtd/DataBarExp/...).
 */
class ZXingCppDecoder(
    enabledSymbologies: Set<Symbology>,
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default,
    /**
     * Maps to native TryHarder. Wired from `ScannerConfig.zxingTryHarder`
 * in [ScannerContainer]: null is thorough (fast only when a host opts out
 * explicitly). Rotation/inversion retries (TryRotate/TryInvert) stay on in
 * both modes.
     */
    private val thorough: Boolean = true,
) : BarcodeDecoder {

    override val name: String = NAME

    /** Native TryRotate already sweeps 0/90/180/270 inside one call. */
    override val resolvesOrientationInternally: Boolean = true

    override val supportedSymbologies: Set<Symbology> = buildSet {
        if (Symbology.DATA_BAR in enabledSymbologies) add(Symbology.DATA_BAR)
        if (Symbology.DATA_BAR_EXPANDED in enabledSymbologies) add(Symbology.DATA_BAR_EXPANDED)
        if (Symbology.DATA_BAR_LIMITED in enabledSymbologies) add(Symbology.DATA_BAR_LIMITED)
        addAll(enabledSymbologies.intersect(Symbology.mlKitNatives))
    }

    override suspend fun decode(frame: ScanFrame): DecodeOutcome = withContext(dispatcher) {
        if (!ZXingCppBridge.isAvailable) {
            return@withContext DecodeOutcome.NotFound("zxing native lib unavailable")
        }
        if (supportedSymbologies.isEmpty()) {
            return@withContext DecodeOutcome.NotFound("no zxing symbologies enabled")
        }
        try {
            val bmp = frame.bitmap
            val w = bmp.width
            val h = bmp.height
            val pixels = IntArray(w * h)
            bmp.getPixels(pixels, 0, w, 0, 0, w, h)
            val json = ZXingCppBridge.decodeBitmap(
                pixels = pixels,
                width = w,
                height = h,
                tryRotate = true,
                tryInvert = true,
                tryHarder = thorough,
                enabledFormats = enabledFormatsArg(),
            )
            parse(json, frame)
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            DecodeOutcome.Error(DecoderException("zxing-cpp decode failed", t), recoverable = true)
        }
    }

    /**
     * Native format filter using v3.1.1 identifiers. DataBar is split by family
     * so a host enabling only LIMITED does not pay for (or receive) omni scans:
     * omni family (Omni/Stk/StkOmni), Expanded (+ExpStk), Limited.
     */
    internal fun enabledFormatsArg(): String {
        val names = mutableListOf<String>()
        if (Symbology.DATA_BAR in supportedSymbologies) {
            names += listOf("DataBarOmni", "DataBarStk", "DataBarStkOmni")
        }
        if (Symbology.DATA_BAR_EXPANDED in supportedSymbologies) {
            names += listOf("DataBarExp", "DataBarExpStk")
        }
        if (Symbology.DATA_BAR_LIMITED in supportedSymbologies) names += "DataBarLtd"
        if (Symbology.EAN_8 in supportedSymbologies) names += "EAN-8"
        if (Symbology.EAN_13 in supportedSymbologies) names += "EAN-13"
        if (Symbology.UPC_A in supportedSymbologies) names += "UPC-A"
        if (Symbology.UPC_E in supportedSymbologies) names += "UPC-E"
        if (Symbology.CODE_39 in supportedSymbologies) names += "Code39"
        if (Symbology.CODE_93 in supportedSymbologies) names += "Code93"
        if (Symbology.CODE_128 in supportedSymbologies) names += "Code128"
        if (Symbology.ITF in supportedSymbologies) names += "ITF"
        if (Symbology.CODABAR in supportedSymbologies) names += "Codabar"
        if (Symbology.QR_CODE in supportedSymbologies) names += "QRCode"
        if (Symbology.AZTEC in supportedSymbologies) names += "Aztec"
        if (Symbology.PDF_417 in supportedSymbologies) names += "PDF417"
        if (Symbology.DATA_MATRIX in supportedSymbologies) names += "DataMatrix"
        return names.joinToString(",")
    }

    /** Parses the native JSON array into SDK barcodes (internal for tests). */
    internal fun parse(json: String, frame: ScanFrame): DecodeOutcome {
        val arr = runCatching { JSONArray(json) }.getOrNull()
            ?: return DecodeOutcome.NotFound("invalid native JSON")
        if (arr.length() == 0) return DecodeOutcome.NotFound("zxing found no barcode")
        val out = mutableListOf<DecodedBarcode>()
        // Dedup native duplicates on (value, symbology), keep first.
        val seen = HashSet<String>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val text = o.optString("text").orEmpty()
            if (text.isEmpty()) continue
            val symbology = mapFormat(o.optString("format").orEmpty())
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
     * Maps native v3.1.1 `ToString` outputs (HRI with spaces: "DataBar Expanded",
     * "QR Code", "Data Matrix", "EAN-13", ...) plus identifier spellings.
     * Normalization (lowercase, letters/digits only) makes both spellings match.
     * Formats with no SDK counterpart (add-ons, Telepen, MaxiCode, Micro/rMQR,
     * DXFilmEdge, ISBN) map to UNKNOWN.
     */
    internal fun mapFormat(format: String): Symbology {
        val key = format.lowercase().filter { it.isLetterOrDigit() }
        return when (key) {
            "databar",
            "databaromni",
            "databarstacked", "databarstk",
            "databarstackedomni", "databarstkomni",
            -> Symbology.DATA_BAR
            "databarexpanded", "databarexp",
            "databarexpandedstacked", "databarexpstk",
            -> Symbology.DATA_BAR_EXPANDED
            "databarlimited", "databarltd" -> Symbology.DATA_BAR_LIMITED
            "ean8" -> Symbology.EAN_8
            "ean13" -> Symbology.EAN_13
            "upca" -> Symbology.UPC_A
            "upce" -> Symbology.UPC_E
            "code39", "code39standard", "code39extended", "code32", "pzn" -> Symbology.CODE_39
            "code93" -> Symbology.CODE_93
            "code128" -> Symbology.CODE_128
            "itf", "itf14" -> Symbology.ITF
            "codabar" -> Symbology.CODABAR
            "qrcode", "qrcodemodel1", "qrcodemodel2" -> Symbology.QR_CODE
            "aztec", "azteccode", "aztecrune" -> Symbology.AZTEC
            "pdf417", "compactpdf417", "micropdf417" -> Symbology.PDF_417
            "datamatrix" -> Symbology.DATA_MATRIX
            else -> Symbology.UNKNOWN
        }
    }

    companion object {
        const val NAME = "ZXingCpp"
    }
}
