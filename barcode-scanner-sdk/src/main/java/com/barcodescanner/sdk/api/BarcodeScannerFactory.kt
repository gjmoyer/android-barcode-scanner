package com.barcodescanner.sdk.api

import android.content.Context
import android.graphics.Bitmap
import android.os.SystemClock
import androidx.camera.view.PreviewView
import androidx.lifecycle.LifecycleOwner
import com.barcodescanner.sdk.camera.CameraScanManager
import com.barcodescanner.sdk.di.ScannerContainer
import com.barcodescanner.sdk.domain.decoder.DecodeOutcome
import com.barcodescanner.sdk.domain.model.ScanFrame
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Default [BarcodeScannerFacade] implementation.
 *
 * Threading: a private [SupervisorJob] + [Dispatchers.Default] scope owns all
 * decode work. Camera frames are single-flight conflated in [CameraScanManager]
 * so a slow MSI fallback can never pile up frames.
 *
 * Emission contract (fixed per review):
 *  - [scanBitmap]/[scanFrame] are pure one-shots: they NEVER touch [results].
 *    A suppressed duplicate is still returned to the caller (not lied to as NotFound).
 *  - only the live camera path emits to [results], with per-key dedup:
 *    identical (value, symbology) within [ScannerConfig.duplicateSuppressionMillis]
 *    is swallowed there.
 */
internal class DefaultBarcodeScanner(
    private val container: ScannerContainer,
    override val config: ScannerConfig,
) : BarcodeScannerFacade {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val _results = MutableSharedFlow<ScanResult>(
        extraBufferCapacity = 8,
        // Slow collectors drop oldest; emitting must never suspend the decode path.
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    override val results: Flow<ScanResult> = _results.asSharedFlow()

    private var cameraManager: CameraScanManager? = null
    private val cameraLock = Any()
    private val closed = AtomicBoolean(false)

    /** Live-path dedup: key -> last emitted elapsed-realtime. */
    private val dedup = ConcurrentHashMap<String, Long>()
    private val emitMutex = Mutex()

    override suspend fun scanBitmap(bitmap: Bitmap, rotationDegrees: Int): ScanResult =
        scanFrame(
            ScanFrame(
                bitmap = bitmap,
                rotationDegrees = rotationDegrees,
                timestampMillis = SystemClock.elapsedRealtime(),
            ),
        )

    override suspend fun scanFrame(frame: ScanFrame): ScanResult {
        check(!closed.get()) { "Scanner is closed" }
        return when (val outcome = container.fusedDecoder.decode(frame)) {
            is DecodeOutcome.Success -> {
                val best = outcome.barcodes.maxBy { it.confidence }
                ScanResult.Success(
                    barcode = best,
                    allCandidates = outcome.barcodes,
                    fromRotatedFrame = best.isUpsideDown || frame.isRotatedCandidate,
                )
            }
            is DecodeOutcome.NotFound -> ScanResult.NotFound(frame.timestampMillis)
            is DecodeOutcome.Error -> ScanResult.Failure(
                message = outcome.cause.message ?: "decode failed",
                cause = outcome.cause,
                kind = if (outcome.recoverable) ScanResult.ErrorKind.TRANSIENT
                else ScanResult.ErrorKind.UNKNOWN,
            )
        }
    }

    override fun startCamera(lifecycleOwner: LifecycleOwner, previewView: PreviewView) {
        startCamera(lifecycleOwner, previewView.surfaceProvider)
    }

    override fun startCamera(
        lifecycleOwner: LifecycleOwner,
        surfaceProvider: androidx.camera.core.Preview.SurfaceProvider,
    ) {
        synchronized(cameraLock) {
            // Checked inside the lock: close() sets `closed` then grabs the same
            // lock, so a check outside would allow starting a camera after close.
            check(!closed.get()) { "Scanner is closed" }
            stopCameraLocked()
            val manager = CameraScanManager(
                context = container.app,
                config = config,
                scope = scope,
                onFrame = { frame ->
                    val result = scanFrame(frame)
                    if (result is ScanResult.Success) {
                        emitLiveDeduped(result)
                    }
                },
            )
            cameraManager = manager
            manager.start(lifecycleOwner, surfaceProvider)
        }
    }

    override fun stopCamera() {
        synchronized(cameraLock) { stopCameraLocked() }
    }

    private fun stopCameraLocked() {
        cameraManager?.stop()
        cameraManager = null
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        synchronized(cameraLock) { stopCameraLocked() }
        scope.cancel()
        runCatching { container.close() }
    }

    /** Live-path emission with per-key dedup; internal for tests. */
    internal suspend fun emitLiveDeduped(result: ScanResult.Success) {
        val key = "${result.barcode.symbology}:${result.barcode.rawValue}"
        val now = SystemClock.elapsedRealtime()
        emitMutex.withLock {
            // Prune stale keys so a long session scanning many unique codes does
            // not grow the map without bound.
            if (dedup.size > DEDUP_PRUNE_THRESHOLD) {
                val cutoff = now - config.duplicateSuppressionMillis
                dedup.entries.removeAll { it.value < cutoff }
            }
            val last = dedup[key]
            if (last != null && now - last < config.duplicateSuppressionMillis) return
            dedup[key] = now
        }
        _results.emit(result)
    }

    private companion object {
        /** Prune the live dedup map once it holds more than this many keys. */
        const val DEDUP_PRUNE_THRESHOLD = 64
    }
}

/**
 * Public factory — the ONLY way host apps construct the scanner.
 * Keeps the constructor surface minimal and lets us change DI internals freely.
 */
object BarcodeScannerFactory {
    fun create(
        context: Context,
        config: ScannerConfig = ScannerConfig.default(),
    ): BarcodeScannerFacade {
        val container = ScannerContainer(context, config)
        return DefaultBarcodeScanner(container, config)
    }

    /** Successes only (convenience for simple hosts). */
    fun BarcodeScannerFacade.successes(): Flow<ScanResult.Success> =
        results.filterIsInstance<ScanResult.Success>()
}
