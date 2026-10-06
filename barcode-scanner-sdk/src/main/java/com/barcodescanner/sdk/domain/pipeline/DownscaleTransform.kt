package com.barcodescanner.sdk.domain.pipeline

import android.graphics.Bitmap
import com.barcodescanner.sdk.domain.model.ScanFrame

/**
 * Bounds the long edge to [maxLongEdge] (nearest-neighbor: filter=false, so
 * narrow bars survive — bilinear would low-pass the pulse widths MSI needs).
 *
 * Must run FIRST in the pipeline: contrast/blur/MSI stages assume bounded
 * input, and gallery stills (12MP) would otherwise bypass contrast
 * normalization via the MAX_PIXELS skip.
 */
class DownscaleTransform(
    private val maxLongEdge: Int = 1280,
) : FrameTransform {
    override suspend fun transform(frame: ScanFrame): ScanFrame {
        val src = frame.bitmap
        val longEdge = maxOf(src.width, src.height)
        if (longEdge <= maxLongEdge) return frame
        val scale = maxLongEdge.toFloat() / longEdge
        val w = (src.width * scale).toInt().coerceAtLeast(1)
        val h = (src.height * scale).toInt().coerceAtLeast(1)
        val scaled = Bitmap.createScaledBitmap(src, w, h, false)
        return frame.copy(bitmap = scaled)
    }
}
