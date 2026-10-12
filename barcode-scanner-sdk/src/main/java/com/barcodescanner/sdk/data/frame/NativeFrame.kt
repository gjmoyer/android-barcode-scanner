package com.barcodescanner.sdk.data.frame

import android.graphics.Bitmap
import java.nio.ByteBuffer

/**
 * Kotlin face of the native frame path (`msi_frame.cpp`): every pixel
 * operation — crop to the aim box, gray conversion of stills, contrast
 * stretch, rotate upright — happens natively, once per camera frame or
 * still. Kotlin only computes geometry and owns buffers.
 */
internal object NativeFrame {

    /** Neutral NV21 chroma: grey, so ML Kit sees exactly the luma plane. */
    const val NEUTRAL_CHROMA: Byte = 0x80.toByte()

    /**
     * Stills above this many pixels are box-downscaled natively by the
     * smallest integer factor that fits: a 12 MP photo stays full resolution,
     * 48–50 MP sensor modes halve. Full resolution matters for MSI — the
     * real-photo regression read 45/110 shelf tags at full resolution against
     * 0–4/110 after the retired 1280 px Kotlin downscale.
     */
    const val MAX_STILL_PIXELS: Long = 16_000_000L

    /**
     * True when `libmsi_decoder` (which hosts the frame path) loaded.
     * Unsupported ABIs (32-bit x86 is not packaged) degrade to "no prepared
     * frame" instead of crashing; see MsiNativeDecoder.nativeAvailable.
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

    /** Smallest integer box-downscale factor that brings a still within [MAX_STILL_PIXELS]. */
    fun stillScale(width: Int, height: Int): Int {
        require(width > 0 && height > 0) { "still dimensions must be positive: ${width}x$height" }
        var scale = 1
        while ((width / scale).toLong() * (height / scale) > MAX_STILL_PIXELS) scale++
        return scale
    }

    /**
     * Gray extent of a still dimension after downscaling by [scale], trimmed
     * to even (NV21). Mirrors `msi::stillGrayDim`; the native side rejects a
     * mismatch rather than writing a differently sized image.
     */
    fun stillGrayDim(dim: Int, scale: Int): Int =
        if (dim <= 0 || scale < 1) 0 else (dim / scale) and 1.inv()

    /**
     * Converts a still [bitmap] into the one upright [LumaFrame] every engine
     * reads: gray conversion (box-downscaled per [stillScale]) and rotation
     * upright by [rotationDegrees] (clockwise) in one native call. Never
     * contrast-stretched: over a whole photo the 2%/98% stretch clips the
     * bars (measured on real shelf tags), unlike on a small live aim-box
     * crop. The caller's bitmap is never modified; configs the
     * native side cannot lock or read (HARDWARE, RGBA_F16, RGBA_1010102) are
     * first copied to ARGB_8888 by the framework and the copy is recycled.
     *
     * @return null when the image is too small to hold an even 2x2 frame.
     * @throws IllegalStateException when the native library is unavailable,
     *   the bitmap is recycled or the conversion fails.
     */
    fun prepareStill(
        bitmap: Bitmap,
        rotationDegrees: Int,
        maxOrientations: Int,
        scanTopFraction: Float,
    ): LumaFrame? {
        check(available) { "native frame library not loaded on this ABI" }
        check(!bitmap.isRecycled) { "bitmap is recycled" }
        val scale = stillScale(bitmap.width, bitmap.height)
        val grayW = stillGrayDim(bitmap.width, scale)
        val grayH = stillGrayDim(bitmap.height, scale)
        if (grayW == 0 || grayH == 0) return null
        val outW = uprightWidth(grayW, grayH, rotationDegrees)
        val outH = uprightHeight(grayW, grayH, rotationDegrees)
        val dst = allocate(outW, outH)
        val readable = if (bitmap.config in NATIVE_CONFIGS) {
            bitmap
        } else {
            checkNotNull(bitmap.copy(Bitmap.Config.ARGB_8888, false)) {
                "cannot copy ${bitmap.config} bitmap to ARGB_8888"
            }
        }
        try {
            // isPremultiplied is read here, exactly, on every API level; the
            // NDK's AndroidBitmapInfo alpha flags only exist from API 30.
            // Opaque bitmaps (JPEG, camera) skip the per-pixel unpremultiply.
            check(
                nativePrepareBitmap(
                    readable, scale, grayW, grayH, rotationDegrees,
                    readable.isPremultiplied && readable.hasAlpha(), dst,
                ),
            ) {
                "native still conversion failed (${bitmap.width}x${bitmap.height} ${bitmap.config})"
            }
        } finally {
            if (readable !== bitmap) readable.recycle()
        }
        return LumaFrame(dst, outW, outH, maxOrientations, scanTopFraction)
    }

    /** Configs `msi_bitmap_jni.cpp` locks and reads directly. */
    private val NATIVE_CONFIGS = setOf(
        Bitmap.Config.ARGB_8888,
        Bitmap.Config.RGB_565,
        Bitmap.Config.ALPHA_8,
    )

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

    private external fun nativePrepareBitmap(
        bitmap: Bitmap,
        scale: Int,
        grayWidth: Int,
        grayHeight: Int,
        rotationDegrees: Int,
        premultiplied: Boolean,
        dst: ByteBuffer,
    ): Boolean
}
