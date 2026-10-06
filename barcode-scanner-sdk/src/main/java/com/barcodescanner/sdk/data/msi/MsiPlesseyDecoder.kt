package com.barcodescanner.sdk.data.msi

import com.barcodescanner.sdk.domain.decoder.BarcodeDecoder
import com.barcodescanner.sdk.domain.decoder.DecodeOutcome
import com.barcodescanner.sdk.domain.decoder.DecoderException
import com.barcodescanner.sdk.domain.model.DecodedBarcode
import com.barcodescanner.sdk.domain.model.ScanFrame
import com.barcodescanner.sdk.domain.model.ScannerConfig
import com.barcodescanner.sdk.domain.model.Symbology
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlin.math.abs

/**
 * Custom pure-Kotlin MSI Plessey decoder.
 *
 * Why custom: neither ML Kit nor zxing-cpp ships an MSI reader, so MSI is the
 * SDK's differentiator. Scanline algorithm with robustness layers:
 *
 *  - **Blur**: multiple binarizations (Otsu, Otsu±, adaptive mean) × N scanlines
 *    across the middle band; each checksum-valid scanline votes.
 *  - **Rotation / upside-down**: handled by the fusion layer's orientation
 *    expansion (0/180° by default, 0/90/180/270° in robust mode). This decoder
 *    is intentionally FORWARD-ONLY: a pixel-reversed scanline does not decode
 *    with the same table (guards are asymmetric and digit bit order mirrors),
 *    so the old forward+reverse loop was removed — 180° labels decode via the
 *    180° orientation candidate instead, at half the CPU.
 *  - **90°-rotated labels**: horizontal scanlines cannot read vertical bars.
 *    In robust mode vertical scanlines are also sampled; in default mode only
 *    0/180° MSI is attempted (documented limitation, consistent with
 *    maxOrientationsTried=2).
 *  - **Skew**: per-scanline k-means narrow/wide split absorbs module distortion.
 *  - **Noise**: quiet-zone + guard checks, digit-count bounds, checksum gate,
 *    cross-scanline majority vote, single-bit blur repair.
 *
 * MSI framing per spec (see docs/MSI_PLESSEY_RESEARCH.md):
 *  START = wide bar + narrow space (2 runs); STOP = narrow bar + wide space +
 *  narrow bar (3 runs). Both are stripped before digit slicing.
 *
 * Status: code table + guards follow the published spec but are marked
 * EXPERIMENTAL until the Zint-rendered verification vectors land
 * (see MsiCodeTable docs). Checksum gating keeps false accepts low meanwhile.
 */
