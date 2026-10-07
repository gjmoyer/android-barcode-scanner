package com.barcodescanner.sdk.data.mlkit

import android.graphics.Bitmap
import android.graphics.Point
import android.graphics.Rect
import com.barcodescanner.sdk.domain.model.ScanFrame
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

/**
 * POC unit tests for [MlKitRegionLocalizer]: ML Kit as MSI *localizer*.
 *
 * The load-bearing behavior is that UNDECODED candidates (rawValue null — the
 * expected MSI case, since MSI is not a supported format) are kept as long as
 * they carry a bounding box, while box-less noise is dropped. Boxes stay valid
 * for cropping on every orientation candidate (crop space is bitmap space).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MlKitRegionLocalizerTest {

    private fun frame(attemptRotation: Int = 0) = ScanFrame(
        bitmap = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888),
        attemptRotation = attemptRotation,
    )

    private fun candidate(
        rawValue: String? = null,
        box: Rect? = Rect(4, 4, 60, 28),
        corners: Array<Point>? = arrayOf(
            Point(4, 4), Point(60, 4), Point(60, 28), Point(4, 28),
        ),
        format: Int = Barcode.FORMAT_UNKNOWN,
    ): Barcode {
        val barcode = mockk<Barcode>()
        every { barcode.rawValue } returns rawValue
        every { barcode.boundingBox } returns box
        every { barcode.cornerPoints } returns corners
        every { barcode.format } returns format
        return barcode
    }

    private fun localizerWith(vararg barcodes: Barcode): MlKitRegionLocalizer {
        val client = mockk<BarcodeScanner>()
        every { client.process(any<InputImage>()) } returns Tasks.forResult(barcodes.toList())
        return MlKitRegionLocalizer(
            clientProvider = { client },
            imageProvider = { _, _ -> mockk(relaxed = true) },
        )
    }

    @Test
    fun keepsUndecodedCandidate_withBox() = runBlocking {
        val localizer = localizerWith(candidate(rawValue = null))
        try {
            val regions = localizer.localize(frame())
            assertEquals(1, regions.size)
            assertEquals(Rect(4, 4, 60, 28), regions.single().boundingBox)
            assertEquals(4, regions.single().cornerPoints?.size)
            assertFalse(regions.single().decoded)
        } finally {
            localizer.close()
        }
    }

    @Test
    fun emptyRawValue_countsAsUndecoded() = runBlocking {
        // Observed on-device: potential barcodes arrive with "" (format -1),
        // not null — both must count as undecoded-but-localized.
        val localizer = localizerWith(candidate(rawValue = ""))
        try {
            val regions = localizer.localize(frame())
            assertEquals(1, regions.size)
            assertFalse(regions.single().decoded)
        } finally {
            localizer.close()
        }
    }

    @Test
    fun marksDecodedCandidate_decoded() = runBlocking {
        val localizer = localizerWith(
            candidate(rawValue = "123", format = Barcode.FORMAT_CODE_128),
        )
        try {
            val regions = localizer.localize(frame())
            assertEquals(1, regions.size)
            assertTrue(regions.single().decoded)
        } finally {
            localizer.close()
        }
    }

    @Test
    fun dropsBoxlessCandidate() = runBlocking {
        val localizer = localizerWith(candidate(box = null))
        try {
            assertTrue(localizer.localize(frame()).isEmpty())
        } finally {
            localizer.close()
        }
    }

    @Test
    fun keepsRotatedCandidate_cropSpaceIsBitmapSpace() = runBlocking {
        val localizer = localizerWith(candidate())
        try {
            // attemptRotation != 0: boxes still crop frame.bitmap correctly
            // (InputImage is built from it); only overlays would need mapping.
            val regions = localizer.localize(frame(attemptRotation = 90))
            assertEquals(1, regions.size)
        } finally {
            localizer.close()
        }
    }

    @Test
    fun emptyResult_isEmpty() = runBlocking {
        val localizer = localizerWith()
        try {
            assertTrue(localizer.localize(frame()).isEmpty())
        } finally {
            localizer.close()
        }
    }
}
