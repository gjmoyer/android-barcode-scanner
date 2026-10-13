package com.barcodescanner.sdk.data.fusion

import com.barcodescanner.sdk.data.frame.LumaFrame
import com.barcodescanner.sdk.domain.decoder.BarcodeDecoder
import com.barcodescanner.sdk.domain.decoder.DecodeOutcome
import com.barcodescanner.sdk.domain.decoder.DecoderException
import com.barcodescanner.sdk.domain.model.DecodedBarcode
import com.barcodescanner.sdk.domain.model.ScannerConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withTimeout

/**
 * One engine as the luma path sees it: [decoder] supplies the name and
 * symbology routing, [decodeLuma] reads the shared upright [LumaFrame] in
 * place. A wrapper rather than an interface because the public engine classes
 * cannot implement an internal interface without leaking [LumaFrame].
 */
internal class LumaEngine(
    val decoder: BarcodeDecoder,
    private val decodeLuma: suspend (LumaFrame) -> DecodeOutcome,
) {
    val name: String get() = decoder.name

    suspend fun decode(frame: LumaFrame): DecodeOutcome = decodeLuma(frame)
}

/**
 * Outcome of one luma decode. [bufferReusable] is false when the decode timed
 * out: an engine may still be reading [LumaFrame.buffer] (a cancelled ML Kit
 * task keeps running on ML Kit's executor), so the owner must drop that buffer
 * instead of preparing the next frame into it.
 */
internal class LumaDecode(val outcome: DecodeOutcome, val bufferReusable: Boolean)

/**
 * The SDK's only fusion: ONE prepared [LumaFrame] — a live aim-box crop or a
 * converted still, already gray and upright (live crops also
 * contrast-stretched; native, see
 * [com.barcodescanner.sdk.data.frame.NativeFrame]) — read in place by every
 * engine. Nothing here converts, crops, rotates or copies pixels, and no
 * engine sees a second variant of the frame.
 *
 * No Kotlin orientation expansion: MSI tries orientations natively per
 * [LumaFrame.maxOrientations] (2 live, [ScannerConfig.maxOrientationsTried]
 * for stills); ML Kit and zxing resolve rotation internally. Engines run in
 * the given order (ML Kit -> MSI -> zxing) under one
 * [ScannerConfig.decodeTimeoutMillis] budget with best-so-far on expiry — a
 * slow last engine (zxing TryHarder on a large still) can be cut off. Hits
 * below [ScannerConfig.minConfidence] or outside the enabled set are ignored,
 * the pool is deduped on value + symbology keeping the highest confidence,
 * the first hit at [CONFIDENT_THRESHOLD] stops the frame and a
 * non-recoverable engine error fails it.
 *
 * Cancellation by the caller propagates; the frame owner must then treat the
 * buffer as still in use, exactly as for `bufferReusable = false`.
 */
internal class LumaFusion(
    engines: List<LumaEngine>,
    private val config: ScannerConfig,
) {
    /** Engines with at least one enabled symbology, in priority order. */
    val engines: List<LumaEngine> = engines.filter {
        it.decoder.supportedSymbologies.intersect(config.enabledSymbologies).isNotEmpty()
    }

    suspend fun decode(frame: LumaFrame): LumaDecode {
        val startNanos = System.nanoTime()
        val pool = mutableListOf<DecodedBarcode>()
        var timedOut = false
        try {
            withTimeout(config.decodeTimeoutMillis) {
                for (engine in engines) {
                    ensureActive()
                    runEngine(engine, frame, pool)
                    val best = pool.maxOfOrNull { it.confidence } ?: 0f
                    if (best >= CONFIDENT_THRESHOLD) break
                }
            }
        } catch (e: TimeoutCancellationException) {
            // Ours (withTimeout above): fall through to best-so-far. A caller
            // cancellation still propagates via ensureActive.
            timedOut = true
            currentCoroutineContext().ensureActive()
        } catch (e: CancellationException) {
            throw e
        } catch (e: DecoderException) {
            return LumaDecode(DecodeOutcome.Error(e, recoverable = false), bufferReusable = true)
        } catch (e: Exception) {
            return LumaDecode(
                DecodeOutcome.Error(DecoderException("Luma fusion failed", e), recoverable = false),
                bufferReusable = true,
            )
        }

        val totalMs = (System.nanoTime() - startNanos) / NANOS_PER_MILLI
        if (pool.isEmpty()) {
            android.util.Log.d(TAG, "frame ${frame.width}x${frame.height} totalMs=$totalMs result=NotFound timedOut=$timedOut")
            return LumaDecode(DecodeOutcome.NotFound("no engine decoded the frame"), !timedOut)
        }
        val ranked = pool.sortedByDescending { it.confidence }
        val best = ranked.first()
        // Value length only: barcodes are host PII and this fires per frame.
        android.util.Log.d(
            TAG,
            "frame ${frame.width}x${frame.height} totalMs=$totalMs " +
                "result=${best.engineName}:${best.symbology}len${best.rawValue.length}@${best.confidence} " +
                "pool=${ranked.size} timedOut=$timedOut",
        )
        return LumaDecode(DecodeOutcome.Success(ranked), !timedOut)
    }

    private suspend fun runEngine(engine: LumaEngine, frame: LumaFrame, pool: MutableList<DecodedBarcode>) {
        val t = System.nanoTime()
        when (val outcome = engine.decode(frame)) {
            is DecodeOutcome.Success -> {
                val accepted = outcome.barcodes.filter {
                    it.symbology in config.enabledSymbologies && it.confidence >= config.minConfidence
                }
                if (accepted.isNotEmpty()) mergeIntoPool(pool, accepted)
                val best = accepted.maxByOrNull { it.confidence }
                android.util.Log.d(
                    TAG,
                    "engine=${engine.name} ms=${(System.nanoTime() - t) / NANOS_PER_MILLI} " +
                        "hit=${best?.symbology}len${best?.rawValue?.length}@${best?.confidence}",
                )
            }
            is DecodeOutcome.Error -> {
                android.util.Log.d(
                    TAG,
                    "engine=${engine.name} ms=${(System.nanoTime() - t) / NANOS_PER_MILLI} " +
                        "error(recoverable=${outcome.recoverable}) " +
                        "cause=${outcome.cause.javaClass.simpleName}: ${outcome.cause.message}",
                )
                if (!outcome.recoverable) throw outcome.cause
            }
            is DecodeOutcome.NotFound -> android.util.Log.d(
                TAG,
                "engine=${engine.name} ms=${(System.nanoTime() - t) / NANOS_PER_MILLI} miss",
            )
        }
    }

    companion object {
        /** Logcat tag; filter with `-s LumaFusion`. */
        const val TAG = "LumaFusion"

        /**
         * 0.95: ML Kit (0.95) and MSI-verified (1.0) stop the frame at once;
         * zxing (0.9) pools instead, so a higher-confidence MSI read of the
         * same frame is not short-circuited.
         */
        const val CONFIDENT_THRESHOLD = 0.95f

        private const val NANOS_PER_MILLI = 1_000_000L

        /** Dedups [pool] on (value, symbology), keeping the max confidence. */
        internal fun mergeIntoPool(pool: MutableList<DecodedBarcode>, accepted: List<DecodedBarcode>) {
            for (b in accepted) {
                val idx = pool.indexOfFirst { it.rawValue == b.rawValue && it.symbology == b.symbology }
                if (idx < 0) pool += b
                else if (b.confidence > pool[idx].confidence) pool[idx] = b
            }
        }
    }
}
