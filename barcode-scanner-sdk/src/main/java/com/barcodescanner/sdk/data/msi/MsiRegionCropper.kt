package com.barcodescanner.sdk.data.msi

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Point
import android.graphics.Rect
import kotlin.math.abs
import kotlin.math.atan2

/**
 * POC: ROI crop + deskew helper for the "ML Kit localizes, MSI decodes" split.
 *
 * Why this exists: [MsiPlesseyDecoder] reads horizontal (default) or
 * horizontal+vertical (robust) scanlines. That covers 0°/180° natively and 90°/270°
 * in robust mode, but any *arbitrary* tilt (15°, 30°, …) smears runs diagonally and
 * any small-in-frame symbol drowns in competing print. An ML Kit candidate region
 * ([com.barcodescanner.sdk.data.mlkit.MlKitRegionLocalizer]) fixes both: crop to the
 * region (fewer pixels, less competing text) and rotate the strip horizontal before
 * decoding.
 *
 * Deliberately dependency-free (no OpenCV): only an *affine* deskew (rotation
 * from the quad's long edge) is applied. That handles rotation exactly and mild
 * perspective well enough for the decoder's per-window k-means cut to absorb the
 * rest. True perspective rectification (`getPerspectiveTransform`/`warpPerspective`)
 * is future work if device trials show skewed MSI boxes landing but failing.
 *
 * Corner convention: ML Kit returns four points clockwise from the symbol's
 * top-left in input-image coordinates; the quad is not necessarily rectangular
 * under perspective. [rawAngleFromCorners] uses only the longest edge, so mild
 * perspective does not skew the estimate.
 */
object MsiRegionCropper {

    data class CropResult(
        /** Caller-owned bitmap (must recycle). Never the input bitmap. */
        val bitmap: Bitmap,
        /** Clockwise degrees applied to deskew (0 = plain crop). */
        val angleApplied: Float,
    )

    /**
     * Pads [box] by [padFraction] of its own size on every side (quiet-zone
     * guarantee: the decoder vetoes windows without quiet margins) and clamps
     * to the image bounds. Returns null when nothing remains.
     */
    fun expandBox(box: Rect, imageWidth: Int, imageHeight: Int, padFraction: Float = 0.15f): Rect? {
        if (box.isEmpty || imageWidth <= 0 || imageHeight <= 0) return null
        val padX = (box.width() * padFraction).toInt()
        val padY = (box.height() * padFraction).toInt()
        val out = Rect(
            (box.left - padX).coerceIn(0, imageWidth),
            (box.top - padY).coerceIn(0, imageHeight),
            (box.right + padX).coerceIn(0, imageWidth),
            (box.bottom + padY).coerceIn(0, imageHeight),
        )
        return if (out.isEmpty) null else out
    }

    /**
     * Expands [box] into an OCR read zone: the printed SKU/GTIN text lives
     * AROUND the bars (usually below them, but the frame may be sensor-rotated,
     * so the pad is uniform rather than directional). Uniform [padFraction] on
     * all sides keeps the crop tight versus the full frame while covering the
     * human-readable print in any orientation. Clamped to the image; null when
     * nothing remains.
     */
    fun expandForOcr(box: Rect, imageWidth: Int, imageHeight: Int, padFraction: Float = 0.6f): Rect? {
        if (box.isEmpty || imageWidth <= 0 || imageHeight <= 0) return null
        val padX = (box.width() * padFraction).toInt()
        val padY = (box.height() * padFraction).toInt()
        val out = Rect(
            (box.left - padX).coerceIn(0, imageWidth),
            (box.top - padY).coerceIn(0, imageHeight),
            (box.right + padX).coerceIn(0, imageWidth),
            (box.bottom + padY).coerceIn(0, imageHeight),
        )
        return if (out.isEmpty) null else out
    }

    /** Shifts [box] by (dx, dy), re-basing crop-space boxes into frame space. */
    fun shiftBox(box: Rect?, dx: Int, dy: Int): Rect? {
        if (box == null) return null
        return Rect(box.left + dx, box.top + dy, box.right + dx, box.bottom + dy)
    }

