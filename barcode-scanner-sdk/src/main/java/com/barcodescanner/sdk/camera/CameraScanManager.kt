package com.barcodescanner.sdk.camera

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.RectF
import android.util.Size
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import com.barcodescanner.sdk.data.frame.LumaBufferPool
import com.barcodescanner.sdk.data.frame.LumaFrame
import com.barcodescanner.sdk.data.frame.NativeFrame
import com.barcodescanner.sdk.domain.model.ScannerConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * CameraX live-scan manager (internal; host apps use startCamera on the facade).
 *
 * Each camera frame is touched at most ONCE, and only natively:
 *  - STRATEGY_KEEP_ONLY_LATEST + a single-flight gate checked BEFORE any pixel
 *    work: a frame arriving while a decode runs is closed untouched (dropped,
 *    never queued or partially processed),
 *  - an accepted frame gets one [NativeFrame.prepare] pass straight from the
 *    Y plane (rowStride respected, no Kotlin pixel loop, no Bitmap): crop to
 *    the aim box, contrast stretch, rotate upright — into a pooled NV21
 *    buffer every engine then reads in place ([LumaFrame]). The ImageProxy is
 *    closed right after that pass, so CameraX can deliver the next frame while
 *    the decode runs,
 *  - only the aim box ([ScannerConfig.scanRegion], what-you-see-is-what-scans
 *    via [viewBoxToBuffer]) is ever read; chroma planes are never read,
 *  - preview and analysis both 16:9 (same field of view — what makes the
 *    [viewBoxToBuffer] mapping exact — and the aspect closest to phone
 *    displays, so few analysis pixels fall off-screen); analysis ~1920x1080
 *    (lower-then-higher fallback stays inside the sizes every device streams
 *    next to a preview). The box is a small crop of it, so resolution buys
 *    bar width, not decode cost,
 *  - structured [scope] (no GlobalScope): stop()/close() cancels in-flight work.
 *
 * Unsupported ABIs (x86 emulators: ARM-only native packaging) get a preview
 * without analysis and one [onError] report instead of a silent dead scanner.
 */
