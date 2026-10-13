package com.barcodescanner.sdk.api

import android.graphics.Bitmap
import com.barcodescanner.sdk.domain.model.ScanFrame
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/**
 * Public entry point. Host apps only touch this interface + [ScannerConfig] + [ScanResult].
 *
 * Usage (see sample-app):
 * ```
 * val scanner = BarcodeScannerFactory.create(context, ScannerConfig.default())
 * scanner.results.collect { ... }       // Flow<ScanResult>
 * scanner.scanBitmap(frameBitmap)       // one-shot
 * scanner.startCamera(lifecycleOwner, previewView) // live
 * scanner.close()
 * ```
 *
 * Implementations must be thread-safe. [close] is idempotent; decode/camera
 * calls made after [close] throw [IllegalStateException] (programming error).
 */
interface BarcodeScannerFacade : AutoCloseable {
    /**
     * Hot flow of results for live camera mode. Buffer holds 8 with
     * DROP_OLDEST: a slow collector misses scans rather than backpressuring
     * the decode path — collect promptly (e.g. `repeatOnLifecycle`) in live UI.
     */
    val results: Flow<ScanResult>

    /** One-shot decode of a bitmap (gallery import, tests). Throws after [close]. */
    suspend fun scanBitmap(bitmap: Bitmap, rotationDegrees: Int = 0): ScanResult

    /** One-shot decode of an already-wrapped frame (advanced use). Throws after [close]. */
    suspend fun scanFrame(frame: ScanFrame): ScanResult

    /**
     * Live CameraX mode; binds to [lifecycleOwner]. Throws after [close].
     * What-you-see-is-what-scans: the [ScannerConfig.scanRegion] box is mapped
     * through the view's FILL_CENTER crop, so an overlay drawn with the same
     * fractions on [previewView] marks exactly the decoded pixels.
     */
    fun startCamera(
        lifecycleOwner: androidx.lifecycle.LifecycleOwner,
        previewView: androidx.camera.view.PreviewView,
    )

    /**
     * Headless / Compose live mode: binds preview to any [Preview.SurfaceProvider]
     * (e.g. `PreviewView.surfaceProvider`, Compose `AndroidView` provider, or a
     * custom surface). Keeps the CameraX *view* out of the scanner API, but with
     * no view size the [ScannerConfig.scanRegion] fractions apply to the full
     * sensor crop, not to the (usually narrower) displayed area — an overlay
     * would understate the decoded region. Use the [PreviewView] overload when
     * the on-screen box must be exact. Throws after [close].
     */
    fun startCamera(
        lifecycleOwner: androidx.lifecycle.LifecycleOwner,
        surfaceProvider: androidx.camera.core.Preview.SurfaceProvider,
    )

    fun stopCamera()

    /**
     * Live camera session state, updated on the main thread. [CameraStatus.Idle]
     * before [startCamera] and after [stopCamera]/[close]. On
     * [CameraStatus.Failed] (then also reported once on [results] as a
     * [ScanResult.Failure], unless the camera was already stopped), stop the
     * camera and offer a retry.
     */
    val cameraStatus: StateFlow<CameraStatus>

    /** Torch state reported by the camera; false whenever no camera session runs. */
    val torchEnabled: StateFlow<Boolean>

    /**
     * Requests a live-camera torch state change. [onResult] is called on the
     * main thread with false when the camera is not running, has no flash, or
     * CameraX rejects the request. Observe [torchEnabled] for the actual state.
     */
    fun setTorchEnabled(enabled: Boolean, onResult: (Boolean) -> Unit)

    /**
     * Pinch zoom for the live camera: multiplies the current zoom ratio by
     * [scaleFactor] (a gesture's incremental scale), clamped to the camera's
     * supported range. No-op when no camera is running; never throws.
     */
    fun scaleZoom(scaleFactor: Float)

    /**
     * Tap to focus for the live camera: one-shot autofocus and exposure at
     * [point]. Create the point on the main thread with the preview's
     * `PreviewView.meteringPointFactory` so view coordinates map to the
     * sensor. Continuous autofocus resumes after a few seconds. No-op when no
     * camera is running; never throws.
     */
    fun focusAt(point: androidx.camera.core.MeteringPoint)

    /** Current config (immutable snapshot). */
    val config: ScannerConfig

    override fun close()
}
