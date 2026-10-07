package com.barcodescanner.sdk.data.msi

import android.graphics.Bitmap
import android.graphics.Color
import com.barcodescanner.sdk.domain.decoder.DecodeOutcome
import com.barcodescanner.sdk.domain.model.ScanFrame
import com.barcodescanner.sdk.domain.model.ScannerConfig
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * End-to-end regression tests for the custom MSI decoder: renders synthetic
 * MSI symbols (spec encoding from [MsiCodeTable]) into bitmaps and asserts
 * full [MsiPlesseyDecoder.decode] recovery, plus unit coverage for the
 * window/soft-decode machinery. No external files (unlike the temporary
 * shelf-photo probes).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MsiDecoderRegressionTest {

    /** Renders payload+check into a black-on-white bitmap (unit = px per module). */
    private fun renderMsi(full: String, unit: Int = 4, height: Int = 64): Bitmap {
        require(full.all { it.isDigit() })
        val modules = StringBuilder()
        modules.append("110") // START: wide bar + narrow space
        for (d in full) {
            val bcd = d.digitToInt().toString(2).padStart(4, '0')
            for (bit in bcd) {
                modules.append(if (bit == '1') "110" else "100")
            }
        }
        modules.append("1001") // STOP: narrow + wide + narrow
        val quiet = "0".repeat(10)
        val row = quiet + modules.toString() + quiet
        val w = row.length
        val bmp = Bitmap.createBitmap(w * unit, height, Bitmap.Config.ARGB_8888)
        val px = IntArray(w * unit * height)
        for (y in 0 until height) {
            for (mx in row.indices) {
                val color = if (row[mx] == '1') Color.BLACK else Color.WHITE
                for (k in 0 until unit) {
                    px[y * w * unit + mx * unit + k] = color
                }
            }
        }
        bmp.setPixels(px, 0, w * unit, 0, 0, w * unit, height)
        return bmp
    }

    private fun decode(
        bitmap: Bitmap,
        policy: ScannerConfig.MsiChecksumPolicy = ScannerConfig.MsiChecksumPolicy.MOD_10,
        robust: Boolean = false,
        stripChecksum: Boolean = true,
    ): DecodeOutcome = runBlocking {
        MsiPlesseyDecoder(
            checksumPolicy = policy,
            robustMode = robust,
            stripChecksum = stripChecksum,
        ).decode(ScanFrame(bitmap = bitmap))
    }

    @Test
    fun decodesCleanSynthetic_mod10() {
        val payload = "012345"
        val full = payload + MsiChecksumValidator.mod10Check(payload)
        val out = decode(renderMsi(full))
        assertTrue("expected Success, got $out", out is DecodeOutcome.Success)
        val best = (out as DecodeOutcome.Success).barcodes.maxBy { it.confidence }
        assertEquals(payload, best.rawValue)
        assertEquals(com.barcodescanner.sdk.domain.model.Symbology.MSI_PLESSEY, best.symbology)
        assertTrue(best.checksumStripped)
    }

    @Test
    fun decodesCleanSynthetic_mod10_10() {
        val payload = "0186477" // quakotml vector shape
        val c1 = MsiChecksumValidator.mod10Check(payload)
        val c2 = MsiChecksumValidator.mod10Check(payload + c1)
        val out = decode(
            renderMsi("$payload$c1$c2"),
            policy = ScannerConfig.MsiChecksumPolicy.MOD_10_10,
            robust = true,
        )
        assertTrue("expected Success, got $out", out is DecodeOutcome.Success)
        val best = (out as DecodeOutcome.Success).barcodes.maxBy { it.confidence }
        assertEquals(payload, best.rawValue)
    }

    @Test
    fun noStrip_emitsFullValidatedCodeword() {
        // Host-interprets contract: validation still gates (unvalidated windows
        // never emit), but rawValue carries the checks and nothing is claimed
        // stripped — e.g. double-Mod10 labels read under MOD_10 arrive whole.
        val payload = "012345"
        val full = payload + MsiChecksumValidator.mod10Check(payload)
        val out = decode(renderMsi(full), stripChecksum = false)
        assertTrue("expected Success, got $out", out is DecodeOutcome.Success)
        val best = (out as DecodeOutcome.Success).barcodes.maxBy { it.confidence }
        assertEquals(full, best.rawValue)
        assertFalse(best.checksumStripped)
    }

    @Test
    fun rejectsBlankImage() {
        val bmp = Bitmap.createBitmap(200, 60, Bitmap.Config.ARGB_8888)
        bmp.eraseColor(Color.WHITE)
        val out = decode(bmp)
        assertTrue("expected NotFound, got $out", out is DecodeOutcome.NotFound)
    }

    @Test
    fun fusedPipeline_decodesSynthetic() {
        // Full stack without camera/MLKit/native: preprocessing + orientation
        // expansion + fusion voting over an MSI-only registry.
        val payload = "012345"
        val full = payload + MsiChecksumValidator.mod10Check(payload)
        val bmp = renderMsi(full)
        val config = ScannerConfig.Builder()
            .only(com.barcodescanner.sdk.domain.model.Symbology.MSI_PLESSEY)
            .msiChecksumPolicy(ScannerConfig.MsiChecksumPolicy.MOD_10)
            .build()
        val registry = com.barcodescanner.sdk.domain.decoder.DecoderRegistry(
            listOf(MsiPlesseyDecoder(ScannerConfig.MsiChecksumPolicy.MOD_10)),
        )
        val pipeline = com.barcodescanner.sdk.domain.pipeline.PreprocessingPipeline.of(
            com.barcodescanner.sdk.domain.pipeline.DownscaleTransform(),
            com.barcodescanner.sdk.domain.pipeline.ContrastNormalizationTransform(),
        )
        val fused = com.barcodescanner.sdk.data.fusion.FusedDecoder(registry, pipeline, config)
        val out = runBlocking {
            fused.decode(ScanFrame(bitmap = bmp))
        }
        assertTrue("expected Success, got $out", out is DecodeOutcome.Success)
        val best = (out as DecodeOutcome.Success).barcodes.maxBy { it.confidence }
        assertEquals(payload, best.rawValue)
    }

    @Test
    fun minPayloadDigits_withholdsShortLuckyValidations() {
        // "80523" = Morovia "8052" + Mod10 check 3: valid under the default spec
        // floor, but a host scanning 6+ digit shelf SKUs must not emit it (short
        // parses need only 2 correlated observations to survive on blurry frames).
        val bmp = renderMsi("80523")
        val decoder = MsiPlesseyDecoder(
            checksumPolicy = ScannerConfig.MsiChecksumPolicy.MOD_10,
            minPayloadDigits = 6,
        )
        try {
            val out = runBlocking { decoder.decode(ScanFrame(bitmap = bmp)) }
            assertTrue("expected NotFound, got $out", out is DecodeOutcome.NotFound)
        } finally {
            decoder.close()
        }
        val defaultOut = decode(bmp)
        assertTrue("expected Success, got $defaultOut", defaultOut is DecodeOutcome.Success)
        assertEquals(
            "8052",
            (defaultOut as DecodeOutcome.Success).barcodes.maxBy { it.confidence }.rawValue,
        )
    }

    @Test
    fun nonePolicy_doesNotClaimChecksumStripped() {
        val payload = "012345"
        val out = decode(renderMsi(payload), policy = ScannerConfig.MsiChecksumPolicy.NONE, robust = true)
        assertTrue("expected Success, got $out", out is DecodeOutcome.Success)
        val best = (out as DecodeOutcome.Success).barcodes.maxBy { it.confidence }
        assertEquals(payload, best.rawValue)
        assertFalse("NONE strips nothing and must not claim it did", best.checksumStripped)
    }

    @Test
    fun stripGuardsRelative_acceptsSpecFraming() {
        val decoder = MsiPlesseyDecoder()
        // START wide+narrow, three '0' digits (bar-first elements, 4px narrow /
        // 8px wide), STOP narrow+wide+narrow. Three digits to clear MIN_DIGITS.
        val runs = mutableListOf(
            MsiPlesseyDecoder.Run(true, 8f), MsiPlesseyDecoder.Run(false, 4f),
        )
        repeat(3) {
            val zero = MsiCodeTable.DIGITS.getValue('0')
            for (i in zero.indices) {
                runs += MsiPlesseyDecoder.Run(i % 2 == 0, if (zero[i]) 8f else 4f)
            }
        }
        runs += MsiPlesseyDecoder.Run(true, 4f)
        runs += MsiPlesseyDecoder.Run(false, 8f)
        runs += MsiPlesseyDecoder.Run(true, 4f)
        val payload = decoder.stripGuardsRelative(runs)
        assertNotNull(payload)
        assertEquals(24, payload!!.size)
        assertEquals("000", decoder.softDecodeDigits(payload))
    }

    @Test
    fun softDecodeDigits_rejectsUniformNoise() {
        val decoder = MsiPlesseyDecoder()
        // Uniform runs carry no information: every digit fits equally -> err 2.0 > budget.
        val uniform = List(8) { i -> MsiPlesseyDecoder.Run(i % 2 == 0, 5f) }
        assertNull(decoder.softDecodeDigits(uniform))
    }

    @Test
    fun runsFromGray_recoversFractionalWidths() {
        val decoder = MsiPlesseyDecoder(robustMode = true)
        // Square-wave profile: narrow bars/spaces 4px @100, wide 8px, bg 220.
        val row = mutableListOf<Int>()
        row += List(12) { 220 } // quiet
        val seq = listOf(8 to true, 4 to false) // START wide bar + narrow space
        val all = seq.toMutableList()
        repeat(8) { i -> all += (if (i % 2 == 0) 4 else 8) to (i % 2 == 0) }
        all += listOf(4 to true, 8 to false, 4 to true) // STOP
        for ((w, black) in all) repeat(w) { row += if (black) 100 else 220 }
        row += List(12) { 220 }
        val runs = decoder.runsFromGray(row.toIntArray())
        assertNotNull(runs)
        // Narrow ≈4.0, wide ≈8.0 within tolerance (subpixel, no binarization involved).
        val narrows = runs!!.filter { it.length < 6f }.map { it.length }
        val wides = runs.filter { it.length >= 6f }.map { it.length }
        assertTrue("expected narrow cluster, got $narrows", narrows.isNotEmpty())
        assertTrue("expected wide cluster, got $wides", wides.isNotEmpty())
        assertTrue(narrows.all { it in 3.0f..5.5f })
        assertTrue(wides.all { it in 6.5f..9.5f })
    }
}
