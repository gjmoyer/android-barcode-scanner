package com.barcodescanner.sdk.data.zxingcpp

import android.graphics.ImageFormat
import android.graphics.Rect
import com.barcodescanner.sdk.data.frame.LumaFrame
import com.barcodescanner.sdk.data.frame.NativeFrame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The adapter must expose the prepared frame exactly as zxing-cpp's
 * `read(ImageProxy)` consumes it (planes[0] buffer + rowStride, cropRect,
 * rotation, YUV format) — without copying the shared buffer.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LumaImageProxyTest {

    private val w = 16
    private val h = 8

    private fun frame(): LumaFrame {
        val buf = NativeFrame.allocate(w, h)
        buf.put(0, 11)
        buf.put(w * h - 1, 22)
        buf.put(w * h, 33) // first V
        buf.put(w * h + 1, 44) // first U
        return LumaFrame(buf, w, h)
    }

    @Test
    fun describesUprightYuv420WithFullCrop() {
        val p = LumaImageProxy(frame())
        assertEquals(ImageFormat.YUV_420_888, p.format)
        assertEquals(w, p.width)
        assertEquals(h, p.height)
        assertEquals(Rect(0, 0, w, h), p.cropRect)
        assertEquals(0, p.imageInfo.rotationDegrees)
    }

    @Test
    fun lumaPlane_isTheSharedBufferInPlace() {
        val f = frame()
        val y = LumaImageProxy(f).planes[0]
        assertEquals(w, y.rowStride)
        assertEquals(1, y.pixelStride)
        val bytes = y.buffer
        assertTrue("zxing reads the plane natively", bytes.isDirect)
        assertEquals(w * h, bytes.remaining())
        assertEquals(11.toByte(), bytes.get(0))
        assertEquals(22.toByte(), bytes.get(w * h - 1))
        // Same memory, not a snapshot: later writes are visible.
        f.buffer.put(1, 55)
        assertEquals(55.toByte(), y.buffer.get(1))
    }

    @Test
    fun chromaPlanes_followNv21Order() {
        val planes = LumaImageProxy(frame()).planes
        assertEquals(3, planes.size)
        val u = planes[1]
        val v = planes[2]
        assertEquals(44.toByte(), u.buffer.get(0))
        assertEquals(33.toByte(), v.buffer.get(0))
        for (c in listOf(u, v)) {
            assertEquals(2, c.pixelStride)
            assertEquals(w, c.rowStride)
            assertEquals(w * h / 2 - 1, c.buffer.remaining())
        }
        assertEquals(NativeFrame.NEUTRAL_CHROMA, v.buffer.get(2))
    }

    @Test
    fun planeBuffers_areIndependentViews() {
        val y = LumaImageProxy(frame()).planes[0]
        y.buffer.position(5)
        assertEquals("a consumer moving position must not affect the next reader", 0, y.buffer.position())
    }

    @Test
    fun cropRect_isDefensivelyCopied_andCloseIsNoOp() {
        val f = frame()
        val p = LumaImageProxy(f)
        p.cropRect.set(1, 1, 2, 2)
        assertEquals(Rect(0, 0, w, h), p.cropRect)
        val r = Rect(2, 2, 8, 6)
        p.setCropRect(r)
        r.set(0, 0, 1, 1)
        assertEquals(Rect(2, 2, 8, 6), p.cropRect)
        p.setCropRect(null)
        assertEquals(Rect(0, 0, w, h), p.cropRect)
        p.close()
        assertEquals(11.toByte(), f.buffer.get(0))
    }
}
