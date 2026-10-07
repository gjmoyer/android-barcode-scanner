package com.barcodescanner.sdk.camera

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import android.graphics.RectF
import android.os.SystemClock
import android.util.Size
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import com.barcodescanner.sdk.domain.model.ScanFrame
import com.barcodescanner.sdk.domain.model.ScannerConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * CameraX live-scan manager (internal; host apps use startCamera on the facade).
 *
 * Frame policy tuned for barcode workloads:
 *  - 1280x720 cap via ResolutionSelector (bounds MSI/preprocessing cost),
 *  - STRATEGY_KEEP_ONLY_LATEST + single-flight decode gate: at most ONE decode
 *    in flight; while a slow MSI frame decodes, newer frames are dropped (not queued),
 *  - stride-aware YUV_420_888 -> ARGB conversion (rowStride/pixelStride and
 *    cropRect respected; direct integer BT.601, no JPEG round-trip so narrow
 *    bars survive; the naive buffer-concat path shears on Pixel/Samsung),
 *  - structured [scope] (no GlobalScope): stop()/close() cancels in-flight work,
 *  - sensor rotation forwarded into [ScanFrame.rotationDegrees] so still-image
 *    and live paths share one orientation pipeline.
 */
internal class CameraScanManager(
    private val context: Context,
    private val config: ScannerConfig,
    private val scope: CoroutineScope,
    private val onFrame: suspend (ScanFrame) -> Unit,
) {
    private var cameraProvider: ProcessCameraProvider? = null
    private var previewUseCase: Preview? = null
    private var analysisUseCase: ImageAnalysis? = null
    private var analysisExecutor: ExecutorService? = null
    /**
     * Preview view for what-you-see-is-what-scans cropping (null for the
     * headless SurfaceProvider entry point, where the legacy sensor-centered
     * ROI applies). Only width/height are ever read.
     */
    private var previewView: PreviewView? = null

    /** Single-flight gate: true while a decode is in flight. */
    private val decodeInFlight = AtomicBoolean(false)

    @Volatile
    private var stopped = false

    fun start(lifecycleOwner: LifecycleOwner, previewView: PreviewView) {
        this.previewView = previewView
        startInternal(lifecycleOwner, previewView.surfaceProvider)
    }

    /** Headless / Compose entry point: binds preview to any SurfaceProvider. */
    fun start(lifecycleOwner: LifecycleOwner, surfaceProvider: Preview.SurfaceProvider) {
        // No view: a stale size from a previous view session must never crop
        // a headless one — the legacy sensor-centered ROI applies instead.
        previewView = null
        startInternal(lifecycleOwner, surfaceProvider)
    }

    private fun startInternal(
        lifecycleOwner: LifecycleOwner,
        surfaceProvider: Preview.SurfaceProvider,
    ) {
        // Restart-safe: shut down any previous executor before creating a new one.
        runCatching { analysisExecutor?.shutdownNow() }
        stopped = false
        analysisExecutor = Executors.newSingleThreadExecutor()
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener({
            if (stopped) return@addListener
            val provider = runCatching { future.get() }.getOrNull() ?: return@addListener
            cameraProvider = provider
            bind(provider, lifecycleOwner, surfaceProvider)
        }, ContextCompat.getMainExecutor(context))
    }

    fun stop() {
        stopped = true
        decodeInFlight.set(false)
        previewView = null
        unbindUseCases()
        cameraProvider = null
        runCatching { analysisExecutor?.shutdownNow() }
        analysisExecutor = null
    }

    /**
     * Unbinds ONLY this manager's use cases. Never `unbindAll()`: the
     * ProcessCameraProvider is process-wide and hosts may have their own
     * preview/video use cases bound to it.
     */
    private fun unbindUseCases() {
        val provider = cameraProvider ?: return
        val cases = listOfNotNull(previewUseCase, analysisUseCase)
        if (cases.isNotEmpty()) runCatching { provider.unbind(*cases.toTypedArray()) }
        previewUseCase = null
        analysisUseCase = null
    }

    @SuppressLint("UnsafeOptInUsageError")
    private fun bind(
        provider: ProcessCameraProvider,
        lifecycleOwner: LifecycleOwner,
        surfaceProvider: Preview.SurfaceProvider,
    ) {
        val executor = analysisExecutor ?: return
        unbindUseCases()
        val preview = Preview.Builder().build().also {
            it.surfaceProvider = surfaceProvider
        }
        val analysis = ImageAnalysis.Builder()
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
            .setResolutionSelector(
                ResolutionSelector.Builder()
                    .setResolutionStrategy(
                        ResolutionStrategy(
                            Size(1280, 720),
                            ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER,
                        ),
                    ).build(),
            )
            .build()
        analysis.setAnalyzer(executor) { imageProxy ->
            val frame = try {
                imageProxy.toScanFrame()
            } finally {
                imageProxy.close()
            } ?: return@setAnalyzer
            // Single-flight: drop this frame if a decode is still running.
            if (!decodeInFlight.compareAndSet(false, true)) {
                frame.bitmap.recycle()
                return@setAnalyzer
            }
            scope.launch {
                try {
                    if (stopped) return@launch
                    onFrame(frame)
                } finally {
                    runCatching { frame.bitmap.recycle() }
                    decodeInFlight.set(false)
                }
            }
        }
        previewUseCase = preview
        analysisUseCase = analysis
        provider.bindToLifecycle(
            lifecycleOwner,
            CameraSelector.DEFAULT_BACK_CAMERA,
            preview,
            analysis,
        )
    }

    private fun ImageProxy.toScanFrame(): ScanFrame? = runCatching {
        // NOTE: named toStrideAwareBitmap (not toBitmap) — CameraX 1.6 added a
        // member ImageProxy.toBitmap() which shadows extensions; an explicit
        // distinct name guarantees OUR stride-aware conversion runs.
        val bitmap = toStrideAwareBitmap() ?: return null
        ScanFrame(
            bitmap = bitmap,
            rotationDegrees = when (imageInfo.rotationDegrees) {
                90, 180, 270 -> imageInfo.rotationDegrees
                else -> 0
            },
            timestampMillis = SystemClock.elapsedRealtime(),
        )
    }.getOrNull()

    /**
     * Stride-aware YUV_420_888 -> Bitmap via direct NV21 -> ARGB conversion.
     *
     * No JPEG round-trip: the old `YuvImage.compressToJpeg(70)` + decode path
     * low-passed the 2-3px narrow bars MSI/DataBar depend on (while the rest of
     * the pipeline uses `filter=false` precisely to preserve them). Direct
     * integer BT.601 conversion preserves edges and is faster (~1 pass vs
     * encode+decode). Honors [ImageProxy.getCropRect], rowStride and
     * pixelStride. Result capped at 1280px long edge with nearest-neighbor
     * scaling (`filter=false`) so narrow bars survive.
     */
    private fun ImageProxy.toStrideAwareBitmap(): Bitmap? = runCatching {
        val nv21 = toNv21() ?: return null
        // Clamp sensor crop to image bounds (defensive: some HALs report a
        // crop larger than the buffer on rotation).
        val raw = cropRect
        val left = raw.left.coerceIn(0, width)
        val top = raw.top.coerceIn(0, height)
        val right = raw.right.coerceIn(left + 1, width)
        val bottom = raw.bottom.coerceIn(top + 1, height)
        val sensor = Rect(left, top, right, bottom)
        // Viewfinder ROI (null = everything visible): what-you-see-is-what-scans.
        // The overlay box is drawn in PreviewView space, so the analysis crop is
        // derived from the displayed area — never the full sensor, which extends
        // past the FILL_CENTER display crop.
        val rotation = when (imageInfo.rotationDegrees) {
            90, 180, 270 -> imageInfo.rotationDegrees
            else -> 0
        }
        val view = previewView?.let { v ->
            // Plain int getters; transient zeros fall back to the sensor ROI.
            val vw = runCatching { v.width }.getOrDefault(0)
            val vh = runCatching { v.height }.getOrDefault(0)
            if (vw > 0 && vh > 0) vw to vh else null
        }
        val crop = if (view == null) {
            roiToBuffer(sensor, width, height, rotation, config.scanRegion)
        } else {
            viewBoxToBuffer(sensor, width, height, rotation, view.first, view.second, config.scanRegion)
        }
        val cw = crop.width()
        val ch = crop.height()
        if (cw <= 0 || ch <= 0) return null
        // Defensive: packed NV21 must hold the full frame; otherwise the
        // stride packer produced a short array (should not happen).
        if (nv21.size < width * height * 3 / 2) return null
        val argb = nv21ToArgb(nv21, width, height, crop)
        val full = Bitmap.createBitmap(argb, cw, ch, Bitmap.Config.ARGB_8888)
        val scale = minOf(1f, 1280f / maxOf(full.width, full.height))
        if (scale >= 1f) full
        else {
            val scaled = Bitmap.createScaledBitmap(
                full,
                (full.width * scale).toInt().coerceAtLeast(1),
                (full.height * scale).toInt().coerceAtLeast(1),
                false,
            )
            full.recycle()
            scaled
        }
    }.getOrNull()

    /**
     * Packed NV21 (as produced by [toNv21]: Y `w*h` + interleaved VU `w*h/2`)
     * to ARGB, converting only [crop]. Integer BT.601 full-range approx:
     * R=Y+1.402(V-128), G=Y-0.344(U-128)-0.714(V-128), B=Y+1.772(U-128).
     * Color accuracy is irrelevant for barcodes; edge preservation is.
     */
    internal fun nv21ToArgb(nv21: ByteArray, w: Int, h: Int, crop: Rect): IntArray {
        val cw = crop.width()
        val ch = crop.height()
        require(cw > 0 && ch > 0) { "empty crop" }
        require(nv21.size >= w * h * 3 / 2) { "short NV21 buffer" }
        val out = IntArray(cw * ch)
        val yBase = 0
        val vuBase = w * h
        var dst = 0
        for (y in 0 until ch) {
            val srcY = crop.top + y
            val yRow = yBase + srcY * w
            val uvRow = vuBase + (srcY shr 1) * w
            for (x in 0 until cw) {
                val srcX = crop.left + x
                val yy = nv21[yRow + srcX].toInt() and 0xFF
                val uvOffset = uvRow + (srcX shr 1) * 2
                val vv = nv21[uvOffset].toInt() and 0xFF
                val uu = nv21[uvOffset + 1].toInt() and 0xFF
                val vOff = vv - 128
                val uOff = uu - 128
                var r = yy + ((360 * vOff) shr 8)
                var g = yy - ((88 * uOff + 183 * vOff) shr 8)
                var b = yy + ((454 * uOff) shr 8)
                if (r < 0) r = 0 else if (r > 255) r = 255
                if (g < 0) g = 0 else if (g > 255) g = 255
                if (b < 0) b = 0 else if (b > 255) b = 255
                out[dst++] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
            }
        }
        return out
    }

    /** Packs YUV_420_888 planes into NV21 respecting strides and cropRect source size. */
    private fun ImageProxy.toNv21(): ByteArray? = runCatching {
        val w = width
        val h = height
        if (w <= 0 || h <= 0) return null
        // Guard against bogus dimensions (int overflow / OOM before native loop).
        val pixels = w.toLong() * h.toLong()
        if (pixels > MAX_FRAME_PIXELS) return null
        val yPlane = planes[0]
        val uPlane = planes[1]
        val vPlane = planes[2]
        val out = ByteArray((pixels * 3 / 2).toInt())

        // Y plane: row-by-row (rowStride may exceed width).
        val yBuf = yPlane.buffer
        val yRowStride = yPlane.rowStride
        var dst = 0
        val yRow = ByteArray(yRowStride)
        for (row in 0 until h) {
            yBuf.position(row * yRowStride)
            val take = minOf(yRowStride, yBuf.remaining())
            yBuf.get(yRow, 0, take)
            val copy = minOf(w, take)
            System.arraycopy(yRow, 0, out, dst, copy)
            dst += copy
        }

        // U/V planes: subsampled by 2; pixelStride 1 (planar) or 2 (interleaved).
        val uvH = h / 2
        val uvW = w / 2
        val uBuf = uPlane.buffer
        val vBuf = vPlane.buffer
        val uRowStride = uPlane.rowStride
        val vRowStride = vPlane.rowStride
        val uPixelStride = uPlane.pixelStride
        val vPixelStride = vPlane.pixelStride
        val uRow = ByteArray(uRowStride)
        val vRow = ByteArray(vRowStride)
        for (row in 0 until uvH) {
            uBuf.position(row * uRowStride)
            vBuf.position(row * vRowStride)
            uBuf.get(uRow, 0, minOf(uRowStride, uBuf.remaining()))
            vBuf.get(vRow, 0, minOf(vRowStride, vBuf.remaining()))
            for (col in 0 until uvW) {
                val vu = vRow[col * vPixelStride]
                val uu = uRow[col * uPixelStride]
                out[dst++] = vu // NV21 order: V then U
                out[dst++] = uu
            }
        }
        out
    }.getOrNull()

    companion object {
        /** Rejects bogus ImageProxy dimensions before the `w*h*3/2` alloc. */
        const val MAX_FRAME_PIXELS = 16_000_000L

        /**
         * Maps the on-screen viewfinder box to buffer pixels — the
         * what-you-see-is-what-scans crop.
         *
         * `PreviewView` shows the sensor with `FILL_CENTER`: the displayed area
         * is the sensor center-cropped to the view aspect, so a box drawn on the
         * view is NOT a box of the full sensor. This mirrors that display crop
         * (center-crop sensor to view aspect), then places the overlay [region]
         * fractions inside the visible area — exactly where the overlay draws
         * them. Null region = everything visible (not everything sensed).
         *
         * @param sensorCrop HAL crop in buffer pixels.
         * @param rotationDegrees imageInfo rotation (0/90/180/270); the overlay
         *   fractions are upright/display-space, the inverse map handles rotation.
         * @param viewWidth/viewHeight PreviewView pixels (>0; otherwise falls
         *   back to the legacy sensor-centered [roiToBuffer]).
         */
        internal fun viewBoxToBuffer(
            sensorCrop: Rect,
            imageWidth: Int,
            imageHeight: Int,
            rotationDegrees: Int,
            viewWidth: Int,
            viewHeight: Int,
            region: ScannerConfig.ScanRegion?,
        ): Rect {
            if (viewWidth <= 0 || viewHeight <= 0) {
                return roiToBuffer(sensorCrop, imageWidth, imageHeight, rotationDegrees, region)
            }
            val buffer = Rect(0, 0, imageWidth, imageHeight)
            val sensor = Rect(sensorCrop).apply { intersect(buffer) }
            if (sensor.isEmpty) return Rect(buffer)
            // Content dims in display (post-rotation) space.
            val cw = if (rotationDegrees == 90 || rotationDegrees == 270) imageHeight else imageWidth
            val ch = if (rotationDegrees == 90 || rotationDegrees == 270) imageWidth else imageHeight
            if (cw <= 0 || ch <= 0) return Rect(sensor)
            // FILL_CENTER: visible = sensor center-cropped to the view aspect.
            val viewAspect = viewWidth.toFloat() / viewHeight
            val contentAspect = cw.toFloat() / ch
            val visWFrac = minOf(1f, viewAspect / contentAspect)
            val visHFrac = minOf(1f, contentAspect / viewAspect)
            // The view spans exactly the visible rect, so overlay fractions of
            // the view are fractions of the visible rect: a centered box sized
            // (wf * visW, hf * visH) in content-fraction space.
            val wf = region?.widthFraction ?: 1f
            val hf = region?.heightFraction ?: 1f
            val bw = (wf * visWFrac).coerceIn(0f, 1f)
            val bh = (hf * visHFrac).coerceIn(0f, 1f)
            val leftD = (cw * (1 - bw) / 2)
            val topD = (ch * (1 - bh) / 2)
            val corners = arrayOf(
                leftD to topD,
                leftD + cw * bw to topD,
                leftD + cw * bw to topD + ch * bh,
                leftD to topD + ch * bh,
            )
            var minX = Float.MAX_VALUE
            var minY = Float.MAX_VALUE
            var maxX = -Float.MAX_VALUE
            var maxY = -Float.MAX_VALUE
            for ((dx, dy) in corners) {
                val (bx, by) = displayToBuffer(dx, dy, imageWidth, imageHeight, rotationDegrees)
                if (bx < minX) minX = bx
                if (by < minY) minY = by
                if (bx > maxX) maxX = bx
                if (by > maxY) maxY = by
            }
            val out = Rect(
                minX.toInt().coerceIn(0, imageWidth),
                minY.toInt().coerceIn(0, imageHeight),
                maxX.toInt().coerceIn(0, imageWidth),
                maxY.toInt().coerceIn(0, imageHeight),
            )
            if (out.isEmpty) return Rect(sensor)
            out.intersect(sensor)
            return if (out.isEmpty) Rect(sensor) else out
        }

        /** Inverse of the display rotation: display px -> buffer px. */
        private fun displayToBuffer(
            dx: Float,
            dy: Float,
            w: Int,
            h: Int,
            rotationDegrees: Int,
        ): Pair<Float, Float> = when (rotationDegrees) {
            90 -> dy to h - dx
            180 -> w - dx to h - dy
            270 -> w - dy to dx
            else -> dx to dy
        }

        /**
         * Maps a centered upright-normalized viewfinder region to buffer pixels
         * within [sensorCrop]. Portrait buffers (rotation 90/270) store the
         * image transposed, so the fractions swap axes; centering is preserved
         * either way, which is why the API only offers centered boxes. Null
         * region (or a region covering the sensor crop) returns the sensor crop
         * as-is — never an empty rect.
         */
        internal fun roiToBuffer(
            sensorCrop: Rect,
            imageWidth: Int,
            imageHeight: Int,
            rotationDegrees: Int,
            region: ScannerConfig.ScanRegion?,
        ): Rect {
            if (region == null) return Rect(sensorCrop)
            val centered = RectF(
                (1 - region.widthFraction) / 2,
                (1 - region.heightFraction) / 2,
                (1 + region.widthFraction) / 2,
                (1 + region.heightFraction) / 2,
            )
            val (fw, fh) = if (rotationDegrees == 90 || rotationDegrees == 270) {
                centered.height() to centered.width()
            } else {
                centered.width() to centered.height()
            }
            if (fw >= 1f && fh >= 1f) return Rect(sensorCrop)
            val cw = (sensorCrop.width() * fw).toInt().coerceIn(1, sensorCrop.width())
            val ch = (sensorCrop.height() * fh).toInt().coerceIn(1, sensorCrop.height())
            if (cw >= sensorCrop.width() && ch >= sensorCrop.height()) return Rect(sensorCrop)
            val left = (sensorCrop.left + (sensorCrop.width() - cw) / 2)
                .coerceIn(0, imageWidth - 1)
            val top = (sensorCrop.top + (sensorCrop.height() - ch) / 2)
                .coerceIn(0, imageHeight - 1)
            return Rect(
                left,
                top,
                (left + cw).coerceIn(left + 1, imageWidth),
                (top + ch).coerceIn(top + 1, imageHeight),
            )
        }
    }
}
