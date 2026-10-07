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
}
