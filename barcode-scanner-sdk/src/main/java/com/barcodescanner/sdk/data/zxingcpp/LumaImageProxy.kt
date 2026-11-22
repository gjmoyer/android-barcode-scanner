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
 * zxing-cpp 3.1.1 touches exactly: [format] (must be a YUV format),
 * `planes[0]` buffer + rowStride, [cropRect], and
 * `imageInfo.rotationDegrees` — 0 here, the frame is already upright. U/V
 * planes describe the NV21 tail (neutral chroma) so the proxy stays a valid
 * YUV_420_888 view for any other consumer.
 *
 * CameraX 1.7 lifted `width`/`height`/`format`/`cropRect` into Kotlin
 * properties on `androidx.camera.common.ImageWrapper` (and
 * `rowStride`/`pixelStride`/`buffer` into `ImagePlane`), so the Java
 * `getXxx()` methods on `ImageProxy`/`PlaneProxy` resolve to those properties
 * and must be overridden as properties, not functions.
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

    // Backing store for cropRect: the getter must hand out a copy, so this
    // cannot be the property itself.
    private var storedCropRect = Rect(0, 0, frame.width, frame.height)

    private val info = object : ImageInfo {
        override fun getTagBundle(): TagBundle = TagBundle.emptyBundle()
        override fun getTimestamp(): Long = 0L
        override fun getRotationDegrees(): Int = 0
        override fun populateExifData(exifBuilder: ExifData.Builder) = Unit
    }

    override fun close() = Unit

    // Read-only property from ImageWrapper; setCropRect stays a plain Java
    // method (its parameter is @Nullable, so it cannot be the property setter).
    override val cropRect: Rect get() = Rect(storedCropRect)

    override fun setCropRect(rect: Rect?) {
        storedCropRect = rect?.let(::Rect) ?: Rect(0, 0, frame.width, frame.height)
    }

    override val format: Int get() = ImageFormat.YUV_420_888

    override val height: Int get() = frame.height

    override val width: Int get() = frame.width

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
        override val rowStride: Int,
        override val pixelStride: Int,
    ) : ImageProxy.PlaneProxy {
        // Fresh view per call: consumers may move position/limit.
        override val buffer: ByteBuffer get() = bytes.duplicate()
    }
}
