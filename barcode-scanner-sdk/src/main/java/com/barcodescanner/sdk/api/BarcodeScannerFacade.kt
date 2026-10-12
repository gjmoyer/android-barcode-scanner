package com.barcodescanner.sdk.api

import android.graphics.Bitmap
import com.barcodescanner.sdk.domain.model.ScanFrame
import kotlinx.coroutines.flow.Flow

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

    /** Current config (immutable snapshot). */
    val config: ScannerConfig

    override fun close()
}
