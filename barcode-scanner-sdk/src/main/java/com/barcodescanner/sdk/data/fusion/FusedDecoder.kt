package com.barcodescanner.sdk.data.fusion

import com.barcodescanner.sdk.data.msi.MsiPlesseyDecoder
import com.barcodescanner.sdk.domain.decoder.BarcodeDecoder
import com.barcodescanner.sdk.domain.decoder.DecodeOutcome
import com.barcodescanner.sdk.domain.decoder.DecoderException
import com.barcodescanner.sdk.domain.decoder.DecoderRegistry
import com.barcodescanner.sdk.domain.decoder.LastResortDecoder
import com.barcodescanner.sdk.domain.model.DecodedBarcode
import com.barcodescanner.sdk.domain.model.ScanFrame
import com.barcodescanner.sdk.domain.model.ScannerConfig
import com.barcodescanner.sdk.domain.model.Symbology
import com.barcodescanner.sdk.domain.pipeline.OrientationCandidates
import com.barcodescanner.sdk.domain.pipeline.PreprocessingPipeline
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withTimeout

/**
 * Fusion decoder: preprocessing -> multi-orientation -> engine chain -> voting.
 *
 * Engine order respects [DecoderRegistry] registration order (default assembly in
 * ScannerContainer: ML Kit -> zxing-cpp -> MSI). To add an engine, register it —
 * fusion needs no code change (Open/Closed Principle). [LastResortDecoder]s
 * (e.g. the MSI OCR text fallback) are deferred out of the orientation loop and
 * run once per frame, only when every primary engine missed.
 *
 * Semantics:
 *  - the WHOLE decode — preprocessing, orientation expansion, engine attempts —
 *    runs under [ScannerConfig.decodeTimeoutMillis] (transforms check
 *    cancellation between stages);
 *  - when a [LastResortDecoder] is registered, the primary bar phase stops at
 *    [BAR_PHASE_PERCENT] of that budget and the remainder is reserved for the
 *    fallback, so robust mode cannot starve it;
 *  - per orientation candidate, each primary engine whose
 *    [BarcodeDecoder.supportedSymbologies] intersects the enabled set is attempted;
 *  - hits below [ScannerConfig.minConfidence] are ignored; hits at/above
 *    [CONFIDENT_THRESHOLD] stop the scan immediately (first-confident-wins);
 *  - otherwise candidates pool across orientations and the highest-confidence
 *    pooled result wins (deduped on value+symbology, max confidence kept);
 *  - on timeout expiry the best-so-far pooled result is returned instead of
 *    failing.
 *
 * Fixed confidences are documented couplings, not measurements: ML Kit 0.95,
 * ZXing 0.9, MSI 1.0 (always ≥2 agreeing observations to emit). Setting
 * [ScannerConfig.minConfidence] above 0.9 therefore disables ZXing, above 0.95
 * disables ML Kit — set 0.5 default.
 *
 * Robustness layers: preprocessing contrast normalization, upright-first
 * orientation expansion (2 attempts default, 4 robust — upright is relative to
 * the frame's sensor rotation), per-engine binarization retries.
 */
