package com.barcodescanner.sdk.data.frame

import android.graphics.Bitmap
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * JVM contract tests for the native still conversion's Kotlin geometry. The
 * pixel conversion itself is asserted by the host harness (tools/msi-harness,
 * `frameSuite` section 7), which also pins `msi::stillGrayDim` to the same
 * values as [NativeFrame.stillGrayDim] here.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class NativeFrameStillTest {

    @Test
    fun stillScale_keepsPhoneStillsFullResolution() {
        assertEquals(1, NativeFrame.stillScale(4000, 3000)) // 12 MP
        assertEquals(1, NativeFrame.stillScale(4000, 4000)) // exactly 16 MP
        assertEquals(1, NativeFrame.stillScale(64, 32))
    }

    @Test
    fun stillScale_halvesHighResolutionSensorModes() {
        assertEquals(2, NativeFrame.stillScale(8000, 6000)) // 48 MP
        assertEquals(2, NativeFrame.stillScale(8160, 6120)) // 50 MP
        assertEquals(3, NativeFrame.stillScale(12000, 9000)) // 108 MP
        for ((w, h) in listOf(8000 to 6000, 12000 to 9000, 16001 to 1001)) {
            val s = NativeFrame.stillScale(w, h)
            assertTrue("$w x $h / $s fits", (w / s).toLong() * (h / s) <= NativeFrame.MAX_STILL_PIXELS)
            assertTrue("$w x $h smallest", s == 1 || (w / (s - 1)).toLong() * (h / (s - 1)) > NativeFrame.MAX_STILL_PIXELS)
        }
    }

    @Test
    fun stillScale_rejectsEmptyImage() {
        assertThrows(IllegalArgumentException::class.java) { NativeFrame.stillScale(0, 10) }
    }

    @Test
    fun stillGrayDim_mirrorsNative() {
        // Same cases as the harness's "still gray dims" assert.
        assertEquals(4000, NativeFrame.stillGrayDim(4000, 1))
        assertEquals(4000, NativeFrame.stillGrayDim(4001, 1))
        assertEquals(4000, NativeFrame.stillGrayDim(8000, 2))
        assertEquals(2, NativeFrame.stillGrayDim(7, 2))
        assertEquals(0, NativeFrame.stillGrayDim(1, 1))
        assertEquals(0, NativeFrame.stillGrayDim(0, 1))
        assertEquals(0, NativeFrame.stillGrayDim(100, 0))
    }

    @Test
    fun prepareStill_withoutNativeLib_throwsIllegalState() {
        assertFalse(NativeFrame.available)
        val bmp = Bitmap.createBitmap(64, 32, Bitmap.Config.ARGB_8888)
        try {
            assertThrows(IllegalStateException::class.java) {
                NativeFrame.prepareStill(bmp, 0, maxOrientations = 4, scanTopFraction = 0.25f)
            }
        } finally {
            bmp.recycle()
        }
    }
}
