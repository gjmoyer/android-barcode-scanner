package com.barcodescanner.sdk.camera

import android.graphics.Rect
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
}