    /**
     * Longest-edge angle of the corner quad, clockwise degrees in (-90, 90].
     * Positive = symbol runs downhill left-to-right. Null when fewer than 4
     * points are supplied (caller falls back to a plain crop).
     *
     * The quad is used unfolded: near-vertical strips transpose losslessly to
     * horizontal inside [cropAndDeskew], so no folding to a strip axis happens
     * here — the Mild-perspective note still holds (only the longest edge
     * matters, so a skewed corner barely moves the estimate).
     */
    internal fun rawAngleFromCorners(corners: List<Point>?): Float? {
        if (corners == null || corners.size < 4) return null
        var bestLen = -1.0
        var bestAngle = 0f
        for (i in 0 until 4) {
            val a = corners[i]
            val b = corners[(i + 1) % 4]
            val dx = (b.x - a.x).toDouble()
            val dy = (b.y - a.y).toDouble()
            val len = dx * dx + dy * dy
            if (len > bestLen) {
                bestLen = len
                bestAngle = Math.toDegrees(atan2(dy, dx)).toFloat()
            }
        }
        var angle = bestAngle
        while (angle <= -90f) angle += 180f
        while (angle > 90f) angle -= 180f
        return angle
    }

    /**
     * Crops [box] (with quiet-zone padding) and deskews via [corners].
     *
     * - near-vertical strip (|raw angle| > 45°): lossless exact ±90° transpose
     *   first (no resampling blur), so sideways labels become horizontal and
     *   decode in *default* mode — no robust vertical pass needed;
     * - |residual| < [straightToleranceDeg] → plain axis-aligned crop (no
     *   resample, bars stay pixel-crisp);
     * - otherwise the padded crop is rotated onto a white canvas (black borders
     *   would invent bar runs at the edges).
     *
     * @return null when the padded box is empty or the crop fails.
     */
    fun cropAndDeskew(
        src: Bitmap,
        box: Rect,
        corners: List<Point>? = null,
        padFraction: Float = 0.15f,
        straightToleranceDeg: Float = 2f,
    ): CropResult? {
        val padded = expandBox(box, src.width, src.height, padFraction) ?: return null
        if (padded.width() < 8 || padded.height() < 8) return null
        var img = runCatching {
            Bitmap.createBitmap(src, padded.left, padded.top, padded.width(), padded.height())
        }.getOrNull() ?: return null
        var applied = 0f
        try {
            val raw = rawAngleFromCorners(corners) ?: 0f
            // Step 1 (lossless): transpose near-vertical strips to horizontal.
            var residual = raw
            if (abs(raw) > 45f) {
                val transpose = if (raw > 0) -90f else 90f
                val m = Matrix().apply { postRotate(transpose) }
                // filter=false: exact multiples of 90° are pixel-exact.
                val transposed = Bitmap.createBitmap(img, 0, 0, img.width, img.height, m, false)
                runCatching { img.recycle() }
                img = transposed
                applied += transpose
                residual = raw - (-transpose)
            }
            // Step 2 (resampled): fine deskew. Bilinear (filter=true) avoids
            // stair-step edges that read as phantom runs; the decoder's
            // multi-variant binarization + subpixel gray path absorb the mild blur.
            if (abs(residual) < straightToleranceDeg) {
                return CropResult(img, applied)
            }
            val m = Matrix().apply {
                postRotate(-residual, img.width / 2f, img.height / 2f)
            }
            val flat = Bitmap.createBitmap(img.width, img.height, Bitmap.Config.ARGB_8888)
            flat.eraseColor(Color.WHITE)
            Canvas(flat).drawBitmap(img, m, Paint(Paint.FILTER_BITMAP_FLAG))
            runCatching { img.recycle() }
            return CropResult(flat, applied - residual)
        } catch (t: Throwable) {
            runCatching { img.recycle() }
            return null
        }
    }
}