class FusedDecoder(
    private val registry: DecoderRegistry,
    private val pipeline: PreprocessingPipeline,
    private val config: ScannerConfig,
) : BarcodeDecoder {

    override val name: String = NAME
    /**
     * Live view of the enabled set (not a construction-time snapshot): hosts
     * may register additional decoders at runtime and [DecoderRegistry] order
     * drives execution, while this set drives filtering. Since [ScannerConfig]
     * is immutable the value is stable per container, but a getter avoids a
     * stale copy if the decoder outlives registry mutations.
     */
    override val supportedSymbologies: Set<Symbology>
        get() = config.enabledSymbologies

    override suspend fun decode(frame: ScanFrame): DecodeOutcome {
        // Owned by this call; recycled below unless decoding was cancelled
        // (timeout or caller cancellation): a cancelled ML Kit/OCR task may still
        // be reading a bitmap, so recycling it would be a use-after-recycle.
        var processed: ScanFrame = frame
        var recycleBitmaps = true
        val consumed = mutableListOf<ScanFrame>()
        try {
            val pool = mutableListOf<DecodedBarcode>()
            try {
                withTimeout(config.decodeTimeoutMillis) {
                    processed = try {
                        pipeline.process(frame)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (t: Throwable) {
                        frame // preprocessing must never kill a scan
                    }
                    val snapshot = registry.snapshot()
                    val eager = snapshot.filterNot { it is LastResortDecoder }
                    val lastResort = snapshot.filterIsInstance<LastResortDecoder>()
                    // Last-resort engines (OCR) get a reserved slice of the frame
                    // budget. Robust mode's extra orientations/binarizations/
                    // TryHarder may legitimately run long, but must never starve
                    // the text fallback by consuming the whole timeout first
                    // (regression: deferring OCR to the end failed in robust mode).
                    // No last-resort engines -> the bar phase may use it all.
                    val barDeadlineNanos = if (lastResort.isEmpty()) {
                        Long.MAX_VALUE
                    } else {
                        System.nanoTime() +
                            (config.decodeTimeoutMillis * BAR_PHASE_PERCENT / 100)
                                .coerceAtLeast(1L) * NANOS_PER_MILLI
                    }

                    // Lazy expansion: rotations materialize on demand, so a
                    // first-orientation confident hit (the common live case)
                    // skips the remaining allocs. Rotated copies are owned by us;
                    // recycle consumed ones after use (base bitmap excluded —
                    // never recycle the caller's bitmap).
                    //
                    // Engines that resolve orientation internally (ML Kit hint,
                    // zxing TryRotate) are fed the primary candidate only, so they
                    // are excluded when deciding whether rotations are needed at
                    // all: a registry of such engines never allocates them.
                    val needsRotatedCandidates = eager.any { !it.resolvesOrientationInternally }
                    val maxCandidates = if (needsRotatedCandidates) {
                        config.maxOrientationsTried
                    } else {
                        1
                    }
                    orientationLoop@ for (candidate in OrientationCandidates.expandLazy(
                        processed,
                        maxCandidates,
                    )) {
                        consumed += candidate
                        ensureActive()
                        if (System.nanoTime() >= barDeadlineNanos) break@orientationLoop
                        for (decoder in eager) {
                            ensureActive()
                            if (System.nanoTime() >= barDeadlineNanos) break@orientationLoop
                            // Internal-rotation engines would get equivalent input
                            // on a physically rotated candidate: skip (dedup).
                            if (candidate.relativeRotation != 0 &&
                                decoder.resolvesOrientationInternally
                            ) {
                                continue
                            }
                            if (!wantsFrame(decoder, candidate)) continue
                            runDecoder(decoder, candidate, pool)
                            val confident = (pool.maxByOrNull { it.confidence }?.confidence ?: 0f) >=
                                CONFIDENT_THRESHOLD
                            if (confident) break@orientationLoop
                        }
                    }
                    // Expensive last-resort engines (OCR text fallback) run at
                    // most ONCE per frame, only when every bar engine missed, in
                    // the budget slice reserved above. The decoder compensates
                    // sensor rotation, so the base view is enough.
                    if (pool.isEmpty() && lastResort.isNotEmpty()) {
                        val base = if (processed.attemptRotation == 0) {
                            processed
                        } else {
                            processed.copy(attemptRotation = 0)
                        }
                        for (decoder in lastResort) {
                            ensureActive()
                            if (!wantsFrame(decoder, base)) continue
                            runDecoder(decoder, base, pool)
                        }
                    }
                }
            } catch (e: TimeoutCancellationException) {
                // Fall through to best-so-far pooling; caller's cancellation still
                // propagates because TimeoutCancellationException is only thrown by
                // OUR withTimeout. Do not recycle: an async engine may still read.
                recycleBitmaps = false
                currentCoroutineContext().ensureActive()
            } catch (e: CancellationException) {
                recycleBitmaps = false
                throw e
            } catch (e: DecoderException) {
                return DecodeOutcome.Error(e, recoverable = false)
            } catch (e: Exception) {
                return DecodeOutcome.Error(DecoderException("Fusion failed", e), recoverable = false)
            }

            if (pool.isEmpty()) return DecodeOutcome.NotFound("fusion: no engine decoded the frame")
            val ranked = pool.sortedByDescending { it.confidence }
            return DecodeOutcome.Success(ranked)
        } finally {
            // Recycle physically rotated copies; never the caller's base bitmap.
            if (recycleBitmaps) {
                for (candidate in consumed) {
                    if (candidate.bitmap !== processed.bitmap && candidate.bitmap !== frame.bitmap) {
                        runCatching { candidate.bitmap.recycle() }
                    }
                }
                if (processed.bitmap !== frame.bitmap) {
                    runCatching { processed.bitmap.recycle() }
                }
            }
        }
    }

    /** Attempts one engine and folds accepted hits into [pool]. */
    private suspend fun runDecoder(
        decoder: BarcodeDecoder,
        candidate: ScanFrame,
        pool: MutableList<DecodedBarcode>,
    ) {
        when (val outcome = decoder.decode(candidate)) {
            is DecodeOutcome.Success -> {
                val accepted = outcome.barcodes
                    .filter { it.symbology in config.enabledSymbologies }
                    .filter { it.confidence >= config.minConfidence }
                if (accepted.isNotEmpty()) mergeIntoPool(pool, accepted)
            }
            is DecodeOutcome.Error -> {
                if (!outcome.recoverable) throw outcome.cause
            }
            is DecodeOutcome.NotFound -> Unit
        }
    }

    /** Dedups pool on (value, symbology), keeping max confidence. */
    private fun mergeIntoPool(pool: MutableList<DecodedBarcode>, accepted: List<DecodedBarcode>) {
        for (b in accepted) {
            val idx = pool.indexOfFirst { it.rawValue == b.rawValue && it.symbology == b.symbology }
            if (idx < 0) pool += b
            else if (b.confidence > pool[idx].confidence) pool[idx] = b
        }
    }

    private fun wantsFrame(decoder: BarcodeDecoder, frame: ScanFrame): Boolean {
        if (decoder.supportedSymbologies.intersect(config.enabledSymbologies).isEmpty()) {
            return false
        }
        if (decoder.name == MsiPlesseyDecoder.NAME) {
            // MSI scanline decoding is the most expensive bar step; sideways views
            // are only attempted in robust mode (which also enables vertical
            // scanlines). "Sideways" is relative to upright: camera frames arrive
            // rotated (rotationDegrees), so the upright candidate is not always
            // attemptRotation=0.
            if (!config.robustMode && frame.relativeRotation in setOf(90, 270)) return false
        }
        return true
    }

    companion object {
        const val NAME = "Fused"
        /**
         * 0.95: ML Kit (0.95) and MSI-verified (1.0) stop immediately; ZXing (0.9)
         * pools instead of short-circuiting a possibly higher-confidence MSI hit
         * on the same orientation.
         */
        const val CONFIDENT_THRESHOLD = 0.95f

        /**
         * Share of [ScannerConfig.decodeTimeoutMillis] the primary bar engines may
         * consume when a last-resort engine is registered (the rest is reserved for
         * it). Without the reservation, robust mode's extra orientations/
         * binarizations/TryHarder can consume the entire timeout and the OCR text
         * fallback never gets to run on a bar miss.
         */
        const val BAR_PHASE_PERCENT = 60

        private const val NANOS_PER_MILLI = 1_000_000L
    }
}
