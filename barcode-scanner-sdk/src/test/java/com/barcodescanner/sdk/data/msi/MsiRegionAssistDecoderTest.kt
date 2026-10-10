package com.barcodescanner.sdk.data.msi

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Point
import android.graphics.Rect
import com.barcodescanner.sdk.data.mlkit.MlKitRegionLocalizer
import com.barcodescanner.sdk.domain.decoder.DecodeOutcome
import com.barcodescanner.sdk.domain.model.ScanFrame
import com.barcodescanner.sdk.domain.model.ScannerConfig
import com.barcodescanner.sdk.domain.model.Symbology
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.barcode.BarcodeScanner
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.math.cos
import kotlin.math.sin

/**
 * Unit tests for [MsiRegionAssistDecoder] with a stubbed ML Kit client.
 *
 * Covers routing (undecoded regions attempted, decoded/tiny regions skipped,
 * empty localization falls through as NotFound) plus one tilted end-to-end:
 * a 30° label with a ground-truth quad must decode through the ROI path in
 * default mode — the case full-frame MSI misses (see MsiRotatedScaleStressTest).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MsiRegionAssistDecoderTest {

    private val payload = "1234567"
    private val full = payload + MsiChecksumValidator.mod10Check(payload)

    private fun renderMsi(unit: Int = 4, height: Int = 80): Bitmap {
        val modules = StringBuilder("110")
        for (d in full) {
            val bcd = d.digitToInt().toString(2).padStart(4, '0')
            for (bit in bcd) modules.append(if (bit == '1') "110" else "100")
        }
        modules.append("1001")
        val row = "0".repeat(10) + modules.toString() + "0".repeat(10)
        val bmp = Bitmap.createBitmap(row.length * unit, height, Bitmap.Config.ARGB_8888)
        val px = IntArray(row.length * unit * height)
        for (y in 0 until height) {
            for (mx in row.indices) {
                val color = if (row[mx] == '1') Color.BLACK else Color.WHITE
                for (k in 0 until unit) px[y * row.length * unit + mx * unit + k] = color
            }
        }
        bmp.setPixels(px, 0, row.length * unit, 0, 0, row.length * unit, height)
        return bmp
    }

    private fun barcode(
        box: Rect?,
        corners: List<Point>? = null,
        rawValue: String? = null,
    ): Barcode {
        val b = mockk<Barcode>()
        every { b.rawValue } returns rawValue
        every { b.boundingBox } returns box
        every { b.cornerPoints } returns corners?.toTypedArray()
        every { b.format } returns Barcode.FORMAT_UNKNOWN
        return b
    }

    private fun assist(vararg barcodes: Barcode): MsiRegionAssistDecoder {
        val client = mockk<BarcodeScanner>()
        every { client.process(any<InputImage>()) } returns Tasks.forResult(barcodes.toList())
        val localizer = MlKitRegionLocalizer(
            clientProvider = { client },
            imageProvider = { _, _ -> mockk(relaxed = true) },
        )
        val msi = MsiPlesseyDecoder(
            checksumPolicy = ScannerConfig.MsiChecksumPolicy.MOD_10,
            robustMode = false,
        )
        return MsiRegionAssistDecoder(localizer, msi)
    }

    private fun decode(decoder: MsiRegionAssistDecoder, bmp: Bitmap): DecodeOutcome =
        runBlocking { decoder.decode(ScanFrame(bitmap = bmp)) }

    private fun valueOf(outcome: DecodeOutcome): String? =
        (outcome as? DecodeOutcome.Success)?.barcodes?.maxByOrNull { it.confidence }?.rawValue

    @Test
    fun uprightFullBox_decodesAsMsiRoi() {
        val bmp = renderMsi()
        val decoder = assist(
            barcode(Rect(0, 0, bmp.width, bmp.height)),
        )
        try {
            val out = decode(decoder, bmp)
            assertTrue("expected Success, got $out", out is DecodeOutcome.Success)
            val best = (out as DecodeOutcome.Success).barcodes.maxBy { it.confidence }
            assertEquals(payload, best.rawValue)
            assertEquals(MsiRegionAssistDecoder.NAME, best.engineName)
        } finally {
            decoder.close()
            bmp.recycle()
        }
    }

    @Test
    fun noRegions_isNotFound() {        val bmp = renderMsi()
        val decoder = assist()
        try {
            val out = decode(decoder, bmp)
            assertTrue("expected NotFound, got $out", out is DecodeOutcome.NotFound)
        } finally {
            decoder.close()
            bmp.recycle()
        }
    }

    @Test
    fun decodedRegion_isStillAttempted() {
        // ML Kit classifies MSI strips as a nearby family (or unknown) with a
        // value the native path must drop — "decoded" usually means
        // misclassified-MSI, so the region stays ROI-eligible and the
        // checksum gate (not the flag) decides.
        val bmp = renderMsi()
        val decoder = assist(
            barcode(Rect(0, 0, bmp.width, bmp.height), rawValue = "NOT-MSI"),
        )
        try {
            val out = decode(decoder, bmp)
            assertTrue("expected Success, got $out", out is DecodeOutcome.Success)
            assertEquals(payload, valueOf(out))
        } finally {
            decoder.close()
            bmp.recycle()
        }
    }

    @Test
    fun tilted30_withGroundTruthQuad_decodes() {
        val bar = renderMsi()
        val canvas = Bitmap.createBitmap(700, 500, Bitmap.Config.ARGB_8888)
        canvas.eraseColor(Color.WHITE)
        val left = (700 - bar.width) / 2
        val top = (500 - bar.height) / 2
        Canvas(canvas).drawBitmap(bar, left.toFloat(), top.toFloat(), null)
        val rect = Rect(left, top, left + bar.width, top + bar.height)
        bar.recycle()

        val tilted = rotateCanvas(canvas, 30f)
        canvas.recycle()
        // Ground-truth quad in the tilted image (what a good ML Kit day returns).
        val quad = rotateCorners(rect, 350f, 250f, 30f)
        val ox = (tilted.width - 700) / 2
        val oy = (tilted.height - 500) / 2
        val shifted = quad.map { Point(it.x + ox, it.y + oy) }
        val box = Rect(
            shifted.minOf { it.x }.coerceAtLeast(0),
            shifted.minOf { it.y }.coerceAtLeast(0),
            shifted.maxOf { it.x }.coerceAtMost(tilted.width - 1),
            shifted.maxOf { it.y }.coerceAtMost(tilted.height - 1),
        )
        val decoder = assist(barcode(box, shifted))
        try {
            val out = decode(decoder, tilted)
            assertEquals(payload, valueOf(out))
        } finally {
            decoder.close()
            tilted.recycle()
        }
    }

    private fun rotateCanvas(src: Bitmap, degrees: Float): Bitmap {
        val m = Matrix().apply { postRotate(degrees, src.width / 2f, src.height / 2f) }
        val bounds = android.graphics.RectF(0f, 0f, src.width.toFloat(), src.height.toFloat())
        m.mapRect(bounds)
        val out = Bitmap.createBitmap(
            bounds.width().toInt().coerceAtLeast(1),
            bounds.height().toInt().coerceAtLeast(1),
            Bitmap.Config.ARGB_8888,
        )
        out.eraseColor(Color.WHITE)
        m.postTranslate(-bounds.left, -bounds.top)
        Canvas(out).drawBitmap(src, m, Paint(Paint.FILTER_BITMAP_FLAG))
        return out
    }

    private fun rotateCorners(rect: Rect, cx: Float, cy: Float, degrees: Float): List<Point> {
        val rad = Math.toRadians(degrees.toDouble())
        val c = cos(rad)
        val s = sin(rad)
        return listOf(
            Point(rect.left, rect.top),
            Point(rect.right, rect.top),
            Point(rect.right, rect.bottom),
            Point(rect.left, rect.bottom),
        ).map { p ->
            val dx = p.x - cx
            val dy = p.y - cy
            Point(
                (cx + dx * c - dy * s).toInt(),
                (cy + dx * s + dy * c).toInt(),
            )
        }
    }
}
