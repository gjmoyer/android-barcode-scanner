package com.barcodescanner.sdk.data.msi

/**
 * MSI Plessey code table (Modified Plessey, pulse-width encoded) — VERIFIED.
 *
 * Encoding (Wikipedia "MSI Barcode", Morovia KB10637, Seagull/BarTender guides):
 * digits 0–9 only. Each digit = 4 BCD bits MSB-first; each bit prints as one
 * bar/space pair totalling 3 modules: bit 0 = narrow bar (1) + wide space (2),
 * bit 1 = wide bar (2) + narrow space (1). In wide/narrow element terms
 * (8 elements per digit, bar-first, 1 = wide):
 *
 *   elements = [b3, !b3, b2, !b2, b1, !b1, b0, !b0]
 *
 * e.g. 5 = BCD 0101 → 0,1,1,0,0,1,1,0 ("01100110"); 8 = BCD 1000 →
 * "10010101"; 9 → "10010110". Cross-checked against Wikipedia's 12-module Maps
 * (digit 1 "100100100110" = pairs 100|100|100|110 = bits 0,0,0,1 ✓).
 *
 * Guards (module Maps, 1 = black): START "110" = wide bar + narrow space;
 * STOP "1001" = narrow bar + wide space + narrow bar.
 *
 * Previous versions of this table used an unverified 8-pattern set; corrected
 * 2026-10-06 from the spec sources above. Verification procedure + Hamming
 * analysis in docs/MSI_PLESSEY_RESEARCH.md (Zint vectors 1234567→4, 8052→3).
 *
 * Table isolated from decode logic so variants need no logic change (OCP).
 */
object MsiCodeTable {

    /** Digit -> 8-element wide/narrow pattern, bar-first (true = wide). */
    val DIGITS: Map<Char, BooleanArray> = mapOf(
        '0' to bits("01010101"),
        '1' to bits("01010110"),
        '2' to bits("01011001"),
        '3' to bits("01011010"),
        '4' to bits("01100101"),
        '5' to bits("01100110"),
        '6' to bits("01101001"),
        '7' to bits("01101010"),
        '8' to bits("10010101"),
        '9' to bits("10010110"),
    )

    /** Reverse lookup: packed byte -> digit. Packed as MSB-first bitmask. */
    val FROM_PACKED: Map<Int, Char> by lazy {
        DIGITS.entries.associate { (d, b) -> pack(b) to d }
    }

    /** Number of elements per digit. */
    const val ELEMENTS_PER_DIGIT = 8

    /** Minimum digits (excluding checksum) to accept a result. Guards short noise. */
    const val MIN_DIGITS = 3

    /** Maximum digits to accept (prevents runaway on ITF/Code128 lookalikes). */
    const val MAX_DIGITS = 32

    fun pack(pattern: BooleanArray): Int {
        var v = 0
        for (b in pattern) v = (v shl 1) or (if (b) 1 else 0)
        return v
    }

    private fun bits(s: String): BooleanArray = BooleanArray(s.length) { s[it] == '1' }
}
