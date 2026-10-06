package com.barcodescanner.sdk.data.ocr

import android.graphics.Bitmap
import android.graphics.Rect
import com.barcodescanner.sdk.data.msi.MsiBinarizer
import com.barcodescanner.sdk.data.msi.MsiChecksumValidator
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
import java.util.concurrent.atomic.AtomicBoolean

/**
 * MSI SKU text fallback ("OCR once bar decoding missed").
 *
 * Shelf tags print the SKU next to the barcode; when blur/glare/occlusion defeat
 * bar decoding, the printed digits often still read. This decoder runs LAST in the
 * fusion chain (only when MSI is enabled and every bar engine missed) and:
 *
 *  1. finds content bands by edge density (lenient gate: a frame with no dense
 *     structure has no label and is rejected without paying for OCR),
 *  2. runs ML Kit text recognition once over the frame,
 *  3. picks the best digit run by checksum validity, dash-adjacency (shelf case
 *     codes print dashed: `000-42000-15121`; SKUs print plain), length, and
 *     proximity to a dense band.
 *
 * Dash-adjacency matters: runs are split on non-digits, and a run touching `-`
 * or `/` in its line is marked dashed, so a lucky-validating case-code segment
 * loses to a plain SKU run. Checksum policy semantics match the bar decoder:
 * non-NONE policies require validation; NONE accepts the longest run (≥ 7).
 *
 * Precision note: single-checksum collisions (1/10) are contained by the
 * dash/length/proximity ranking plus the agreement-agnostic single-shot nature —
 * hosts should treat `engineName == "MsiOcr"` hits as lower-trust (0.7) and can
 * filter them out entirely via `ScanResult` inspection if desired.
 */
