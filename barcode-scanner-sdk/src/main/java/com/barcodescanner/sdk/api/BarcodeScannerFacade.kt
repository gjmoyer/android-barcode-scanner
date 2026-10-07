package com.barcodescanner.sdk.api

import android.graphics.Bitmap
import com.barcodescanner.sdk.domain.model.ScanFrame
import kotlinx.coroutines.flow.Flow

/**
 * Public entry point. Host apps only touch this interface + [ScannerConfig] + [ScanResult].
 *
 * Usage (see sample-app):
 * ```
 * val scanner = BarcodeScannerFactory.create(context, ScannerConfig.robust())
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
    /** Hot flow of results for live camera mode. */
    val results: Flow<ScanResult>

    /** One-shot decode of a bitmap (gallery import, tests). Throws after [close]. */
    suspend fun scanBitmap(bitmap: Bitmap, rotationDegrees: Int = 0): ScanResult

    /** One-shot decode of an already-wrapped frame (advanced use). Throws after [close]. */
    suspend fun scanFrame(frame: ScanFrame): ScanResult

    /** Live CameraX mode; binds to [lifecycleOwner]. Throws after [close]. */
    fun startCamera(
        lifecycleOwner: androidx.lifecycle.LifecycleOwner,
        previewView: androidx.camera.view.PreviewView,
    )

    fun stopCamera()

    /** Current config (immutable snapshot). */
    val config: ScannerConfig

    override fun close()
}
