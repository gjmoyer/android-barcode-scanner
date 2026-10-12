package com.barcodescanner.sdk.data.frame

import java.nio.ByteBuffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * JVM contract tests for the shared prepared-frame buffer. Pixel behaviour of
 * the native pass is asserted by the host harness (tools/msi-harness,
 * `frameSuite`); here: layout, validation and the no-native-library guard.
 */
class LumaFrameTest {

    @Test
    fun allocate_isDirectNv21WithNeutralChroma() {
        val buf = NativeFrame.allocate(8, 4)
        assertEquals(true, buf.isDirect)
        assertEquals(8 * 4 * 3 / 2, buf.capacity())
        assertEquals(0, buf.position())
        assertEquals(buf.capacity(), buf.limit())
        for (i in 0 until 32) assertEquals("luma[$i]", 0.toByte(), buf.get(i))
        for (i in 32 until 48) assertEquals("chroma[$i]", NativeFrame.NEUTRAL_CHROMA, buf.get(i))
    }

    @Test
    fun allocate_rejectsOddOrEmptyDimensions() {
        assertThrows(IllegalArgumentException::class.java) { NativeFrame.allocate(7, 4) }
        assertThrows(IllegalArgumentException::class.java) { NativeFrame.allocate(8, 3) }
        assertThrows(IllegalArgumentException::class.java) { NativeFrame.allocate(0, 4) }
    }

    @Test
    fun uprightSize_swapsOnlyForQuarterTurns() {
        assertEquals(640, NativeFrame.uprightWidth(640, 320, 0))
        assertEquals(320, NativeFrame.uprightWidth(640, 320, 90))
        assertEquals(640, NativeFrame.uprightWidth(640, 320, 180))
        assertEquals(320, NativeFrame.uprightWidth(640, 320, 270))
        assertEquals(640, NativeFrame.uprightHeight(640, 320, -90))
        assertEquals(320, NativeFrame.uprightHeight(640, 320, 360))
    }

    @Test
    fun lumaFrame_validatesBufferAndOptions() {
        val ok = NativeFrame.allocate(8, 4)
        LumaFrame(ok, 8, 4)
        LumaFrame(ok, 8, 4, maxOrientations = 4, scanTopFraction = 0.25f)
        assertThrows(IllegalArgumentException::class.java) {
            LumaFrame(ByteBuffer.allocate(48), 8, 4) // heap buffer
        }
        assertThrows(IllegalArgumentException::class.java) {
            LumaFrame(ByteBuffer.allocateDirect(47), 8, 4) // no room for chroma
        }
        assertThrows(IllegalArgumentException::class.java) {
            // Oversized (e.g. a pooled buffer for a larger crop): chroma at
            // width*height would be stale luma, not the neutral block.
            LumaFrame(NativeFrame.allocate(8, 6), 8, 4)
        }
        assertThrows(IllegalArgumentException::class.java) { LumaFrame(ok, 7, 4) }
        assertThrows(IllegalArgumentException::class.java) { LumaFrame(ok, 8, 4, maxOrientations = 5) }
        assertThrows(IllegalArgumentException::class.java) { LumaFrame(ok, 8, 4, scanTopFraction = 0.95f) }
    }

    @Test
    fun prepare_withoutNativeLib_returnsFalse() {
        // The .so cannot load on the JVM — exactly the unsupported-ABI case.
        assertFalse(NativeFrame.available)
        val src = ByteBuffer.allocateDirect(16 * 8)
        val dst = NativeFrame.allocate(8, 4)
        assertFalse(NativeFrame.prepare(src, 16, 8, 16, 0, 0, 8, 4, 0, true, dst))
    }
}
