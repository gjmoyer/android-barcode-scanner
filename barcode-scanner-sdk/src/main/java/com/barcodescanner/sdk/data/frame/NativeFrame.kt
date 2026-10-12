package com.barcodescanner.sdk.data.frame

import java.nio.ByteBuffer

/**
 * Kotlin face of the native frame path (`msi_frame.cpp`): every pixel
 * operation of the live pipeline — crop to the aim box, contrast stretch,
 * rotate upright — happens in ONE native pass per camera frame. Kotlin only
 * computes geometry and owns buffers.
 */
internal object NativeFrame {

    /** Neutral NV21 chroma: grey, so ML Kit sees exactly the luma plane. */
    const val NEUTRAL_CHROMA: Byte = 0x80.toByte()

    /**
     * True when `libmsi_decoder` (which hosts the frame path) loaded.
     * Unsupported ABIs (x86/x86_64 — ARM-only packaging) degrade to "no
     * prepared frame" instead of crashing; see MsiNativeDecoder.nativeAvailable.
     */
    val available: Boolean = runCatching { System.loadLibrary("msi_decoder") }.isSuccess

    /**
     * Direct NV21 buffer for an upright [width] x [height] luma image with the
     * chroma half pre-filled neutral, once — [prepare] only ever writes luma.
     */
    fun allocate(width: Int, height: Int): ByteBuffer {
        require(width > 0 && height > 0 && width % 2 == 0 && height % 2 == 0) {
            "NV21 dimensions must be positive and even: ${width}x$height"
        }
        val buffer = ByteBuffer.allocateDirect(LumaFrame.nv21Size(width, height))
        val luma = width * height
        buffer.position(luma)
        buffer.put(ByteArray(buffer.capacity() - luma) { NEUTRAL_CHROMA })
        buffer.clear()
        return buffer
    }

    /** Upright output width of a [cropWidth] x [cropHeight] crop rotated [rotationDegrees]. */
    fun uprightWidth(cropWidth: Int, cropHeight: Int, rotationDegrees: Int): Int =
        if (isQuarterTurn(rotationDegrees)) cropHeight else cropWidth

    /** Upright output height of a [cropWidth] x [cropHeight] crop rotated [rotationDegrees]. */
    fun uprightHeight(cropWidth: Int, cropHeight: Int, rotationDegrees: Int): Int =
        if (isQuarterTurn(rotationDegrees)) cropWidth else cropHeight

    /**
     * Crops `[left, right) x [top, bottom)` out of the luma plane [src]
     * (direct, [srcRowStride] bytes per row), contrast-stretches it when
     * [stretch], and writes it rotated upright by [rotationDegrees]
     * (clockwise, ImageInfo.rotationDegrees) into [dst]'s luma area — one
     * native read and one write. The region must lie inside the plane.
     * False on any invalid input or when the native library is unavailable.
     */
    fun prepare(
        src: ByteBuffer,
        srcWidth: Int,
        srcHeight: Int,
        srcRowStride: Int,
        left: Int,
        top: Int,
        right: Int,
        bottom: Int,
        rotationDegrees: Int,
        stretch: Boolean,
        dst: ByteBuffer,
    ): Boolean {
        if (!available || !src.isDirect || !dst.isDirect) return false
        return try {
            nativePrepare(
                src, srcWidth, srcHeight, srcRowStride,
                left, top, right, bottom,
                rotationDegrees, stretch, dst,
            )
        } catch (e: UnsatisfiedLinkError) {
            false
        }
    }

    private fun isQuarterTurn(rotationDegrees: Int): Boolean =
        ((rotationDegrees % 360 + 360) % 360) % 180 == 90

    private external fun nativePrepare(
        src: ByteBuffer,
        srcWidth: Int,
        srcHeight: Int,
        srcRowStride: Int,
        left: Int,
        top: Int,
        right: Int,
        bottom: Int,
        rotationDegrees: Int,
        stretch: Boolean,
        dst: ByteBuffer,
    ): Boolean
}
