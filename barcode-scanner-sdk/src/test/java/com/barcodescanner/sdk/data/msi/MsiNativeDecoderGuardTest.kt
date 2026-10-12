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
}
