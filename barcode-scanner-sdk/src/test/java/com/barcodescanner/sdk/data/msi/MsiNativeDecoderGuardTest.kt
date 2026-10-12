package com.barcodescanner.sdk.data.msi

import com.barcodescanner.sdk.domain.decoder.DecodeOutcome
import com.barcodescanner.sdk.domain.model.ScanFrame
import com.barcodescanner.sdk.domain.model.ScannerConfig
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import android.graphics.Bitmap
import com.barcodescanner.sdk.data.frame.LumaFrame
import com.barcodescanner.sdk.data.frame.NativeFrame
import com.barcodescanner.sdk.domain.model.Symbology

/**
 * JVM-level regression tests for [MsiNativeDecoder]'s native-library guard.
 *
 * The .so cannot load under Robolectric, which is exactly the production
 * hazard (unsupported ABI): class init and [MsiNativeDecoder.decode] must
 * degrade to NotFound instead of throwing ExceptionInInitializerError (an
 * Error that fusion's `catch (Exception)` cannot contain).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MsiNativeDecoderGuardTest {

    @Test
    fun classInit_doesNotThrowWithoutNativeLib() {
        // Would throw ExceptionInInitializerError before the guard.
        @Suppress("UNUSED_VARIABLE")
        val name = MsiNativeDecoder.NAME
        assertFalse(MsiNativeDecoder.nativeAvailable)
    }

    @Test
    fun decode_withoutNativeLib_isNotFound() = runBlocking {
        val decoder = MsiNativeDecoder(requireConsecutiveFrames = 1)
        val bmp = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888)
        try {
            val out = decoder.decode(ScanFrame(bitmap = bmp))
            assertTrue("expected NotFound, got $out", out is DecodeOutcome.NotFound)
        } finally {
            bmp.recycle()
        }
    }

    @Test
    fun nativePolicyCode_pinsJniContract() {
        // Must mirror toRequiredPolicy() in msi_jni.cpp; a drift silently
        // pushes the wrong scheme into native candidate selection.
        val expected = mapOf(
            ScannerConfig.MsiChecksumPolicy.MOD_10 to 0,
            ScannerConfig.MsiChecksumPolicy.MOD_11 to 1,
            ScannerConfig.MsiChecksumPolicy.MOD_10_10 to 2,
            ScannerConfig.MsiChecksumPolicy.MOD_10_11 to 3,
            ScannerConfig.MsiChecksumPolicy.NONE to -1,
        )
        assertEquals(ScannerConfig.MsiChecksumPolicy.entries.toSet(), expected.keys)
        expected.forEach { (policy, code) ->
            assertEquals(policy.name, code, MsiNativeDecoder.nativePolicyCode(policy))
        }
    }

    @Test
    fun decodeLuma_withoutNativeLib_isNotFound() = runBlocking {
        val decoder = MsiNativeDecoder(requireConsecutiveFrames = 1)
        val out = decoder.decodeLuma(LumaFrame(NativeFrame.allocate(64, 32), 64, 32))
        assertTrue("expected NotFound, got $out", out is DecodeOutcome.NotFound)
    }

    // --- shared acceptance tail (both native entry points) ---------------

    private fun shelfDecoder(required: Int = 1) = MsiNativeDecoder(
        checksumPolicy = ScannerConfig.MsiChecksumPolicy.MOD_10_10,
        requireConsecutiveFrames = required,
    )

    @Test
    fun accept_lumaRead_stripsChecksAndReportsUpright() {
        val out = shelfDecoder().acceptNativeRead("011578333|mod1010|3|0", upsideDown = true)
        val hit = (out as DecodeOutcome.Success).barcodes.single()
        assertEquals("0115783", hit.rawValue)
        assertEquals(Symbology.MSI_PLESSEY, hit.symbology)
        assertTrue(hit.checksumStripped)
        // 4-field luma reads report their own rotation: 0 overrides the hint.
        assertFalse(hit.isUpsideDown)
    }

    @Test
    fun accept_lumaRead_relativeRotation180_isUpsideDown() {
        val out = shelfDecoder().acceptNativeRead("011578333|mod1010|3|180", upsideDown = false)
        assertTrue((out as DecodeOutcome.Success).barcodes.single().isUpsideDown)
    }

    @Test
    fun accept_bitmapRead_keepsCandidateOrientation() {
        val out = shelfDecoder().acceptNativeRead("011578333|mod1010|3", upsideDown = true)
        assertTrue((out as DecodeOutcome.Success).barcodes.single().isUpsideDown)
    }

    @Test
    fun accept_rejectsMissMalformedForeignAndDegenerate() {
        val d = shelfDecoder()
        assertTrue(d.acceptNativeRead(null, false) is DecodeOutcome.NotFound)
        assertTrue(d.acceptNativeRead("011578333", false) is DecodeOutcome.NotFound)
        assertTrue(d.acceptNativeRead("011578333|mod1010|3|0|x", false) is DecodeOutcome.NotFound)
        // Mod10-valid 8-digit truncation of the tag: not Mod1010.
        assertTrue(d.acceptNativeRead("01157833|mod10|3|0", false) is DecodeOutcome.NotFound)
        // All-zero window passes Mod1010 but is withheld as degenerate.
        assertTrue(d.acceptNativeRead("000000000|mod1010|3|0", false) is DecodeOutcome.NotFound)
    }

    @Test
    fun accept_voteGateNeedsTwoAgreeingReads() {
        val d = shelfDecoder(required = 2)
        assertTrue(d.acceptNativeRead("011578333|mod1010|3|0", false) is DecodeOutcome.NotFound)
        assertTrue(d.acceptNativeRead("011578333|mod1010|3|0", false) is DecodeOutcome.Success)
        // Confirmation auto-resets; a miss in between resets the count again.
        assertTrue(d.acceptNativeRead("011578333|mod1010|3|0", false) is DecodeOutcome.NotFound)
        assertTrue(d.acceptNativeRead(null, false) is DecodeOutcome.NotFound)
        assertTrue(d.acceptNativeRead("011578333|mod1010|3|0", false) is DecodeOutcome.NotFound)
        assertTrue(d.acceptNativeRead("011578333|mod1010|3|0", false) is DecodeOutcome.Success)
    }
}
