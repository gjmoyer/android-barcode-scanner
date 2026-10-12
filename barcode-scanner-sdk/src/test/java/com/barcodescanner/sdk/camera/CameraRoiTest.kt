package com.barcodescanner.sdk.camera

import android.graphics.Rect
import com.barcodescanner.sdk.data.frame.NativeFrame
import com.barcodescanner.sdk.domain.model.ScannerConfig
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CameraRoiTest {

    private val sensor = Rect(0, 0, 1280, 720)

    private fun region(w: Float, h: Float) = ScannerConfig.ScanRegion(w, h)

    @Test
    fun nullRegion_returnsSensorCopy() {
        val out = CameraScanManager.roiToBuffer(sensor, 1280, 720, 0, null)
        assertEquals(sensor, out)
    }

    @Test
    fun landscape_centeredFractions() {
        // Upright 0.9×0.5 box on a 1280×720 buffer.
        val out = CameraScanManager.roiToBuffer(sensor, 1280, 720, 0, region(0.9f, 0.5f))
        assertEquals(Rect(64, 180, 1216, 540), out)
    }

    @Test
    fun portrait90_swapsAxes() {
        // Same upright box, buffer stored transposed: fractions swap.
        val out = CameraScanManager.roiToBuffer(sensor, 1280, 720, 90, region(0.9f, 0.5f))
        // fw=0.5 → cw=640 centered → x 320-960; fh=0.9 → ch=648 → y 36-684.
        assertEquals(Rect(320, 36, 960, 684), out)
    }

    @Test
    fun rotation180_sameAsZero() {
        val a = CameraScanManager.roiToBuffer(sensor, 1280, 720, 0, region(0.9f, 0.5f))
        val b = CameraScanManager.roiToBuffer(sensor, 1280, 720, 180, region(0.9f, 0.5f))
        assertEquals(a, b)
    }

    @Test
    fun sensorOffset_respected() {
        // HAL crop smaller than the buffer: ROI applies within it.
        val cropped = Rect(100, 100, 1100, 620)
        val out = CameraScanManager.roiToBuffer(cropped, 1280, 720, 0, region(0.5f, 0.5f))
        assertEquals(Rect(350, 230, 850, 490), out)
    }

    @Test
    fun fullCoverage_returnsSensor() {
        val out = CameraScanManager.roiToBuffer(sensor, 1280, 720, 0, region(1f, 1f))
        assertEquals(sensor, out)
    }

    // ------------------------------------------------- viewBoxToBuffer

    @Test
    fun view_sameAspect_matchesLegacyBox() {
        // Portrait phone, view aspect == content aspect: no display crop, so the
        // view box equals the legacy sensor box (0.9×0.5 → x 320-960, y 36-684).
        val out = CameraScanManager.viewBoxToBuffer(sensor, 1280, 720, 90, 720, 1280, region(0.9f, 0.5f))
        assertEquals(Rect(320, 36, 960, 684), out)
    }

    @Test
    fun view_widerThanContent_cropsTopBottom() {
        // Landscape buffer shown on a wider view: FILL_CENTER crops top/bottom.
        // visH = (1280/720)/(1000/400) = 0.711; box 0.9×0.5 → x 64-1216, y 232-488.
        val out = CameraScanManager.viewBoxToBuffer(sensor, 1280, 720, 0, 1000, 400, region(0.9f, 0.5f))
        assertEquals(Rect(64, 232, 1216, 488), out)
    }

    @Test
    fun view_nullRegion_decodesOnlyWhatIsVisible() {
        // Same geometry, no box: the visible rect itself — never sensor strips
        // the user cannot see (y 103-616, full width; float floor, not a bug).
        val out = CameraScanManager.viewBoxToBuffer(sensor, 1280, 720, 0, 1000, 400, null)
        assertEquals(Rect(0, 103, 1280, 616), out)
    }

    @Test
    fun view_unknownSize_fallsBackToLegacy() {
        val expected = CameraScanManager.roiToBuffer(sensor, 1280, 720, 90, region(0.9f, 0.5f))
        assertEquals(
            expected,
            CameraScanManager.viewBoxToBuffer(sensor, 1280, 720, 90, 0, 0, region(0.9f, 0.5f)),
        )
    }

    @Test
    fun view_rotation180_matchesZero() {
        val a = CameraScanManager.viewBoxToBuffer(sensor, 1280, 720, 0, 1000, 400, region(0.9f, 0.5f))
        val b = CameraScanManager.viewBoxToBuffer(sensor, 1280, 720, 180, 1000, 400, region(0.9f, 0.5f))
        assertEquals(a, b)
    }

    @Test
    fun view_sensorOffset_respected() {
        // HAL crop + display crop compose: box lives inside both.
        val cropped = Rect(100, 100, 1100, 620)
        val out = CameraScanManager.viewBoxToBuffer(cropped, 1280, 720, 0, 1000, 400, region(0.5f, 0.5f))
        assertEquals(Rect(320, 232, 960, 488), out)
    }

    @Test
    fun evenCrop_trimsOddEdgesInsideBox() {
        assertEquals(Rect(10, 21, 114, 79), CameraScanManager.evenCrop(Rect(10, 21, 115, 80)))
        assertEquals(Rect(0, 0, 640, 360), CameraScanManager.evenCrop(Rect(0, 0, 640, 360)))
    }

    @Test
    fun evenCrop_degenerate_isNull() {
        assertEquals(null, CameraScanManager.evenCrop(Rect(0, 0, 17, 15)))
        assertEquals(Rect(0, 0, 16, 16), CameraScanManager.evenCrop(Rect(0, 0, 17, 17)))
    }

    @Test
    fun clampToBuffer_keepsCropInsidePlane_neverEmpty() {
        assertEquals(Rect(0, 0, 1280, 720), CameraScanManager.clampToBuffer(Rect(-8, -8, 1300, 740), 1280, 720))
        // A crop starting at/after the edge used to make coerceIn throw.
        assertEquals(Rect(1279, 719, 1280, 720), CameraScanManager.clampToBuffer(Rect(1280, 720, 1290, 730), 1280, 720))
    }

    @Test
    fun normalizeRotation_onlyQuarterTurns() {
        assertEquals(listOf(0, 90, 180, 270, 0, 0, 0), listOf(0, 90, 180, 270, 45, -90, 360).map(CameraScanManager::normalizeRotation))
    }

    @Test
    fun portrait1080p_wideBox_yieldsEvenUprightCrop() {
        // 16:9 1920x1080 analysis, sensor rotated 90, 1080x2400 (20:9) preview,
        // 0.9 x 0.2 box: the geometry the aim-box regression simulated (~800x384).
        val buf = Rect(0, 0, 1920, 1080)
        val box = CameraScanManager.viewBoxToBuffer(buf, 1920, 1080, 90, 1080, 2400, region(0.9f, 0.2f))
        val crop = CameraScanManager.evenCrop(box)!!
        assertEquals(0, crop.width() % 2)
        assertEquals(0, crop.height() % 2)
        assertEquals(776, NativeFrame.uprightWidth(crop.width(), crop.height(), 90))
        assertEquals(384, NativeFrame.uprightHeight(crop.width(), crop.height(), 90))
    }
}
