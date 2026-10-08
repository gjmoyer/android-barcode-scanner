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
import com.barcodescanner.sdk.domain.model.Symbology
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

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
    /**
     * Elapsed-realtime of the last live [ScanResult.Success] (any symbology,
     * even dedup-suppressed). Drives the fallback-sweep policy: fresh frames
     * skip the expensive sweep and let the next frame try; only aiming that
     * persists with no success gets thorough sweeps. Starts 0 so the first
     * frames after launch sweep exactly like one-shot scans.
     */
    private val lastSuccessAt = AtomicLong(0)

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
    /** Debug dump fires once per scanner instance (see [ScannerConfig.debugOcrFrameDump]). */
    private val ocrDumpDone = AtomicBoolean(false)

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
                    val now = SystemClock.elapsedRealtime()
                    val stale = now - lastSuccessAt.get() > STALE_SWEEP_AFTER_MILLIS
                    val result = scanFrame(frame.copy(allowFallbackSweep = stale))
                    if (result is ScanResult.Success) {
                        lastSuccessAt.set(now)
                        // Stability gate swallows the first sighting of an MSI
                        // bar value; the next frame decides (see method).
                        if (confirmLiveBarHit(result)) {
                            emitLiveDeduped(result)
                            maybeDumpOcrFrame(frame, result)
                        }
                    }
                },
                onError = { t ->
                    // Async bind failure (no/in-use camera, dead lifecycle):
                    // the host's try/catch around startCamera cannot see it
                    // (bind runs after start returns), so surface it on the
                    // results flow instead of failing silently.
                    _results.tryEmit(
                        ScanResult.Failure(
                            message = "camera unavailable: ${t.message}",
                            cause = t,
                            kind = ScanResult.ErrorKind.TRANSIENT,
                        ),
                    )
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

    /**
     * Debug dump of the first live `MsiOcr` frame (see
     * [ScannerConfig.debugOcrFrameDump]). Runs synchronously through bitmap
     * copy (the camera manager recycles [frame.bitmap] after `onFrame`
     * returns, so the copy must happen here); PNG encode happens on IO.
     * Capped to [OCR_DUMP_MAX_FILES] files in the app-private `ocr-debug/`
     * dir — pull with
     * `adb pull /sdcard/Android/data/<pkg>/files/ocr-debug`.
     */
    private fun maybeDumpOcrFrame(frame: ScanFrame, result: ScanResult.Success) {
        if (!config.debugOcrFrameDump) return
        if (result.barcode.engineName != OCR_DUMP_ENGINE) return
        if (!ocrDumpDone.compareAndSet(false, true)) return
        val snapshot = runCatching {
            frame.bitmap.copy(frame.bitmap.config ?: Bitmap.Config.ARGB_8888, false)
        }.getOrNull() ?: return
        scope.launch(Dispatchers.IO) {
            try {
                val dir = File(container.app.getExternalFilesDir(null), OCR_DUMP_DIR)
                dir.mkdirs()
                dir.listFiles()
                    ?.sortedByDescending { it.name }
                    ?.drop(OCR_DUMP_MAX_FILES - 1)
                    ?.forEach { runCatching { it.delete() } }
                val sku = result.barcode.rawValue.filter { it.isLetterOrDigit() }
                val out = File(dir, "ocr_${System.currentTimeMillis()}_${sku}.png")
                out.outputStream().use { snapshot.compress(Bitmap.CompressFormat.PNG, 100, it) }
                android.util.Log.d("OcrDump", "wrote ${out.absolutePath}")
            } catch (t: Throwable) {
                android.util.Log.w("OcrDump", "dump failed", t)
            } finally {
                runCatching { snapshot.recycle() }
            }
        }
    }

    private companion object {
        /** Prune the live dedup map once it holds more than this many keys. */
        const val DEDUP_PRUNE_THRESHOLD = 64
        /**
         * Live frames newer than this after the last success skip the fallback
         * sweep (ML Kit isolation + native second opinion only). Older aiming
         * re-enables thorough sweeps so un-isolatable labels still resolve.
         * Not a config knob: it is a frame-scheduling policy, and one-shot
         * scans always sweep (see ScanFrame.allowFallbackSweep).
         */
        const val STALE_SWEEP_AFTER_MILLIS = 2_000L
        /**
         * Consecutive live sightings of one MSI bar value required to emit.
         * 2 separates systematic misparses (which repeat within a frame but
         * rarely survive re-aiming) from truth, at the cost of one withheld
         * frame (~200ms) on every fresh aim. Not a config knob: it is a
         * correctness policy, and one-shot scans bypass it.
         */
        const val BAR_STABILITY_FRAMES = 2
        const val OCR_DUMP_ENGINE = "MsiOcr"
        const val OCR_DUMP_DIR = "ocr-debug"
        const val OCR_DUMP_MAX_FILES = 3
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
