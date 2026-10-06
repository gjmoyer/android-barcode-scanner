package com.barcodescanner.sdk.data.zxingcpp

import com.barcodescanner.sdk.domain.model.Symbology
import org.junit.Assert.*
import org.junit.Test

class ZXingCppDecoderTest {

    @Test
    fun mapFormat_v311HriStrings() {
        // Exact zxing-cpp v3.1.1 ToString (HRI) outputs, incl. spaces/hyphens.
        val d = ZXingCppDecoder(setOf(Symbology.DATA_BAR))
        assertEquals(Symbology.DATA_BAR, d.mapFormat("DataBar"))
        assertEquals(Symbology.DATA_BAR, d.mapFormat("DataBar Omni"))
        assertEquals(Symbology.DATA_BAR, d.mapFormat("DataBar Stacked"))
        assertEquals(Symbology.DATA_BAR, d.mapFormat("DataBar Stacked Omni"))
        assertEquals(Symbology.DATA_BAR_EXPANDED, d.mapFormat("DataBar Expanded"))
        assertEquals(Symbology.DATA_BAR_EXPANDED, d.mapFormat("DataBar Expanded Stacked"))
        assertEquals(Symbology.DATA_BAR_LIMITED, d.mapFormat("DataBar Limited"))
        assertEquals(Symbology.EAN_8, d.mapFormat("EAN-8"))
        assertEquals(Symbology.EAN_13, d.mapFormat("EAN-13"))
        assertEquals(Symbology.UPC_A, d.mapFormat("UPC-A"))
        assertEquals(Symbology.UPC_E, d.mapFormat("UPC-E"))
        assertEquals(Symbology.QR_CODE, d.mapFormat("QR Code"))
        assertEquals(Symbology.DATA_MATRIX, d.mapFormat("Data Matrix"))
        assertEquals(Symbology.PDF_417, d.mapFormat("PDF417"))
        assertEquals(Symbology.AZTEC, d.mapFormat("Aztec Code"))
    }

    @Test
    fun mapFormat_identifierSpellings() {
        val d = ZXingCppDecoder(setOf(Symbology.DATA_BAR))
        assertEquals(Symbology.DATA_BAR, d.mapFormat("DataBarOmni"))
        assertEquals(Symbology.DATA_BAR_EXPANDED, d.mapFormat("DataBarExp"))
        assertEquals(Symbology.DATA_BAR_LIMITED, d.mapFormat("DataBarLtd"))
        assertEquals(Symbology.QR_CODE, d.mapFormat("QRCode"))
    }

    @Test
    fun mapFormat_noSdkCounterpartIsUnknown() {
        val d = ZXingCppDecoder(setOf(Symbology.QR_CODE))
        assertEquals(Symbology.UNKNOWN, d.mapFormat("Micro QR Code"))
        assertEquals(Symbology.UNKNOWN, d.mapFormat("rMQR Code"))
        assertEquals(Symbology.UNKNOWN, d.mapFormat("MaxiCode"))
        assertEquals(Symbology.UNKNOWN, d.mapFormat("Telepen"))
        assertEquals(Symbology.UNKNOWN, d.mapFormat("SomethingNew"))
    }

    @Test
    fun enabledFormatsArg_usesV311Identifiers() {
        val d = ZXingCppDecoder(
            setOf(Symbology.DATA_BAR, Symbology.DATA_BAR_EXPANDED, Symbology.DATA_BAR_LIMITED),
        )
        val parts = d.enabledFormatsArg().split(",")
        assertTrue(parts.contains("DataBarOmni"))
        assertTrue(parts.contains("DataBarExp"))
        assertTrue(parts.contains("DataBarExpStk"))
        assertTrue(parts.contains("DataBarLtd"))
        // v2-era composite names must never be emitted (v3 FromString still parses
        // them via deprecated aliases, but identifiers are canonical).
        assertFalse(parts.contains("DataBarExpanded"))
        assertFalse(parts.contains("DataBarLimited"))
    }

    @Test
    fun enabledFormatsArg_limitedOnlyStaysNarrow() {
        val d = ZXingCppDecoder(setOf(Symbology.DATA_BAR_LIMITED))
        assertEquals("DataBarLtd", d.enabledFormatsArg())
    }
}
