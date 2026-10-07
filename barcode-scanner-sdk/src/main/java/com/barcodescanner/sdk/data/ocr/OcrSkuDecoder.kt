package com.barcodescanner.sdk.data.ocr

import android.graphics.Bitmap
import android.graphics.Rect
import com.barcodescanner.sdk.data.msi.MsiBinarizer
import com.barcodescanner.sdk.data.msi.MsiChecksumValidator
import com.barcodescanner.sdk.domain.decoder.DecodeOutcome
import com.barcodescanner.sdk.domain.decoder.DecoderException
import com.barcodescanner.sdk.domain.decoder.LastResortDecoder
import com.barcodescanner.sdk.domain.model.DecodedBarcode
import com.barcodescanner.sdk.domain.model.ScanFrame
import com.barcodescanner.sdk.domain.model.ScannerConfig
import com.barcodescanner.sdk.domain.model.Symbology
import com.barcodescanner.sdk.domain.pipeline.OrientationCandidates
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean

/**
 * MSI SKU text fallback ("OCR once bar decoding missed").
 *
 * Shelf tags print the SKU next to the barcode; when blur/glare/occlusion defeat
 * bar decoding, the printed digits often still read. This decoder is a
 * [LastResortDecoder]: fusion runs it at most once per frame, only when MSI is
 * enabled and every bar engine missed, and it:
 *
 *  1. finds content bands by edge density (lenient gate: a frame with no dense
 *     structure has no label and is rejected without paying for OCR),
 *  2. runs ML Kit text recognition once over the frame,
 *  3. picks the best digit run by checksum validity, dash-adjacency (shelf case
 *     codes print dashed: `000-42000-15121`; SKUs print plain), length, and
 *     proximity to a dense band,
 *  4. pairs the SKU with the dashed GTIN on the tag (`000-42000-15121`,
 *     `006-99235-00100`, possibly space-split by OCR as `006 99235-00100`).
 *
 * Dash-adjacency matters: runs are split on non-digits, and a run touching `-`
 * or `/` in its line is marked dashed, so a case-code segment loses to a plain
 * SKU run — but the dashed run itself becomes the GTIN candidate.
 *
 * Checksum semantics: shelf labels print the SKU WITHOUT the check digit(s)
 * (those live in the barcode), so validating the printed text is usually
 * impossible. [requireChecksum] therefore defaults to false: any plain 7+ digit
 * run is emitted at confidence 0.5 in the digits-as-printed form. Hosts that
 * need strict precision can set [requireChecksum] = true; runs that happen to
 * validate then earn confidence 0.7.
 *
 * Reported value: the digit run EXACTLY AS PRINTED (never stripped — the old
 * strip silently truncated real SKUs, e.g. Starbucks payload `0168971` is
 * itself a valid Mod10 codeword and became `016897`). The paired GTIN is
 * reported alongside as digits-only ([DecodedBarcode.gtin]) plus as-printed
 * ([DecodedBarcode.gtinRaw]).
 *
 * Pair validation: neither the SKU nor the GTIN carries a verifiable check
 * digit in print, so this decoder cannot validate either alone. The pair
 * validates via record lookup — `sku ↔ gtin` must belong to the same record.
 * A one-digit OCR misread in either field fails the join instead of emitting
 * a confident misread. Hosts must perform that join; an unpaired SKU
 * (`gtin == null`) is lower-trust than a paired one at the same confidence.
 *
 * Precision note: unvalidated OCR reads depend on read quality (one misread
 * digit cannot be detected without the check digit) — hosts should treat
 * `engineName == "MsiOcr"` hits as lower-trust and can filter them out
 * entirely via `ScanResult` inspection if desired.
 */