class MsiPlesseyDecoder(
    private val checksumPolicy: ScannerConfig.MsiChecksumPolicy =
        ScannerConfig.MsiChecksumPolicy.MOD_10,
    private val robustMode: Boolean = false,
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default,
    /**
     * Minimum checksum-stripped payload length to emit. Default = spec floor;
     * short windows validate by luck and correlate across adjacent scanlines,
     * so blurry live frames can yield 4-digit false positives at 2 votes.
     * Hosts scanning shelf SKUs should raise this to ~6.
     */
    private val minPayloadDigits: Int = MsiCodeTable.MIN_DIGITS,
) : BarcodeDecoder {

    override val name: String = NAME
    override val supportedSymbologies: Set<Symbology> = setOf(Symbology.MSI_PLESSEY)

    override suspend fun decode(frame: ScanFrame): DecodeOutcome = withContext(dispatcher) {
        try {
            val working = downscaleIfNeeded(frame.bitmap)
            val ownedWorking = working !== frame.bitmap
            try {
                val variants = MsiBinarizer.binarizeVariants(working, if (robustMode) 4 else 2)
                // Primary: native working gray (no resample artifacts) + 960w in ONE
                // vote pool. Upscaling sharp images can merge runs (bilinear
                // ripples), while native keeps them intact; tiny modules need the
                // upscale. Combined voting lets each scale contribute its best.
                // Scaled copies render from the frame bitmap (best quality source).
                val copies = mutableListOf(grayCopy(working, -1))
                var scaled960: GrayCopy? = null
                try {
                    scaled960 = grayCopy(frame.bitmap, PRIMARY_GRAY_WIDTH)
                    copies += scaled960
                    val primary = decodeWithGray(frame, working, variants, copies)
                    if (primary is DecodeOutcome.Success) return@withContext primary
                    // Fallback (tiny modules): 1440w alone, long payloads only.
                    val scaled1440 = grayCopy(frame.bitmap, FALLBACK_GRAY_WIDTH)
                    try {
                        val fallback = decodeWithGray(
                            frame, working, variants, listOf(scaled1440),
                            minPayload = MIN_FALLBACK_DIGITS,
                        )
                        if (fallback is DecodeOutcome.Success) return@withContext fallback
                        return@withContext primary
                    } finally {
                        runCatching { scaled1440.bitmap?.recycle() }
                    }
                } finally {
                    runCatching { scaled960?.bitmap?.recycle() }
                }
            } finally {
                if (ownedWorking) working.recycle()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            DecodeOutcome.Error(DecoderException("MSI decode failed", t), recoverable = true)
        }
    }

    /** Grayscale working copy: [src] as-is for [width] < 0 (no bitmap to recycle),
     * else bilinear scaled (caller recycles [GrayCopy.bitmap]). */
    private data class GrayCopy(
        val gray: IntArray,
        val bitmap: android.graphics.Bitmap?,
        val w: Int,
        val h: Int,
    )

    private fun grayCopy(src: android.graphics.Bitmap, width: Int): GrayCopy {
        if (width < 0) {
            return GrayCopy(MsiBinarizer.toGray(src), null, src.width, src.height)
        }
        val h = maxOf(1, (src.height * (width.toFloat() / src.width)).toInt())
        val bmp = android.graphics.Bitmap.createScaledBitmap(src, width, h, true)
        return GrayCopy(MsiBinarizer.toGray(bmp), bmp, width, h)
    }

    /**
     * One decode pass over precomputed binary [variants] plus grayscale [grays]
     * (subpixel rows, columns, min-profiles per copy).
     *
     * Higher gray resolution localizes edges more precisely (tiny modules);
     * the binary path stays at 640w regardless (speed). Band geometry scales
     * with each copy's width to keep tilt smear proportional.
     *
     * @param minPayload winner payloads shorter than this are withheld as
     *   NotFound (0 = disabled). Used by the fallback scale, where observation
     *   counts make short Luhn collisions likely.
     */
    private suspend fun CoroutineScope.decodeWithGray(
        frame: ScanFrame,
        working: android.graphics.Bitmap,
        variants: List<MsiBinarizer.BinaryImage>,
        grays: List<GrayCopy>,
        minPayload: Int = 0,
    ): DecodeOutcome {
        try {
                // Gray-derived run lists are identical across binarization variants,
                // so collect them ONCE per copy (counting the same observation N
                // times would inflate votes and defeat the agreement gate).
                val grayRunLists = mutableListOf<List<Run>>()
                val projectionLines = mutableListOf<IntArray>()
                for (copy in grays) {
                    // Min-projection profiles don't depend on binarization; band
                    // geometry scales with copy width to keep tilt smear proportional.
                    val bandH = maxOf(8, copy.w / 60)
                    projectionLines += minProjectionProfiles(
                        copy.gray,
                        copy.w,
                        copy.h,
                        bandH = bandH,
                        step = if (robustMode) maxOf(4, bandH / 2) else bandH,
                    )
                    for (y in scanlineYs(copy.h, if (robustMode) 9 else 5)) {
                        val yy = y.coerceIn(0, copy.h - 1)
                        // Same row in grayscale -> subpixel runs (exact widths for
                        // small modules; min-projection would smear tilted bars).
                        runsFromGray(IntArray(copy.w) { x -> copy.gray[yy * copy.w + x] })
                            ?.let { grayRunLists += it }
                    }
                    if (robustMode) {
                        for (x in scanlineYs(copy.w, 3)) {
                            val xx = x.coerceIn(0, copy.w - 1)
                            runsFromGray(IntArray(copy.h) { yy -> copy.gray[yy * copy.w + xx] })
                                ?.let { grayRunLists += it }
                        }
                    }
                }
                // Min-projection gray profiles -> subpixel runs (glare recovery).
                for (profile in projectionLines) {
                    runsFromGray(profile)?.let { grayRunLists += it }
                }
                val votes = mutableMapOf<String, Int>()
                val revVotes = mutableMapOf<String, Int>()
                val details = mutableMapOf<String, CandidateDetail>()

                // Winner ranking is score = payload length × votes: a longer
                // checksum-validated parse explains more barcode modules, while
                // short parses validate by luck far more often (fewer constraints)
                // and would otherwise outvote the truth via correlated observations
                // (e.g. "01" ×10 vs "01864776" ×4). Agreement (≥2 votes) is still
                // required; score only ranks qualified keys. Tie-breaks: votes,
                // then length.
                fun pickWinner(): Map.Entry<String, Int>? = votes.entries
                    .filter { it.value >= 2 }
                    .maxWithOrNull(
                        compareBy(
                            { it.key.length * it.value },
                            { it.value },
                            { it.key.length },
                        ),
                    )

                for (variant in variants) {
                    ensureActive()
                    // Binary rows -> integer runs (fast path for clean labels).
                    val runLists = grayRunLists.toMutableList()
                    for (y in scanlineYs(working.height, if (robustMode) 9 else 5)) {
                        val yy = y.coerceIn(0, variant.height - 1)
                        runLengths(BooleanArray(variant.width) { x -> variant.get(x, yy) })
                            ?.let { runLists += it }
                    }
                    if (robustMode) {
                        for (x in scanlineYs(working.width, 3)) {
                            val xx = x.coerceIn(0, variant.width - 1)
                            runLengths(BooleanArray(variant.height) { yy -> variant.get(xx, yy) })
                                ?.let { runLists += it }
                        }
                    }
                    for (runs in runLists) {
                        ensureActive()
                        // Every checksum-validated candidate votes (no longest-only
                        // filter): a longer junk-extended window must not steal its
                        // observation's vote from the true sub-window it contains —
                        // truth validates everywhere the junk window does, plus more
                        // observations, so it accumulates at least as many votes.
                        // Short prefix artifacts scatter across distinct keys (each
                        // needs its own luck) while truth concentrates; the
                        // length×votes ranking below separates them. Forward and
                        // reverse directions compete; orientation tracked per key.
                        fun consider(candidate: String, rev: Boolean) {
                            if (candidate.length !in MsiCodeTable.MIN_DIGITS..MsiCodeTable.MAX_DIGITS + 2) return
                            val validation = MsiChecksumValidator.validate(candidate, checksumPolicy)
                            if (!validation.valid) return
                            val key = validation.payloadWithoutChecksum
                            votes[key] = (votes[key] ?: 0) + 1
                            if (rev) revVotes[key] = (revVotes[key] ?: 0) + 1
                            details.getOrPut(key) {
                                CandidateDetail(full = candidate, checksumStripped = true)
                            }
                        }
                        for (candidate in scanlineCandidates(runs)) consider(candidate, false)
                        for (candidate in scanlineCandidatesReversed(runs)) consider(candidate, true)
                    }
                    // No early return: accumulate ALL binarization variants, then take
                    // the max-vote winner (a later variant may out-vote an early one).
                }

                val winner = pickWinner()
                // All policies require ≥2 agreeing observations: a lone checksum
                // collision (1/10 per lottery, certain at scale across hundreds of
                // windows) must never be emitted. Fallback scales additionally
                // withhold short payloads (see minPayload); [minPayloadDigits]
                // raises the floor for hosts scanning longer SKUs.
                val outcome: DecodeOutcome =
                    if (winner != null &&
                        winner.key.length >= maxOf(minPayload, minPayloadDigits)
                    ) {
                        val rev = revVotes[winner.key] ?: 0
                        val upsideDown = frame.attemptRotation == 180 || rev * 2 > winner.value
                        success(winner.key, details.getValue(winner.key), frame, upsideDown)
                    } else {
                        DecodeOutcome.NotFound("MSI: no checksum-valid scanline")
                    }
                return outcome
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            return DecodeOutcome.Error(DecoderException("MSI decode failed", t), recoverable = true)
        }
    }

    private fun success(
        payload: String,
        detail: CandidateDetail,
        frame: ScanFrame,
        upsideDown: Boolean = frame.attemptRotation == 180,
    ): DecodeOutcome {
        return DecodeOutcome.Success(
            listOf(
                DecodedBarcode(
                    rawValue = payload,
                    symbology = Symbology.MSI_PLESSEY,
                    // All emitted results hold ≥2 agreeing observations (see decode).
                    confidence = 1.0f,
                    engineName = NAME,
                    checksumStripped = detail.checksumStripped,
                    isUpsideDown = upsideDown,
                ),
            ),
        )
    }

    // ------------------------------------------------------------------ image

    private fun downscaleIfNeeded(src: android.graphics.Bitmap): android.graphics.Bitmap {
        if (src.width <= WORKING_WIDTH) return src
        val scale = WORKING_WIDTH.toFloat() / src.width
        val h = (src.height * scale).toInt().coerceAtLeast(1)
        // filter=false (nearest): bilinear low-pass smears the narrow bars
        // pulse-width decoding depends on.
        return android.graphics.Bitmap.createScaledBitmap(src, WORKING_WIDTH, h, false)
    }

    /** Horizontal binary scanlines at [scanlineYs] positions (one per y). */
    internal fun scanlines(img: MsiBinarizer.BinaryImage, count: Int): List<BooleanArray> {
        if (img.height <= 0 || img.width <= 0) return emptyList()
        return scanlineYs(img.height, count).map { y ->
            BooleanArray(img.width) { x -> img.get(x, y.coerceIn(0, img.height - 1)) }
        }
    }

    /** Y positions for horizontal scanlines: full 5–95% band (shelf barcodes live at the bottom). */
    internal fun scanlineYs(h: Int, count: Int): List<Int> {
        if (h <= 0 || count <= 0) return emptyList()
        val top = (h * 0.05).toInt()
        val bottom = (h * 0.95).toInt()
        if (bottom <= top) return listOf(h / 2)
        return List(count) { i ->
            if (count == 1) (top + bottom) / 2
            else top + ((bottom - top) * i / (count - 1))
        }
    }

    /** Vertical scanlines (for 90/270° labels in robust mode); returned as row-equivalents. */
    internal fun verticalScanlines(img: MsiBinarizer.BinaryImage, count: Int): List<BooleanArray> {
        val cols = mutableListOf<BooleanArray>()
        if (img.width <= 0 || img.height <= 0) return cols
        val left = (img.width * 0.05).toInt()
        val right = (img.width * 0.95).toInt()
        if (right <= left) {
            cols += BooleanArray(img.height) { y -> img.get(img.width / 2, y) }
            return cols
        }
        for (i in 0 until count) {
            val x = if (count == 1) (left + right) / 2
            else left + ((right - left) * i / (count - 1))
            cols += BooleanArray(img.height) { y -> img.get(x.coerceIn(0, img.width - 1), y) }
        }
        return cols
    }

    // ------------------------------------------------------------- scanline 1D

    /**
     * Min-luminance projection profiles over horizontal y-bands.
     *
     * Reflective shelf labels fragment every single scanline (glare washes
     * different modules on different rows), so no one row holds the full barcode.
     * For each band, profile[x] = darkest luminance in the band: bars survive
     * wherever ANY row saw them, while background stays bright. Profiles stay in
     * grayscale: decoding uses subpixel runs ([runsFromGray]), which recover the
     * narrow/wide split that integer binarization destroys at ~3px modules.
     *
     * @param bandH Band height in px (16 keeps perspective-tilt smear ~2px).
     * @param step Band stride in px (8 robust / 16 default).
     */
    internal fun minProjectionProfiles(
        gray: IntArray,
        w: Int,
        h: Int,
        bandH: Int = 16,
        step: Int = 8,
    ): List<IntArray> {
        if (w <= 0 || h <= 0 || gray.size < w * h) return emptyList()
        val out = mutableListOf<IntArray>()
        var y0 = 0
        while (y0 < h) {
            val y1 = minOf(h, y0 + bandH)
            if (y1 - y0 < 4) break
            val profile = IntArray(w) { 255 }
            for (y in y0 until y1) {
                val base = y * w
                for (x in 0 until w) {
                    val v = gray[base + x]
                    if (v < profile[x]) profile[x] = v
                }
            }
            out += profile
            y0 += step
        }
        return out
    }

    /**
     * Decodes one binarized scanline forward-only.
     * Returns the full digit string INCLUDING check digit(s), or null.
     */
    internal fun decodeScanline(row: BooleanArray): String? =
        scanlineCandidates(row).firstOrNull()

    /**
     * All structurally valid candidates on one scanline (guards + table pass;
     * checksum is validated by the caller before voting).
     *
     * Why windows, not whole-row: a shelf-photo row crosses giant price text AND
     * the barcode. A global narrow/wide cut is set by the text strokes and
     * misclassifies every barcode module. Each candidate window (START..STOP for
     * some digit count) gets a LOCAL cut from its own runs, so text elsewhere on
     * the row cannot poison it. Candidate starts are black runs after quiet
     * (or row start); scale-free relative shape prefilters (wide bar > narrow
     * space, wide middle space) reject text windows before any cut is computed.
     */
    internal fun scanlineCandidates(row: BooleanArray): List<String> {
        val runs = runLengths(row) ?: return emptyList()
        return scanlineCandidates(runs)
    }

    /**
     * Window search over a run list (binary or subpixel-gray sourced).
     *
     * Why windows, not whole-row: a shelf-photo row crosses giant price text AND
     * the barcode. A global narrow/wide cut is set by the text strokes and
     * misclassifies every barcode module. Each candidate window (START..STOP for
     * some digit count) gets a LOCAL cut from its own runs, so text elsewhere on
     * the row cannot poison it. Candidate starts are black runs after quiet
     * (or row start); scale-free relative shape prefilters (wide bar > narrow
     * space, wide middle space) reject text windows before any cut is computed.
     */
    internal fun scanlineCandidates(
        runs: List<Run>,
    ): List<String> {
        if (runs.size < MIN_RUNS) return emptyList()
        val out = mutableListOf<String>()
        for (window in candidateWindows(runs)) {
            // Multi-cut: k-means cut ±15%. At ~3px modules a single cut lands
            // inside the narrow/wide overlap for some digits while a nearby
            // cut makes them exactly-or-one-off table entries (single-bit
            // repair + checksum arbitrate). Cheap: windows are tiny.
            val baseCut = splitThreshold(window.map { it.length })
            for (cut in listOf(baseCut, baseCut * 0.85f, baseCut * 1.15f).distinct()) {
                val elems = quantize(window, cut) ?: continue
                val digits = extractDigits(elems) ?: continue
                if (!out.contains(digits)) out += digits
            }
            // Soft fallback: same window, cut-independent relative guard strip +
            // per-digit least-squares fit. Rescues windows where blur shifts a
            // few runs across any global cut (hard path then fails the whole
            // window on one digit). Checksum + voting still gate precision.
            stripGuardsRelative(window)?.let { payload ->
                softDecodeDigits(payload)?.let { digits ->
                    if (!out.contains(digits)) out += digits
                }
            }
            // Keep scanning: a longer window from the same start may hold the
            // true length while a shorter one matched a prefix by luck.
        }
        return out
    }

    /**
     * Guard-passing windows (START..STOP for some digit count) over a run list.
     * Shared by structural decoding ([scanlineCandidates]): scale-free relative
     * shape prefilters + quiet boundaries, no thresholds involved.
     */
    internal fun candidateWindows(runs: List<Run>): List<List<Run>> {
        if (runs.size < MIN_RUNS) return emptyList()
        val out = mutableListOf<List<Run>>()
        // Black runs sit at even indices (runs alternate, starting with black).
        for (s in 0 until runs.size step 2) {
            if (!runs[s].isBlack) continue
            if (s > 0) {
                val before = runs[s - 1]
                if (before.isBlack || before.length < MIN_SEGMENT_QUIET) continue
            }
            // START prefilter (scale-free): wide bar followed by narrower space.
            if (s + 1 >= runs.size || runs[s + 1].isBlack) continue
            if (runs[s].length <= runs[s + 1].length) continue
            val maxDigits = minOf(
                MsiCodeTable.MAX_DIGITS + 2,
                (runs.size - s - GUARD_RUNS) / MsiCodeTable.ELEMENTS_PER_DIGIT,
            )
            for (n in MsiCodeTable.MIN_DIGITS..maxDigits) {
                val len = n * MsiCodeTable.ELEMENTS_PER_DIGIT + GUARD_RUNS
                if (s + len > runs.size) break
                // Window must end at a run boundary followed by quiet (or row end).
                if (s + len < runs.size) {
                    val after = runs[s + len]
                    if (after.isBlack || after.length < MIN_SEGMENT_QUIET) continue
                }
                val window = runs.subList(s, s + len)
                // STOP prefilter (scale-free): narrow, WIDE, narrow at the tail.
                val t = window.size
                if (!window[t - 3].isBlack || window[t - 2].isBlack || !window[t - 1].isBlack) continue
                if (window[t - 2].length <= window[t - 3].length) continue
                if (window[t - 2].length <= window[t - 1].length) continue
                out += window.toList()
            }
        }
        return out
    }

    /**
     * Reverse-direction (upside-down, 180°) windows over a run list.
     *
     * A 180°-rotated label scanned left-to-right reads the symbol backwards:
     * STOP-first with space-first digit chunks ([S3,B3,…,S0,B0]). Mirroring
     * preserves widths, so reversing each 8-element chunk recovers the forward
     * bar-first pattern and the existing table/soft machinery applies unchanged;
     * the final digit string is then un-reversed into print order. Guards are
     * checked relatively (STOP head is palindromic; reverse-START tail is a
     * narrow space + wide bar).
     */
    internal fun candidateWindowsReversed(runs: List<Run>): List<List<Run>> {
        if (runs.size < MIN_RUNS) return emptyList()
        val out = mutableListOf<List<Run>>()
        for (s in 0 until runs.size step 2) {
            if (!runs[s].isBlack) continue
            if (s > 0) {
                val before = runs[s - 1]
                if (before.isBlack || before.length < MIN_SEGMENT_QUIET) continue
            }
            // HEAD prefilter: STOP shape (narrow, WIDE, narrow).
            if (s + 2 >= runs.size) continue
            if (!runs[s].isBlack || runs[s + 1].isBlack || !runs[s + 2].isBlack) continue
            if (runs[s + 1].length <= runs[s].length) continue
            if (runs[s + 1].length <= runs[s + 2].length) continue
            val maxDigits = minOf(
                MsiCodeTable.MAX_DIGITS + 2,
                (runs.size - s - GUARD_RUNS) / MsiCodeTable.ELEMENTS_PER_DIGIT,
            )
            for (n in MsiCodeTable.MIN_DIGITS..maxDigits) {
                val len = n * MsiCodeTable.ELEMENTS_PER_DIGIT + GUARD_RUNS
                if (s + len > runs.size) break
                if (s + len < runs.size) {
                    val after = runs[s + len]
                    if (after.isBlack || after.length < MIN_SEGMENT_QUIET) continue
                }
                val window = runs.subList(s, s + len)
                // TAIL prefilter: reverse-START (narrow space + WIDE bar).
                val t = window.size
                if (window[t - 2].isBlack || !window[t - 1].isBlack) continue
                if (window[t - 1].length <= window[t - 2].length) continue
                out += window.toList()
            }
        }
        return out
    }

    /**
     * Decodes reverse-direction windows (see [candidateWindowsReversed]) into
     * print-order digit strings (checksums included, as usual).
     */
    internal fun scanlineCandidatesReversed(runs: List<Run>): List<String> {
        val out = mutableListOf<String>()
        for (window in candidateWindowsReversed(runs)) {
            val payload = stripGuardsReversed(window) ?: continue
            // Hard path: per-chunk wide flags, chunks un-mirrored, table lookup.
            val baseCut = splitThreshold(window.map { it.length })
            for (cut in listOf(baseCut, baseCut * 0.85f, baseCut * 1.15f).distinct()) {
                decodeReversedHard(payload, cut)?.let { digits ->
                    if (!out.contains(digits)) out += digits
                }
            }
            // Soft path: un-mirrored widths through the standard soft decoder.
            unmirrorRuns(payload)?.let { fwd ->
                softDecodeDigits(fwd)?.let { digits ->
                    if (!out.contains(digits)) out += digits
                }
            }
        }
        return out
    }

    /**
     * Strips reversed guards: STOP head (narrow,wide,narrow) + reverse-START
     * tail (narrow space, wide bar), all relative. Returns space-first digit runs.
     */
    internal fun stripGuardsReversed(window: List<Run>): List<Run>? {
        if (window.size < GUARD_RUNS + MsiCodeTable.ELEMENTS_PER_DIGIT * MsiCodeTable.MIN_DIGITS) {
            return null
        }
        if (window.isEmpty() || !window.first().isBlack) return null
        var from = 0
        var to = window.size
        if (to - from < 3 || !window[from].isBlack || window[from + 1].isBlack ||
            !window[from + 2].isBlack
        ) {
            return null
        }
        if (window[from + 1].length <= window[from].length) return null
        if (window[from + 1].length <= window[from + 2].length) return null
        from += 3
        if (to - from < 2 || window[to - 2].isBlack || !window[to - 1].isBlack) return null
        if (window[to - 1].length <= window[to - 2].length) return null
        to -= 2
        val payload = window.subList(from, to)
        if (payload.isEmpty() || payload.size % MsiCodeTable.ELEMENTS_PER_DIGIT != 0) return null
        val digits = payload.size / MsiCodeTable.ELEMENTS_PER_DIGIT
        if (digits !in MsiCodeTable.MIN_DIGITS..MsiCodeTable.MAX_DIGITS + 2) return null
        return payload.toList()
    }

    /**
     * Un-mirrors space-first digit runs into forward bar-first order so the
     * standard table/soft machinery applies. Returns print-REVERSED widths
     * (callers un-reverse the decoded string).
     */
    internal fun unmirrorRuns(payload: List<Run>): List<Run>? {
        if (payload.isEmpty() || payload.size % MsiCodeTable.ELEMENTS_PER_DIGIT != 0) return null
        val fwd = mutableListOf<Run>()
        var i = 0
        while (i + MsiCodeTable.ELEMENTS_PER_DIGIT <= payload.size) {
            val chunk = payload.subList(i, i + MsiCodeTable.ELEMENTS_PER_DIGIT)
            // Mirrored chunk is space-first [S3,B3,…,S0,B0]; forward order is its
            // exact reverse (mirror preserves widths).
            for (k in chunk.size - 1 downTo 0) {
                fwd += Run(fwd.size % 2 == 0, chunk[k].length)
            }
            i += MsiCodeTable.ELEMENTS_PER_DIGIT
        }
        return fwd
    }

    /** Hard decode of a reversed payload with per-window [cut]; print-order output. */
    internal fun decodeReversedHard(payload: List<Run>, cut: Float): String? {
        val fwd = unmirrorRuns(payload) ?: return null
        // Direct boolean mapping (guards already stripped — quantize() is only
        // for guarded windows), then the standard table + single-bit repair.
        val elems = fwd.map { it.length > cut }
        if (elems.size != payload.size) return null
        val sb = StringBuilder()
        var i = 0
        while (i + MsiCodeTable.ELEMENTS_PER_DIGIT <= elems.size) {
            val chunk = elems.subList(i, i + MsiCodeTable.ELEMENTS_PER_DIGIT).toBooleanArray()
            val packed = MsiCodeTable.pack(chunk)
            val digit = MsiCodeTable.FROM_PACKED[packed]
            if (digit == null) {
                val repaired = trySingleBitRepair(chunk) ?: return null
                sb.append(repaired)
            } else {
                sb.append(digit)
            }
            i += MsiCodeTable.ELEMENTS_PER_DIGIT
        }
        val s = sb.toString()
        if (s.length !in MsiCodeTable.MIN_DIGITS..MsiCodeTable.MAX_DIGITS + 2) return null
        return s.reversed()
    }

    /** Backward-compat overload: reversed=true reuses orientation expansion; kept for tests. */
    internal fun decodeScanline(row: BooleanArray, reversed: Boolean): String? {
        // NOTE: reversed pixel streams keep STOP-first guards and mirrored digit
        // bit order, so they only decode palindromic payloads. Callers should use
        // the 180° orientation candidate instead. Preserved for unit tests.
        return decodeScanline(if (reversed) row.reversedArray() else row)
    }

    internal data class Run(val isBlack: Boolean, val length: Float)

    internal fun runLengths(row: BooleanArray): List<Run>? {
        if (row.isEmpty()) return null
        var start = 0
        while (start < row.size && !row[start]) start++
        // All-white scanline (blank band / perpendicular bars): no content, not an error.
        if (start >= row.size) return null
        var end = row.size - 1
        while (end > start && !row[end]) end--
        if (end <= start) return null
        val leftQuiet = start
        val rightQuiet = row.size - 1 - end
        if (leftQuiet < MIN_QUIET || rightQuiet < MIN_QUIET) {
            // Lenient single-sided fallback only in robust mode (FP funnel otherwise).
            if (!robustMode) return null
            if (leftQuiet < MIN_QUIET / 2 && rightQuiet < MIN_QUIET / 2) return null
        }
        val runs = mutableListOf<Run>()
        var cur = row[start]
        var len = 0
        for (i in start..end) {
            if (row[i] == cur) len++
            else {
                runs += Run(cur, len.toFloat())
                cur = row[i]
                len = 1
            }
        }
        runs += Run(cur, len.toFloat())
        if (runs.isEmpty()) return null
        val cut = splitThreshold(runs.map { it.length })
        return deSpeckle(runs, cut)
    }

    /**
     * Subpixel run-lengths straight from grayscale (no binarization).
     *
     * At ~3px modules, integer binarization irrecoverably merges narrow/wide
     * distributions (both quantize to 3–4px). Instead: lightly smooth, find bar
     * cores (minima) and space cores (maxima) with a prominence floor, and place
     * each bar/space boundary at the linearly interpolated zero-crossing of the
     * per-edge adaptive level (mean of the two adjacent extrema). Widths come out
     * in fractional px (~0.3px accuracy), restoring the narrow/wide split that
     * binary runs destroy on small/blurry labels.
     *
     * @param prominence Minimum extrema height in gray levels (kills sensor noise).
     * @return Runs with fractional lengths, or null when no content/quiet zone.
     */
    internal fun runsFromGray(profile: IntArray, prominence: Int = 6): List<Run>? {
        if (profile.size < 16) return null
        // 3-tap smooth ([1,2,1]/4) to suppress single-pixel noise.
        val s = FloatArray(profile.size)
        s[0] = profile[0].toFloat()
        for (i in 1 until profile.size - 1) {
            s[i] = (profile[i - 1] + 2 * profile[i] + profile[i + 1]) / 4f
        }
        s[profile.size - 1] = profile.last().toFloat()
        // Extrema with prominence: alternating min/max strictly inside the profile.
        data class Ex(val pos: Int, val v: Float, val isMin: Boolean)
        val ext = mutableListOf<Ex>()
        var i = 1
        while (i < s.size - 1) {
            if (s[i] < s[i - 1] && s[i] <= s[i + 1]) {
                // Minimum (bar core); measure depth vs surrounding maxima later.
                ext += Ex(i, s[i], true)
            } else if (s[i] > s[i - 1] && s[i] >= s[i + 1]) {
                ext += Ex(i, s[i], false)
            }
            i++
        }
        if (ext.size < 6) return null
        // Drop shallow extrema (noise): keep those standing out by >= prominence
        // from BOTH neighbors; always keep first/last for boundary math.
        val kept = mutableListOf<Ex>()
        for (k in ext.indices) {
            if (k == 0 || k == ext.lastIndex) {
                kept += ext[k]
                continue
            }
            val left = abs(ext[k].v - ext[k - 1].v)
            val right = abs(ext[k].v - ext[k + 1].v)
            if (minOf(left, right) >= prominence) kept += ext[k]
        }
        // Re-alternate after filtering (drop consecutive same-polarity, keep deeper).
        val alt = mutableListOf<Ex>()
        for (e in kept) {
            val last = alt.lastOrNull()
            if (last != null && last.isMin == e.isMin) {
                if (e.isMin == (e.v < last.v)) {
                    alt[alt.lastIndex] = e // deeper extremum wins
                }
                continue
            }
            alt += e
        }
        if (alt.size < 6) return null
        // Quiet margins first: profile ends must sit near background. This veto is
        // load-bearing for precision: shelf-dark edges would otherwise flood
        // the window search with edge-junk observations that checksum-validate
        // by luck AND agree with each other (correlated bands). Windows keep
        // their own segment-quiet rule on top.
        val bg = estimateBackground(profile)
        var lead = 0
        while (lead < profile.size && abs(profile[lead] - bg) < QUIET_BG_TOL) lead++
        var trail = profile.size - 1
        while (trail > lead && abs(profile[trail] - bg) < QUIET_BG_TOL) trail--
        val leftQuiet = lead
        val rightQuiet = profile.size - 1 - trail
        if (leftQuiet < MIN_QUIET || rightQuiet < MIN_QUIET) {
            if (!robustMode) return null
            if (leftQuiet < MIN_QUIET / 2 && rightQuiet < MIN_QUIET / 2) return null
        }
        // Edges: linear-interpolated crossing of per-edge level (mean of extrema).
        val edges = mutableListOf<Float>()
        for (k in 0 until alt.size - 1) {
            val a = alt[k]
            val b = alt[k + 1]
            val level = (a.v + b.v) / 2f
            // Walk from a.pos toward b.pos to the crossing.
            val from = a.pos
            val to = b.pos
            var edge = -1f
            var p = from
            while (p < to) {
                val v0 = s[p] - level
                val v1 = s[p + 1] - level
                if ((v0 <= 0 && v1 >= 0) || (v0 >= 0 && v1 <= 0)) {
                    val denom = (v1 - v0)
                    val frac = if (abs(denom) < 1e-6f) 0.5f else (-v0 / denom).coerceIn(0f, 1f)
                    edge = p + frac
                    break
                }
                p++
            }
            if (edge < 0) return null // non-monotonic pair; bail on this profile
            edges += edge
        }
        if (edges.size < 5) return null
        // Bound the edge list by content margins. The first falling flank
        // (quiet -> first bar) has no preceding maximum, so without this the
        // leading bar is silently dropped and every window slices off-phase
        // (clean images decode shifted garbage instead of the payload!).
        // Same for the trailing flank. Levels for the margin intervals reuse
        // the adjacent pair's level.
        data class Bounded(val edge: Float, val level: Float)
        val bounded = mutableListOf<Bounded>()
        bounded += Bounded(lead.toFloat(), (alt[0].v + alt[1].v) / 2f)
        for (k in edges.indices) {
            val level = if (k < alt.size - 1) {
                (alt[k].v + alt[k + 1].v) / 2f
            } else {
                (alt[alt.size - 2].v + alt[alt.size - 1].v) / 2f
            }
            bounded += Bounded(edges[k], level)
        }
        bounded += Bounded(
            (trail + 1).toFloat(),
            (alt[alt.size - 2].v + alt[alt.size - 1].v) / 2f,
        )
        // Runs between consecutive bounds; polarity per interval from its midpoint
        // vs its own level. Sub-0.25px slivers are skipped (dust/rounding — the
        // merge pass below repairs polarity), never fatal.
        val runs = mutableListOf<Run>()
        for (k in 0 until bounded.size - 1) {
            val w = bounded[k + 1].edge - bounded[k].edge
            if (w < 0.25f) continue
            val mid = ((bounded[k].edge + bounded[k + 1].edge) / 2f).toInt()
                .coerceIn(0, s.size - 1)
            runs += Run(s[mid] < bounded[k].level, w)
        }
        if (runs.size < MIN_RUNS) return null
        // Normalize: merge consecutive same-polarity intervals. Midpoint sampling
        // near an edge level can misclassify a flat/noisy interval, producing
        // same-polarity neighbors that would break the bar-first alternation the
        // window search relies on.
        val merged = mutableListOf<Run>()
        for (r in runs) {
            val last = merged.lastOrNull()
            if (last != null && last.isBlack == r.isBlack) {
                merged[merged.lastIndex] = Run(last.isBlack, last.length + r.length)
            } else {
                merged += r
            }
        }
        if (merged.size < MIN_RUNS) return null
        // Strict alternation starting AND ending with black (quiet remnants dropped;
        // quiet itself was validated above, and window search assumes bar-first).
        val trimmed = merged
            .let { if (it.isNotEmpty() && !it.first().isBlack) it.drop(1) else it }
            .let { if (it.isNotEmpty() && !it.last().isBlack) it.dropLast(1) else it }
        if (trimmed.size < MIN_RUNS) return null
        val cut = splitThreshold(trimmed.map { it.length })
        // Gentle: only sub-pixel interpolation dust is merged (floor 0.9px,
        // ratio 4); washed-out ~1px narrow spaces are real signal (see deSpeckle).
        return deSpeckle(trimmed, cut, floorPx = 0.9f, ratio = 4)
    }

    private fun estimateBackground(profile: IntArray): Int {
        // 70th percentile of the whole profile: background dominates (bars are
        // narrow dips), and dark shelf/text at the ends can't drag it down the
        // way an ends-median can.
        if (profile.isEmpty()) return 255
        val sorted = profile.sorted()
        return sorted[(sorted.size * 7 / 10).coerceIn(0, sorted.size - 1)]
    }

    /**
     * Merges sub-module speckle. Relative (not absolute 1px): only runs shorter
     * than [floorPx], with both neighbors ≥ [ratio]× the speckle, are merged.
     *
     * Defaults suit binarized rows (1px dust is common). The gray subpixel path
     * passes a sub-1px floor with ratio 4: washed-out 1px narrow spaces are REAL
     * signal (merging them fuses bars into B11-class artifacts), while only
     * interpolation dust (< 1px) is removed.
     */
    internal fun deSpeckle(
        runs: List<Run>,
        cut: Float,
        floorPx: Float = maxOf(1f, cut * 0.25f),
        ratio: Int = 3,
    ): List<Run> {
        if (runs.size < 3) return runs
        val out = mutableListOf<Run>()
        var i = 0
        while (i < runs.size) {
            val r = runs[i]
            if (r.length <= floorPx && out.isNotEmpty() && i + 1 < runs.size) {
                val prev = out.last()
                val next = runs[i + 1]
                if (prev.length >= ratio * r.length && next.length >= ratio * r.length) {
                    out[out.lastIndex] = Run(prev.isBlack, prev.length + r.length + next.length)
                    i += 2
                    continue
                }
            }
            out += r
            i++
        }
        return out
    }

    /** Legacy overload for tests (absolute 1px rule). Prefer [deSpeckle] with cut. */
    private fun deSpeckle(runs: List<Run>): List<Run> {
        if (runs.isEmpty()) return runs
        val cut = splitThreshold(runs.map { it.length })
        return deSpeckle(runs, cut)
    }

    /**
     * 1-D k-means (k=2) over run lengths -> threshold between narrow and wide.
     * Falls back to lo×1.5 when degenerate (all equal).
     */
    internal fun splitThreshold(lengths: List<Float>): Float {
        if (lengths.isEmpty()) return 2f
        var lo = lengths.min()
        var hi = lengths.max()
        if (hi - lo < 0.5f) return lo * 1.5f
        var threshold = (lo + hi) / 2f
        repeat(10) {
            var sumLo = 0f
            var nLo = 0
            var sumHi = 0f
            var nHi = 0
            for (l in lengths) {
                if (l <= threshold) {
                    sumLo += l
                    nLo++
                } else {
                    sumHi += l
                    nHi++
                }
            }
            if (nLo == 0 || nHi == 0) return threshold
            val next = ((sumLo / nLo) + (sumHi / nHi)) / 2f
            if (abs(next - threshold) < 0.01f) return next
            threshold = next
        }
        return threshold
    }

    /**
     * Quantizes runs to wide/narrow elements and strips MSI guards.
     *
     * Spec framing: START = wide bar + narrow space (2 runs); STOP = narrow bar
     * + wide space + narrow bar (3 runs). Width judged against [cut]; polarity
     * must match exactly or the scanline is rejected (no lenient fallback —
     * off-phase slicing is the misread funnel).
     */
    internal fun quantize(runs: List<Run>, cut: Float): List<Boolean>? {
        if (runs.isEmpty()) return null
        val wide = runs.map { it.length > cut }
        val black = runs.map { it.isBlack }
        // Must start with a bar (runLengths trims leading white; double-check phase).
        if (black.isEmpty() || !black.first()) return null
        var from = 0
        var to = runs.size
        // START: wide black + narrow white.
        if (to - from >= 2 && black[from] && wide[from] && !black[from + 1] && !wide[from + 1]) {
            from += 2
        } else {
            return null // missing START guard
        }
        // STOP: narrow black + wide white + narrow black.
        if (to - from >= 3 &&
            black[to - 3] && !wide[to - 3] &&
            !black[to - 2] && wide[to - 2] &&
            black[to - 1] && !wide[to - 1]
        ) {
            to -= 3
        } else {
            return null // missing STOP guard
        }
        val count = to - from
        if (count < MsiCodeTable.ELEMENTS_PER_DIGIT * MsiCodeTable.MIN_DIGITS) return null
        if (count % MsiCodeTable.ELEMENTS_PER_DIGIT != 0) return null
        return wide.subList(from, to).toList()
    }

    internal fun extractDigits(elements: List<Boolean>): String? {
        return extractDigits(elements, startsWithBar = true)
    }

    internal fun extractDigits(elements: List<Boolean>, startsWithBar: Boolean): String? {
        if (!startsWithBar) return null // bar-first phase enforced post-strip
        if (elements.isEmpty() || elements.size % MsiCodeTable.ELEMENTS_PER_DIGIT != 0) return null
        val sb = StringBuilder()
        var i = 0
        while (i + MsiCodeTable.ELEMENTS_PER_DIGIT <= elements.size) {
            val chunk = elements.subList(i, i + MsiCodeTable.ELEMENTS_PER_DIGIT).toBooleanArray()
            val packed = MsiCodeTable.pack(chunk)
            val digit = MsiCodeTable.FROM_PACKED[packed]
            if (digit == null) {
                // Single-element error tolerance: try flipping each bit once (blur repair).
                val repaired = trySingleBitRepair(chunk) ?: return null
                sb.append(repaired)
            } else {
                sb.append(digit)
            }
            i += MsiCodeTable.ELEMENTS_PER_DIGIT
        }
        val s = sb.toString()
        if (s.length !in MsiCodeTable.MIN_DIGITS..MsiCodeTable.MAX_DIGITS + 2) return null
        return s
    }

    /** Flips each of the 8 bits once; returns digit if exactly one repair matches. */
    internal fun trySingleBitRepair(chunk: BooleanArray): Char? {
        var found: Char? = null
        var count = 0
        for (b in chunk.indices) {
            chunk[b] = !chunk[b]
            val d = MsiCodeTable.FROM_PACKED[MsiCodeTable.pack(chunk)]
            chunk[b] = !chunk[b]
            if (d != null) {
                found = d
                count++
                if (count > 1) return null // ambiguous
            }
        }
        return found
    }

    /**
     * Cut-independent guard strip using only relative comparisons: START is a
     * bar wider than the following space; STOP is narrow, WIDE, narrow.
     * Returns the digit-payload runs, or null.
     */
    internal fun stripGuardsRelative(runs: List<Run>): List<Run>? {
        if (runs.size < MsiCodeTable.ELEMENTS_PER_DIGIT * MsiCodeTable.MIN_DIGITS + GUARD_RUNS) {
            return null
        }
        if (runs.isEmpty() || !runs.first().isBlack) return null
        var from = 0
        var to = runs.size
        if (to - from < 2 || !runs[from].isBlack || runs[from + 1].isBlack) return null
        if (runs[from].length <= runs[from + 1].length) return null
        from += 2
        if (to - from < 3) return null
        if (!runs[to - 3].isBlack || runs[to - 2].isBlack || !runs[to - 1].isBlack) return null
        if (runs[to - 2].length <= runs[to - 3].length) return null
        if (runs[to - 2].length <= runs[to - 1].length) return null
        to -= 3
        val payload = runs.subList(from, to)
        if (payload.isEmpty() || payload.size % MsiCodeTable.ELEMENTS_PER_DIGIT != 0) return null
        val digits = payload.size / MsiCodeTable.ELEMENTS_PER_DIGIT
        if (digits !in MsiCodeTable.MIN_DIGITS..MsiCodeTable.MAX_DIGITS + 2) return null
        return payload.toList()
    }

    /**
     * Soft per-digit decoder: least-squares fit of each 8-run chunk against all
     * 10 digit patterns with a per-digit base unit (mean/12, since every digit
     * has exactly 4 narrow + 4 wide elements). Rescues digits where blur shifts
     * runs across any global cut. Every digit must fit within [maxErrPerDigit]
     * (normalized squared error); checksum + voting still gate precision.
     */
    internal fun softDecodeDigits(payload: List<Run>, maxErrPerDigit: Float = SOFT_ERR_BUDGET): String? {
        val hyps = softDecodeDetailed(payload) ?: return null
        val sb = StringBuilder()
        for (h in hyps) {
            if (h.bestErr > maxErrPerDigit) return null
            sb.append(h.best)
        }
        val s = sb.toString()
        if (s.length !in MsiCodeTable.MIN_DIGITS..MsiCodeTable.MAX_DIGITS + 2) return null
        return s
    }

    /** Per-digit soft hypothesis: best pattern with its normalized error. */
    internal data class DigitHyp(
        val best: Char,
        val bestErr: Float,
    )

    /** Soft fit without acceptance gate (see [softDecodeDigits]); null on malformed input. */    internal fun softDecodeDetailed(payload: List<Run>): List<DigitHyp>? {
        if (payload.isEmpty() || payload.size % MsiCodeTable.ELEMENTS_PER_DIGIT != 0) return null
        val out = mutableListOf<DigitHyp>()
        var i = 0
        while (i + MsiCodeTable.ELEMENTS_PER_DIGIT <= payload.size) {
            val chunk = payload.subList(i, i + MsiCodeTable.ELEMENTS_PER_DIGIT)
            var sum = 0f
            for (r in chunk) sum += r.length
            val base = sum / 12f
            if (base < 0.5f) return null
            var best = ' '
            var bestErr = Float.MAX_VALUE
            for ((digit, pattern) in MsiCodeTable.DIGITS) {
                var err = 0f
                for (b in pattern.indices) {
                    val expected = if (pattern[b]) 2f * base else base
                    val d = (chunk[b].length - expected) / base
                    err += d * d
                }
                if (err < bestErr) {
                    best = digit
                    bestErr = err
                }
            }
            if (best == ' ') return null
            out += DigitHyp(best, bestErr)
            i += MsiCodeTable.ELEMENTS_PER_DIGIT
        }
        return out
    }

    private data class CandidateDetail(
        val full: String,
        val checksumStripped: Boolean,
    )

    companion object {
        const val NAME = "MsiPlessey"
        const val WORKING_WIDTH = 640
        /** Primary grayscale width for the subpixel path (bilinear). */
        const val PRIMARY_GRAY_WIDTH = 960
        /** Fallback grayscale width for tiny modules (tried only on primary miss). */
        const val FALLBACK_GRAY_WIDTH = 1440
        /** Fallback hits shorter than this are withheld (Luhn luck at this scale). */
        const val MIN_FALLBACK_DIGITS = 6
        const val MIN_RUNS = 12
        const val MIN_QUIET = 6
        /** START(2) + STOP(3) guard runs framing every candidate window. */
        const val GUARD_RUNS = 5
        /** White gap required around a candidate window (segment quiet zone). */
        const val MIN_SEGMENT_QUIET = 4
        /** Gray-level tolerance for "near background" in quiet detection. */
        const val QUIET_BG_TOL = 12
        /** Per-digit soft error budget for direct accepts (see softDecodeDigits). */
        const val SOFT_ERR_BUDGET = 0.9f
    }
}
