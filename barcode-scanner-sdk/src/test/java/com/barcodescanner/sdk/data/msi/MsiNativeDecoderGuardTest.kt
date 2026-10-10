package com.barcodescanner.sdk.data.msi

import com.barcodescanner.sdk.domain.decoder.DecodeOutcome
import com.barcodescanner.sdk.domain.model.ScanFrame
import kotlinx.coroutines.runBlocking
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
}
