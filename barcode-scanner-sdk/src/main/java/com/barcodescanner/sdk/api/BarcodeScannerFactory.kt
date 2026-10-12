package com.barcodescanner.sdk.api

import android.content.Context
import android.graphics.Bitmap
import android.os.SystemClock
import androidx.camera.view.PreviewView
import androidx.lifecycle.LifecycleOwner
import com.barcodescanner.sdk.camera.CameraScanManager
import com.barcodescanner.sdk.data.frame.LumaFrame
import com.barcodescanner.sdk.di.ScannerContainer
import com.barcodescanner.sdk.domain.decoder.DecodeOutcome
import com.barcodescanner.sdk.domain.model.ScanFrame
import com.barcodescanner.sdk.domain.model.Symbology
import kotlinx.coroutines.CancellationException
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
 * so a slow decode can never pile up frames.
 *
 * Two decode paths: live camera frames arrive as one natively prepared aim-box
 * [LumaFrame] and go through [ScannerContainer.liveFusion]; one-shot
 * [scanBitmap]/[scanFrame] go through [ScannerContainer.fusedDecoder].
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
    /**
     * Stability gate for MSI bar reads (see [confirmLiveBarHit]): pending key
     * plus consecutive count. Plain vars (not mutex-guarded): readers/writers
     * are the single-flight live loop plus stop/close resets — a torn update
     * costs at most one extra withheld frame or one early emit, never a wrong
     * value (emission still goes through dedup + checksum gating upstream).
     */
    @Volatile
    private var pendingKey: String? = null
    @Volatile
    private var pendingCount = 0

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
        return container.fusedDecoder.decode(frame)
            .toScanResult(frame.timestampMillis, frame.isRotatedCandidate)
    }

    private fun DecodeOutcome.toScanResult(timestampMillis: Long, rotatedCandidate: Boolean): ScanResult =
        when (val outcome = this) {
            is DecodeOutcome.Success -> {
                val best = outcome.barcodes.maxBy { it.confidence }
                ScanResult.Success(
                    barcode = best,
                    allCandidates = outcome.barcodes,
                    fromRotatedFrame = best.isUpsideDown || rotatedCandidate,
                )
            }
            is DecodeOutcome.NotFound -> ScanResult.NotFound(timestampMillis)
            is DecodeOutcome.Error -> ScanResult.Failure(
                message = outcome.cause.message ?: "decode failed",
                cause = outcome.cause,
                kind = if (outcome.recoverable) ScanResult.ErrorKind.TRANSIENT
                else ScanResult.ErrorKind.UNKNOWN,
            )
        }

    override fun startCamera(lifecycleOwner: LifecycleOwner, previewView: PreviewView) {
        // The view itself (not just its surface): the manager needs its size to
        // map the on-screen box through FILL_CENTER (what-you-see-is-what-scans).
        startCameraWith { it.start(lifecycleOwner, previewView) }
    }

    override fun startCamera(
        lifecycleOwner: LifecycleOwner,
        surfaceProvider: androidx.camera.core.Preview.SurfaceProvider,
    ) {
        startCameraWith { it.start(lifecycleOwner, surfaceProvider) }
    }

    private fun startCameraWith(start: (CameraScanManager) -> Unit) {
        synchronized(cameraLock) {
            // Checked inside the lock: close() sets `closed` then grabs the same
            // lock, so a check outside would allow starting a camera after close.
            check(!closed.get()) { "Scanner is closed" }
            stopCameraLocked()
            val manager = CameraScanManager(
                context = container.app,
                config = config,
                scope = scope,
                onFrame = { frame -> handleLiveFrame(frame) },
                onError = { t ->
                    // Async bind failure (no/in-use camera, dead lifecycle) or
                    // no native frame library on this ABI: the host's
                    // try/catch around startCamera cannot see either (bind
                    // runs after start returns), so surface it on the results
                    // flow instead of failing silently.
                    _results.tryEmit(
                        ScanResult.Failure(
                            message = "live scanning unavailable: ${t.message}",
                            cause = t,
                            kind = ScanResult.ErrorKind.TRANSIENT,
                        ),
                    )
                },
            )
            cameraManager = manager
            start(manager)
        }
    }

    override fun stopCamera() {
        synchronized(cameraLock) { stopCameraLocked() }
    }

    /**
     * Live-camera frame body; internal for tests. Decodes the one prepared
     * aim-box frame through [ScannerContainer.liveFusion] — engines read
     * [frame] in place; nothing here touches pixels. Returns true when
     * [LumaFrame.buffer] may be rewritten (the camera pool's contract): false
     * after a timed-out decode, whose ML Kit task may still be reading it.
     *
     * Never throws for post-close or decode-internal failures: stop()/close()
     * can win the race against an already-dispatched analyzer callback, and
     * faulting the shared decode scope would crash the app (uncaught
     * coroutine exception). A dropped live frame is always safe (the next
     * frame arrives ~100ms later); one-shot [scanFrame]/[scanBitmap] still
     * throw to the caller as documented.
     */
    internal suspend fun handleLiveFrame(frame: LumaFrame): Boolean {
        if (closed.get()) return true
        var reusable = false
        try {
            val live = container.liveFusion.decode(frame)
            reusable = live.bufferReusable
            // Upright by construction (native rotate): no rotated candidates.
            val result = live.outcome.toScanResult(SystemClock.elapsedRealtime(), rotatedCandidate = false)
            // Stability gate swallows the first sighting of an MSI bar value;
            // the next frame decides (see method).
            if (result is ScanResult.Success && confirmLiveBarHit(result)) emitLiveDeduped(result)
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            // Includes post-close failures of a frame dispatched before stop().
            android.util.Log.d(TAG, "live frame dropped: ${t.javaClass.simpleName}")
        }
        return reusable
    }

    private fun stopCameraLocked() {
        cameraManager?.stop()
        cameraManager = null
        // Fresh aim on next engagement: a stale pending key matching the new
        // aim's first sighting would emit without confirmation.
        pendingKey = null
        pendingCount = 0
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        synchronized(cameraLock) { stopCameraLocked() }
        scope.cancel()
        runCatching { container.close() }
    }

    /**
     * Stability gate for live MSI bar reads; internal for tests.
     *
     * A single frame's checksum-validated MSI parse can be a systematic
     * misparse (same window geometry repeating across scanlines agrees with
     * itself — observed: a confident value matching no printed label), so the
     * first sighting of a bar value is withheld and only a repeat on a later
     * frame emits. Non-matching successes reset the count; frames with no
     * result leave it (aiming jitter must not reset an honest read).
     *
     * Scope is deliberately narrow: MSI bar hits only, identified by symbology
     * + high confidence (bars emit 1.0, OCR text ≤0.7 passes straight through,
     * as do all other symbologies). One-shot scans bypass this entirely —
     * there is no second frame — so [scanFrame]/[scanBitmap] return the first
     * read as before.
     *
     * @return true when the result may emit.
     */
    internal fun confirmLiveBarHit(result: ScanResult.Success): Boolean {
        val b = result.barcode
        if (b.symbology != Symbology.MSI_PLESSEY || b.confidence <= 0.9f) return true
        val key = "${b.symbology}:${b.rawValue}"
        return if (key == pendingKey) {
            pendingCount += 1
            pendingCount >= BAR_STABILITY_FRAMES
        } else {
            pendingKey = key
            pendingCount = 1
            false
        }
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
        const val TAG = "BarcodeScanner"
        /** Prune the live dedup map once it holds more than this many keys. */
        const val DEDUP_PRUNE_THRESHOLD = 64
        /**
         * Consecutive live sightings of one MSI bar value required to emit.
         * 2 separates systematic misparses (which repeat within a frame but
         * rarely survive re-aiming) from truth, at the cost of one withheld
         * frame (~200ms) on every fresh aim. Not a config knob: it is a
         * correctness policy, and one-shot scans bypass it.
         */
        const val BAR_STABILITY_FRAMES = 2
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
