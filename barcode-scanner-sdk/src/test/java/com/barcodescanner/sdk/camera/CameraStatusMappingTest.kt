package com.barcodescanner.sdk.camera

import androidx.camera.core.CameraState
import androidx.lifecycle.Lifecycle
import com.barcodescanner.sdk.api.CameraStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CameraStatusMappingTest {

    private fun map(state: CameraState, lifecycle: Lifecycle.State = Lifecycle.State.RESUMED) =
        CameraScanManager.toCameraStatus(state, lifecycle)

    @Test
    fun lifecycleTypes_mapToHostStatus() {
        assertEquals(CameraStatus.InUse, map(CameraState.create(CameraState.Type.PENDING_OPEN)))
        assertEquals(CameraStatus.Starting, map(CameraState.create(CameraState.Type.OPENING)))
        assertEquals(CameraStatus.Running, map(CameraState.create(CameraState.Type.OPEN)))
        // Transitional: rebind, lifecycle stop, or reopen after a recoverable error.
        assertNull(map(CameraState.create(CameraState.Type.CLOSING)))
        assertNull(map(CameraState.create(CameraState.Type.CLOSED)))
    }

    @Test
    fun inUseErrors_mapToInUse() {
        for (code in listOf(CameraState.ERROR_CAMERA_IN_USE, CameraState.ERROR_MAX_CAMERAS_IN_USE)) {
            val state = CameraState.create(CameraState.Type.PENDING_OPEN, CameraState.StateError.create(code))
            assertEquals(CameraStatus.InUse, map(state))
        }
    }

    @Test
    fun criticalError_failsOnlyInForeground() {
        val error = CameraState.StateError.create(CameraState.ERROR_CAMERA_FATAL_ERROR)
        val state = CameraState.create(CameraState.Type.CLOSING, error)
        assertTrue(map(state, Lifecycle.State.RESUMED) is CameraStatus.Failed)
        assertTrue(map(state, Lifecycle.State.STARTED) is CameraStatus.Failed)
        // Background: policy closed the camera; CameraX reopens it on the next start.
        assertNull(map(state, Lifecycle.State.CREATED))
    }

    @Test
    fun recoverableError_followsStateType() {
        val error = CameraState.StateError.create(CameraState.ERROR_OTHER_RECOVERABLE_ERROR)
        assertEquals(CameraStatus.Starting, map(CameraState.create(CameraState.Type.OPENING, error)))
    }
}
