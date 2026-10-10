package com.barcodescanner.sdk.data.msi

import com.barcodescanner.sdk.domain.model.ScannerConfig.MsiChecksumPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

/**
 * Ground-truth checksum vectors for the six committed shelf-label fixtures
 * (dir `src/test/resources/msi-shelf`, also exercised end-to-end by the
 * host harness `tools/msi-harness` and on-device by `DeviceMsiTest`).
 *
 * Each entry is payload -> full codeword as printed in the bars. The native
 * engine detects the codeword; [MsiChecksumValidator] enforces the policy and
 * strips the check digits — these tests pin that Kotlin-side contract on the
 * JVM (the .so cannot load under unit tests).
 */
@RunWith(JUnit4::class)
class MsiShelfVectorsTest {

    private fun checkFull(full: String, policy: MsiChecksumPolicy, payload: String) {
        val v = MsiChecksumValidator.validate(full, policy)
        assertTrue("$full must validate under $policy", v.valid)
        assertEquals(payload, v.payloadWithoutChecksum)
    }

    @Test
    fun yakult_mod10() {
        assertEquals(9, MsiChecksumValidator.mod10Check("0828147"))
        checkFull("08281479", MsiChecksumPolicy.MOD_10, "0828147")
    }

    @Test
    fun quaker_mod1010() {
        checkFull("018647768", MsiChecksumPolicy.MOD_10_10, "0186477")
    }

    @Test
    fun oneDegree_mod10() {
        checkFull("02435238", MsiChecksumPolicy.MOD_10, "0243523")
    }

    @Test
    fun dixie_mod10() {
        checkFull("00875732", MsiChecksumPolicy.MOD_10, "0087573")
    }

    @Test
    fun starbucks_mod1010() {
        checkFull("016897100", MsiChecksumPolicy.MOD_10_10, "0168971")
    }

    @Test
    fun silk_mod1010() {
        checkFull("082659368", MsiChecksumPolicy.MOD_10_10, "0826593")
    }

    @Test
    fun doubleCheck_codewords_alsoSatisfySingle() {
        // By construction the second check digit IS Mod10 over payload+first
        // check, so double-check labels validate under MOD_10 too (stripping
        // one). The decoder reports the first validating scheme; the Kotlin
        // revalidation under the configured policy decides the payload.
        val v = MsiChecksumValidator.validate("016897100", MsiChecksumPolicy.MOD_10)
        assertTrue(v.valid)
        assertEquals("01689710", v.payloadWithoutChecksum)
    }

    @Test
    fun singleCheck_rejectedUnderDoublePolicy() {
        val v = MsiChecksumValidator.validate("08281479", MsiChecksumPolicy.MOD_10_10)
        assertTrue("must not validate (test guards the assertion direction)", !v.valid)
    }
}
