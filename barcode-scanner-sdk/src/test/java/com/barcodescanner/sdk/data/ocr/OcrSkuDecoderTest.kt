package com.barcodescanner.sdk.data.ocr

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import com.barcodescanner.sdk.domain.decoder.DecodeOutcome
import com.barcodescanner.sdk.domain.model.ScanFrame
import com.barcodescanner.sdk.domain.model.ScannerConfig
import com.barcodescanner.sdk.domain.model.Symbology
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * OcrSkuDecoder logic with a fake OCR engine (ML Kit cannot run on JVM).
 * Band detection runs on real bitmaps; text recognition is stubbed with
 * transcribed-style lines + boxes, exactly as [MlKitOcrEngine] would return.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class OcrSkuDecoderTest {

    private class FakeOcrEngine(val lines: List<OcrLine>) : OcrEngine {
        var calls = 0
        override suspend fun recognize(bitmap: Bitmap, rotationDegrees: Int): List<OcrLine> {
            calls++
            return lines
        }

        override fun close() = Unit
    }

    /** White label with a black bar block (dense rows for band anchoring). */
    private fun labelWithBars(w: Int = 400, h: Int = 300): Bitmap {
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        bmp.eraseColor(Color.WHITE)
        val px = IntArray(w * h)
        bmp.getPixels(px, 0, w, 0, 0, w, h)
        // Bar block: alternating black/white columns across the middle band.
        for (y in h / 2 until h / 2 + 40) {
            for (x in 0 until w) {
                if ((x / 4) % 2 == 0) px[y * w + x] = Color.BLACK
            }
        }
        bmp.setPixels(px, 0, w, 0, 0, w, h)
        return bmp
    }

    private fun decoder(
        fake: FakeOcrEngine,
        policy: ScannerConfig.MsiChecksumPolicy = ScannerConfig.MsiChecksumPolicy.MOD_10,
        requireChecksum: Boolean = false,
    ) = OcrSkuDecoder(
        enabledSymbologies = setOf(Symbology.MSI_PLESSEY),
        checksumPolicy = policy,
        requireChecksum = requireChecksum,
        ocrEngineProvider = { fake },
    )

    private fun successRaw(outcome: DecodeOutcome): String {
        assertTrue("expected Success, got $outcome", outcome is DecodeOutcome.Success)
        return (outcome as DecodeOutcome.Success).barcodes.maxBy { it.confidence }.rawValue
    }

    private fun successConfidence(outcome: DecodeOutcome): Float {
        assertTrue("expected Success, got $outcome", outcome is DecodeOutcome.Success)
        return (outcome as DecodeOutcome.Success).barcodes.maxBy { it.confidence }.confidence
    }

    @Test
    fun picksAdjacentChecksumValidSkuOverDistantDashedCode() {
        // Both runs genuinely checksum-validate under Mod10 ("42002": check over
        // "4200" is 2; "0168971": check over "016897" is 1); the plain adjacent
        // SKU must beat the dashed distant case-code segment.
        val fake = FakeOcrEngine(
            listOf(
                // Far from bars (y ~40), dashed-adjacent valid run.
                OcrLine("X-42002", Rect(10, 30, 200, 60)),
                // Adjacent to bars (y ~130, bars at y 150-190), plain SKU.
                OcrLine("0168971", Rect(10, 120, 220, 150)),
            ),
        )
        val d = decoder(fake, requireChecksum = true)
        val out = runBlocking { d.decode(ScanFrame(bitmap = labelWithBars())) }
        // Printed run emitted as-is (never stripped); strict mode earns 0.7.
        assertEquals("0168971", successRaw(out))
        assertEquals(0.7f, successConfidence(out), 0f)
        d.close()
    }

    @Test
    fun trustMode_emitsPrintedSkuWithoutCheckDigit() {
        // Yakult prints "0828147" with no check digit (none of the standard
        // schemes apply); default trust mode must still emit the SKU as printed.
        val fake = FakeOcrEngine(
            listOf(OcrLine("0828147 006 99235-00100 10 13.5 OZ", Rect(10, 120, 300, 150))),
        )
        val d = decoder(fake)
        val out = runBlocking { d.decode(ScanFrame(bitmap = labelWithBars())) }
        assertEquals("0828147", successRaw(out))
        assertEquals(0.5f, successConfidence(out), 0f)
        d.close()
    }

    @Test
    fun ignoresShortRunsAndPriceNoise() {
        val fake = FakeOcrEngine(
            listOf(
                OcrLine("4.40", Rect(10, 100, 200, 140)),
                OcrLine("09/14/22", Rect(10, 200, 300, 230)),
                OcrLine("0168971", Rect(10, 120, 220, 150)),
            ),
        )
        val d = decoder(fake)
        val out = runBlocking { d.decode(ScanFrame(bitmap = labelWithBars())) }
        // Printed run as-is ("0168971" validates Mod10 with check 1).
        assertEquals("0168971", successRaw(out))
        d.close()
    }

    @Test
    fun withholdsShortSingleCheckHits() {
        // "1512" genuinely validates Mod11 (1/11 luck on a case-code segment),
        // but at 4 payload digits a single OCR pass cannot back it — withheld.
        val fake = FakeOcrEngine(listOf(OcrLine("X-15121", Rect(10, 120, 200, 150))))
        val d = decoder(fake, ScannerConfig.MsiChecksumPolicy.MOD_11, requireChecksum = true)
        val out = runBlocking { d.decode(ScanFrame(bitmap = labelWithBars())) }
        assertTrue("expected NotFound, got $out", out is DecodeOutcome.NotFound)
        d.close()
    }

    @Test
    fun rejectsWrongChecksumPolicy() {        // "80527" carries a Mod11 check (7); Mod10 must not accept the SKU.
        val fake = FakeOcrEngine(listOf(OcrLine("80527", Rect(10, 120, 200, 150))))
        val d = decoder(fake, ScannerConfig.MsiChecksumPolicy.MOD_10, requireChecksum = true)
        val out = runBlocking { d.decode(ScanFrame(bitmap = labelWithBars())) }
        assertTrue("expected NotFound, got $out", out is DecodeOutcome.NotFound)
        d.close()
    }

    @Test
    fun nonePolicyAcceptsLongestRun() {
        val fake = FakeOcrEngine(
            listOf(
                OcrLine("Call 555-0134 today", Rect(10, 120, 300, 150)),
                OcrLine("REF 987654321", Rect(10, 200, 300, 230)),
            ),
        )
        val d = decoder(fake, ScannerConfig.MsiChecksumPolicy.NONE)
        val out = runBlocking { d.decode(ScanFrame(bitmap = labelWithBars())) }
        // "5550134" is dashed-adjacent ("555-0134") so plain "987654321" wins.
        assertEquals("987654321", successRaw(out))
        d.close()
    }

    @Test
    fun emptyOcrGivesNotFound() {
        val fake = FakeOcrEngine(emptyList())
        val d = decoder(fake)
        val out = runBlocking { d.decode(ScanFrame(bitmap = labelWithBars())) }
        assertTrue("expected NotFound, got $out", out is DecodeOutcome.NotFound)
        assertEquals(1, fake.calls)
        d.close()
    }

    @Test
    fun blankFrameSkipsOcrEntirely() {
        val fake = FakeOcrEngine(listOf(OcrLine("0087573", Rect(10, 10, 100, 30))))
        val bmp = Bitmap.createBitmap(400, 300, Bitmap.Config.ARGB_8888)
        bmp.eraseColor(Color.WHITE)
        val d = decoder(fake)
        val out = runBlocking { d.decode(ScanFrame(bitmap = bmp)) }
        assertTrue("expected NotFound, got $out", out is DecodeOutcome.NotFound)
        // No content bands -> OCR model never invoked (latency + precision).
        assertEquals(0, fake.calls)
        d.close()
    }

    @Test
    fun disabledMsiNeverRuns() {
        val fake = FakeOcrEngine(listOf(OcrLine("0087573", Rect(10, 10, 100, 30))))
        val d = OcrSkuDecoder(
            enabledSymbologies = setOf(Symbology.QR_CODE),
            ocrEngineProvider = { fake },
        )
        val out = runBlocking { d.decode(ScanFrame(bitmap = labelWithBars())) }
        assertTrue("expected NotFound, got $out", out is DecodeOutcome.NotFound)
        assertEquals(0, fake.calls)
        d.close()
    }

    @Test
    fun denseBands_findsBarBlock_notBlank() {
        val d = decoder(FakeOcrEngine(emptyList()))
        val gray = com.barcodescanner.sdk.data.msi.MsiBinarizer.toGray(labelWithBars())
        val bands = d.denseBands(gray, 400, 300)
        assertTrue("expected bands over bar block, got $bands", bands.isNotEmpty())
        assertTrue(bands.any { it.first <= 170 && it.last >= 150 })
        val blank = Bitmap.createBitmap(400, 300, Bitmap.Config.ARGB_8888)
        blank.eraseColor(Color.WHITE)
        val grayBlank = com.barcodescanner.sdk.data.msi.MsiBinarizer.toGray(blank)
        assertTrue(d.denseBands(grayBlank, 400, 300).isEmpty())
        d.close()
    }

    @Test
    fun isPlainRun_marksDashAdjacent() {
        assertTrue(OcrSkuDecoder.isPlainRun("0087573", 0, 7))
        assertTrue(OcrSkuDecoder.isPlainRun("SKU 0087573", 4, 11))
        assertFalse(OcrSkuDecoder.isPlainRun("000-42000-15121", 4, 9))
        assertFalse(OcrSkuDecoder.isPlainRun("09/14/22", 0, 2))
        assertFalse(OcrSkuDecoder.isPlainRun("10-48 CT", 0, 2))
    }
}
