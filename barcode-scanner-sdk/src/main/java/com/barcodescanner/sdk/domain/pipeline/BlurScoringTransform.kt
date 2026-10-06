package com.barcodescanner.sdk.domain.pipeline

import com.barcodescanner.sdk.domain.model.ScanFrame
import kotlin.math.abs

/**
 * Estimates frame sharpness with a downsampled Laplacian-variance proxy.
 *
 * Single getPixels + stride sampling (≈100× fewer JNI calls than per-cell
 * getPixels). The score is informational: blurry frames are NOT dropped, but
 * orientation candidates inherit the base score for best-effort ordering.
 *
 * Output: 0 (fully blurred) .. 1 (tack sharp), stored in [ScanFrame.sharpnessScore].
 * [normalizer] is the soft-knee constant — calibrate on a labeled blur set
 * (see docs/MSI_PLESSEY_RESEARCH.md § tuning) rather than treating 450 as lore.
 */
class BlurScoringTransform(
    private val sampleStep: Int = 8,
    private val normalizer: Float = NORMALIZER,
) : FrameTransform {

    override suspend fun transform(frame: ScanFrame): ScanFrame {
        val score = estimateSharpness(frame) ?: return frame
        return frame.copy(sharpnessScore = score)
    }

    internal fun estimateSharpness(frame: ScanFrame): Float? = runCatching {
        val bmp = frame.bitmap
        val w = bmp.width
        val h = bmp.height
        if (w < 16 || h < 16) return null
        // One bulk read; sample on a coarse grid.
        val px = IntArray(w * h)
        bmp.getPixels(px, 0, w, 0, 0, w, h)
        val cols = w / sampleStep
        val rows = h / sampleStep
        if (cols < 4 || rows < 4) return null
        val luma = FloatArray(cols * rows)
        var k = 0
        for (ry in 0 until rows) {
            for (cx in 0 until cols) {
                val x = cx * sampleStep
                val y = ry * sampleStep
                val c = px[y * w + x]
                val r = (c shr 16) and 0xFF
                val g = (c shr 8) and 0xFF
                val b = c and 0xFF
                luma[k++] = (77 * r + 150 * g + 29 * b) / 256f
            }
        }
        // Laplacian kernel response variance.
        var mean = 0.0
        var m2 = 0.0
        var n = 0
        for (y in 1 until rows - 1) {
            for (x in 1 until cols - 1) {
                val lap = abs(
                    4 * luma[y * cols + x] -
                        luma[y * cols + x - 1] - luma[y * cols + x + 1] -
                        luma[(y - 1) * cols + x] - luma[(y + 1) * cols + x],
                )
                n++
                val delta = lap - mean
                mean += delta / n
                m2 += delta * (lap - mean)
            }
        }
        if (n == 0) return null
        val variance = (m2 / n).toFloat()
        (variance / (variance + normalizer)).coerceIn(0f, 1f)
    }.getOrNull()

    companion object {
        const val NORMALIZER = 450f
    }
}
