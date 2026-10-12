package com.barcodescanner.sdk.data.zxingcpp

import android.annotation.SuppressLint
import android.graphics.ImageFormat
import android.graphics.Rect
import android.media.Image
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageInfo
import androidx.camera.core.ImageProxy
import androidx.camera.core.impl.TagBundle
import androidx.camera.core.impl.utils.ExifData
import com.barcodescanner.sdk.data.frame.LumaFrame
import java.nio.ByteBuffer

/**
 * Presents a prepared [LumaFrame] as a YUV_420_888 [ImageProxy] so the
 * prebuilt zxing-cpp wrapper reads the shared buffer in place: its only
 * zero-copy entry point is `BarcodeReader.read(ImageProxy)` (the luma-buffer
 * JNI `readYBuffer` is private; the Bitmap entry would force an ARGB copy).
 *
 * zxing-cpp 3.1.1 touches exactly: [getFormat] (must be a YUV format),
 * `planes[0]` buffer + rowStride, [getCropRect], and
 * `imageInfo.rotationDegrees` — 0 here, the frame is already upright. U/V
 * planes describe the NV21 tail (neutral chroma) so the proxy stays a valid
 * YUV_420_888 view for any other consumer.
 *
 * Never closes anything: the frame owner controls the buffer's lifetime.
 * Implementing CameraX interfaces pins restricted `impl` types (TagBundle,
 * ExifData); a CameraX upgrade that changes them fails compilation here,
 * not at runtime.
 */
@SuppressLint("RestrictedApi")
internal class LumaImageProxy(private val frame: LumaFrame) : ImageProxy {

    private val lumaSize = frame.width * frame.height

    // NV21 tail is V,U interleaved: V first, U one byte later, both pixelStride 2.
    private val planes: Array<ImageProxy.PlaneProxy> = arrayOf(
        Plane(slice(0, lumaSize), frame.width, 1),
        Plane(slice(lumaSize + 1, lumaSize / 2 - 1), frame.width, 2),
        Plane(slice(lumaSize, lumaSize / 2 - 1), frame.width, 2),
    )

    private var cropRect = Rect(0, 0, frame.width, frame.height)

    private val info = object : ImageInfo {
        override fun getTagBundle(): TagBundle = TagBundle.emptyBundle()
        override fun getTimestamp(): Long = 0L
        override fun getRotationDegrees(): Int = 0
        override fun populateExifData(exifBuilder: ExifData.Builder) = Unit
    }

    override fun close() = Unit

    override fun getCropRect(): Rect = Rect(cropRect)

    override fun setCropRect(rect: Rect?) {
        cropRect = rect?.let(::Rect) ?: Rect(0, 0, frame.width, frame.height)
    }

    override fun getFormat(): Int = ImageFormat.YUV_420_888

    override fun getHeight(): Int = frame.height

    override fun getWidth(): Int = frame.width

    override fun getPlanes(): Array<ImageProxy.PlaneProxy> = planes.copyOf()

    override fun getImageInfo(): ImageInfo = info

    @ExperimentalGetImage
    override fun getImage(): Image? = null

    /** Independent position/limit over the shared bytes (no copy). */
    private fun slice(offset: Int, length: Int): ByteBuffer =
        frame.buffer.duplicate().apply {
            clear()
            position(offset)
            limit(offset + length)
        }.slice()

    private class Plane(
        private val bytes: ByteBuffer,
        private val rowStride: Int,
        private val pixelStride: Int,
    ) : ImageProxy.PlaneProxy {
        override fun getRowStride(): Int = rowStride
        override fun getPixelStride(): Int = pixelStride

        // Fresh view per call: consumers may move position/limit.
        override fun getBuffer(): ByteBuffer = bytes.duplicate()
    }
}
