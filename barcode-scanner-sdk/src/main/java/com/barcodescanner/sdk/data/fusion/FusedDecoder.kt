package com.barcodescanner.sdk.data.fusion

import com.barcodescanner.sdk.domain.decoder.BarcodeDecoder
import com.barcodescanner.sdk.domain.decoder.DecodeOutcome
import com.barcodescanner.sdk.domain.decoder.DecoderException
import com.barcodescanner.sdk.domain.decoder.DecoderRegistry
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
 * fusion needs no code change (Open/Closed Principle).
 *
 * Semantics:
 *  - per orientation candidate, each engine whose [BarcodeDecoder.supportedSymbologies]
 *    intersects the enabled set is attempted;
 *  - hits below [ScannerConfig.minConfidence] are ignored; hits at/above
 *    [CONFIDENT_THRESHOLD] stop the scan immediately (first-confident-wins);
 *  - otherwise candidates pool across orientations and the highest-confidence
 *    pooled result wins (deduped on value+symbology, max confidence kept);
 *  - on [ScannerConfig.decodeTimeoutMillis] expiry the best-so-far pooled result
 *    is returned instead of failing.
 *
 * Fixed confidences are documented couplings, not measurements: ML Kit 0.95,
 * ZXing 0.9, MSI 1.0 (always ≥2 agreeing observations to emit). Setting
 * [ScannerConfig.minConfidence] above 0.9 therefore disables ZXing, above 0.95
 * disables ML Kit — set 0.5 default.
 *
 * Robustness layers: preprocessing contrast normalization, orientation expansion
 * (0/180° default, 0/90/180/270° robust), per-engine binarization retries.
 */
class FusedDecoder(
    private val registry: DecoderRegistry,
    private val pipeline: PreprocessingPipeline,
    private val config: ScannerConfig,
) : BarcodeDecoder {

    override val name: String = NAME
    override val supportedSymbologies: Set<Symbology> = config.enabledSymbologies

    override suspend fun decode(frame: ScanFrame): DecodeOutcome {
        val processed: ScanFrame = try {
            pipeline.process(frame)
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            frame // preprocessing must never kill a scan
        }

        // Lazy expansion: rotations materialize on demand, so a first-orientation
        // confident hit (the common live case) skips the remaining allocs.
        // Rotated copies are owned by us; recycle consumed ones after use
        // (base bitmap excluded — never recycle the caller's bitmap).
        val consumed = mutableListOf<ScanFrame>()
        try {
            val pool = mutableListOf<DecodedBarcode>()
            try {
                withTimeout(config.decodeTimeoutMillis) {
                    orientationLoop@ for (candidate in OrientationCandidates.expandLazy(
                        processed,
                        config.maxOrientationsTried,
                    )) {
                        consumed += candidate
                        ensureActive()
                        for (decoder in registry.snapshot()) {
                            ensureActive()
                            if (!wantsFrame(decoder, candidate)) continue
                            when (val outcome = decoder.decode(candidate)) {
                                is DecodeOutcome.Success -> {
                                    val accepted = outcome.barcodes
                                        .filter { it.symbology in config.enabledSymbologies }
                                        .filter { it.confidence >= config.minConfidence }
                                    if (accepted.isEmpty()) continue
                                    mergeIntoPool(pool, accepted)
                                    val best = pool.maxBy { it.confidence }
                                    if (best.confidence >= CONFIDENT_THRESHOLD) {
                                        break@orientationLoop
                                    }
                                }
                                is DecodeOutcome.Error -> {
                                    if (!outcome.recoverable) throw outcome.cause
                                }
                                is DecodeOutcome.NotFound -> Unit
                            }
                        }
                    }
                }
            } catch (e: TimeoutCancellationException) {
                // Fall through to best-so-far pooling; caller's cancellation still propagates
                // because TimeoutCancellationException is only thrown by OUR withTimeout.
                currentCoroutineContext().ensureActive()
            } catch (e: CancellationException) {
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
        if (decoder.name == com.barcodescanner.sdk.data.msi.MsiPlesseyDecoder.NAME) {
            if (Symbology.MSI_PLESSEY !in config.enabledSymbologies) return false
            // MSI scanline decoding is the most expensive step; sideways rotations
            // are only attempted in robust mode (which also enables vertical scanlines).
            if (!config.robustMode && frame.attemptRotation in setOf(90, 270)) return false
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
    }
}
