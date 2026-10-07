package com.barcodescanner.sdk.data.msi

import android.graphics.Bitmap
import com.barcodescanner.sdk.domain.decoder.DecodeOutcome
import com.barcodescanner.sdk.domain.model.ScanFrame
import com.barcodescanner.sdk.domain.model.ScannerConfig
import com.barcodescanner.sdk.domain.model.Symbology
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import javax.imageio.ImageIO

/**
 * Pristine-barcode regression tests: symbols rendered by the INDEPENDENT Zint
 * reference encoder (not our own tables), committed under src/test/resources.
 * This is the user-facing contract: clean labels must decode exactly and fast.
 *
 * Fixtures (Zint 2.16 `MSI_PLESSEY --quietzones`, checksums appended manually
 * from Wikipedia/Morovia vectors):
 * - mod10_1234567.png        "12345674" (Mod10 check 4, Wikipedia vector)
 * - mod10_8052.png           "80523" (Mod10 check 3, Morovia vector)
 * - mod10_alldigits.png      "01234567897" (all digits incl. leading zero)
 * - mod11_8052.png           "80527" (IBM Mod11 check 7; must FAIL Mod10)
 * - mod1010_1234567.png      "123456741" (double Mod10)
 * - mod1110_1234567.png      "123456741" (Mod11 then Mod10)
 * - mod10_tiny.png           same as mod10_1234567 at ~2px modules (scale=1)
 * - mod10_1234567_r180.png   upside-down (fusion orientation path)
 * - mod10_1234567_r90.png    sideways (robust vertical-scanline path)
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MsiPristineTest {

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

    private fun decodeExact(
        bitmap: Bitmap,
        policy: ScannerConfig.MsiChecksumPolicy,
        robust: Boolean,
    ): String {
        val decoder = MsiPlesseyDecoder(checksumPolicy = policy, robustMode = robust)
        try {
            val outcome = runBlocking { decoder.decode(ScanFrame(bitmap = bitmap)) }
            assertTrue("expected Success, got $outcome", outcome is DecodeOutcome.Success)
            return (outcome as DecodeOutcome.Success).barcodes.maxBy { it.confidence }.rawValue
        } finally {
            decoder.close()
        }
    }

    private data class Case(
        val file: String,
        val policy: ScannerConfig.MsiChecksumPolicy,
        val expected: String,
    )

    private val uprightCases = listOf(
        Case("mod10_1234567.png", ScannerConfig.MsiChecksumPolicy.MOD_10, "1234567"),
        Case("mod10_8052.png", ScannerConfig.MsiChecksumPolicy.MOD_10, "8052"),
        Case("mod10_alldigits.png", ScannerConfig.MsiChecksumPolicy.MOD_10, "0123456789"),
        Case("mod11_8052.png", ScannerConfig.MsiChecksumPolicy.MOD_11, "8052"),
        Case("mod1010_1234567.png", ScannerConfig.MsiChecksumPolicy.MOD_10_10, "1234567"),
        Case("mod1110_1234567.png", ScannerConfig.MsiChecksumPolicy.MOD_10_11, "1234567"),
        Case("mod10_tiny.png", ScannerConfig.MsiChecksumPolicy.MOD_10, "1234567"),
    )

    @Test
    fun pristine_defaultMode_exact() {
        for (c in uprightCases) {
            assertEquals(c.file, c.expected, decodeExact(loadBitmap(c.file), c.policy, robust = false))
        }
    }

    @Test
    fun pristine_robustMode_exact() {
        for (c in uprightCases) {
            assertEquals(c.file, c.expected, decodeExact(loadBitmap(c.file), c.policy, robust = true))
        }
    }

    @Test
    fun pristine_wrongPolicy_rejects() {
        // "80527" carries a Mod11 check (7); Mod10 must NOT accept the SKU.
        val bmp = loadBitmap("mod11_8052.png")
        val decoder = MsiPlesseyDecoder(
            checksumPolicy = ScannerConfig.MsiChecksumPolicy.MOD_10,
            robustMode = true,
        )
        try {
            val outcome = runBlocking { decoder.decode(ScanFrame(bitmap = bmp)) }
            val acceptedAsSku = outcome is DecodeOutcome.Success &&
                (outcome as DecodeOutcome.Success).barcodes.any { it.rawValue == "8052" }
            assertFalse("Mod10 must not accept Mod11 payload, got $outcome", acceptedAsSku)
        } finally {
            decoder.close()
        }
    }

    @Test
    fun pristine_upsideDown_decodesViaReversePath() {
        // 180° label decoded directly (no orientation expansion): the reverse
        // run-direction path must recover print order and flag upside-down.
        // (OrientationCandidates.expand itself is covered in
        // OrientationCandidatesTest; physical pixel rotation under the default
        // Robolectric graphics mode is a no-op, so fusion's compensated-rotation
        // path is covered with native graphics in RotatedImageFusionTest and on
        // device in DeviceMsiTest.)
        val bmp = loadBitmap("mod10_1234567_r180.png")
        val decoder = MsiPlesseyDecoder(
            checksumPolicy = ScannerConfig.MsiChecksumPolicy.MOD_10,
            robustMode = false,
        )
        try {
            val outcome = runBlocking { decoder.decode(ScanFrame(bitmap = bmp)) }
            assertTrue("expected Success, got $outcome", outcome is DecodeOutcome.Success)
            val best = (outcome as DecodeOutcome.Success).barcodes.maxBy { it.confidence }
            assertEquals("1234567", best.rawValue)
            assertEquals(Symbology.MSI_PLESSEY, best.symbology)
            assertTrue("expected upside-down flag", best.isUpsideDown)
        } finally {
            decoder.close()
        }
    }

    @Test
    fun pristine_sideways_decodesViaVerticalScanlines() {
        // 90° label: bars run horizontally, so only robust vertical scanlines see them.
        val bmp = loadBitmap("mod10_1234567_r90.png")
        val decoder = MsiPlesseyDecoder(
            checksumPolicy = ScannerConfig.MsiChecksumPolicy.MOD_10,
            robustMode = true,
        )
        try {
            val outcome = runBlocking { decoder.decode(ScanFrame(bitmap = bmp)) }
            assertTrue("expected Success, got $outcome", outcome is DecodeOutcome.Success)
            val best = (outcome as DecodeOutcome.Success).barcodes.maxBy { it.confidence }
            assertEquals("1234567", best.rawValue)
        } finally {
            decoder.close()
        }
    }

    @Test
    fun pristine_decode_completesPromptly() {
        // Absurd-blowup guard only (wall-clock on shared CI is not a benchmark):
        // catches accidental algorithmic explosions (e.g. unbounded window search
        // on 12MP frames), not normal ms-level variance.
        val bmp = loadBitmap("mod10_alldigits.png")
        val decoder = MsiPlesseyDecoder(
            checksumPolicy = ScannerConfig.MsiChecksumPolicy.MOD_10,
            robustMode = true,
        )
        try {
            val t = System.nanoTime()
            val outcome = runBlocking { decoder.decode(ScanFrame(bitmap = bmp)) }
            val ms = (System.nanoTime() - t) / 1_000_000
            assertTrue("expected Success, got $outcome", outcome is DecodeOutcome.Success)
            assertTrue("pristine decode took ${ms}ms, blowup budget 30000ms", ms < 30_000)
        } finally {
            decoder.close()
        }
    }
}
