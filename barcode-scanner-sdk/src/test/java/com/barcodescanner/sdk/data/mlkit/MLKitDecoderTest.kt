package com.barcodescanner.sdk.data.mlkit

import android.graphics.Bitmap
import com.barcodescanner.sdk.domain.decoder.DecodeOutcome
import com.barcodescanner.sdk.domain.model.ScanFrame
import com.barcodescanner.sdk.domain.model.Symbology
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.barcode.BarcodeScanner
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
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

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MLKitDecoderTest {

    private fun frame(rotationDegrees: Int = 0, attemptRotation: Int = 0) = ScanFrame(
        bitmap = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888),
        rotationDegrees = rotationDegrees,
        attemptRotation = attemptRotation,
    )

    private fun clientReturning(vararg barcodes: Barcode): BarcodeScanner {
        val client = mockk<BarcodeScanner>()
        every { client.process(any<InputImage>()) } returns Tasks.forResult(barcodes.toList())
        return client
    }

    private fun mlKitBarcode(
        rawValue: String = "HELLO",
        format: Int = Barcode.FORMAT_QR_CODE,
    ): Barcode {
        val barcode = mockk<Barcode>()
        every { barcode.rawValue } returns rawValue
        every { barcode.format } returns format
        every { barcode.boundingBox } returns null
        every { barcode.cornerPoints } returns null
        return barcode
    }

    private fun decoderWith(client: BarcodeScanner, symbologies: Set<Symbology>) =
        MLKitDecoder(
            symbologies,
            clientProvider = { client },
            imageProvider = { _, _ -> mockk(relaxed = true) },
        )

    private fun describe(outcome: DecodeOutcome): String = when (outcome) {
        is DecodeOutcome.Error -> "$outcome; root=${outcome.cause.cause}"
        else -> "$outcome"
    }

    @Test
    fun mapsBarcode_toSdkModel() = runBlocking {
        val decoder = decoderWith(clientReturning(mlKitBarcode()), setOf(Symbology.QR_CODE))
        try {
            val out = decoder.decode(frame())
            assertTrue("expected Success, got ${describe(out)}", out is DecodeOutcome.Success)
            val b = (out as DecodeOutcome.Success).barcodes.single()
            assertEquals("HELLO", b.rawValue)
            assertEquals(Symbology.QR_CODE, b.symbology)
            assertEquals(0.95f, b.confidence, 0f)
            assertFalse(b.isUpsideDown)
        } finally {
            decoder.close()
        }
    }

    @Test
    fun upsideDownFlag_isRelativeToSensorRotation() = runBlocking {
        val decoder = decoderWith(clientReturning(mlKitBarcode()), setOf(Symbology.QR_CODE))
        try {
            // Portrait frame (sensor 90): the upside-down candidate is 270, not 180.
            val out = decoder.decode(frame(rotationDegrees = 90, attemptRotation = 270))
            assertTrue(out is DecodeOutcome.Success)
            assertTrue((out as DecodeOutcome.Success).barcodes.single().isUpsideDown)
        } finally {
            decoder.close()
        }
    }

    @Test
    fun unsupportedFormat_isFilteredOut() = runBlocking {
        val decoder = decoderWith(
            clientReturning(mlKitBarcode(format = Barcode.FORMAT_AZTEC)),
            setOf(Symbology.QR_CODE),
        )
        try {
            val out = decoder.decode(frame())
            assertTrue("expected NotFound, got ${describe(out)}", out is DecodeOutcome.NotFound)
        } finally {
            decoder.close()
        }
    }

    @Test
    fun noResults_isNotFound() = runBlocking {
        val decoder = decoderWith(clientReturning(), setOf(Symbology.QR_CODE))
        try {
            val out = decoder.decode(frame())
            assertTrue("expected NotFound, got ${describe(out)}", out is DecodeOutcome.NotFound)
        } finally {
            decoder.close()
        }
    }

    @Test
    fun decodeAfterClose_returnsError() = runBlocking {
        val decoder = decoderWith(clientReturning(mlKitBarcode()), setOf(Symbology.QR_CODE))
        decoder.close()
        val out = decoder.decode(frame())
        assertTrue("expected Error, got ${describe(out)}", out is DecodeOutcome.Error)
    }
}
