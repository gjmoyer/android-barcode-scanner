package com.barcodescanner.sdk.data.frame

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Test
import java.nio.ByteBuffer

class LumaBufferPoolTest {

    private var allocations = 0
    private val pool = LumaBufferPool { w, h ->
        allocations++
        NativeFrame.allocate(w, h)
    }

    @Test
    fun releasedBuffer_isReused_withoutAllocation() {
        val first = pool.take(64, 32)
        pool.release(first)
        assertSame(first, pool.take(64, 32))
        assertEquals(1, allocations)
    }

    @Test
    fun unreleasedBuffer_isNeverHandedOutAgain() {
        // A timed-out decode never releases: an ML Kit task may still read it.
        val inUse = pool.take(64, 32)
        val next = pool.take(64, 32)
        assertNotSame(inUse, next)
        assertEquals(2, allocations)
    }

    @Test
    fun spareIsTakenOnce() {
        val buffer = pool.take(64, 32)
        pool.release(buffer)
        assertSame(buffer, pool.take(64, 32))
        assertNotSame(buffer, pool.take(64, 32))
    }

    @Test
    fun sameCapacity_otherDimensions_isReused() {
        // Rotation flips upright dims; capacity (hence luma size) is equal and
        // the chroma tail is uniformly neutral, so the buffer still fits.
        val landscape = pool.take(64, 32)
        pool.release(landscape)
        val portrait = pool.take(32, 64)
        assertSame(landscape, portrait)
        assertEquals(LumaFrame.nv21Size(32, 64), portrait.capacity())
    }

    @Test
    fun capacityMismatch_allocatesExactBuffer() {
        pool.release(pool.take(64, 32))
        val bigger = pool.take(128, 32)
        assertEquals(LumaFrame.nv21Size(128, 32), bigger.capacity())
        assertEquals(2, allocations)
    }

    @Test
    fun clear_dropsSpare() {
        val buffer = pool.take(64, 32)
        pool.release(buffer)
        pool.clear()
        assertNotSame(buffer, pool.take(64, 32))
    }

    @Test
    fun allocatedBuffer_hasNeutralChromaTail() {
        val buffer: ByteBuffer = LumaBufferPool().take(8, 4)
        for (i in 32 until buffer.capacity()) assertEquals(NativeFrame.NEUTRAL_CHROMA, buffer.get(i))
    }
}
