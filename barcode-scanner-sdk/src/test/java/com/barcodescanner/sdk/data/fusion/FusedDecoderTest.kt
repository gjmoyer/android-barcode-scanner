package com.barcodescanner.sdk.data.fusion

import android.graphics.Bitmap
import com.barcodescanner.sdk.data.mlkit.MLKitDecoder
import com.barcodescanner.sdk.data.msi.MsiPlesseyDecoder
import com.barcodescanner.sdk.data.zxingcpp.ZXingCppDecoder
import com.barcodescanner.sdk.domain.decoder.BarcodeDecoder
import com.barcodescanner.sdk.domain.decoder.DecodeOutcome
import com.barcodescanner.sdk.domain.decoder.DecoderRegistry
import com.barcodescanner.sdk.domain.decoder.LastResortDecoder
import com.barcodescanner.sdk.domain.model.DecodedBarcode
import com.barcodescanner.sdk.domain.model.ScanFrame
import com.barcodescanner.sdk.domain.model.ScannerConfig
import com.barcodescanner.sdk.domain.model.Symbology
import com.barcodescanner.sdk.domain.pipeline.FrameTransform
import com.barcodescanner.sdk.domain.pipeline.PreprocessingPipeline
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class FusedDecoderTest {

    private class FakeDecoder(
        override val name: String,
        override val supportedSymbologies: Set<Symbology> = setOf(Symbology.QR_CODE),
        override val resolvesOrientationInternally: Boolean = false,
        private val results: List<DecodeOutcome> = emptyList(),
        private val delayFromCall: Int = Int.MAX_VALUE,
        private val delayMillis: Long = 0,
    ) : BarcodeDecoder {
        var calls = 0
            private set
        val seen = mutableListOf<ScanFrame>()

        override suspend fun decode(frame: ScanFrame): DecodeOutcome {
            calls++
            seen += frame
            if (calls >= delayFromCall) delay(delayMillis)
            return results.getOrElse(calls - 1) { results.lastOrNull() ?: DecodeOutcome.NotFound() }
        }
    }

    private class FakeLastResort(
        private val result: DecodeOutcome,
    ) : LastResortDecoder {
        override val name: String = "FakeLastResort"
        override val supportedSymbologies: Set<Symbology> = setOf(Symbology.MSI_PLESSEY)
        var calls = 0
            private set

        override suspend fun decode(frame: ScanFrame): DecodeOutcome {
            calls++
            return result
        }
    }

    private fun barcode(
        value: String,
        confidence: Float,
        symbology: Symbology = Symbology.QR_CODE,
    ) = DecodedBarcode(
        rawValue = value,
        symbology = symbology,
        confidence = confidence,
        engineName = "Fake",
    )

    private fun config(
        maxOrientations: Int = 1,
        timeout: Long = 1_000,
        symbologies: Set<Symbology> = setOf(Symbology.QR_CODE),
    ) = ScannerConfig(
        enabledSymbologies = symbologies,
        maxOrientationsTried = maxOrientations,
        decodeTimeoutMillis = timeout,
        duplicateSuppressionMillis = 0,
        msiChecksumPolicy = ScannerConfig.MsiChecksumPolicy.MOD_10,
        msiMinPayloadDigits = 3,
        msiOcrRequireChecksum = false,
        minConfidence = 0.5f,
    )

    private fun frame(
        rotationDegrees: Int = 0,
        attemptRotation: Int = 0,
        allowFallbackSweep: Boolean = true,
    ) = ScanFrame(
        bitmap = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888),
        rotationDegrees = rotationDegrees,
        attemptRotation = attemptRotation,
        allowFallbackSweep = allowFallbackSweep,
    )

    private fun fused(config: ScannerConfig, vararg decoders: BarcodeDecoder) = FusedDecoder(
        DecoderRegistry(decoders.toList()),
        PreprocessingPipeline.empty(),
        config,
    )

    /** Pipeline that always allocates a fresh bitmap, so recycle state is observable. */
    private fun copyPipeline() = PreprocessingPipeline.of(
        FrameTransform { f ->
            f.copy(bitmap = Bitmap.createBitmap(f.bitmap.width, f.bitmap.height, Bitmap.Config.ARGB_8888))
        },
    )

    @Test
    fun confidentHit_stopsBeforeLaterEngines() = runBlocking {
        val first = FakeDecoder("First", results = listOf(DecodeOutcome.Success(listOf(barcode("A", 0.95f)))))
        val second = FakeDecoder("Second", results = listOf(DecodeOutcome.Success(listOf(barcode("B", 0.9f)))))
        val out = fused(config(), first, second).decode(frame())
        assertTrue(out is DecodeOutcome.Success)
        assertEquals("A", (out as DecodeOutcome.Success).barcodes.single().rawValue)
        assertEquals("later engines must not run after a confident hit", 0, second.calls)
    }

    @Test
    fun subConfidentHits_poolByConfidence() = runBlocking {
        val first = FakeDecoder("First", results = listOf(DecodeOutcome.Success(listOf(barcode("A", 0.9f)))))
        val second = FakeDecoder("Second", results = listOf(DecodeOutcome.Success(listOf(barcode("B", 0.6f)))))
        val out = fused(config(), first, second).decode(frame())
        assertTrue(out is DecodeOutcome.Success)
        assertEquals(listOf("A", "B"), (out as DecodeOutcome.Success).barcodes.map { it.rawValue })
        assertEquals(1, second.calls)
    }

    @Test
    fun completedDecode_recyclesProcessedBitmap() = runBlocking {
        val decoder = FakeDecoder("First", results = listOf(DecodeOutcome.NotFound()))
        val base = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888)
        fusedWithPipeline(
            DecoderRegistry(listOf(decoder)),
            copyPipeline(),
            config(maxOrientations = 1),
        ).decode(ScanFrame(bitmap = base))
        assertTrue("processed copy must be recycled on normal completion", decoder.seen[0].bitmap.isRecycled)
        assertFalse("caller's bitmap must never be recycled", base.isRecycled)
    }

    @Test
    fun timeout_returnsBestSoFar_andKeepsInFlightBitmapsAlive() = runBlocking {
        // Fast hit on orientation 0 (0.9, below early-break), then a hanging engine
        // on orientation 1 trips decodeTimeoutMillis.
        val first = FakeDecoder("First", results = listOf(DecodeOutcome.Success(listOf(barcode("A", 0.9f)))))
        val second = FakeDecoder(
            "Second",
            results = listOf(DecodeOutcome.NotFound()),
            delayFromCall = 2,
            delayMillis = 10_000,
        )
        val base = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888)
        val out = fusedWithPipeline(
            DecoderRegistry(listOf(first, second)),
            copyPipeline(),
            config(maxOrientations = 2, timeout = 150),
        ).decode(ScanFrame(bitmap = base))
        assertTrue("expected best-so-far Success, got $out", out is DecodeOutcome.Success)
        assertEquals("A", (out as DecodeOutcome.Success).barcodes.single().rawValue)
        assertTrue("expected the hanging second attempt", second.calls >= 2)
        // A cancelled ML Kit-style task may still be reading the bitmap: it must
        // NOT be recycled. (Regression: fusion used to recycle unconditionally.)
        assertFalse("in-flight processed bitmap was recycled", first.seen[0].bitmap.isRecycled)
        assertFalse(base.isRecycled)
    }

    @Test
    fun lastResort_runsOnce_afterBarEnginesMiss() = runBlocking {
        val bar = FakeDecoder(
            "Bar",
            supportedSymbologies = setOf(Symbology.MSI_PLESSEY),
            results = listOf(DecodeOutcome.NotFound()),
        )
        val ocr = FakeLastResort(
            DecodeOutcome.Success(listOf(barcode("OCR1", 0.5f, Symbology.MSI_PLESSEY))),
        )
        val out = fused(
            config(maxOrientations = 2, symbologies = setOf(Symbology.MSI_PLESSEY)),
            bar,
            ocr,
        ).decode(frame())
        assertTrue(out is DecodeOutcome.Success)
        assertEquals("OCR1", (out as DecodeOutcome.Success).barcodes.single().rawValue)
        assertEquals("bar engines ran per orientation", 2, bar.calls)
        assertEquals("last-resort engine must run exactly once", 1, ocr.calls)
    }

    @Test
    fun lastResort_runsWhenBarPhaseExhaustsItsBudget() = runBlocking {
        // Regression: with 4 orientations, deferring OCR to the end
        // under one shared timeout meant slow bar work starved the fallback.
        // The bar phase must stop at its reserved deadline and hand over to OCR.
        val slowBar = FakeDecoder(
            "SlowBar",
            supportedSymbologies = setOf(Symbology.MSI_PLESSEY),
            results = listOf(DecodeOutcome.NotFound()),
            delayFromCall = 1,
            delayMillis = 2_000,
        )
        val ocr = FakeLastResort(
            DecodeOutcome.Success(listOf(barcode("OCR1", 0.5f, Symbology.MSI_PLESSEY))),
        )
        val out = fused(
            config(
                maxOrientations = 4,
                timeout = 3_000,
                symbologies = setOf(Symbology.MSI_PLESSEY),
            ),
            slowBar,
            ocr,
        ).decode(frame())
        assertTrue("expected OCR fallback Success, got $out", out is DecodeOutcome.Success)
        assertEquals("OCR1", (out as DecodeOutcome.Success).barcodes.single().rawValue)
        assertEquals("last-resort must get its reserved slice", 1, ocr.calls)
        assertTrue(
            "bar phase must stop at its budget, got ${slowBar.calls} orientations",
            slowBar.calls < 4,
        )
    }

    @Test
    fun lastResort_skippedWhenBarEngineHits() = runBlocking {
        val bar = FakeDecoder(
            "Bar",
            supportedSymbologies = setOf(Symbology.MSI_PLESSEY),
            results = listOf(
                DecodeOutcome.Success(listOf(barcode("BAR", 0.9f, Symbology.MSI_PLESSEY))),
            ),
        )
        val ocr = FakeLastResort(DecodeOutcome.Success(listOf(barcode("OCR1", 0.5f))))
        val out = fused(
            config(maxOrientations = 2, symbologies = setOf(Symbology.MSI_PLESSEY)),
            bar,
            ocr,
        ).decode(frame())
        assertTrue(out is DecodeOutcome.Success)
        assertEquals("BAR", (out as DecodeOutcome.Success).barcodes.single().rawValue)
        assertEquals("last-resort must not run after a bar hit", 0, ocr.calls)
    }

    @Test
    fun msiDecoder_attemptedOnEveryView() = runBlocking {
        // No degraded mode: MSI is attempted on every orientation candidate
        // (sideways included — the thorough decoder samples vertical scanlines).
        val msi = FakeDecoder(
            MsiPlesseyDecoder.NAME,
            supportedSymbologies = setOf(Symbology.MSI_PLESSEY),
            results = listOf(DecodeOutcome.NotFound()),
        )
        fused(
            config(maxOrientations = 4, symbologies = setOf(Symbology.MSI_PLESSEY)),
            msi,
        ).decode(frame())
        assertEquals("all orientations attempted", 4, msi.calls)
    }

    @Test
    fun msiDecoder_uprightFirstOnPortraitFrames() = runBlocking {        // Portrait frame (rotationDegrees=90): priority order is 90,270,180,0 whose
        // relative rotations are 0,180,90,270 — upright-first ordering is
        // sensor-relative, so the first candidate is already the upright view.
        val msi = FakeDecoder(
            MsiPlesseyDecoder.NAME,
            supportedSymbologies = setOf(Symbology.MSI_PLESSEY),
            results = listOf(DecodeOutcome.NotFound()),
        )
        fused(
            config(maxOrientations = 4, symbologies = setOf(Symbology.MSI_PLESSEY)),
            msi,
        ).decode(frame(rotationDegrees = 90))
        val relatives = msi.seen.map { (it.attemptRotation - it.rotationDegrees + 360) % 360 }
        assertEquals(listOf(0, 180, 90, 270), relatives)
    }

    @Test
    fun freshFrame_skipsFallbackSweep() = runBlocking {
        // Fresh live frame (ML Kit isolation just ran elsewhere): the full-frame
        // MSI sweep and the full-frame OCR fallback wait for the stale backstop
        // instead of starving the viewfinder — but ROI-style primary engines
        // still run, on exactly one candidate.
        val msiFull = FakeDecoder(
            MsiPlesseyDecoder.NAME,
            supportedSymbologies = setOf(Symbology.MSI_PLESSEY),
            results = listOf(DecodeOutcome.NotFound()),
        )
        val roi = FakeDecoder(
            "MsiRoi",
            supportedSymbologies = setOf(Symbology.MSI_PLESSEY),
            resolvesOrientationInternally = true,
            results = listOf(DecodeOutcome.NotFound()),
        )
        val ocr = FakeLastResort(DecodeOutcome.NotFound())
        val out = fused(
            config(maxOrientations = 4, symbologies = setOf(Symbology.MSI_PLESSEY)),
            roi,
            msiFull,
            ocr,
        ).decode(frame(allowFallbackSweep = false))
        assertTrue("expected NotFound, got $out", out is DecodeOutcome.NotFound)
        assertEquals("ROI path still runs on fresh frames", 1, roi.calls)
        assertEquals("full-frame MSI waits for the backstop", 0, msiFull.calls)
        assertEquals("full-frame OCR waits for the backstop", 0, ocr.calls)
    }

    @Test
    fun sweepFrame_runsFullChain() = runBlocking {
        // One-shot and stale live frames sweep everything, all orientations.
        val msiFull = FakeDecoder(
            MsiPlesseyDecoder.NAME,
            supportedSymbologies = setOf(Symbology.MSI_PLESSEY),
            results = listOf(DecodeOutcome.NotFound()),
        )
        val ocr = FakeLastResort(DecodeOutcome.NotFound())
        val out = fused(
            config(maxOrientations = 4, symbologies = setOf(Symbology.MSI_PLESSEY)),
            msiFull,
            ocr,
        ).decode(frame(allowFallbackSweep = true))
        assertTrue("expected NotFound, got $out", out is DecodeOutcome.NotFound)
        assertEquals("full-frame MSI sweeps", 4, msiFull.calls)
        assertEquals("full-frame OCR runs once", 1, ocr.calls)
    }

    @Test
    fun orientationInternalEngines_getPrimaryCandidateOnly() = runBlocking {
        // ML Kit/zxing handle rotation themselves: feeding them physical
        // rotations is duplicate work (and every extra candidate paid TryHarder).
        val internal = FakeDecoder(
            "Internal",
            resolvesOrientationInternally = true,
            results = listOf(DecodeOutcome.NotFound()),
        )
        val physical = FakeDecoder(
            "Physical",
            supportedSymbologies = setOf(Symbology.MSI_PLESSEY),
            results = listOf(DecodeOutcome.NotFound()),
        )
        fused(
            config(
                maxOrientations = 4,
                symbologies = setOf(Symbology.QR_CODE, Symbology.MSI_PLESSEY),
            ),
            internal,
            physical,
        ).decode(frame())
        assertEquals("internal-rotation engine must run once", 1, internal.calls)
        assertEquals(listOf(0), internal.seen.map { it.relativeRotation })
        assertEquals("physical engine still gets every orientation", 4, physical.calls)
    }

    @Test
    fun builtInEnginesDeclareInternalRotation() {
        assertTrue(MLKitDecoder(setOf(Symbology.QR_CODE)).resolvesOrientationInternally)
        assertTrue(ZXingCppDecoder(setOf(Symbology.DATA_BAR)).resolvesOrientationInternally)
    }

    private fun fusedWithPipeline(
        registry: DecoderRegistry,
        pipeline: PreprocessingPipeline,
        config: ScannerConfig,
    ) = FusedDecoder(registry, pipeline, config)
}
