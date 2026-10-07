package com.barcodescanner.sdk.domain.pipeline

import android.graphics.Bitmap
import com.barcodescanner.sdk.domain.model.ScanFrame
import kotlinx.coroutines.cancel
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PreprocessingPipelineTest {

    private fun frame(): ScanFrame =
        ScanFrame(bitmap = Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888))

    @Test
    fun process_appliesTransformsInOrder() = runBlocking {
        val order = mutableListOf<String>()
        val pipeline = PreprocessingPipeline.of(
            FrameTransform { order += "first"; it },
            FrameTransform { order += "second"; it },
        )
        pipeline.process(frame())
        assertEquals(listOf("first", "second"), order)
    }

    @Test
    fun process_checksCancellationBetweenTransforms() = runBlocking {
        var ran = false
        val pipeline = PreprocessingPipeline.of(
            FrameTransform { ran = true; it },
        )
        val job = launch(start = CoroutineStart.UNDISPATCHED) {
            coroutineContext.cancel()
            pipeline.process(frame())
        }
        job.join()
        assertFalse("transform must not run in a cancelled context", ran)
    }
}
