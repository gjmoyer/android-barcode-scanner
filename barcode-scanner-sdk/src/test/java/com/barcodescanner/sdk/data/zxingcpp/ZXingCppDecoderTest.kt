package com.barcodescanner.sdk.data.zxingcpp

import android.graphics.Bitmap
import android.graphics.Point
import com.barcodescanner.sdk.domain.decoder.DecodeOutcome
import com.barcodescanner.sdk.domain.model.ScanFrame
import com.barcodescanner.sdk.domain.model.Symbology
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import zxingcpp.BarcodeReader

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ZXingCppDecoderTest {

    private fun frame(rotationDegrees: Int = 0, attemptRotation: Int = 0) = ScanFrame(
        bitmap = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888),
        rotationDegrees = rotationDegrees,
        attemptRotation = attemptRotation,
    )

    private fun result(
        format: BarcodeReader.Format,
        text: String?,
    ) = BarcodeReader.Result(
        format = format,
        bytes = null,
        text = text,
        contentType = BarcodeReader.ContentType.TEXT,
        position = BarcodeReader.Position(
            topLeft = Point(0, 0),
            topRight = Point(1, 0),
            bottomRight = Point(1, 1),
            bottomLeft = Point(0, 1),
            orientation = 0.0,
        ),
        orientation = 0,
        ecLevel = null,
        symbologyIdentifier = null,
        sequenceSize = 0,
        sequenceIndex = 0,
        sequenceId = null,
        readerInit = false,
        lineCount = 0,
        error = null,
    )

    @Test
    fun mapFormat_databarFamily() {
        val d = ZXingCppDecoder(setOf(Symbology.DATA_BAR))
        assertEquals(Symbology.DATA_BAR, d.mapFormat(BarcodeReader.Format.DATA_BAR))
        assertEquals(Symbology.DATA_BAR, d.mapFormat(BarcodeReader.Format.DATA_BAR_OMNI))
        assertEquals(Symbology.DATA_BAR, d.mapFormat(BarcodeReader.Format.DATA_BAR_STK))
        assertEquals(Symbology.DATA_BAR, d.mapFormat(BarcodeReader.Format.DATA_BAR_STK_OMNI))
        assertEquals(Symbology.DATA_BAR_EXPANDED, d.mapFormat(BarcodeReader.Format.DATA_BAR_EXP))
        assertEquals(Symbology.DATA_BAR_EXPANDED, d.mapFormat(BarcodeReader.Format.DATA_BAR_EXP_STK))
        assertEquals(Symbology.DATA_BAR_LIMITED, d.mapFormat(BarcodeReader.Format.DATA_BAR_LTD))
    }

    @Test
    fun mapFormat_natives() {
        val d = ZXingCppDecoder(setOf(Symbology.DATA_BAR))
        assertEquals(Symbology.EAN_8, d.mapFormat(BarcodeReader.Format.EAN_8))
        assertEquals(Symbology.EAN_13, d.mapFormat(BarcodeReader.Format.EAN_13))
        assertEquals(Symbology.UPC_A, d.mapFormat(BarcodeReader.Format.UPC_A))
        assertEquals(Symbology.UPC_E, d.mapFormat(BarcodeReader.Format.UPC_E))
        assertEquals(Symbology.CODE_39, d.mapFormat(BarcodeReader.Format.CODE_39))
        assertEquals(Symbology.CODE_128, d.mapFormat(BarcodeReader.Format.CODE_128))
        assertEquals(Symbology.ITF, d.mapFormat(BarcodeReader.Format.ITF))
        assertEquals(Symbology.QR_CODE, d.mapFormat(BarcodeReader.Format.QR_CODE))
        assertEquals(Symbology.DATA_MATRIX, d.mapFormat(BarcodeReader.Format.DATA_MATRIX))
        assertEquals(Symbology.PDF_417, d.mapFormat(BarcodeReader.Format.PDF_417))
        assertEquals(Symbology.AZTEC, d.mapFormat(BarcodeReader.Format.AZTEC_CODE))
    }

    @Test
    fun mapFormat_noSdkCounterpartIsUnknown() {
        val d = ZXingCppDecoder(setOf(Symbology.QR_CODE))
        assertEquals(Symbology.UNKNOWN, d.mapFormat(BarcodeReader.Format.MICRO_QR_CODE))
        assertEquals(Symbology.UNKNOWN, d.mapFormat(BarcodeReader.Format.RMQR_CODE))
        assertEquals(Symbology.UNKNOWN, d.mapFormat(BarcodeReader.Format.MAXI_CODE))
        assertEquals(Symbology.UNKNOWN, d.mapFormat(BarcodeReader.Format.TELEPEN))
        assertEquals(Symbology.UNKNOWN, d.mapFormat(BarcodeReader.Format.DX_FILM_EDGE))
    }

    @Test
    fun toFormats_usesWrapperEnum() {
        val d = ZXingCppDecoder(
            setOf(Symbology.DATA_BAR, Symbology.DATA_BAR_EXPANDED, Symbology.DATA_BAR_LIMITED),
        )
        val formats = d.toFormats()
        assertTrue(formats.contains(BarcodeReader.Format.DATA_BAR_OMNI))
        assertTrue(formats.contains(BarcodeReader.Format.DATA_BAR_STK))
        assertTrue(formats.contains(BarcodeReader.Format.DATA_BAR_STK_OMNI))
        assertTrue(formats.contains(BarcodeReader.Format.DATA_BAR_EXP))
        assertTrue(formats.contains(BarcodeReader.Format.DATA_BAR_EXP_STK))
        assertTrue(formats.contains(BarcodeReader.Format.DATA_BAR_LTD))
    }

    @Test
    fun toFormats_limitedOnlyStaysNarrow() {
        val d = ZXingCppDecoder(setOf(Symbology.DATA_BAR_LIMITED))
        assertEquals(setOf(BarcodeReader.Format.DATA_BAR_LTD), d.toFormats())
    }

    @Test
    fun toFormats_unknownOnlyFallsBackToScanAll() {
        // Empty set is the wrapper default (scan all formats) — the passthrough.
        val d = ZXingCppDecoder(setOf(Symbology.UNKNOWN))
        assertTrue(d.toFormats().isEmpty())
    }

    @Test
    fun supportedSymbologies_nativesExcluded() {
        // ML Kit owns natives: ZXing is DataBar (+UNKNOWN passthrough) only.
        assertEquals(emptySet<Symbology>(), ZXingCppDecoder(setOf(Symbology.QR_CODE)).supportedSymbologies)
        assertEquals(
            setOf(Symbology.DATA_BAR),
            ZXingCppDecoder(setOf(Symbology.DATA_BAR, Symbology.QR_CODE)).supportedSymbologies,
        )
        assertEquals(
            setOf(Symbology.UNKNOWN),
            ZXingCppDecoder(setOf(Symbology.UNKNOWN)).supportedSymbologies,
        )
    }

    @Test
    fun toFormats_neverListsNatives() {
        val d = ZXingCppDecoder(setOf(Symbology.DATA_BAR, Symbology.QR_CODE, Symbology.EAN_13))
        val formats = d.toFormats()
        assertTrue(formats.contains(BarcodeReader.Format.DATA_BAR_OMNI))
        assertFalse(formats.contains(BarcodeReader.Format.QR_CODE))
        assertFalse(formats.contains(BarcodeReader.Format.EAN_13))
    }

    @Test
    fun parse_mapsDedupsAndFiltersUnknown() {
        val d = ZXingCppDecoder(setOf(Symbology.DATA_BAR, Symbology.DATA_BAR_EXPANDED))
        val results = listOf(
            result(BarcodeReader.Format.DATA_BAR, "A"),
            result(BarcodeReader.Format.DATA_BAR_OMNI, "A"),
            result(BarcodeReader.Format.QR_CODE, "B"),
            result(BarcodeReader.Format.MAXI_CODE, "X"),
        )
        val out = d.parse(results, frame())
        assertTrue("expected Success, got $out", out is DecodeOutcome.Success)
        val barcodes = (out as DecodeOutcome.Success).barcodes
        // QR is ML Kit's (never ZXing's now); MaxiCode has no host opt-in here.
        assertEquals(1, barcodes.size)
        assertEquals(Symbology.DATA_BAR, barcodes.single().symbology)
        assertTrue(barcodes.all { it.confidence == 0.9f })
        assertTrue(barcodes.all { it.engineName == ZXingCppDecoder.NAME })
    }

    @Test
    fun parse_emptyIsNotFound() {
        val d = ZXingCppDecoder(setOf(Symbology.QR_CODE))
        assertTrue(d.parse(emptyList(), frame()) is DecodeOutcome.NotFound)
    }

    @Test
    fun parse_blankTextSkipped() {
        val d = ZXingCppDecoder(setOf(Symbology.DATA_BAR))
        val out = d.parse(
            listOf(result(BarcodeReader.Format.DATA_BAR, ""), result(BarcodeReader.Format.DATA_BAR, null)),
            frame(),
        )
        assertTrue(out is DecodeOutcome.NotFound)
    }

    @Test
    fun parse_unknownPassthroughAtReducedConfidence() {
        val d = ZXingCppDecoder(setOf(Symbology.DATA_BAR, Symbology.UNKNOWN))
        val out = d.parse(listOf(result(BarcodeReader.Format.MAXI_CODE, "X")), frame())
        assertTrue("expected Success, got $out", out is DecodeOutcome.Success)
        val barcode = (out as DecodeOutcome.Success).barcodes.single()
        assertEquals(Symbology.UNKNOWN, barcode.symbology)
        assertEquals(0.6f, barcode.confidence)
    }

    @Test
    fun parse_upsideDownFlag_isRelativeToSensorRotation() {
        val d = ZXingCppDecoder(setOf(Symbology.DATA_BAR))
        val results = listOf(result(BarcodeReader.Format.DATA_BAR, "A"))
        val upright = d.parse(results, frame()) as DecodeOutcome.Success
        assertFalse(upright.barcodes.single().isUpsideDown)
        // Portrait frame (sensor 90): the upside-down candidate is 270, not 180.
        val upsideDown =
            d.parse(results, frame(rotationDegrees = 90, attemptRotation = 270)) as DecodeOutcome.Success
        assertTrue(upsideDown.barcodes.single().isUpsideDown)
    }

    @Test
    fun decode_delegatesToPrebuiltReader() = runBlocking {
        val reader = mockk<BarcodeReader>()
        every { reader.read(any<Bitmap>(), any(), any()) } returns listOf(result(BarcodeReader.Format.DATA_BAR_EXP, "01987654321"))
        var captured: BarcodeReader.Options? = null
        val d = ZXingCppDecoder(
            setOf(Symbology.DATA_BAR_EXPANDED),
            readerFactory = { options ->
                captured = options
                reader
            },
        )
        val out = d.decode(frame())
        assertTrue("expected Success, got $out", out is DecodeOutcome.Success)
        val barcode = (out as DecodeOutcome.Success).barcodes.single()
        assertEquals("01987654321", barcode.rawValue)
        assertEquals(Symbology.DATA_BAR_EXPANDED, barcode.symbology)
        assertEquals(ZXingCppDecoder.NAME, barcode.engineName)
        // Rotation/inversion retries stay on; thorough by default.
        assertEquals(true, captured?.tryRotate)
        assertEquals(true, captured?.tryInvert)
        assertEquals(true, captured?.tryHarder)
        assertTrue(captured?.formats?.contains(BarcodeReader.Format.DATA_BAR_EXP) == true)
    }

    @Test
    fun decode_missingNative_isNotFound() = runBlocking {
        val d = ZXingCppDecoder(
            setOf(Symbology.DATA_BAR),
            readerFactory = { throw UnsatisfiedLinkError("mock missing .so") },
        )
        val out = d.decode(frame())
        assertTrue("expected NotFound, got $out", out is DecodeOutcome.NotFound)
    }

    @Test
    fun decode_noSymbologies_isNotFound() = runBlocking {
        val d = ZXingCppDecoder(
            setOf(Symbology.QR_CODE),
            readerFactory = { throw AssertionError("reader must not be constructed") },
        )
        assertTrue(d.decode(frame()) is DecodeOutcome.NotFound)
    }
}
