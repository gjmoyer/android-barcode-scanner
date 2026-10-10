package com.barcodescanner.sdk.api

import android.graphics.Bitmap
import com.barcodescanner.sdk.di.ScannerContainer
import com.barcodescanner.sdk.domain.model.DecodedBarcode
import com.barcodescanner.sdk.domain.model.ScanFrame
import com.barcodescanner.sdk.domain.model.ScannerConfig
import com.barcodescanner.sdk.domain.model.Symbology
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
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

    @Test
    fun liveFrame_afterClose_doesNotThrow() = runBlocking {
        // Regression: an analyzer callback dispatched before stop()/close()
        // used to throw IllegalStateException out of the shared decode scope
        // (uncaught coroutine exception = app crash). Live frames are
        // droppable; only one-shots throw.
        val scanner = scanner(suppressionMillis = 0)
        val bmp = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888)
        scanner.close()
        try {
            scanner.handleLiveFrame(ScanFrame(bitmap = bmp))
        } finally {
            bmp.recycle()
        }
    }

    @Test
    fun liveFrame_emptyFrame_completesWithoutEmission() = runBlocking {
        // Full live path on the JVM (ML Kit/zxing/native all miss without
        // device libraries): must complete and emit nothing, not throw.
        val scanner = scanner(suppressionMillis = 0)
        val received = mutableListOf<ScanResult>()
        val job = launch(Dispatchers.Unconfined) { scanner.results.collect { received += it } }
        val bmp = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888)
        bmp.eraseColor(android.graphics.Color.WHITE)
        try {
            scanner.handleLiveFrame(ScanFrame(bitmap = bmp))
            yield()
            assertEquals(emptyList<ScanResult>(), received)
            job.cancel()
        } finally {
            bmp.recycle()
            scanner.close()
        }
    }
}
