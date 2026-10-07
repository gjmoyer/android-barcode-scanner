package com.barcodescanner.sdk.data.msi

import com.barcodescanner.sdk.domain.model.ScannerConfig
import org.junit.Assert.*
import org.junit.Test

class MsiChecksumValidatorTest {

    @Test
    fun mod10_knownVector() {
        // Payload "12345" -> Mod10 check = 5 (Luhn). Full "123455" must validate.
        val check = MsiChecksumValidator.mod10Check("12345")
        assertEquals(5, check)
        val v = MsiChecksumValidator.validate("12345$check", ScannerConfig.MsiChecksumPolicy.MOD_10)
        assertTrue(v.valid)
        assertEquals("12345", v.payloadWithoutChecksum)
    }

    @Test
    fun mod10_rejectsWrongCheck() {
        val v = MsiChecksumValidator.validate("123450", ScannerConfig.MsiChecksumPolicy.MOD_10)
        assertFalse(v.valid)
    }

    @Test
    fun mod11_roundTrip() {
        val payload = "987654"
        val check = MsiChecksumValidator.mod11Check(payload)
        val v = MsiChecksumValidator.validate("$payload$check", ScannerConfig.MsiChecksumPolicy.MOD_11)
        assertTrue(v.valid)
        assertEquals(payload, v.payloadWithoutChecksum)
    }

    @Test
    fun none_acceptsAnything() {
        val v = MsiChecksumValidator.validate("000", ScannerConfig.MsiChecksumPolicy.NONE)
        assertTrue(v.valid)
    }

    @Test
    fun mod10_wikipediaVector() {
        // Wikipedia "MSI Barcode" example: 1234567 -> check 4.
        assertEquals(4, MsiChecksumValidator.mod10Check("1234567"))
        val v = MsiChecksumValidator.validate("12345674", ScannerConfig.MsiChecksumPolicy.MOD_10)
        assertTrue(v.valid)
        assertEquals("1234567", v.payloadWithoutChecksum)
    }

    @Test
    fun mod10_moroviaVector() {
        // Morovia KB10637: "8052" -> check 3.
        assertEquals(3, MsiChecksumValidator.mod10Check("8052"))
    }

    @Test
    fun mod10_10_doubleChecksum() {
        val payload = "12345"
        val c1 = MsiChecksumValidator.mod10Check(payload)
        val c2 = MsiChecksumValidator.mod10Check(payload + c1)
        val full = "$payload$c1$c2"
        val v = MsiChecksumValidator.validate(full, ScannerConfig.MsiChecksumPolicy.MOD_10_10)
        assertTrue(v.valid)
        assertEquals(payload, v.payloadWithoutChecksum)
    }

    @Test
    fun rejectsNonDigits() {
        val v = MsiChecksumValidator.validate("12A45", ScannerConfig.MsiChecksumPolicy.MOD_10)
        assertFalse(v.valid)
    }

    @Test
    fun mod11_unrepresentableTen_isRejectedNotGuessedAsZero() {
        // Payload "6": weighted sum 12 -> (11 - 1) = 10, no decimal check digit.
        assertEquals(-1, MsiChecksumValidator.mod11Check("6"))
        // A label whose printed check is 0 (printer 10->0 convention) must not
        // validate: a guessed 0 would accept 1/11 of wrong checks.
        assertFalse(MsiChecksumValidator.validate("60", ScannerConfig.MsiChecksumPolicy.MOD_11).valid)
    }
}
