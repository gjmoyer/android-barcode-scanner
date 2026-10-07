package com.barcodescanner.sdk.api

import com.barcodescanner.sdk.di.ScannerContainer
import com.barcodescanner.sdk.domain.model.DecodedBarcode
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
}
