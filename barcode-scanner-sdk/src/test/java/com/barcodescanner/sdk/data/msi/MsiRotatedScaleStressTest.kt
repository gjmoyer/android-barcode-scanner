package com.barcodescanner.sdk.data.msi

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Point
import android.graphics.Rect
import android.graphics.RectF
import com.barcodescanner.sdk.domain.decoder.DecodeOutcome
import com.barcodescanner.sdk.domain.model.ScanFrame
import com.barcodescanner.sdk.domain.model.ScannerConfig
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.math.cos
import kotlin.math.sin

/**
 * POC stress matrix for the "ML Kit localizes, MSI decodes" evaluation.
 *
 * Two questions, answered with evidence instead of speculation:
 *
 * 1. **Where does today's full-frame scanline MSI fail?** Synthetic symbols at
 *    arbitrary tilts (0/15/30/45/60/90/180°) and varying module sizes / frame
 *    fills are decoded with the production [MsiPlesseyDecoder] in default and
 *    robust modes. The 4-orientation expansion + horizontal/vertical scanlines
 *    cover cardinals; off-cardinal tilts are expected to miss — this test
 *    quantifies exactly that.
 * 2. **Would a perfect localizer fix it?** For every tilted image the test
 *    replays the intended ML Kit split with a *ground-truth* region (axis-aligned
 *    bounding box + corner quad of the symbol, i.e. what `enableAllPotentialBarcodes`
 *    returns on a good day): [MsiRegionCropper.cropAndDeskew] rectifies the strip
 *    and the same decoder runs on the crop. The gap between (1) and (2) is the
 *    headroom available to region assistance; the remaining gap to 100% (if any)
 *    is resampling/chart loss the real ML Kit path would also pay.
 *
 * What this does NOT prove (needs on-device measurement, see `DeviceMsiTest`):
 * whether ML Kit's model actually returns a box on MSI symbols — MSI is not a
 * supported format and localization is best-effort. A follow-up device run should
 * log `MlKitRegionLocalizer.localize()` hit-rate per fixture before wiring the
 * ROI path into [com.barcodescanner.sdk.data.fusion.FusedDecoder].
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MsiRotatedScaleStressTest {

    // ------------------------------------------------------------ rendering

    /** Spec encoding (same as MsiDecoderRegressionTest): START + BCD + STOP + quiet. */
    private fun renderMsi(full: String, unit: Int = 4, height: Int = 80): Bitmap {
        require(full.all { it.isDigit() })
        val modules = StringBuilder()
        modules.append("110")
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

    /** Centers [bar] on a white canvas (simulates distance / small-in-frame). */
    private fun embedCentered(bar: Bitmap, canvasW: Int, canvasH: Int): Pair<Bitmap, Rect> {
        val canvas = Bitmap.createBitmap(canvasW, canvasH, Bitmap.Config.ARGB_8888)
        canvas.eraseColor(Color.WHITE)
        val left = (canvasW - bar.width) / 2
        val top = (canvasH - bar.height) / 2
        Canvas(canvas).drawBitmap(bar, left.toFloat(), top.toFloat(), null)
        return canvas to Rect(left, top, left + bar.width, top + bar.height)
    }

    /** Clockwise (screen, y-down) rotation about the canvas center, white-filled. */
    private fun rotateCanvas(src: Bitmap, degrees: Float): Bitmap {
        val m = Matrix().apply { postRotate(degrees, src.width / 2f, src.height / 2f) }
        val bounds = RectF(0f, 0f, src.width.toFloat(), src.height.toFloat())
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

    /** Clockwise-rotates rect corners about (cx, cy); y-down screen convention. */
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

    private fun decode(bmp: Bitmap, robust: Boolean): DecodeOutcome = runBlocking {
        MsiPlesseyDecoder(
            checksumPolicy = ScannerConfig.MsiChecksumPolicy.MOD_10,
            robustMode = robust,
        ).decode(ScanFrame(bitmap = bmp))
    }

    private fun valueOf(outcome: DecodeOutcome): String? =
        (outcome as? DecodeOutcome.Success)?.barcodes?.maxByOrNull { it.confidence }?.rawValue

    // ------------------------------------------------------------ cropper math

    @Test
    fun cropper_rawAngleFromCorners_returnsUnfoldedEdgeAngle() {
        // Horizontal quad -> ~0.
        assertEquals(
            0f,
            MsiRegionCropper.rawAngleFromCorners(
                listOf(Point(0, 0), Point(100, 0), Point(100, 20), Point(0, 20)),
            )!!, 0.5f,
        )
        // Vertical quad -> ~90 (unfolded; the transpose step in cropAndDeskew
        // consumes this, so no folding happens at the estimator).
        assertEquals(
            90f,
            MsiRegionCropper.rawAngleFromCorners(
                listOf(Point(0, 0), Point(20, 0), Point(20, 100), Point(0, 100)),
            )!!, 0.5f,
        )
        // 30° downhill quad -> ~30.
        val tilt = rotateCorners(Rect(0, 0, 100, 20), 50f, 10f, 30f)
        assertEquals(30f, MsiRegionCropper.rawAngleFromCorners(tilt)!!, 3f)
        // Null / short input -> null (caller falls back to plain crop).
        assertEquals(null, MsiRegionCropper.rawAngleFromCorners(null))
        assertEquals(
            null,
            MsiRegionCropper.rawAngleFromCorners(listOf(Point(0, 0), Point(1, 1))),
        )
    }

    @Test
    fun cropper_expandBox_padsAndClamps() {
        val padded = MsiRegionCropper.expandBox(Rect(100, 100, 200, 140), 1000, 1000)!!
        assertTrue(padded.left < 100 && padded.top < 100)
        assertTrue(padded.right > 200 && padded.bottom > 140)
        // Clamped at the image edge, never empty.
        val edge = MsiRegionCropper.expandBox(Rect(0, 0, 50, 50), 100, 100)!!
        assertEquals(Rect(0, 0, 57, 57), edge)
        assertEquals(null, MsiRegionCropper.expandBox(Rect(0, 0, 0, 0), 100, 100))
    }

    // ------------------------------------------------------------ stress matrix

    @Test
    fun stress_tiltAndScale_fullFrameVsPerfectRoi() {
        val payload = "1234567"
        val full = payload + MsiChecksumValidator.mod10Check(payload)
        val angles = listOf(0f, 15f, 30f, 45f, 60f, 90f, 180f)
        // Scale/fill combos: full-bleed, mid-size in frame, tiny modules.
        data class Scale(val name: String, val unit: Int, val canvasW: Int, val canvasH: Int)

        val scales = listOf(
            Scale("unit4_full", 4, -1, -1),
            Scale("unit4_half", 4, 900, 700),
            Scale("unit2_full", 2, -1, -1),
        )

        val lines = mutableListOf("angle|scale|fullDefault|fullRobust|crop(def/rob)|cropMsVsFullMs")
        var fullWins = 0
        var cropWins = 0
        var total = 0
        for (scale in scales) {
            for (angle in angles) {
                total++
                val bar = renderMsi(full, unit = scale.unit)
                val (canvas, rect) = if (scale.canvasW < 0) {
                    bar to Rect(0, 0, bar.width, bar.height)
                } else {
                    embedCentered(bar, scale.canvasW, scale.canvasH)
                }
                if (canvas !== bar) bar.recycle()
                val preW = canvas.width
                val preH = canvas.height
                val tilted = if (angle == 0f) canvas else rotateCanvas(canvas, angle)
                if (tilted !== canvas) canvas.recycle()

                val tFull = System.nanoTime()
                val fullOut = decode(tilted, robust = false)
                val fullMs = (System.nanoTime() - tFull) / 1_000_000
                val fullOk = valueOf(fullOut) == payload

                // Robust full-frame only on the full-bleed scale (runtime bound).
                val robustOut = if (scale.name == "unit4_full") decode(tilted, robust = true) else null
                val robustOk = robustOut?.let { valueOf(it) == payload }

                // Perfect-ROI replay: ground-truth quad rotated with the canvas,
                // re-based into the (possibly grown) tilted bitmap. The tilted
                // bitmap's content origin shifts by (-bounds.left, -bounds.top);
                // recompute by matching sizes: rotation about the original center
                // maps into the new canvas with the same relative offset.
                // The crop is tried in default mode first, then robust mode: the
                // crop is small so the robust retry is cheap, and it tells us
                // whether ROI+robust closes the worst resampling angles (45°).
                var cropOut: DecodeOutcome? = null
                var cropMs = 0L
                var deskew = 0f
                var cropMode = "-"
                val tCrop = System.nanoTime()
                val crop = if (angle == 0f) {
                    MsiRegionCropper.cropAndDeskew(tilted, rect, null)
                } else {
                    // Re-derive the symbol quad inside the tilted image: rotate the
                    // original rect about the pre-rotation canvas center, then shift
                    // by the canvas growth offset.
                    val quad = rotateCorners(rect, preW / 2f, preH / 2f, angle)
                    val ox = (tilted.width - preW) / 2
                    val oy = (tilted.height - preH) / 2
                    val shifted = quad.map { Point(it.x + ox, it.y + oy) }
                    val xs = shifted.map { it.x }
                    val ys = shifted.map { it.y }
                    val box = Rect(
                        xs.min().coerceAtLeast(0),
                        ys.min().coerceAtLeast(0),
                        xs.max().coerceAtMost(tilted.width - 1),
                        ys.max().coerceAtMost(tilted.height - 1),
                    )
                    MsiRegionCropper.cropAndDeskew(tilted, box, shifted)
                }
                try {
                    deskew = crop?.angleApplied ?: 0f
                    val first = crop?.let { decode(it.bitmap, robust = false) }
                    if (first?.let { valueOf(it) == payload } == true) {
                        cropOut = first
                        cropMode = "def"
                    } else {
                        val second = crop?.let { decode(it.bitmap, robust = true) }
                        cropOut = second
                        cropMode = if (second?.let { valueOf(it) == payload } == true) "rob" else "MISS"
                    }
                } finally {
                    crop?.bitmap?.recycle()
                }
                cropMs = (System.nanoTime() - tCrop) / 1_000_000
                val cropOk = cropOut?.let { valueOf(it) == payload } == true
                if (fullOk) fullWins++
                if (cropOk) cropWins++
                lines += "a=${angle.toInt()}|${scale.name}|" +
                    "${if (fullOk) "OK" else "MISS"}|" +
                    (robustOk?.let { if (it) "OK" else "MISS" } ?: "-") + "|" +
                    "$cropMode|${cropMs}vs${fullMs}ms|d=${deskew}"
                tilted.recycle()
            }
        }
        println("MSI_STRESS\n" + lines.joinToString("\n"))
        println("MSI_STRESS_SUMMARY fullDefault=$fullWins/$total roiCrop=$cropWins/$total")

        // Hard gates (cropper correctness, not ML Kit hit-rate):
        // the axis-aligned perfect crop of an untilted symbol must decode.
        val bar = renderMsi(full, unit = 4)
        try {
            val crop = MsiRegionCropper.cropAndDeskew(
                bar, Rect(0, 0, bar.width, bar.height), null,
            )
            assertNotNull("plain crop of clean symbol must succeed", crop)
            try {
                assertEquals(payload, valueOf(decode(crop!!.bitmap, robust = false)))
            } finally {
                crop!!.bitmap.recycle()
            }
        } finally {
            bar.recycle()
        }
        // The evaluation claim: with perfect localization the decoder recovers
        // strictly more of the matrix than full-frame default mode.
        assertTrue(
            "expected ROI path to beat full-frame (crop=$cropWins full=$fullWins). Table:\n" +
                lines.joinToString("\n"),
            cropWins >= fullWins,
        )
    }
}
