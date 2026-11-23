package com.barcodescanner.sample

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PointF
import android.graphics.RectF
import android.os.SystemClock
import android.util.AttributeSet
import android.view.View
import com.barcodescanner.sdk.api.ScanResult

/**
 * Draws a short-lived green highlight around a decoded barcode, similar to the
 * confirmation box commercial scanners show over the camera image.
 *
 * The SDK reports [com.barcodescanner.sdk.domain.model.DecodedBarcode.boundingBox]
 * and corner points in the coordinates of the prepared upright frame whose
 * size is [ScanResult.Success.frameWidth] x [ScanResult.Success.frameHeight].
 * For live camera scans that frame is exactly the configured `scanRegion`
 * crop, so mapping its pixels into the same centered box the viewfinder draws
 * puts the highlight on screen over the bars.
 *
 * Hosts with a custom preview layout only need to replace [scanRegion] with
 * their own target rectangle; the SDK-side geometry stays unchanged.
 */
class BarcodeHighlightView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    /** Upright fractions matching `ScannerConfigBuilder.scanRegion(w, h)`. */
    private val widthFraction: Float = 0.9f,
    private val heightFraction: Float = 0.2f,
) : View(context, attrs) {

    private data class Mark(val points: List<PointF>, val startedAtMs: Long)

    private val marks = mutableListOf<Mark>()
    private val density = resources.displayMetrics.density

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.rgb(0, 230, 118)
    }
    private val glow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 14f * density
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        color = Color.rgb(0, 230, 118)
    }
    private val border = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3.5f * density
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        color = Color.rgb(120, 255, 190)
    }

    /** Highlights the barcode in [result]; no-op when the engine had no geometry. */
    fun show(result: ScanResult.Success) {
        val points = pointsFor(result) ?: return
        marks += Mark(points, SystemClock.elapsedRealtime())
        // A live scan can publish rapid repeats; never keep an unbounded list.
        while (marks.size > MAX_MARKS) marks.removeAt(0)
        invalidate()
    }

    /** Removes any active highlight (e.g. when starting the next scan). */
    fun clear() {
        if (marks.isEmpty()) return
        marks.clear()
        invalidate()
    }

    private fun pointsFor(result: ScanResult.Success): List<PointF>? {
        val frameWidth = result.frameWidth
        val frameHeight = result.frameHeight
        if (frameWidth <= 0 || frameHeight <= 0 || width <= 0 || height <= 0) return null
        val region = scanRegion()
        val barcode = result.barcode
        val corners = barcode.cornerPoints
        if (corners != null && corners.size >= 4) {
            return corners.take(4).map {
                mapPoint(it.x.toFloat(), it.y.toFloat(), frameWidth, frameHeight, region)
            }
        }
        val box = barcode.boundingBox ?: return null
        if (box.width() <= 0 || box.height() <= 0) return null
        return listOf(
            mapPoint(box.left.toFloat(), box.top.toFloat(), frameWidth, frameHeight, region),
            mapPoint(box.right.toFloat(), box.top.toFloat(), frameWidth, frameHeight, region),
            mapPoint(box.right.toFloat(), box.bottom.toFloat(), frameWidth, frameHeight, region),
            mapPoint(box.left.toFloat(), box.bottom.toFloat(), frameWidth, frameHeight, region),
        )
    }

    /**
     * The on-screen rectangle the SDK crops for analysis. The prepared frame
     * is that crop, so frame pixels scale linearly into this rectangle.
     */
    private fun scanRegion(): RectF {
        val left = (1 - widthFraction) / 2 * width
        val top = (1 - heightFraction) / 2 * height
        return RectF(left, top, left + widthFraction * width, top + heightFraction * height)
    }

    private fun mapPoint(
        x: Float,
        y: Float,
        frameWidth: Int,
        frameHeight: Int,
        region: RectF,
    ): PointF = PointF(
        region.left + (x / frameWidth).coerceIn(0f, 1f) * region.width(),
        region.top + (y / frameHeight).coerceIn(0f, 1f) * region.height(),
    )

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (marks.isEmpty() || width == 0 || height == 0) return
        val now = SystemClock.elapsedRealtime()
        var i = marks.lastIndex
        while (i >= 0) {
            val mark = marks[i]
            val age = now - mark.startedAtMs
            if (age >= TOTAL_DURATION_MS) {
                marks.removeAt(i)
            } else {
                val alpha = if (age <= HOLD_MILLIS) {
                    1f
                } else {
                    1f - (age - HOLD_MILLIS).toFloat() / FADE_MILLIS
                }
                drawMark(canvas, mark, alpha.coerceIn(0f, 1f))
            }
            i--
        }
        if (marks.isNotEmpty()) postInvalidateOnAnimation()
    }

    private fun drawMark(canvas: Canvas, mark: Mark, alpha: Float) {
        val path = Path()
        mark.points.forEachIndexed { index, point ->
            if (index == 0) path.moveTo(point.x, point.y) else path.lineTo(point.x, point.y)
        }
        path.close()

        fill.alpha = (FILL_ALPHA * alpha).toInt()
        glow.alpha = (GLOW_ALPHA * alpha).toInt()
        border.alpha = (BORDER_ALPHA * alpha).toInt()
        canvas.drawPath(path, fill)
        canvas.drawPath(path, glow)
        canvas.drawPath(path, border)
    }

    companion object {
        /** Fully visible duration after a decode. */
        const val HOLD_MILLIS = 450L
        /** Fade-out duration after [HOLD_MILLIS]. */
        const val FADE_MILLIS = 450L
        /** Keep the camera (and preview) alive at least this long after a hit. */
        const val TOTAL_DURATION_MS = HOLD_MILLIS + FADE_MILLIS

        private const val MAX_MARKS = 3
        private const val FILL_ALPHA = 55
        private const val GLOW_ALPHA = 85
        private const val BORDER_ALPHA = 235
    }
}
