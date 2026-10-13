package com.barcodescanner.sdk.data.frame

import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicReference

/**
 * Pool of one prepared-frame buffer for the live camera. The single-flight
 * gate keeps at most one [LumaFrame] alive, so the buffer the previous decode
 * released is the next frame's target and steady state allocates nothing.
 *
 * Only a buffer no engine can still read may be [release]d: after a timed-out
 * or cancelled decode an ML Kit task may keep reading it, so the owner simply
 * never releases it and the next [take] allocates a fresh one.
 *
 * Thread-safe: [take] runs on the analyzer thread, [release] on the decode
 * coroutine.
 */
internal class LumaBufferPool(
    private val allocate: (width: Int, height: Int) -> ByteBuffer = NativeFrame::allocate,
) {
    private val spare = AtomicReference<ByteBuffer?>(null)

    /**
     * An exact NV21 buffer for [width] x [height]. Any spare of the same
     * capacity fits whatever its last dimensions: equal capacity means equal
     * luma size, and the chroma tail is uniformly neutral from allocation on.
     */
    fun take(width: Int, height: Int): ByteBuffer =
        spare.getAndSet(null)?.takeIf { it.capacity() == LumaFrame.nv21Size(width, height) }
            ?: allocate(width, height)

    /** Returns a buffer nothing reads any more; it becomes the next [take]. */
    fun release(buffer: ByteBuffer) {
        spare.set(buffer)
    }

    fun clear() {
        spare.set(null)
    }
}
