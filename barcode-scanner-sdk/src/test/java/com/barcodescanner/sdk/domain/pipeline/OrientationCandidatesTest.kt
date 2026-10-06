package com.barcodescanner.sdk.domain.pipeline

import android.graphics.Bitmap
import com.barcodescanner.sdk.domain.model.ScanFrame
import org.junit.Assert.*
import org.junit.Test
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.junit.runner.RunWith

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class OrientationCandidatesTest {

    @Test
    fun expand_respectsMaxOrientations() {
        val bmp = Bitmap.createBitmap(100, 50, Bitmap.Config.ARGB_8888)
        val frame = ScanFrame(bitmap = bmp)
        assertEquals(1, OrientationCandidates.expand(frame, 1).size)
        assertEquals(2, OrientationCandidates.expand(frame, 2).size)
        assertEquals(4, OrientationCandidates.expand(frame, 4).size)
    }

    @Test
    fun expand_priorityStartsUprightThenUpsideDown() {
        val bmp = Bitmap.createBitmap(100, 50, Bitmap.Config.ARGB_8888)
        val frame = ScanFrame(bitmap = bmp)
        val expanded = OrientationCandidates.expand(frame, 4)
        assertEquals(0, expanded[0].attemptRotation)
        assertEquals(180, expanded[1].attemptRotation)
    }

    @Test
    fun priorityOrder_compensatesSensorRotation() {
        assertArrayEquals(
            intArrayOf(0, 180, 90, 270),
            OrientationCandidates.priorityOrder(0).toIntArray(),
        )
        assertArrayEquals(
            intArrayOf(90, 270, 180, 0),
            OrientationCandidates.priorityOrder(90).toIntArray(),
        )
        assertArrayEquals(
            intArrayOf(180, 0, 270, 90),
            OrientationCandidates.priorityOrder(180).toIntArray(),
        )
        assertArrayEquals(
            intArrayOf(270, 90, 0, 180),
            OrientationCandidates.priorityOrder(270).toIntArray(),
        )
    }

    @Test
    fun expand_portraitFrame_startsWithUprightCompensation() {
        // Portrait camera capture: buffer stored sideways, rotationDegrees=90.
        val bmp = Bitmap.createBitmap(100, 50, Bitmap.Config.ARGB_8888)
        val frame = ScanFrame(bitmap = bmp, rotationDegrees = 90)
        val expanded = OrientationCandidates.expand(frame, 4)
        assertEquals(90, expanded[0].attemptRotation)
        // The first candidate is already upright: no ML Kit rotation hint left.
        assertEquals(0, expanded[0].effectiveRotation)
        assertEquals(270, expanded[1].attemptRotation)
        assertEquals(180, expanded[1].effectiveRotation)
    }

    @Test
    fun expand_portraitDefaultMode_triesOnlyUprightViews() {
        // maxOrientationsTried=2 previously meant 0°/180° (sideways for a
        // portrait frame). It must now mean the two upright-axis views.
        val bmp = Bitmap.createBitmap(100, 50, Bitmap.Config.ARGB_8888)
        val frame = ScanFrame(bitmap = bmp, rotationDegrees = 270)
        val expanded = OrientationCandidates.expand(frame, 2)
        assertEquals(listOf(270, 90), expanded.map { it.attemptRotation })
    }

    @Test
    fun rotate_180PreservesDimensions() {
        val bmp = Bitmap.createBitmap(100, 50, Bitmap.Config.ARGB_8888)
        val rotated = OrientationCandidates.rotate(bmp, 180)
        assertEquals(100, rotated.width)
        assertEquals(50, rotated.height)
    }

    @Test
    fun rotate_90SwapsDimensions() {
        val bmp = Bitmap.createBitmap(100, 50, Bitmap.Config.ARGB_8888)
        val rotated = OrientationCandidates.rotate(bmp, 90)
        assertEquals(50, rotated.width)
        assertEquals(100, rotated.height)
    }
}
