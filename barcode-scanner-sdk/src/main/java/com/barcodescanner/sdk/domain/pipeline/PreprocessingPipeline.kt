package com.barcodescanner.sdk.domain.pipeline

import com.barcodescanner.sdk.domain.model.ScanFrame
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/**
 * A single preprocessing step: denoise, normalize rotation, enhance contrast, etc.
 *
 * Transforms must be:
 *  - pure functions of the input frame (no hidden state),
 *  - safe to run off the main thread,
 *  - cheap relative to decoding (heavy work belongs in decoders, not here).
 */
fun interface FrameTransform {
    suspend fun transform(frame: ScanFrame): ScanFrame
}

/**
 * Ordered chain of [FrameTransform]s applied before decoding.
 *
 * The default chain (assembled in ScannerContainer) handles the robustness
 * requirements — bounded scale and low contrast:
 *
 *  1. Downscale to a bounded long edge (narrow bars survive nearest-neighbor).
 *  2. Grayscale + contrast normalization (cheap, big win for MSI/DataBar narrow bars).
 *  3. Multi-orientation expansion happens in the fusion decoder, not here, so this
 *     pipeline stays single-frame in / single-frame out.
 *
 * [process] checks cancellation between transforms so the fusion decode timeout
 * bounds preprocessing too.
 *
 * Bitmap ownership: the caller's `frame.bitmap` is NEVER recycled here. When a
 * transform returns a frame with a different bitmap object, the previous
 * intermediate is recycled immediately (so a Downscale->Contrast chain leaves
 * exactly one processed bitmap, not two). On cancellation/exception
 * intermediates are recycled and the original frame is returned to the caller
 * via the exception path (fusion falls back to the raw frame). The final
 * processed bitmap is owned by the caller (FusedDecoder recycles it when it
 * differs from the input).
 */
class PreprocessingPipeline(
    private val transforms: List<FrameTransform>,
) {
    suspend fun process(frame: ScanFrame): ScanFrame {
        val original = frame.bitmap
        var current = frame
        try {
            for (t in transforms) {
                currentCoroutineContext().ensureActive()
                val prev = current
                current = t.transform(prev)
                // Recycle the intermediate we just replaced, never the caller's.
                if (current.bitmap !== prev.bitmap && prev.bitmap !== original) {
                    runCatching { prev.bitmap.recycle() }
                }
            }
            return current
        } catch (e: Throwable) {
            // Drop intermediates produced so far; never the caller's bitmap.
            if (current.bitmap !== original) {
                // `current` may be a half-built intermediate: recycle it only if
                // it is not the original frame (fusion rethrows cancellation and
                // falls back to `frame` for other Throwables).
                if (current !== frame) runCatching { current.bitmap.recycle() }
            }
            throw e
        }
    }

    fun plus(transform: FrameTransform): PreprocessingPipeline =
        PreprocessingPipeline(transforms + transform)

    companion object {
        fun of(vararg transforms: FrameTransform) = PreprocessingPipeline(transforms.toList())
        fun empty() = PreprocessingPipeline(emptyList())
    }
}
