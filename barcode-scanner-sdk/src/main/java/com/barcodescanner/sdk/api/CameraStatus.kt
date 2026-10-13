package com.barcodescanner.sdk.api

/**
 * Live camera session state published on [BarcodeScannerFacade.cameraStatus],
 * so hosts can explain a black preview instead of leaving users guessing.
 *
 * Best effort: derived from CameraX's asynchronously posted camera state.
 */
sealed interface CameraStatus {
    /** No camera session: never started, stopped, or the scanner is closed. */
    data object Idle : CameraStatus

    /** Binding to or opening the camera. */
    data object Starting : CameraStatus

    /** Camera open and streaming frames. */
    data object Running : CameraStatus

    /**
     * Waiting for the camera while another app or client holds it. Recoverable:
     * CameraX opens the camera automatically once it is released.
     */
    data object InUse : CameraStatus

    /**
     * The session cannot recover by itself (provider or bind failure, critical
     * camera error while in the foreground, or no native decoder for this ABI
     * so the preview runs without decoding). Final for its session: later
     * camera states never replace it. Stop the camera and offer a retry.
     */
    data class Failed(val reason: String, val cause: Throwable? = null) : CameraStatus
}
