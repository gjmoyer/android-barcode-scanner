package com.barcodescanner.sdk.data.fusion

import com.barcodescanner.sdk.data.frame.LumaFrame
import com.barcodescanner.sdk.data.frame.NativeFrame
import com.barcodescanner.sdk.data.mlkit.MLKitDecoder
import com.barcodescanner.sdk.data.msi.MsiNativeDecoder
import com.barcodescanner.sdk.data.zxingcpp.ZXingCppDecoder
import com.barcodescanner.sdk.di.ScannerContainer
import com.barcodescanner.sdk.domain.decoder.BarcodeDecoder
import com.barcodescanner.sdk.domain.decoder.DecodeOutcome
import com.barcodescanner.sdk.domain.decoder.DecoderException
import com.barcodescanner.sdk.domain.model.DecodedBarcode
import com.barcodescanner.sdk.domain.model.ScanFrame
import com.barcodescanner.sdk.domain.model.ScannerConfig
import com.barcodescanner.sdk.domain.model.Symbology
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LiveFusionTest {

    /** Routing identity only: the live path must never touch the bitmap entry. */
    private class RoutingOnly(
        override val name: String,
        override val supportedSymbologies: Set<Symbology>,
    ) : BarcodeDecoder {
        override suspend fun decode(frame: ScanFrame): DecodeOutcome =
            throw AssertionError("live fusion must not call the bitmap decode()")
    }

    private class FakeEngine(
        name: String,
        private val outcome: DecodeOutcome = DecodeOutcome.NotFound(),
        symbologies: Set<Symbology> = setOf(Symbology.QR_CODE),
        private val delayMillis: Long = 0,
    ) {
        val seen = mutableListOf<LumaFrame>()
        val engine = LiveEngine(RoutingOnly(name, symbologies)) { frame ->
            seen += frame
            if (delayMillis > 0) delay(delayMillis)
            outcome
        }
    }

    private fun hit(value: String, confidence: Float, symbology: Symbology = Symbology.QR_CODE) =
        DecodeOutcome.Success(
            listOf(DecodedBarcode(rawValue = value, symbology = symbology, confidence = confidence, engineName = "Fake")),
        )

    private fun config(
        timeout: Long = 1_000,
        symbologies: Set<Symbology> = setOf(Symbology.QR_CODE),
    ) = ScannerConfig(
        enabledSymbologies = symbologies,
        maxOrientationsTried = 1,
        decodeTimeoutMillis = timeout,
        duplicateSuppressionMillis = 0,
        msiChecksumPolicy = ScannerConfig.MsiChecksumPolicy.MOD_10,
        msiMinPayloadDigits = 3,
        msiOcrRequireChecksum = false,
        minConfidence = 0.5f,
    )

    private fun frame() = LumaFrame(NativeFrame.allocate(64, 32), 64, 32)

    private fun fusion(config: ScannerConfig, vararg engines: FakeEngine) =
        LiveFusion(engines.map { it.engine }, config)

    private fun values(out: DecodeOutcome) = (out as DecodeOutcome.Success).barcodes.map { it.rawValue }

    @Test
    fun confidentHit_stopsBeforeLaterEngines() = runBlocking {
        val first = FakeEngine("First", hit("A", 0.95f))
        val second = FakeEngine("Second", hit("B", 1.0f))
        val out = fusion(config(), first, second).decode(frame())
        assertEquals(listOf("A"), values(out.outcome))
        assertTrue(out.bufferReusable)
        assertTrue("later engines must not run after a confident hit", second.seen.isEmpty())
    }

    @Test
    fun subConfidentHits_poolRankedByConfidence() = runBlocking {
        val first = FakeEngine("First", hit("A", 0.9f))
        val second = FakeEngine("Second", hit("B", 1.0f))
        val out = fusion(config(), first, second).decode(frame())
        assertEquals(listOf("B", "A"), values(out.outcome))
    }

    @Test
    fun everyEngineReadsTheSamePreparedFrameOnce() = runBlocking {
        val engines = arrayOf(FakeEngine("A"), FakeEngine("B"), FakeEngine("C"))
        val shared = frame()
        val out = fusion(config(), *engines).decode(shared)
        assertTrue(out.outcome is DecodeOutcome.NotFound)
        assertTrue(out.bufferReusable)
        for (e in engines) {
            assertEquals(1, e.seen.size)
            assertSame("engines must read the one prepared frame, not a copy", shared, e.seen.single())
        }
    }

    @Test
    fun enginesWithoutEnabledSymbology_areDropped() = runBlocking {
        val qr = FakeEngine("Qr")
        val msi = FakeEngine("Msi", hit("123", 1.0f, Symbology.MSI_PLESSEY), setOf(Symbology.MSI_PLESSEY))
        val f = fusion(config(), qr, msi)
        assertEquals(listOf("Qr"), f.engines.map { it.name })
        f.decode(frame())
        assertTrue(msi.seen.isEmpty())
    }

    @Test
    fun lowConfidenceAndDisabledSymbologyHits_areIgnored() = runBlocking {
        val weak = FakeEngine("Weak", hit("A", 0.4f))
        val foreign = FakeEngine("Foreign", hit("B", 1.0f, Symbology.EAN_13))
        val out = fusion(config(), weak, foreign).decode(frame())
        assertTrue("got ${out.outcome}", out.outcome is DecodeOutcome.NotFound)
    }

    @Test
    fun timeout_returnsBestSoFar_andMarksBufferInUse() = runBlocking {
        val first = FakeEngine("First", hit("A", 0.9f))
        val hanging = FakeEngine("Hanging", delayMillis = 10_000)
        val out = fusion(config(timeout = 150), first, hanging).decode(frame())
        assertEquals(listOf("A"), values(out.outcome))
        // A timed-out engine (ML Kit task) may still read the buffer.
        assertFalse(out.bufferReusable)
    }

    @Test
    fun timeout_withoutHits_isNotFound_andMarksBufferInUse() = runBlocking {
        val hanging = FakeEngine("Hanging", delayMillis = 10_000)
        val out = fusion(config(timeout = 150), hanging).decode(frame())
        assertTrue(out.outcome is DecodeOutcome.NotFound)
        assertFalse(out.bufferReusable)
    }

    @Test
    fun recoverableError_continues_nonRecoverable_failsFrame() = runBlocking {
        val soft = FakeEngine("Soft", DecodeOutcome.Error(DecoderException("soft"), recoverable = true))
        val next = FakeEngine("Next", hit("A", 0.95f))
        assertEquals(listOf("A"), values(fusion(config(), soft, next).decode(frame()).outcome))

        val hard = FakeEngine("Hard", DecodeOutcome.Error(DecoderException("hard"), recoverable = false))
        val after = FakeEngine("After", hit("A", 0.95f))
        val out = fusion(config(), hard, after).decode(frame())
        assertTrue(out.outcome is DecodeOutcome.Error)
        assertTrue("the failing engine returned, nothing still reads", out.bufferReusable)
        assertTrue(after.seen.isEmpty())
    }

    @Test
    fun callerCancellation_propagates() = runBlocking {
        // An OUTER timeout must not be mistaken for the fusion's own budget
        // (which would return a result the cancelled caller never reads and
        // hide that the buffer is still in use).
        val hanging = FakeEngine("Hanging", delayMillis = 10_000)
        val result = runCatching {
            withTimeout(100) { fusion(config(timeout = 5_000), hanging).decode(frame()) }
        }
        assertTrue("expected cancellation, got $result", result.exceptionOrNull() is CancellationException)
    }

    @Test
    fun container_wiresEnginesCheapFirst_andDropsDisabled() {
        val all = config(symbologies = setOf(Symbology.QR_CODE, Symbology.MSI_PLESSEY, Symbology.DATA_BAR))
        val container = ScannerContainer(RuntimeEnvironment.getApplication(), all)
        try {
            assertEquals(
                listOf(MLKitDecoder.NAME, MsiNativeDecoder.NAME, ZXingCppDecoder.NAME),
                container.liveFusion.engines.map { it.name },
            )
        } finally {
            container.close()
        }
        val qrOnly = ScannerContainer(RuntimeEnvironment.getApplication(), config())
        try {
            assertEquals(listOf(MLKitDecoder.NAME), qrOnly.liveFusion.engines.map { it.name })
        } finally {
            qrOnly.close()
        }
    }
}