class OcrSkuDecoder(
    enabledSymbologies: Set<Symbology>,
    private val checksumPolicy: ScannerConfig.MsiChecksumPolicy =
        ScannerConfig.MsiChecksumPolicy.MOD_10,
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default,
    /**
     * False (default): emit the best plain 7+ digit run as printed (confidence
     * 0.5). True: require validation under [checksumPolicy] (confidence 0.7) —
     * only useful when the printed number actually carries its check digit(s).
     */
    private val requireChecksum: Boolean = false,
    private val ocrEngineProvider: () -> OcrEngine = { MlKitOcrEngine() },
) : LastResortDecoder {

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
        // Upright the frame first so band detection and ML Kit boxes share one
        // coordinate space: ML Kit reports boxes rotated-upright even when fed a
        // rotation hint, while bands are computed on raw pixels.
        val rotation = frame.effectiveRotation
        val upright = if (rotation == 0) null else OrientationCandidates.rotate(frame.bitmap, rotation)
        var cancelled = false
        try {
            val source = upright ?: frame.bitmap
            val working = downscaleIfNeeded(source)
            try {
                val gray = MsiBinarizer.toGray(working)
                val bands = denseBands(gray, working.width, working.height)
                if (bands.isEmpty()) {
                    return@withContext DecodeOutcome.NotFound("no content bands for OCR anchoring")
                }
                val lines = engine().recognize(source, 0)
                if (lines.isEmpty()) {
                    return@withContext DecodeOutcome.NotFound("OCR found no text")
                }
                // Bands are in working coords; ML Kit boxes are in source coords.
                val scale = source.height.toFloat() / working.height
                val pick = pickSku(lines, bands, scale)
                    ?: return@withContext DecodeOutcome.NotFound("no SKU-like digit run")
                // Pair the SKU with the dashed GTIN on the same tag when present.
                // GTIN absence never vetoes the SKU (backward compat); the host's
                // sku↔gtin record join is the validator.
                val gtin = pickGtin(
                    lines = lines,
                    skuLineIndex = pick.lineIndex,
                    skuBox = pick.box,
                    bands = bands,
                    scale = scale,
                )
                val rotated = frame.isRotatedCandidate
                DecodeOutcome.Success(
                    listOf(
                        DecodedBarcode(
                            // Printed run as-is (never strip: printer conventions differ).
                            rawValue = pick.digits,
                            symbology = Symbology.MSI_PLESSEY,
                            // Validated reads outrank unvalidated ones; hosts with a
                            // minConfidence above 0.5 filter the latter out.
                            confidence = if (pick.validated) 0.7f else 0.5f,
                            boundingBox = if (rotated) null else pick.box,
                            engineName = NAME,
                            checksumStripped = false,
                            isUpsideDown = frame.isUpsideDownCandidate,
                            gtin = gtin?.digits,
                            gtinRaw = gtin?.raw,
                        ),
                    ),
                )
            } finally {
                if (working !== source) working.recycle()
            }
        } catch (e: CancellationException) {
            cancelled = true
            throw e
        } catch (e: DecoderException) {
            DecodeOutcome.Error(e, recoverable = false)
        } catch (t: Throwable) {
            DecodeOutcome.Error(DecoderException("OCR fallback failed", t), recoverable = true)
        } finally {
            // A cancelled ML Kit OCR task may still be reading the upright copy.
            if (!cancelled) runCatching { upright?.recycle() }
        }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        synchronized(lock) {
            runCatching { engine?.close() }
            engine = null
        }
    }

    internal data class SkuPick(
        val digits: String,
        val box: Rect?,
        val validated: Boolean,
        /** Index into the OCR `lines` list the SKU came from (-1 if unknown). */
        val lineIndex: Int = -1,
    )

    /** A dashed GTIN candidate: digits-only for lookup plus as-printed for display. */
    internal data class GtinPick(val digits: String, val raw: String, val box: Rect?)

    /**
     * Picks the SKU digit run across OCR lines.
     *
     * Rules (in order): digit runs split on non-digits and must clear the
     * length floor (strict: 4 + checksum validation; trust mode: 7, since no
     * validation can apply to print without check digits); among survivors
     * prefer plain runs (not dash/slash-adjacent: case codes print dashed,
     * SKUs print plain), then longer, then nearer a content band.
     */
    internal fun pickSku(
        lines: List<OcrLine>,
        bands: List<IntRange>,
        scale: Float,
    ): SkuPick? {
        // Validation only makes sense when the host asks for it AND the policy
        // is not NONE. Shelf labels print the SKU without its check digit(s),
        // so trust mode demands a long run instead.
        val mustValidate = requireChecksum &&
            checksumPolicy != ScannerConfig.MsiChecksumPolicy.NONE
        val minDigits = if (mustValidate) 4 else 7
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
            val lineIndex: Int,
        )
        val scored = mutableListOf<Scored>()
        for ((lineIndex, line) in lines.withIndex()) {
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
                val payload = if (mustValidate) {
                    val validation = MsiChecksumValidator.validate(digits, checksumPolicy)
                    if (!validation.valid) continue
                    // Short runs validating by 1/10-1/11 luck with no agreement
                    // mechanism are confident misreads waiting to happen.
                    if (isSingleCheck && validation.payloadWithoutChecksum.length < MIN_SINGLE_DIGITS) {
                        continue
                    }
                    validation.payloadWithoutChecksum
                } else {
                    digits
                }
                scored += Scored(digits, payload, box, plain, proximity, lineIndex)
            }
        }
        if (scored.isEmpty()) return null
        val best = scored.sortedWith(
            compareByDescending<Scored> { it.plain }
                .thenByDescending { it.payload.length }
                .thenBy { it.proximity },
        ).first()
        // Emit the run as printed; `payload` only gated/ranked.
        return SkuPick(
            digits = best.digits,
            box = best.box?.let { Rect(it) },
            validated = mustValidate,
            lineIndex = best.lineIndex,
        )
    }

    /**
     * Pairs the chosen SKU with the dashed GTIN printed on the same shelf tag.
     *
     * Shelf format (per host): plain SKU (`0828147`) + dashed GTIN
     * (`000-42000-15121`, `006-99235-00100`). OCR may split the GTIN over a
     * space (`006 99235-00100`), so candidates are windows of consecutive digit
     * runs joined by single ` `/`-`/`/` separators with at least one dash/slash
     * join (this is what distinguishes a GTIN from a second plain number).
     * Total digits must be [GTIN_MIN_DIGITS]..[GTIN_MAX_DIGITS] (rejects dates
     * like `09/14/22` and counts like `10-48` by length, not by format guess).
     *
     * Ranking: full-length GTINs (12-14 digits) beat partials, then longer,
     * then same-line-as-SKU, then nearer the SKU box (same label) / content
     * band. Returns null when no GTIN-like window exists — the SKU still emits
     * alone (backward compat); the host join simply has nothing to check.
     */
    internal fun pickGtin(
        lines: List<OcrLine>,
        skuLineIndex: Int,
        skuBox: Rect?,
        bands: List<IntRange>,
        scale: Float,
    ): GtinPick? {
        data class Scored(
            val digits: String,
            val raw: String,
            val box: Rect?,
            val fullLength: Boolean,
            val sameLine: Boolean,
            val distance: Float,
        )
        val scored = mutableListOf<Scored>()
        for ((lineIndex, line) in lines.withIndex()) {
            val box = line.box
            for (c in gtinCandidates(line.text)) {
                val sameLine = lineIndex == skuLineIndex
                val distance = if (skuBox != null && box != null) {
                    val sx = (skuBox.left + skuBox.right) / 2f
                    val sy = (skuBox.top + skuBox.bottom) / 2f
                    val gx = (box.left + box.right) / 2f
                    val gy = (box.top + box.bottom) / 2f
                    kotlin.math.abs(sx - gx) + kotlin.math.abs(sy - gy) * 2f
                } else if (box != null && bands.isNotEmpty()) {
                    val cy = (box.top + box.bottom) / 2f
                    bands.minOf { band ->
                        val bc = (band.first + band.last) / 2f * scale
                        kotlin.math.abs(cy - bc)
                    }
                } else {
                    Float.MAX_VALUE
                }
                scored += Scored(
                    digits = c.digits,
                    raw = c.raw,
                    box = box,
                    fullLength = c.digits.length in GTIN_PREFERRED_MIN..GTIN_MAX_DIGITS,
                    sameLine = sameLine,
                    distance = distance,
                )
            }
        }
        if (scored.isEmpty()) return null
        val best = scored.sortedWith(
            compareByDescending<Scored> { it.fullLength }
                .thenByDescending { it.digits.length }
                .thenByDescending { it.sameLine }
                .thenBy { it.distance },
        ).first()
        return GtinPick(best.digits, best.raw, best.box?.let { Rect(it) })
    }

    /**
     * Dashed digit windows on one OCR line (see [pickGtin]).
     *
     * Windowing rule (precision-critical): maximal dash/slash-joined groups
     * first; at most ONE short leading space-joined run (≤5 digits, e.g. `006`
     * in `006 99235-00100`) is prepended when the dashed core is partial
     * (<12 digits). Never extend right over spaces (that absorbs trailing
     * counts: `000-42000-15121 10-48` must not become 14 digits), and never
     * prepend a long run (a 7+ digit SKU is not a GTIN segment:
     * `0168971 10-48` must stay NotFound, not an 11-digit pseudo-GTIN).
     */
    internal fun gtinCandidates(line: String): List<GtinWindow> {
        val runs = DIGIT_RUN.findAll(line).toList()
        if (runs.isEmpty()) return emptyList()
        // 1. Maximal dash/slash groups (spaces always break).
        data class Group(val fromRun: Int, val toRun: Int, val digits: String)
        val groups = mutableListOf<Group>()
        var gStart = 0
        val gDigits = StringBuilder(runs[0].value)
        for (k in 1 until runs.size) {
            val sep = line.substring(runs[k - 1].range.last + 1, runs[k].range.first)
            val trimmed = sep.trim()
            val isDash = sep.length <= 3 && trimmed.length == 1 && trimmed[0] in "-/"
            if (isDash) {
                gDigits.append(runs[k].value)
            } else {
                groups += Group(gStart, k - 1, gDigits.toString())
                gStart = k
                gDigits.clear()
                gDigits.append(runs[k].value)
            }
        }
        groups += Group(gStart, runs.size - 1, gDigits.toString())
        // 2. Emit qualifying groups, with one short left-prepend for partials.
        val out = mutableListOf<GtinWindow>()
        val seen = HashSet<String>()
        fun emit(fromRun: Int, toRun: Int, digits: String) {
            if (digits.length !in GTIN_MIN_DIGITS..GTIN_MAX_DIGITS) return
            val raw = line.substring(runs[fromRun].range.first, runs[toRun].range.last + 1)
            if (seen.add(raw)) out += GtinWindow(digits = digits, raw = raw)
        }
        for (g in groups) {
            val dashed = g.toRun > g.fromRun // dash-joined by construction
            if (!dashed) continue
            if (g.digits.length in GTIN_MIN_DIGITS..GTIN_MAX_DIGITS) {
                emit(g.fromRun, g.toRun, g.digits)
                // Partial cores (<12) may be split GTINs: try upgrading with one
                // short left segment (`006 99235-00100` → 13). Full cores stand.
                if (g.digits.length >= GTIN_PREFERRED_MIN || g.fromRun == 0) continue
                val prev = runs[g.fromRun - 1]
                if (prev.value.length > GTIN_LEAD_SEGMENT_MAX) continue
                val sep = line.substring(prev.range.last + 1, runs[g.fromRun].range.first)
                if (!sep.isBlank() || sep.length !in 1..3) continue
                val combined = prev.value + g.digits
                if (combined.length in GTIN_MIN_DIGITS..GTIN_MAX_DIGITS) {
                    emit(g.fromRun - 1, g.toRun, combined)
                }
                continue
            }
            // Partial core (<10 or >14 handled below): try one short left
            // segment over whitespace (split GTIN). Long left runs are SKUs.
            if (g.digits.length < GTIN_MIN_DIGITS && g.fromRun > 0) {
                val prev = runs[g.fromRun - 1]
                if (prev.value.length <= GTIN_LEAD_SEGMENT_MAX) {
                    val sep = line.substring(prev.range.last + 1, runs[g.fromRun].range.first)
                    if (sep.isBlank() && sep.length in 1..3) {
                        val combined = prev.value + g.digits
                        if (combined.length in GTIN_MIN_DIGITS..GTIN_MAX_DIGITS) {
                            emit(g.fromRun - 1, g.toRun, combined)
                            continue
                        }
                    }
                }
            }
            // Over-long dashed groups (>14) are corrupt merges, never GTINs.
        }
        return out
    }

    internal data class GtinWindow(val digits: String, val raw: String)

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
        /**
         * GTIN digit bounds: 12-14 is a full GTIN (preferred), 10-11 a partial
         * (split across OCR lines or truncated read — still emitted for the
         * host join to judge). Below 10 are dates/counts/prices, not GTINs.
         */
        const val GTIN_MIN_DIGITS = 10
        const val GTIN_MAX_DIGITS = 14
        const val GTIN_PREFERRED_MIN = 12
        /**
         * Longest run that may be prepended as a split-GTIN leading segment.
         * SKUs are 7+ digits by definition ([pickSku] floor), so a ≤5 cap cleanly
         * separates `006` (segment) from `0168971` (SKU that must not merge with
         * a following `10-48` count).
         */
        const val GTIN_LEAD_SEGMENT_MAX = 5
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
