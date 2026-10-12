package com.barcodescanner.sdk.data.frame

import java.nio.ByteBuffer

/**
 * One prepared, upright 8-bit luma image that every engine reads in place —
 * the single per-frame product of [NativeFrame.prepare] (camera) or the
 * native still conversion. No engine copies, converts or rotates it.
 *
 * Buffer layout is NV21 so ML Kit can wrap it without a copy:
 * `width * height` luma bytes (stride = [width], 0 = black), then
 * `width * height / 2` neutral VU bytes (128) written once at allocation
 * ([NativeFrame.allocate]) and never touched again. Hence even dimensions,
 * and an EXACT-size buffer: a larger (pooled) buffer would put the chroma
 * ML Kit reads at `width * height` over stale luma instead of the neutral
 * block written at allocation, and break ML Kit's NV21 size expectation.
 *
 * Lifetime: the producer owns [buffer]. It must not be rewritten while any
 * engine may still read it — an ML Kit task outliving a timed-out decode
 * included (see the camera buffer pool).
 *
 * @param maxOrientations MSI orientations tried natively (1..4, order 0, 180,
 *   90, 270). Live aim-box frames use 1: reversed reads of upright tags were
 *   all false MOD 10 passes in the aim-box regression.
 * @param scanTopFraction Share of the height the MSI band search skips (see
 *   `msi::DecodeOptions`): 0 for aim-box crops, 0.25 for full frames.
 */
internal class LumaFrame(
    val buffer: ByteBuffer,
    val width: Int,
    val height: Int,
    val maxOrientations: Int = 1,
    val scanTopFraction: Float = 0f,
) {
    init {
        require(buffer.isDirect) { "LumaFrame needs a direct buffer (engines read it natively)" }
        require(width > 0 && height > 0) { "LumaFrame dimensions must be positive: ${width}x$height" }
        require(width % 2 == 0 && height % 2 == 0) {
            "LumaFrame dimensions must be even (NV21 chroma): ${width}x$height"
        }
        require(buffer.capacity() == nv21Size(width, height)) {
            "LumaFrame buffer ${buffer.capacity()} != NV21 size ${nv21Size(width, height)}"
        }
        require(maxOrientations in 1..4) { "maxOrientations must be 1..4, was $maxOrientations" }
        require(scanTopFraction in 0f..0.9f) { "scanTopFraction must be 0..0.9, was $scanTopFraction" }
    }

    companion object {
        /** NV21 byte count: full-resolution luma + half-resolution interleaved VU. */
        fun nv21Size(width: Int, height: Int): Int = width * height + width * height / 2
    }
}