class OcrSkuDecoder(
    enabledSymbologies: Set<Symbology>,
    private val checksumPolicy: ScannerConfig.MsiChecksumPolicy =
        ScannerConfig.MsiChecksumPolicy.MOD_10,
    private val robustMode: Boolean = false,
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val ocrEngineProvider: () -> OcrEngine = { MlKitOcrEngine() },
) : BarcodeDecoder {

    override val name: String = NAME

    override val supportedSymbologies: Set<Symbology> =
        if (Symbology.MSI_PLESSEY in enabledSymbologies) setOf(Symbology.MSI_PLESSEY) else emptySet()

    private val lock = Any()
    private var engine: OcrEngine? = null
    private val closed = AtomicBoolean(false)

    private fun engine(): OcrEngine {
        if (closed.get()) throw DecoderException("OcrSkuDecoder is closed")
        synchronized(lock) {
            if (closed.get()) throw DecoderException("OcrSkuDecoder is closed")
            return engine ?: ocrEngineProvider().also { engine = it }
        }
    }

    override suspend fun decode(frame: ScanFrame): DecodeOutcome = withContext(dispatcher) {
        if (closed.get()) {
            return@withContext DecodeOutcome.Error(DecoderException("OcrSkuDecoder is closed"), false)
        }
        if (supportedSymbologies.isEmpty()) {
            return@withContext DecodeOutcome.NotFound("MSI not enabled")
        }
        try {
            val working = downscaleIfNeeded(frame.bitmap)
            val owned = working !== frame.bitmap
            try {
                val gray = MsiBinarizer.toGray(working)
                val bands = denseBands(gray, working.width, working.height)
                if (bands.isEmpty()) {
                    return@withContext DecodeOutcome.NotFound("no content bands for OCR anchoring")
                }
                val lines = engine().recognize(frame.bitmap, frame.effectiveRotation)
                if (lines.isEmpty()) {
                    return@withContext DecodeOutcome.NotFound("OCR found no text")
                }
                // Bands are in working coords; ML Kit boxes are in frame coords.
                val scale = frame.bitmap.height.toFloat() / working.height
                val pick = pickSku(lines, bands, scale)
                    ?: return@withContext DecodeOutcome.NotFound("no SKU-like digit run")
                val rotated = frame.isRotatedCandidate
                DecodeOutcome.Success(
                    listOf(
                        DecodedBarcode(
                            rawValue = pick.digits,
                            symbology = Symbology.MSI_PLESSEY,
                            confidence = 0.7f,
                            boundingBox = if (rotated) null else pick.box,
                            engineName = NAME,
                            checksumStripped = checksumPolicy != ScannerConfig.MsiChecksumPolicy.NONE,
                            isUpsideDown = frame.attemptRotation == 180,
                        ),
                    ),
                )
            } finally {
                if (owned) working.recycle()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: DecoderException) {
            DecodeOutcome.Error(e, recoverable = false)
        } catch (t: Throwable) {
            DecodeOutcome.Error(DecoderException("OCR fallback failed", t), recoverable = true)
        }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        synchronized(lock) {
            runCatching { engine?.close() }
            engine = null
        }
    }

    internal data class SkuPick(val digits: String, val box: Rect?)

    /**
     * Picks the SKU digit run across OCR lines.
     *
     * Rules (in order): digit runs split on non-digits and must clear
     * [minDigits] (4; 7 under NONE where no checksum applies); non-NONE
     * policies require checksum validation; among survivors prefer plain runs
     * (not dash/slash-adjacent: case codes print dashed, SKUs print plain),
     * then longer, then nearer a content band.
     */
    internal fun pickSku(
        lines: List<OcrLine>,
        bands: List<IntRange>,
        scale: Float,
    ): SkuPick? {
        // NONE has no checksum to gate on: demand a long run instead.
        // Single-checksum hits additionally require MIN_SINGLE_DIGITS: a short
        // run validating by 1/10-1/11 luck with no agreement mechanism behind it
        // is a confident misread waiting to happen ("1512" off a case code);
        // real shelf SKUs run 6+ digits, and short payloads remain the bar
        // decoder's job (it has the ≥2-observation gate OCR inherently lacks).
        val minDigits = when (checksumPolicy) {
            ScannerConfig.MsiChecksumPolicy.NONE -> 7
            else -> 4
        }
        // Single-checksum policies validate by 1/10-1/11 luck with no
        // cross-observation agreement behind a single OCR pass.
        val isSingleCheck = checksumPolicy == ScannerConfig.MsiChecksumPolicy.MOD_10 ||
            checksumPolicy == ScannerConfig.MsiChecksumPolicy.MOD_11
        data class Scored(
            val digits: String,
            val payload: String,
            val box: Rect?,
            val plain: Boolean,
            val proximity: Float,
        )
        val scored = mutableListOf<Scored>()
        for (line in lines) {
            val box = line.box
            val proximity = if (box == null || bands.isEmpty()) {
                Float.MAX_VALUE
            } else {
                val cy = (box.top + box.bottom) / 2f
                bands.minOf { band ->
                    val bc = (band.first + band.last) / 2f * scale
                    kotlin.math.abs(cy - bc)
                }
            }
            for (m in DIGIT_RUN.findAll(line.text)) {
                val digits = m.value
                if (digits.length < minDigits) continue
                val plain = isPlainRun(line.text, m.range.first, m.range.last + 1)
                val payload = if (checksumPolicy == ScannerConfig.MsiChecksumPolicy.NONE) {
                    digits
                } else {
                    val validation = MsiChecksumValidator.validate(digits, checksumPolicy)
                    if (!validation.valid) continue
                    validation.payloadWithoutChecksum
                }
                if (isSingleCheck && payload.length < MIN_SINGLE_DIGITS) continue
                scored += Scored(digits, payload, box, plain, proximity)
            }
        }
        if (scored.isEmpty()) return null
        val best = scored.sortedWith(
            compareByDescending<Scored> { it.plain }
                .thenByDescending { it.payload.length }
                .thenBy { it.proximity },
        ).first()
        return SkuPick(best.payload, best.box?.let { Rect(it) })
    }

    /**
     * Content bands (y-ranges in working coords) via edge density: rows whose
     * dark/light transition count clears a threshold, merged contiguously.
     * Barcodes, price text, label edges all qualify — the gate only rejects
     * content-free frames (blank walls), never real labels.
     */
    internal fun denseBands(gray: IntArray, w: Int, h: Int): List<IntRange> {
        if (w < 16 || h < 16 || gray.size < w * h) return emptyList()
        var sum = 0L
        for (v in gray) sum += v
        val mean = (sum / gray.size).toInt()
        val step = 4
        val dense = BooleanArray((h + step - 1) / step)
        for (r in dense.indices) {
            val y = (r * step).coerceAtMost(h - 1)
            var transitions = 0
            var prev = gray[y * w] < mean
            for (x in 1 until w) {
                val cur = gray[y * w + x] < mean
                if (cur != prev) {
                    transitions++
                    prev = cur
                }
                if (transitions >= DENSE_ROW_TRANSITIONS) break
            }
            dense[r] = transitions >= DENSE_ROW_TRANSITIONS
        }
        val bands = mutableListOf<IntRange>()
        var start = -1
        for (r in dense.indices) {
            if (dense[r] && start < 0) start = r
            if (!dense[r] && start >= 0) {
                bands += bandRange(start, r - 1, step, h)
                start = -1
            }
        }
        if (start >= 0) bands += bandRange(start, dense.lastIndex, step, h)
        return bands.filter { it.last - it.first + 1 >= MIN_BAND_HEIGHT_PX }
    }

    private fun bandRange(r0: Int, r1: Int, step: Int, h: Int): IntRange {
        val top = (r0 * step).coerceIn(0, h - 1)
        val bottom = ((r1 * step + step - 1)).coerceIn(0, h - 1)
        return top..maxOf(top, bottom)
    }

    private fun downscaleIfNeeded(src: Bitmap): Bitmap {
        if (src.width <= WORKING_WIDTH) return src
        val scale = WORKING_WIDTH.toFloat() / src.width
        val h = (src.height * scale).toInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(src, WORKING_WIDTH, h, false)
    }

    companion object {
        const val NAME = "MsiOcr"
        const val WORKING_WIDTH = 640
        /** Rows with at least this many dark/light transitions count as dense. */
        const val DENSE_ROW_TRANSITIONS = 16
        /** Bands thinner than this (px, working coords) are dropped. */
        const val MIN_BAND_HEIGHT_PX = 8
        /**
         * Single-checksum hits shorter than this (payload digits) are withheld:
         * one OCR pass has no cross-observation agreement behind it, so short
         * runs validating by 1/10-1/11 luck would be confident misreads.
         */
        const val MIN_SINGLE_DIGITS = 6
        private val DIGIT_RUN = Regex("\\d+")

        /**
         * True when neither neighbor of the digit run (in line coordinates,
         * [start] inclusive, [end] exclusive) is a dash or slash — i.e. the run
         * is a standalone number, not a segment of a dashed case/pack code.
         */
        internal fun isPlainRun(line: String, start: Int, end: Int): Boolean {
            val before = line.getOrNull(start - 1)
            val after = line.getOrNull(end)
            return before != '-' && before != '/' && after != '-' && after != '/'
        }
    }
}
