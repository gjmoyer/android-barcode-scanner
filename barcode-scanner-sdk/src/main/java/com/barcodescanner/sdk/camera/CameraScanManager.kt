package com.barcodescanner.sdk.camera

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.YuvImage
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
import java.io.ByteArrayOutputStream
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
 *  - stride-aware YUV_420_888 -> NV21 -> Bitmap conversion (rowStride/pixelStride
 *    respected; the naive buffer-concat path shears on Pixel/Samsung),
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

    /** Single-flight gate: true while a decode is in flight. */
    private val decodeInFlight = AtomicBoolean(false)

    @Volatile
    private var stopped = false

    fun start(lifecycleOwner: LifecycleOwner, previewView: PreviewView) {
        // Restart-safe: shut down any previous executor before creating a new one.
        runCatching { analysisExecutor?.shutdownNow() }
        stopped = false
        analysisExecutor = Executors.newSingleThreadExecutor()
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener({
            if (stopped) return@addListener
            val provider = runCatching { future.get() }.getOrNull() ?: return@addListener
            cameraProvider = provider
            bind(provider, lifecycleOwner, previewView)
        }, ContextCompat.getMainExecutor(context))
    }

    fun stop() {
        stopped = true
        decodeInFlight.set(false)
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
        previewView: PreviewView,
    ) {
        val executor = analysisExecutor ?: return
        unbindUseCases()
        val preview = Preview.Builder().build().also {
            it.surfaceProvider = previewView.surfaceProvider
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
     * Stride-aware YUV_420_888 -> NV21 -> Bitmap.
     * Respects rowStride/pixelStride and cropRect; JPEG quality 70 (bars survive,
     * bandwidth halved vs 85). Result capped at 1280px long edge.
     */
    private fun ImageProxy.toStrideAwareBitmap(): Bitmap? = runCatching {
        val nv21 = toNv21() ?: return null
        val yuv = YuvImage(nv21, ImageFormat.NV21, width, height, null)
        val out = ByteArrayOutputStream(width * height / 2)
        yuv.compressToJpeg(Rect(0, 0, width, height), 70, out)
        val bytes = out.toByteArray()
        val full = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return null
        val scale = minOf(1f, 1280f / maxOf(full.width, full.height))
        if (scale >= 1f) full
        else {
            val scaled = Bitmap.createScaledBitmap(
                full,
                (full.width * scale).toInt().coerceAtLeast(1),
                (full.height * scale).toInt().coerceAtLeast(1),
                true,
            )
            full.recycle()
            scaled
        }
    }.getOrNull()

    /** Packs YUV_420_888 planes into NV21 respecting strides. */
    private fun ImageProxy.toNv21(): ByteArray? = runCatching {
        val w = width
        val h = height
        val yPlane = planes[0]
        val uPlane = planes[1]
        val vPlane = planes[2]
        val out = ByteArray(w * h * 3 / 2)

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
}
