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
 */
class PreprocessingPipeline(
    private val transforms: List<FrameTransform>,
) {
    suspend fun process(frame: ScanFrame): ScanFrame {
        var current = frame
        for (t in transforms) {
            currentCoroutineContext().ensureActive()
            current = t.transform(current)
        }
        return current
    }

    fun plus(transform: FrameTransform): PreprocessingPipeline =
        PreprocessingPipeline(transforms + transform)

    companion object {
        fun of(vararg transforms: FrameTransform) = PreprocessingPipeline(transforms.toList())
        fun empty() = PreprocessingPipeline(emptyList())
    }
}
