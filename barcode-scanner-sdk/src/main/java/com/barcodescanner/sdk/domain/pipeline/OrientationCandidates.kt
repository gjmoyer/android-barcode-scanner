package com.barcodescanner.sdk.domain.pipeline

import android.graphics.Bitmap
import android.graphics.Matrix
import com.barcodescanner.sdk.domain.model.ScanFrame

/**
 * Expands one frame into upright + rotated candidates for orientation-robust decoding.
 *
 * Barcodes in the wild are rotated / upside-down / slightly skewed. Instead of
 * requiring each engine to be rotation-invariant, the fusion decoder asks this
 * helper for up to 4 views and tries them in order of increasing cost:
 * the upright view first, then upside-down (common), then the two sideways views.
 *
 * "Upright" is sensor-relative: camera frames carry [ScanFrame.rotationDegrees]
 * (e.g. 90 for portrait phone captures, whose raw buffer is stored sideways).
 * The first candidate therefore applies the compensating rotation — previously
 * every live frame wasted the first attempts on 0°/180° views of rotated
 * content, which made MSI (whose scanline decoder is orientation-fragile)
 * effectively miss portrait barcodes before the 90°/270° candidates were
 * reached. Gallery stills (rotationDegrees = 0) keep the classic 0°, 180°,
 * 90°, 270° order.
 *
 * Callers should cap attempts via [com.barcodescanner.sdk.domain.model.ScannerConfig.maxOrientationsTried].
 */
object OrientationCandidates {

    /** Default priority order: upright, upside-down, then sideways. */
    val PRIORITY_ORDER = intArrayOf(0, 180, 90, 270)

    /** Upright-first candidate rotations for a frame captured at [sensorRotation]. */
    fun priorityOrder(sensorRotation: Int): List<Int> {
        val base = ((sensorRotation % 360) + 360) % 360
        return listOf(base, (base + 180) % 360, (base + 90) % 360, (base + 270) % 360)
    }

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
        for (rot in priorityOrder(frame.rotationDegrees).take(maxOrientations)) {
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
