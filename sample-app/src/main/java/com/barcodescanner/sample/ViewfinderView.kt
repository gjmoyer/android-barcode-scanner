package com.barcodescanner.sample

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View

/**
 * Viewfinder overlay: dims everything outside the [ScannerConfig.scanRegion]
 * box and outlines it. Fractions are upright-display fractions matching the
 * config's centered box; alignment with the decoded buffer region is
 * approximate (PreviewView FILL_CENTER crops aspects slightly differently than
 * the analysis stream), so the config box is deliberately generous — aim to
 * fill it, exact edges don't matter.
 */
class ViewfinderView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    /** Upright fractions matching `ScannerConfigBuilder.scanRegion(w, h)`. */
    var widthFraction: Float = 0.9f,
    var heightFraction: Float = 0.5f,
) : View(context, attrs) {

    private val dim = Paint().apply { color = Color.argb(110, 0, 0, 0) }
    private val border = Paint().apply {
        color = Color.argb(255, 80, 200, 120)
        style = Paint.Style.STROKE
        strokeWidth = 4f
        isAntiAlias = true
    }

    private fun box(): RectF {
        val l = (1 - widthFraction) / 2 * width
        val t = (1 - heightFraction) / 2 * height
        return RectF(l, t, l + widthFraction * width, t + heightFraction * height)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (width == 0 || height == 0) return
        val b = box()
        canvas.drawRect(0f, 0f, width.toFloat(), b.top, dim)
        canvas.drawRect(0f, b.bottom, width.toFloat(), height.toFloat(), dim)
        canvas.drawRect(0f, b.top, b.left, b.bottom, dim)
        canvas.drawRect(b.right, b.top, width.toFloat(), b.bottom, dim)
        canvas.drawRect(b, border)
    }
}
