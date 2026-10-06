package com.barcodescanner.sdk.domain.pipeline

import android.graphics.Bitmap
import android.graphics.Matrix
import com.barcodescanner.sdk.domain.model.ScanFrame

/**
 * Expands one frame into upright + rotated candidates for orientation-robust decoding.
 *
 * Barcodes in the wild are rotated / upside-down / slightly skewed. Instead of
 * requiring each engine to be rotation-invariant, the fusion decoder asks this
 * helper for up to 4 views (0°, 90°, 180°, 270°) and tries them in order of
 * increasing cost: 0° first, then 180° (upside-down labels are common), then 90°/270°.
 *
 * Callers should cap attempts via [com.barcodescanner.sdk.domain.model.ScannerConfig.maxOrientationsTried].
 */
object OrientationCandidates {

    /** Priority order: upright, upside-down, then sideways. */
    val PRIORITY_ORDER = intArrayOf(0, 180, 90, 270)

    fun expand(frame: ScanFrame, maxOrientations: Int = 4): List<ScanFrame> =
        expandLazy(frame, maxOrientations).toList()

    /**
     * Lazy orientation expansion: rotated bitmaps materialize on demand, so an
     * early confident hit (the common live-scan case: upright barcode, first
     * engine) never pays for the rotations it doesn't try. Callers must recycle
     * consumed candidates whose bitmap differs from the input (see FusedDecoder).
     */
    fun expandLazy(frame: ScanFrame, maxOrientations: Int = 4): Sequence<ScanFrame> = sequence {
        require(maxOrientations in 1..4) { "maxOrientations must be 1..4" }
        for (rot in PRIORITY_ORDER.take(maxOrientations)) {
            if (rot == 0) {
                yield(frame.copy(attemptRotation = 0))
            } else {
                yield(frame.copy(bitmap = rotate(frame.bitmap, rot), attemptRotation = rot))
            }
        }
    }

    fun rotate(src: Bitmap, degrees: Int): Bitmap {
        if (degrees % 360 == 0) return src
        val m = Matrix().apply { postRotate(degrees.toFloat()) }
        // filter=false: multiples of 90° are pixel-exact with nearest-neighbor;
        // bilinear would blur the narrow bars MSI/DataBar depend on.
        return Bitmap.createBitmap(src, 0, 0, src.width, src.height, m, false)
    }
}