internal class CameraScanManager(
    private val context: Context,
    private val config: ScannerConfig,
    private val scope: CoroutineScope,
    /**
     * Decodes one prepared frame. Returns true when [LumaFrame.buffer] may be
     * rewritten afterwards; false when an engine may still be reading it (a
     * timed-out ML Kit task), in which case the buffer is dropped from the pool.
     */
    private val onFrame: suspend (LumaFrame) -> Boolean,
    /**
     * Called (on the main thread) when camera binding fails — e.g. no back
     * camera, camera in use, or the lifecycle is already destroyed. Without
     * this, [ProcessCameraProvider.bindToLifecycle] throws asynchronously
     * inside the provider-future listener (after [start] returned), which is
     * an uncaught main-thread exception and crashes the app.
     */
    private val onError: (Throwable) -> Unit = {},
) {
    private var cameraProvider: ProcessCameraProvider? = null
    private var previewUseCase: Preview? = null
    private var analysisUseCase: ImageAnalysis? = null
    private var analysisExecutor: ExecutorService? = null
    /**
     * Preview view for what-you-see-is-what-scans cropping (null for the
     * headless SurfaceProvider entry point, where the legacy sensor-centered
     * ROI applies). Only width/height are ever read. Volatile: written on the
     * main thread (start/stop), read on the analyzer executor — a stale read
     * only mis-crops one frame, but there is no reason to allow a torn one.
     */
    @Volatile
    private var previewView: PreviewView? = null

    /** Single-flight gate: true while a frame is being prepared or decoded. */
    private val decodeInFlight = AtomicBoolean(false)

    /** Prepared-frame buffers; an in-use (timed-out) buffer is never released. */
    private val pool = LumaBufferPool()

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
            val provider = runCatching { future.get() }.getOrNull()
            if (provider == null) {
                val t = IllegalStateException("camera provider unavailable")
                android.util.Log.w(TAG, "camera provider unavailable", t)
                onError(t)
                return@addListener
            }
            cameraProvider = provider
            bind(provider, lifecycleOwner, surfaceProvider)
        }, ContextCompat.getMainExecutor(context))
    }

    fun stop() {
        stopped = true
        decodeInFlight.set(false)
        pool.clear()
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
        val preview = Preview.Builder()
            .setResolutionSelector(
                ResolutionSelector.Builder().setAspectRatioStrategy(STREAM_ASPECT).build(),
            )
            .build()
            .also { it.surfaceProvider = surfaceProvider }
        // No native frame library (x86 emulator): preview only. Binding an
        // analysis stream nothing can read would just burn camera bandwidth.
        val analysis = if (NativeFrame.available) buildAnalysis(executor) else null
        val useCases = listOfNotNull(preview, analysis).toTypedArray()
        previewUseCase = preview
        analysisUseCase = analysis
        try {
            provider.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, *useCases)
        } catch (t: Throwable) {
            // Binding throws for missing/in-use cameras, destroyed lifecycles,
            // or unbindable use cases — and it throws HERE, asynchronously on
            // the main executor after start() returned, so the host's
            // try/catch around startCamera cannot see it. Clean up the
            // half-bound use cases and report instead of crashing.
            runCatching { provider.unbind(*useCases) }
            previewUseCase = null
            analysisUseCase = null
            android.util.Log.w(TAG, "camera bind failed", t)
            onError(t)
            return
        }
        if (analysis == null) {
            val t = UnsupportedOperationException(
                "live decoding needs libmsi_decoder, not packaged for ${android.os.Build.SUPPORTED_ABIS.joinToString()}",
            )
            android.util.Log.w(TAG, "preview only: ${t.message}")
            onError(t)
        }
    }

    private fun buildAnalysis(executor: ExecutorService): ImageAnalysis {
        val analysis = ImageAnalysis.Builder()
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
            .setResolutionSelector(
                ResolutionSelector.Builder()
                    .setAspectRatioStrategy(STREAM_ASPECT)
                    .setResolutionStrategy(
                        ResolutionStrategy(
                            Size(ANALYSIS_LONG_EDGE, ANALYSIS_SHORT_EDGE),
                            ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER,
                        ),
                    ).build(),
            )
            .build()
        analysis.setAnalyzer(executor) { proxy ->
            // Gate BEFORE any pixel work: a frame arriving mid-decode is closed
            // untouched, so no frame is ever processed twice or half-processed.
            if (stopped || !decodeInFlight.compareAndSet(false, true)) {
                proxy.close()
                return@setAnalyzer
            }
            val frame = try {
                prepare(proxy)
            } catch (t: Throwable) {
                android.util.Log.w(TAG, "frame prepare failed", t)
                null
            } finally {
                // prepare() copied the aim box out: the camera buffer goes back
                // now, not after the decode.
                proxy.close()
            }
            if (frame == null) {
                decodeInFlight.set(false)
                return@setAnalyzer
            }
            scope.launch {
                var reusable = false
                try {
                    reusable = stopped || onFrame(frame)
                } finally {
                    // Return to the pool BEFORE opening the gate so the next
                    // frame finds it; an in-use (or cancelled) buffer is dropped.
                    if (reusable) pool.release(frame.buffer)
                    decodeInFlight.set(false)
                }
            }
        }
        return analysis
    }

    /**
     * The one pixel pass of a live frame: aim-box geometry in Kotlin (a few
     * float ops), then [NativeFrame.prepare] reads the box straight out of the
     * Y plane into a pooled buffer. Null when the frame is unusable.
     */
    private fun prepare(proxy: ImageProxy): LumaFrame? {
        if (proxy.format != ImageFormat.YUV_420_888) return null
        val luma = proxy.planes.firstOrNull() ?: return null
        // YUV_420_888 guarantees pixelStride 1 for Y; the native pass relies on it.
        if (luma.pixelStride != 1) return null
        val rotation = normalizeRotation(proxy.imageInfo.rotationDegrees)
        val sensor = clampToBuffer(proxy.cropRect, proxy.width, proxy.height)
        val view = previewView?.let { v ->
            // Plain int getters; transient zeros fall back to the sensor ROI.
            val vw = runCatching { v.width }.getOrDefault(0)
            val vh = runCatching { v.height }.getOrDefault(0)
            if (vw > 0 && vh > 0) vw to vh else null
        }
        val box = if (view == null) {
            roiToBuffer(sensor, proxy.width, proxy.height, rotation, config.scanRegion)
        } else {
            viewBoxToBuffer(sensor, proxy.width, proxy.height, rotation, view.first, view.second, config.scanRegion)
        }
        val crop = evenCrop(box) ?: return null
        val width = NativeFrame.uprightWidth(crop.width(), crop.height(), rotation)
        val height = NativeFrame.uprightHeight(crop.width(), crop.height(), rotation)
        val buffer = pool.take(width, height)
        val ok = NativeFrame.prepare(
            luma.buffer, proxy.width, proxy.height, luma.rowStride,
            crop.left, crop.top, crop.right, crop.bottom,
            rotation, true, buffer,
        )
        if (!ok) {
            pool.release(buffer)
            return null
        }
        return LumaFrame(buffer, width, height, maxOrientations = LIVE_ORIENTATIONS, scanTopFraction = 0f)
    }

    companion object {
        /** Logcat tag; filter with `adb logcat -s CameraScanManager`. */
        const val TAG = "CameraScanManager"
        /**
         * Analysis target (~1080p). Lower-then-higher fallback: never above
         * the preview-class sizes every device streams next to a preview.
         */
        const val ANALYSIS_LONG_EDGE = 1920
        const val ANALYSIS_SHORT_EDGE = 1080
        /** Shared by preview and analysis: one field of view for the box mapping. */
        private val STREAM_ASPECT = AspectRatioStrategy.RATIO_16_9_FALLBACK_AUTO_STRATEGY
        /**
         * MSI orientations tried per live frame: upright and upside-down
         * (sideways tags cannot fit the wide aim box; stills try all four).
         * The native frame decoder arbitrates a read against the opposite
         * direction, so reversed bars cannot yield a false read. Doubles the
         * decode cost of frames without a read.
         */
        const val LIVE_ORIENTATIONS = 2
        /** Smallest usable crop edge; anything below is degenerate geometry. */
        const val MIN_CROP_PX = 16

        /** ImageInfo rotation normalized to 0/90/180/270 (anything else = 0). */
        internal fun normalizeRotation(degrees: Int): Int = when (degrees) {
            90, 180, 270 -> degrees
            else -> 0
        }

        /**
         * HAL crop clamped into the [imageWidth] x [imageHeight] plane, never
         * empty (some HALs report a crop larger than the buffer on rotation).
         */
        internal fun clampToBuffer(raw: Rect, imageWidth: Int, imageHeight: Int): Rect {
            val left = raw.left.coerceIn(0, imageWidth - 1)
            val top = raw.top.coerceIn(0, imageHeight - 1)
            return Rect(
                left,
                top,
                raw.right.coerceIn(left + 1, imageWidth),
                raw.bottom.coerceIn(top + 1, imageHeight),
            )
        }

        /**
         * [box] trimmed by at most one pixel per axis to even dimensions (NV21
         * output; the box is centered, so its far-edge pixel is immaterial).
         * Stays inside [box], hence inside the plane. Null below [MIN_CROP_PX].
         */
        internal fun evenCrop(box: Rect): Rect? {
            val w = box.width() and 1.inv()
            val h = box.height() and 1.inv()
            if (w < MIN_CROP_PX || h < MIN_CROP_PX) return null
            return Rect(box.left, box.top, box.left + w, box.top + h)
        }

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
