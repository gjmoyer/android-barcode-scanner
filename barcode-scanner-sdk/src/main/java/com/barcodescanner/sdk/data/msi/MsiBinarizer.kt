package com.barcodescanner.sdk.data.msi

import android.graphics.Bitmap
import kotlin.math.max
import kotlin.math.min

/**
 * Binarization strategies for the custom MSI decoder.
 *
 * Blurry/out-of-focus shelf labels smear narrow bars into pale gray, so a single
 * global threshold fails twice: it either washes the bars out (threshold above the
 * bar cores) or keeps only bar cores (bars decode too narrow). We therefore try,
 * in order:
 *  1. Otsu global threshold (fast, good for clean frames),
 *  2. Background-subtracted (pale-gray bars become full-black relative to local
 *     background; handles low contrast + shelf shading gradients),
 *  3. Local adaptive mean (windowed, handles shadows + mild blur),
 *  4. Otsu − offset (catches under-exposed labels).
 *
 * Each returns a packed binary row field (true = black/bar). The decoder tries
 * variants in order and votes across all of them.
 */
internal object MsiBinarizer {

    /** Returns up to [maxVariants] binary fields for [bitmap] (grayscale assumed, ARGB accepted). */
    fun binarizeVariants(bitmap: Bitmap, maxVariants: Int = 3): List<BinaryImage> {
        val gray = toGray(bitmap)
        val w = bitmap.width
        val h = bitmap.height
        val otsu = otsuThreshold(gray)
        val ordered = mutableListOf<BooleanArray>()
        ordered += global(bitmap, gray, otsu)
        if (maxVariants >= 2) ordered += backgroundSubtracted(bitmap, gray)
        if (maxVariants >= 3) ordered += adaptiveMean(bitmap, gray)
        if (maxVariants >= 4) ordered += global(bitmap, gray, (otsu - 18).coerceIn(0, 255))
        return ordered.take(maxVariants).map { BinaryImage(it, w, h) }
    }

    fun toGray(bitmap: Bitmap): IntArray {
        val w = bitmap.width
        val h = bitmap.height
        val px = IntArray(w * h)
        bitmap.getPixels(px, 0, w, 0, 0, w, h)
        for (i in px.indices) {
            val c = px[i]
            val r = (c shr 16) and 0xFF
            val g = (c shr 8) and 0xFF
            val b = c and 0xFF
            px[i] = (77 * r + 150 * g + 29 * b) shr 8
        }
        return px
    }

    fun otsuThreshold(gray: IntArray): Int {
        val hist = IntArray(256)
        for (v in gray) hist[v]++
        val total = gray.size.toDouble()
        var sum = 0.0
        for (i in 0..255) sum += i * hist[i]
        var sumB = 0.0
        var wB = 0.0
        var best = -1.0
        var first = 127
        var last = 127
        for (i in 0..255) {
            wB += hist[i]
            if (wB == 0.0) continue
            val wF = total - wB
            if (wF == 0.0) break
            sumB += i * hist[i]
            val mB = sumB / wB
            val mF = (sum - sumB) / wF
            val between = wB * wF * (mB - mF) * (mB - mF)
            if (between > best) {
                best = between
                first = i
                last = i
            } else if (between == best) {
                // Tie across an empty histogram span (e.g. pure black/white):
                // extend the maximizing range; the mean (below) lands mid-valley
                // instead of degenerate 0.
                last = i
            }
        }
        // Mean of the maximizing range; falls back to 127 when degenerate.
        val threshold = (first + last) / 2
        return threshold.coerceIn(1, 254)
    }

    private fun global(bitmap: Bitmap, gray: IntArray, t: Int): BooleanArray {
        val out = BooleanArray(gray.size)
        for (i in gray.indices) out[i] = gray[i] < t
        return out
    }

    /**
     * Background subtraction for pale/low-contrast bars.
     *
     * Estimates the local background with a wide box mean (radius >> bar width,
     * << shelf-shading scale) via integral image, then thresholds the darkness
     * relative to background: `black = (bg - pixel) > T` with T from Otsu over
     * the darkness field (floored so blank areas stay white). A gray bar on a
     * bright label becomes as black as a true-black bar — global thresholds
     * cannot do this because the bar cores sit above any global cut that also
     * keeps dark price text black.
     */
    internal fun backgroundSubtracted(
        bitmap: Bitmap,
        gray: IntArray,
        radiusFactor: Int = 12,
        floor: Int = 5,
    ): BooleanArray {
        val w = bitmap.width
        val h = bitmap.height
        val integral = LongArray((w + 1) * (h + 1))
        for (y in 0 until h) {
            var rowSum = 0L
            for (x in 0 until w) {
                rowSum += gray[y * w + x]
                integral[(y + 1) * (w + 1) + x + 1] = integral[y * (w + 1) + x + 1] + rowSum
            }
        }
        fun rectSum(x0: Int, y0: Int, x1: Int, y1: Int): Long {
            val a = integral[y0 * (w + 1) + x0]
            val b = integral[y0 * (w + 1) + x1]
            val cI = integral[y1 * (w + 1) + x0]
            val d = integral[y1 * (w + 1) + x1]
            return d - b - cI + a
        }
        val r = max(8, max(w, h) / radiusFactor)
        val dark = IntArray(gray.size)
        for (y in 0 until h) {
            for (x in 0 until w) {
                val x0 = max(0, x - r)
                val y0 = max(0, y - r)
                val x1 = min(w, x + r + 1)
                val y1 = min(h, y + r + 1)
                val area = (x1 - x0) * (y1 - y0)
                val bg = (rectSum(x0, y0, x1, y1) / area).toInt()
                dark[y * w + x] = (bg - gray[y * w + x]).coerceAtLeast(0)
            }
        }
        val t = max(floor, otsuThreshold(dark))
        val out = BooleanArray(gray.size)
        for (i in gray.indices) out[i] = dark[i] > t
        return out
    }

    private fun adaptiveMean(bitmap: Bitmap, gray: IntArray, window: Int = 25, c: Int = 7): BooleanArray {
        val w = bitmap.width
        val h = bitmap.height
        // Integral image for O(1) window means.
        val integral = LongArray((w + 1) * (h + 1))
        for (y in 0 until h) {
            var rowSum = 0L
            for (x in 0 until w) {
                rowSum += gray[y * w + x]
                integral[(y + 1) * (w + 1) + x + 1] = integral[y * (w + 1) + x + 1] + rowSum
            }
        }
        fun rectSum(x0: Int, y0: Int, x1: Int, y1: Int): Long {
            val a = integral[y0 * (w + 1) + x0]
            val b = integral[y0 * (w + 1) + x1]
            val cI = integral[y1 * (w + 1) + x0]
            val d = integral[y1 * (w + 1) + x1]
            return d - b - cI + a
        }
        val half = window / 2
        val out = BooleanArray(gray.size)
        for (y in 0 until h) {
            for (x in 0 until w) {
                val x0 = max(0, x - half)
                val y0 = max(0, y - half)
                val x1 = min(w, x + half + 1)
                val y1 = min(h, y + half + 1)
                val area = (x1 - x0) * (y1 - y0)
                val mean = rectSum(x0, y0, x1, y1) / area
                out[y * w + x] = gray[y * w + x] < mean - c
            }
        }
        return out
    }

    data class BinaryImage(val bits: BooleanArray, val width: Int, val height: Int) {
        fun get(x: Int, y: Int): Boolean = bits[y * width + x]
    }
}
