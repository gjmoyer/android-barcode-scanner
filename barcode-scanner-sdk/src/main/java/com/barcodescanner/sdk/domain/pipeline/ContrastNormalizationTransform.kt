package com.barcodescanner.sdk.domain.pipeline

import android.graphics.Bitmap
import android.graphics.Color
import com.barcodescanner.sdk.domain.model.ScanFrame
import kotlin.math.max
import kotlin.math.min

/**
 * Grayscale + contrast-stretch normalization.
 *
 * Why this exists: MSI Plessey and DataBar rely on narrow bar/space widths.
 * Phone cameras in warehouses deliver low-contrast, warm-tinted frames; a cheap
 * per-frame histogram stretch dramatically improves binarization inside
 * ML Kit / zxing-cpp / the custom MSI decoder without any engine-specific code.
 *
 * Cost: one pass over pixels at decode resolution. Callers should downscale
 * camera frames to <= 1280px on the long edge before this step.
 */
class ContrastNormalizationTransform(
    /** Percentiles to clip before stretching; avoids hot-pixel blowout. */
    private val lowPercentile: Float = 0.02f,
    private val highPercentile: Float = 0.98f,
) : FrameTransform {

    override suspend fun transform(frame: ScanFrame): ScanFrame {
        val src = frame.bitmap
        // DownscaleTransform (stage 0) bounds the long edge to 1280, so a tall
        // portrait can still be 1280x1920 = 2.4MP — size the skip for that.
        if (src.width * src.height > MAX_PIXELS) return frame // caller must downscale first
        val gray = toGrayscaleFast(src) ?: return frame
        val (lo, hi) = percentileBounds(gray, lowPercentile, highPercentile)
        // Flat frame (hi-lo small): already uniform, return untouched — no alloc.
        if (hi - lo < MIN_RANGE) return frame
        return frame.copy(bitmap = stretch(gray, src.width, src.height, lo, hi))
    }

    private fun toGrayscaleFast(src: Bitmap): IntArray? = runCatching {
        val w = src.width
        val h = src.height
        val px = IntArray(w * h)
        src.getPixels(px, 0, w, 0, 0, w, h)
        for (i in px.indices) {
            val c = px[i]
            val r = (c shr 16) and 0xFF
            val g = (c shr 8) and 0xFF
            val b = c and 0xFF
            // Rec.601 luma, integer math
            px[i] = (77 * r + 150 * g + 29 * b) shr 8
        }
        px
    }.getOrNull()

    private fun percentileBounds(gray: IntArray, lo: Float, hi: Float): Pair<Int, Int> {
        val hist = IntArray(256)
        for (v in gray) hist[v]++
        val n = gray.size.toFloat()
        var acc = 0f
        var low = 0
        var high = 255
        for (i in 0..255) {
            acc += hist[i] / n
            if (acc < lo) low = i
            if (acc <= hi) high = i
        }
        return max(0, low) to min(255, high)
    }

    private fun stretch(gray: IntArray, w: Int, h: Int, lo: Int, hi: Int): Bitmap {
        val out = IntArray(gray.size)
        val scale = 255f / (hi - lo)
        for (i in gray.indices) {
            val v = ((gray[i] - lo) * scale + 0.5f).toInt().coerceIn(0, 255)
            out[i] = Color.rgb(v, v, v)
        }
        return Bitmap.createBitmap(out, w, h, Bitmap.Config.ARGB_8888)
    }

    companion object {
        const val MAX_PIXELS = 1280 * 1920
        const val MIN_RANGE = 12
    }
}
