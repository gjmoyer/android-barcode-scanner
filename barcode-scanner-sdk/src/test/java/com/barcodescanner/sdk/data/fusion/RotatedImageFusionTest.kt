package com.barcodescanner.sdk.data.fusion

import android.graphics.Bitmap
import com.barcodescanner.sdk.data.msi.MsiPlesseyDecoder
import com.barcodescanner.sdk.domain.decoder.DecodeOutcome
import com.barcodescanner.sdk.domain.decoder.DecoderRegistry
import com.barcodescanner.sdk.domain.model.ScanFrame
import com.barcodescanner.sdk.domain.model.ScannerConfig
import com.barcodescanner.sdk.domain.model.Symbology
import com.barcodescanner.sdk.domain.pipeline.ContrastNormalizationTransform
import com.barcodescanner.sdk.domain.pipeline.DownscaleTransform
import com.barcodescanner.sdk.domain.pipeline.OrientationCandidates
import com.barcodescanner.sdk.domain.pipeline.PreprocessingPipeline
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import javax.imageio.ImageIO

/**
 * End-to-end rotation tests with REAL pixel rotation (Robolectric native graphics
 * mode — the legacy shadow does not apply Matrix transforms to pixels, which is
 * why the rest of the suite cannot cover this).
 *
 * Covers the fusion sensor-compensation path: a camera captures a rotated buffer
 * and reports the rotation needed to make it upright; fusion materializes the
 * compensated candidate and the MSI scanline decoder must read it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class RotatedImageFusionTest {

    private fun loadBitmap(name: String): Bitmap {
        val stream = javaClass.classLoader!!.getResourceAsStream("msi-pristine/$name")
            ?: error("missing fixture msi-pristine/$name")
        val img = ImageIO.read(stream)!!
        val w = img.width
        val h = img.height
        val px = IntArray(w * h)
        img.getRGB(0, 0, w, h, px, 0, w)
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        bmp.setPixels(px, 0, w, 0, 0, w, h)
        return bmp
    }

    private fun config(robust: Boolean) = ScannerConfig(
        enabledSymbologies = setOf(Symbology.MSI_PLESSEY),
        maxOrientationsTried = if (robust) 4 else 2,
        robustMode = robust,
        decodeTimeoutMillis = 10_000,
        duplicateSuppressionMillis = 0,
        msiChecksumPolicy = ScannerConfig.MsiChecksumPolicy.MOD_10,
        msiMinPayloadDigits = 3,
        msiOcrRequireChecksum = false,
        minConfidence = 0.5f,
    )

    private fun fusedDecoder(robust: Boolean) = FusedDecoder(
        DecoderRegistry(
            listOf(
                MsiPlesseyDecoder(
                    checksumPolicy = ScannerConfig.MsiChecksumPolicy.MOD_10,
                    robustMode = robust,
                ),
            ),
        ),
        PreprocessingPipeline.of(DownscaleTransform(), ContrastNormalizationTransform()),
        config(robust),
    )

    private fun decodedValue(outcome: DecodeOutcome): String? =
        (outcome as? DecodeOutcome.Success)?.barcodes?.maxByOrNull { it.confidence }?.rawValue

    @Test
    fun rotate90_actuallyMovesPixels() {
        // Guards the test infrastructure: without native graphics this would pass
        // dimensions but keep content, making the tests below vacuous.
        val bmp = Bitmap.createBitmap(8, 4, Bitmap.Config.ARGB_8888)
        bmp.eraseColor(android.graphics.Color.WHITE)
        bmp.setPixel(0, 0, android.graphics.Color.BLACK)
        val rotated = OrientationCandidates.rotate(bmp, 90)
        assertEquals(4, rotated.width)
        assertEquals(8, rotated.height)
        // Clockwise: the original top-left corner moves to the top-right.
        assertEquals(android.graphics.Color.BLACK, rotated.getPixel(3, 0))
        assertEquals(android.graphics.Color.WHITE, rotated.getPixel(0, 0))
    }

    @Test
    fun sensorCompensatedRotations_decodeThroughFusion() = runBlocking {
        val upright = loadBitmap("mod10_1234567.png")
        for (contentRotation in listOf(0, 90, 180, 270)) {
            // Simulate a camera whose raw buffer is stored rotated by
            // contentRotation; it reports the clockwise rotation needed to
            // restore upright content = 360 - contentRotation.
            val raw = OrientationCandidates.rotate(upright, contentRotation)
            val sensorRotation = (360 - contentRotation) % 360
            val outcome = fusedDecoder(robust = false).decode(
                ScanFrame(bitmap = raw, rotationDegrees = sensorRotation),
            )
            assertEquals(
                "contentRotation=$contentRotation sensorRotation=$sensorRotation",
                "1234567",
                decodedValue(outcome),
            )
        }
    }

    @Test
    fun upsideDownContent_decodesThroughFusion() = runBlocking {
        val upsideDown = loadBitmap("mod10_1234567_r180.png")
        val outcome = fusedDecoder(robust = false).decode(
            ScanFrame(bitmap = upsideDown, rotationDegrees = 0),
        )
        assertTrue("expected Success, got $outcome", outcome is DecodeOutcome.Success)
        val best = (outcome as DecodeOutcome.Success).barcodes.maxBy { it.confidence }
        assertEquals("1234567", best.rawValue)
        assertTrue("reverse-direction decode must flag upside-down", best.isUpsideDown)
    }

    @Test
    fun sidewaysContent_robustDecodes_defaultStaysHonest() = runBlocking {
        val upright = loadBitmap("mod10_1234567.png")
        val sideways = OrientationCandidates.rotate(upright, 90)

        // Documented default-mode limitation: horizontal-only MSI cannot read
        // a 90° label, and it must return NotFound, never a misread.
        val defaultOutcome = fusedDecoder(robust = false).decode(
            ScanFrame(bitmap = sideways, rotationDegrees = 0),
        )
        assertTrue("default mode must not decode sideways content", defaultOutcome is DecodeOutcome.NotFound)

        // Robust mode recovers via vertical scanlines / rotated candidates.
        val robustOutcome = fusedDecoder(robust = true).decode(
            ScanFrame(bitmap = sideways, rotationDegrees = 0),
        )
        assertEquals("1234567", decodedValue(robustOutcome))
    }
}
