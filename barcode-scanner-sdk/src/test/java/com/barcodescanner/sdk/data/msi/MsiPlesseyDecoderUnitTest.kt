package com.barcodescanner.sdk.data.msi

import org.junit.Assert.*
import org.junit.Test

class MsiPlesseyDecoderUnitTest {

    private val decoder = MsiPlesseyDecoder(robustMode = true)

    @Test
    fun splitThreshold_separatesNarrowWide() {
        val t = decoder.splitThreshold(listOf(2f, 2f, 3f, 2f, 8f, 9f, 8f))
        assertTrue(t > 3f && t < 8f)
    }

    @Test
    fun singleBitRepair_recoversUnambiguousFlip() {
        // '5' = 01100110; flipping bit 0 -> 11100110 has exactly one valid
        // single-flip neighbor ('5'). (Min Hamming distance of the MSI table is 2,
        // so most 1-bit corruptions are ambiguous by design — repair returns null
        // for those and the checksum/voting layers decide.)
        val original = MsiCodeTable.DIGITS.getValue('5').copyOf()
        original[0] = !original[0]
        assertEquals('5', decoder.trySingleBitRepair(original))
    }

    @Test
    fun singleBitRepair_rejectsAmbiguousFlip() {
        // Flipping bit 3 of '5' yields neighbors '1' and '5' — ambiguous, must be null.
        val original = MsiCodeTable.DIGITS.getValue('5').copyOf()
        original[3] = !original[3]
        assertNull(decoder.trySingleBitRepair(original))
    }

    @Test
    fun runLengths_allWhiteReturnsNull_notThrow() {
        // Blank band (or perpendicular bars): must be NotFound-signal, never a crash.
        assertNull(decoder.runLengths(BooleanArray(100) { false }))
    }

    @Test
    fun runLengths_allBlackReturnsNull_notThrow() {
        assertNull(decoder.runLengths(BooleanArray(100) { true }))
    }

    @Test
    fun decodeScanline_blankLineReturnsNull() {
        assertNull(decoder.decodeScanline(BooleanArray(120) { false }))
    }

    @Test
    fun quantize_rejectsMissingGuards() {
        // Well-formed runs without START/STOP framing must be rejected, not sliced off-phase.
        val runs = listOf(
            MsiPlesseyDecoder.Run(true, 2f),
            MsiPlesseyDecoder.Run(false, 2f),
            MsiPlesseyDecoder.Run(true, 2f),
            MsiPlesseyDecoder.Run(false, 2f),
        )
        assertNull(decoder.quantize(runs, cut = 4f))
    }

    @Test
    fun extractDigits_roundTripsTable() {
        // Build element stream for "123" directly from the code table
        // (post-guard-strip digit payload; 3 digits to clear MIN_DIGITS).
        val elems = MsiCodeTable.DIGITS.getValue('1').toList() +
            MsiCodeTable.DIGITS.getValue('2').toList() +
            MsiCodeTable.DIGITS.getValue('3').toList()
        val s = decoder.extractDigits(elems, startsWithBar = true)
        assertEquals("123", s)
    }

    @Test
    fun decodeScanline_wikipediaModuleMap() {
        // End-to-end scanline from Wikipedia's module Maps (1 = black module):
        // payload "12" + Mod10 check 5 -> full "125".
        // Start 110 | '1' 100100100110 | '2' 100100110100 | '5' 100110100110 | Stop 1001.
        val modules = "110" + "100100100110" + "100100110100" + "100110100110" + "1001"
        val scale = 4 // px per module: narrow run = 4px, wide run = 8px
        val row = mutableListOf<Boolean>()
        repeat(24) { row += false } // left quiet zone
        for (m in modules) repeat(scale) { row += (m == '1') }
        repeat(24) { row += false } // right quiet zone
        assertEquals("125", decoder.decodeScanline(row.toBooleanArray()))
    }

    @Test
    fun extractDigits_rejectsSpacePhase() {
        val elems = MsiCodeTable.DIGITS.getValue('1').toList() +
            MsiCodeTable.DIGITS.getValue('2').toList() +
            MsiCodeTable.DIGITS.getValue('3').toList()
        assertNull(decoder.extractDigits(elems, startsWithBar = false))
    }
}
