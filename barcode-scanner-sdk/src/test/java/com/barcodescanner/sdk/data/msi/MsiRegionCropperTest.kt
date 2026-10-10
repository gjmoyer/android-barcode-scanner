package com.barcodescanner.sdk.data.msi

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Ownership contract tests for [MsiRegionCropper]: every bitmap it returns is
 * caller-owned, and producing it must NEVER recycle (or alias-then-recycle)
 * the input frame.
 *
 * The full-bounds case pins the fix for a device-only crash: there,
 * `Bitmap.createBitmap(src, ...)` over the whole image can return the source
 * itself, so the cropper recycled the live fusion frame and every subsequent
 * full-frame decode/rotation faulted (`getPixels() on a recycled bitmap`).
 * Robolectric's bitmap shadows always copy, so this JVM test pins the
 * ownership invariant (input alive across crop recycle) rather than
 * reproducing the framework aliasing itself.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MsiRegionCropperTest {

    private fun striped(w: Int = 120, h: Int = 60): Bitmap {
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val px = IntArray(w * h)
        for (y in 0 until h) {
            for (x in 0 until w) {
                px[y * w + x] = if ((x / 6) % 2 == 0) Color.BLACK else Color.WHITE
            }
        }
        bmp.setPixels(px, 0, w, 0, 0, w, h)
        return bmp
    }

    @Test
    fun fullBoundsCrop_neverRecyclesInput() {
        val src = striped()
        try {
            // Box covering the frame: padding clamps to the full image, the
            // exact shape that used to alias + recycle the input.
            val res = MsiRegionCropper.cropAndDeskew(src, Rect(0, 0, src.width, src.height))
            assertNotNull("expected a crop", res)
            assertFalse("input must be alive while the crop is used", src.isRecycled)
            // Caller recycles the crop (MsiRegionAssistDecoder contract)...
            res!!.bitmap.recycle()
            // ...and the input must STILL be alive for the other engines.
            assertFalse("recycling the crop must not recycle the input", src.isRecycled)
            assertEquals(src.width, res.bitmap.width)
        } finally {
            src.recycle()
        }
    }

    @Test
    fun partialCrop_neverRecyclesInput() {
        val src = striped()
        try {
            val res = MsiRegionCropper.cropAndDeskew(src, Rect(10, 10, 100, 50))
            assertNotNull(res)
            res!!.bitmap.recycle()
            assertFalse(src.isRecycled)
        } finally {
            src.recycle()
        }
    }

    @Test
    fun emptyBox_returnsNull() {
        val src = striped()
        try {
            assertNull(MsiRegionCropper.cropAndDeskew(src, Rect(5, 5, 5, 5)))
            assertFalse(src.isRecycled)
        } finally {
            src.recycle()
        }
    }
}
