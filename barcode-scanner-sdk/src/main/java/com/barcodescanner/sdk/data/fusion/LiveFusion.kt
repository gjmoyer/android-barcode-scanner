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
 * One engine as the live aim-box path sees it: [decoder] supplies the name and
 * symbology routing, [decodeLuma] reads the shared upright [LumaFrame] in
 * place. A wrapper rather than an interface because the public engine classes
 * cannot implement an internal interface without leaking [LumaFrame].
 */
internal class LiveEngine(
    val decoder: BarcodeDecoder,
    private val decodeLuma: suspend (LumaFrame) -> DecodeOutcome,
) {
    val name: String get() = decoder.name

    suspend fun decode(frame: LumaFrame): DecodeOutcome = decodeLuma(frame)
}

/**
 * Outcome of one live decode. [bufferReusable] is false when the decode timed
 * out: an engine may still be reading [LumaFrame.buffer] (a cancelled ML Kit
 * task keeps running on ML Kit's executor), so the owner must drop that buffer
 * instead of preparing the next frame into it.
 */
internal class LiveDecode(val outcome: DecodeOutcome, val bufferReusable: Boolean)

/**
 * Live camera fusion over ONE prepared [LumaFrame] — the aim-box crop the
 * native pass already contrast-stretched and turned upright. Every engine
 * reads that same buffer; nothing here converts, crops, rotates or copies
 * pixels, and no engine sees a second variant of the frame.
 *
 * Compared with [FusedDecoder] (bitmap one-shot path) there is no
 * preprocessing pipeline, no orientation expansion (MSI tries orientations
 * natively per [LumaFrame.maxOrientations]; ML Kit and zxing resolve rotation
 * internally) and no fallback sweep or region assist — the aim box is the
 * localization. Engines run in the given order (ML Kit -> MSI -> zxing);
 * semantics otherwise match [FusedDecoder]: whole decode under
 * [ScannerConfig.decodeTimeoutMillis] with best-so-far on expiry, hits below
 * [ScannerConfig.minConfidence] or outside the enabled set ignored, pooling
 * deduped on value + symbology, first hit at [FusedDecoder.CONFIDENT_THRESHOLD]
 * stops the frame, a non-recoverable engine error fails it.
 *
 * Cancellation by the caller propagates; the frame owner must then treat the
 * buffer as still in use, exactly as for `bufferReusable = false`.
 */
internal class LiveFusion(
    engines: List<LiveEngine>,
    private val config: ScannerConfig,
) {
    /** Engines with at least one enabled symbology, in priority order. */
    val engines: List<LiveEngine> = engines.filter {
        it.decoder.supportedSymbologies.intersect(config.enabledSymbologies).isNotEmpty()
    }

    suspend fun decode(frame: LumaFrame): LiveDecode {
        val startNanos = System.nanoTime()
        val pool = mutableListOf<DecodedBarcode>()
        var timedOut = false
        try {
            withTimeout(config.decodeTimeoutMillis) {
                for (engine in engines) {
                    ensureActive()
                    runEngine(engine, frame, pool)
                    val best = pool.maxOfOrNull { it.confidence } ?: 0f
                    if (best >= FusedDecoder.CONFIDENT_THRESHOLD) break
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
            return LiveDecode(DecodeOutcome.Error(e, recoverable = false), bufferReusable = true)
        } catch (e: Exception) {
            return LiveDecode(
                DecodeOutcome.Error(DecoderException("Live fusion failed", e), recoverable = false),
                bufferReusable = true,
            )
        }

        val totalMs = (System.nanoTime() - startNanos) / NANOS_PER_MILLI
        if (pool.isEmpty()) {
            android.util.Log.d(TAG, "frame ${frame.width}x${frame.height} totalMs=$totalMs result=NotFound timedOut=$timedOut")
            return LiveDecode(DecodeOutcome.NotFound("live: no engine decoded the frame"), !timedOut)
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
        return LiveDecode(DecodeOutcome.Success(ranked), !timedOut)
    }

    private suspend fun runEngine(engine: LiveEngine, frame: LumaFrame, pool: MutableList<DecodedBarcode>) {
        val t = System.nanoTime()
        when (val outcome = engine.decode(frame)) {
            is DecodeOutcome.Success -> {
                val accepted = outcome.barcodes.filter {
                    it.symbology in config.enabledSymbologies && it.confidence >= config.minConfidence
                }
                if (accepted.isNotEmpty()) FusedDecoder.mergeIntoPool(pool, accepted)
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
        /** Logcat tag; filter with `-s LiveFusion`. */
        const val TAG = "LiveFusion"
        private const val NANOS_PER_MILLI = 1_000_000L
    }
}
