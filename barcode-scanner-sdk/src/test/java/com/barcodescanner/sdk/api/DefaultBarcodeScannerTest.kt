package com.barcodescanner.sdk.api

import android.graphics.Bitmap
import android.os.Looper
import androidx.camera.core.SurfaceOrientedMeteringPointFactory
import com.barcodescanner.sdk.data.frame.LumaFrame
import com.barcodescanner.sdk.data.frame.NativeFrame
import com.barcodescanner.sdk.di.ScannerContainer
import com.barcodescanner.sdk.domain.model.DecodedBarcode
import com.barcodescanner.sdk.domain.model.ScannerConfig
import com.barcodescanner.sdk.domain.model.Symbology
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DefaultBarcodeScannerTest {

    private fun success(value: String) = ScanResult.Success(
        DecodedBarcode(
            rawValue = value,
            symbology = Symbology.QR_CODE,
            confidence = 0.95f,
            engineName = "Fake",
        ),
    )

    private fun msiBar(value: String, engine: String = "MsiPlessey") = ScanResult.Success(
        DecodedBarcode(
            rawValue = value,
            symbology = Symbology.MSI_PLESSEY,
            confidence = 1.0f,
            engineName = engine,
        ),
    )

    private fun msiOcr(value: String) = ScanResult.Success(
        DecodedBarcode(
            rawValue = value,
            symbology = Symbology.MSI_PLESSEY,
            confidence = 0.5f,
            engineName = "MsiOcr",
        ),
    )

    private fun scanner(suppressionMillis: Long): DefaultBarcodeScanner {
        val config = ScannerConfig.Builder()
            .duplicateSuppressionMillis(suppressionMillis)
            .build()
        return DefaultBarcodeScanner(
            ScannerContainer(RuntimeEnvironment.getApplication(), config),
            config,
        )
    }

    @Test
    fun scanBitmap_withoutNativeLib_isFailureNotCrash() = runBlocking {
        // The still path is native-only; on an ABI without the library (here:
        // the JVM) a one-shot reports Failure instead of throwing.
        val scanner = scanner(suppressionMillis = 0)
        val bmp = Bitmap.createBitmap(64, 32, Bitmap.Config.ARGB_8888)
        try {
            val result = scanner.scanBitmap(bmp)
            assertTrue("expected Failure, got $result", result is ScanResult.Failure)
            assertTrue((result as ScanResult.Failure).message.contains("native"))
        } finally {
            bmp.recycle()
            scanner.close()
        }
    }

    @Test
    fun dedup_suppressesIdenticalResultsWithinWindow() = runBlocking {
        val scanner = scanner(suppressionMillis = 10_000)
        try {
            val received = mutableListOf<ScanResult>()
            val job = launch(Dispatchers.Unconfined) { scanner.results.collect { received += it } }
            scanner.emitLiveDeduped(success("A"))
            scanner.emitLiveDeduped(success("A"))
            scanner.emitLiveDeduped(success("B"))
            yield()
            assertEquals(
                listOf("A", "B"),
                received.map { (it as ScanResult.Success).barcode.rawValue },
            )
            job.cancel()
        } finally {
            scanner.close()
        }
    }

    @Test
    fun dedup_zeroWindow_emitsEveryResult() = runBlocking {
        val scanner = scanner(suppressionMillis = 0)
        try {
            val received = mutableListOf<ScanResult>()
            val job = launch(Dispatchers.Unconfined) { scanner.results.collect { received += it } }
            scanner.emitLiveDeduped(success("A"))
            scanner.emitLiveDeduped(success("A"))
            yield()
            assertEquals(2, received.size)
            job.cancel()
        } finally {
            scanner.close()
        }
    }

    @Test
    fun stability_withholdsFirstMsiBarSighting() {
        // Systematic misparses agree within a frame: only a repeat emits.
        val scanner = scanner(suppressionMillis = 0)
        try {
            assertEquals(false, scanner.confirmLiveBarHit(msiBar("86774681")))
            assertEquals(true, scanner.confirmLiveBarHit(msiBar("86774681")))
        } finally {
            scanner.close()
        }
    }

    @Test
    fun stability_resetsOnDifferentValue() {
        val scanner = scanner(suppressionMillis = 0)
        try {
            assertEquals(false, scanner.confirmLiveBarHit(msiBar("A")))
            assertEquals(false, scanner.confirmLiveBarHit(msiBar("B")))
            assertEquals(true, scanner.confirmLiveBarHit(msiBar("B")))
        } finally {
            scanner.close()
        }
    }

    @Test
    fun stability_passesThroughOcrAndOtherSymbologies() {        val scanner = scanner(suppressionMillis = 0)
        try {
            // OCR text (0.5) and non-MSI symbologies emit on first sighting.
            assertEquals(true, scanner.confirmLiveBarHit(msiOcr("0168971")))
            assertEquals(true, scanner.confirmLiveBarHit(success("QR")))
            // Both ROI and full-frame MSI lanes are bar reads: gated alike.
            assertEquals(false, scanner.confirmLiveBarHit(msiBar("X", engine = "MsiRoi")))
            assertEquals(true, scanner.confirmLiveBarHit(msiBar("X", engine = "MsiRoi")))
        } finally {
            scanner.close()
        }
    }

    /** A prepared live frame: all-white luma, neutral chroma (nothing to decode). */
    private fun blankFrame(): LumaFrame {
        val buffer = NativeFrame.allocate(64, 32)
        for (i in 0 until 64 * 32) buffer.put(i, 0xFF.toByte())
        return LumaFrame(buffer, 64, 32)
    }

    @Test
    fun liveFrame_afterClose_doesNotThrow_andBufferStaysReusable() = runBlocking {
        // Regression: an analyzer callback dispatched before stop()/close()
        // used to throw IllegalStateException out of the shared decode scope
        // (uncaught coroutine exception = app crash). Live frames are
        // droppable; only one-shots throw. No engine touched the buffer.
        val scanner = scanner(suppressionMillis = 0)
        scanner.close()
        assertTrue(scanner.handleLiveFrame(blankFrame()))
    }

    @Test
    fun liveFrame_blankFrame_completesWithoutEmission() = runBlocking {
        // Full live path on the JVM (ML Kit/zxing/native all miss without
        // device libraries): must complete and emit nothing, not throw.
        val scanner = scanner(suppressionMillis = 0)
        val received = mutableListOf<ScanResult>()
        val job = launch(Dispatchers.Unconfined) { scanner.results.collect { received += it } }
        try {
            scanner.handleLiveFrame(blankFrame())
            yield()
            assertEquals(emptyList<ScanResult>(), received)
            job.cancel()
        } finally {
            scanner.close()
        }
    }

    @Test
    fun liveSuccess_fromStoppedSession_isDroppedWithoutPrimingState() = runBlocking {
        // Retry restarts the camera while an old decode may still be running:
        // its late result must not finish the new session or seed its state.
        val scanner = scanner(suppressionMillis = 10_000)
        try {
            val received = mutableListOf<ScanResult>()
            val job = launch(Dispatchers.Unconfined) { scanner.results.collect { received += it } }
            val oldSession = scanner.currentCameraSession()
            scanner.stopCamera()
            scanner.acceptLiveSuccess(oldSession, success("A"))
            scanner.acceptLiveSuccess(oldSession, msiBar("86774681"))
            yield()
            assertTrue("a stopped session must not publish", received.isEmpty())
            // Stability not primed: the first MSI bar sighting is still withheld.
            assertEquals(false, scanner.confirmLiveBarHit(msiBar("86774681")))
            // Dedup not primed: the current session emits "A" at once.
            scanner.acceptLiveSuccess(scanner.currentCameraSession(), success("A"))
            yield()
            assertEquals(listOf("A"), received.map { (it as ScanResult.Success).barcode.rawValue })
            job.cancel()
        } finally {
            scanner.close()
        }
    }

    @Test
    fun liveFrame_fromStoppedSession_isSkipped() = runBlocking {
        val scanner = scanner(suppressionMillis = 0)
        try {
            val oldSession = scanner.currentCameraSession()
            scanner.stopCamera()
            // Skipped before any engine runs: the buffer stays reusable.
            assertTrue(scanner.handleLiveFrame(blankFrame(), oldSession))
        } finally {
            scanner.close()
        }
    }

    @Test
    fun liveSuccess_hostStoppingOnFirstResult_dropsLaterResultsOfThatSession() = runBlocking {
        // Like a scan dialog, the host stops the camera on its first result.
        // A collector doing so inline re-enters the camera lock mid-publish: it
        // must not deadlock, and that session's later results must not publish.
        val scanner = scanner(suppressionMillis = 0)
        try {
            val received = mutableListOf<String>()
            val job = launch(Dispatchers.Unconfined) {
                scanner.results.collect {
                    received += (it as ScanResult.Success).barcode.rawValue
                    scanner.stopCamera()
                }
            }
            val session = scanner.currentCameraSession()
            scanner.acceptLiveSuccess(session, success("A"))
            scanner.acceptLiveSuccess(session, success("B"))
            yield()
            assertEquals(listOf("A"), received)
            job.cancel()
        } finally {
            scanner.close()
        }
    }

    @Test
    fun cameraState_idleWithTorchOffBeforeStartAndAfterClose() {
        val scanner = scanner(suppressionMillis = 0)
        assertEquals(CameraStatus.Idle, scanner.cameraStatus.value)
        assertEquals(false, scanner.torchEnabled.value)
        scanner.close()
        assertEquals(CameraStatus.Idle, scanner.cameraStatus.value)
        assertEquals(false, scanner.torchEnabled.value)
    }

    @Test
    fun cameraFailure_isFinalForItsSession_andStaleSessionsAreIgnored() = runBlocking {
        val scanner = scanner(suppressionMillis = 0)
        try {
            val received = mutableListOf<ScanResult>()
            val job = launch(Dispatchers.Unconfined) { scanner.results.collect { received += it } }
            val session = scanner.currentCameraSession()
            scanner.publishCameraStatus(session, CameraStatus.Running)
            assertEquals(CameraStatus.Running, scanner.cameraStatus.value)
            scanner.reportCameraFailure(session, UnsupportedOperationException("no libmsi_decoder"))
            assertTrue(scanner.cameraStatus.value is CameraStatus.Failed)
            // CameraX may still post OPEN after a failure outside its state machine.
            scanner.publishCameraStatus(session, CameraStatus.Running)
            assertTrue(scanner.cameraStatus.value is CameraStatus.Failed)
            yield()
            assertEquals(1, received.count { it is ScanResult.Failure })
            // Stop resets; the stopped session can no longer publish anything.
            scanner.stopCamera()
            assertEquals(CameraStatus.Idle, scanner.cameraStatus.value)
            scanner.publishCameraStatus(session, CameraStatus.Running)
            scanner.reportCameraFailure(session, IllegalStateException("late"))
            yield()
            assertEquals(CameraStatus.Idle, scanner.cameraStatus.value)
            assertEquals(1, received.count { it is ScanResult.Failure })
            job.cancel()
        } finally {
            scanner.close()
        }
    }

    @Test
    fun cameraControls_withoutCamera_areNoOps() {
        val scanner = scanner(suppressionMillis = 0)
        val point = SurfaceOrientedMeteringPointFactory(1f, 1f).createPoint(0.5f, 0.5f)
        val torchResults = mutableListOf<Boolean>()
        scanner.scaleZoom(2f)
        scanner.scaleZoom(Float.NaN)
        scanner.focusAt(point)
        scanner.setTorchEnabled(true) { torchResults += it }
        scanner.close()
        // Closed scanner: still never throws.
        scanner.scaleZoom(0.5f)
        scanner.focusAt(point)
        scanner.setTorchEnabled(true) { torchResults += it }
        // Results are posted to the main thread, never delivered inline.
        assertTrue(torchResults.isEmpty())
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(listOf(false, false), torchResults)
        assertEquals(false, scanner.torchEnabled.value)
        assertEquals(CameraStatus.Idle, scanner.cameraStatus.value)
    }

    @Test
    fun criticalCameraError_publishesFailedAndOneFailureResult() = runBlocking {
        val scanner = scanner(suppressionMillis = 0)
        try {
            val received = mutableListOf<ScanResult>()
            val job = launch(Dispatchers.Unconfined) { scanner.results.collect { received += it } }
            val session = scanner.currentCameraSession()
            val failure = CameraStatus.Failed("camera fatal error", IllegalStateException("fatal"))
            scanner.publishCameraStatus(session, failure)
            scanner.publishCameraStatus(session, CameraStatus.Failed("again"))
            yield()
            assertEquals(failure, scanner.cameraStatus.value)
            val failures = received.filterIsInstance<ScanResult.Failure>()
            assertEquals(listOf("camera fatal error"), failures.map { it.message })
            job.cancel()
        } finally {
            scanner.close()
        }
    }

    @Test
    fun cameraFailure_hostStoppingOnFailedStatus_reportsNoFailureIntoTheNextSession() = runBlocking {
        // A status collector that stops (as on Retry) inline must not receive
        // the old session's results failure in the session that follows.
        val scanner = scanner(suppressionMillis = 0)
        try {
            val received = mutableListOf<ScanResult>()
            val results = launch(Dispatchers.Unconfined) { scanner.results.collect { received += it } }
            val status = launch(Dispatchers.Unconfined) {
                scanner.cameraStatus.collect { if (it is CameraStatus.Failed) scanner.stopCamera() }
            }
            val session = scanner.currentCameraSession()
            scanner.reportCameraFailure(session, IllegalStateException("bind failed"))
            yield()
            assertTrue(session != scanner.currentCameraSession())
            assertEquals(CameraStatus.Idle, scanner.cameraStatus.value)
            assertTrue(received.none { it is ScanResult.Failure })
            status.cancel()
            results.cancel()
        } finally {
            scanner.close()
        }
    }

    @Test
    fun torchRequest_fromWorkerThread_reportsOnMainThread() {
        val scanner = scanner(suppressionMillis = 0)
        try {
            val callbackOnMain = mutableListOf<Boolean>()
            val worker = Thread {
                scanner.setTorchEnabled(true) {
                    callbackOnMain += Looper.myLooper() == Looper.getMainLooper()
                }
            }
            worker.start()
            worker.join()
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(listOf(true), callbackOnMain)
        } finally {
            scanner.close()
        }
    }
}
