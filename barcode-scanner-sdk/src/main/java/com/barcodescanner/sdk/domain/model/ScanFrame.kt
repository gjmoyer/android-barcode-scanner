package com.barcodescanner.sdk.domain.model

import android.graphics.Bitmap

/**
 * A one-shot still for [scanFrame][com.barcodescanner.sdk.api.BarcodeScannerFacade.scanFrame].
 *
 * Ownership: the caller keeps [bitmap]; the SDK reads it once (native gray
 * conversion) and never mutates or recycles it.
 *
 * @param bitmap Any config; ARGB_8888, RGB_565 and ALPHA_8 (read as luma) are
 *   read directly, others (HARDWARE, RGBA_F16, ...) through a temporary
 *   ARGB_8888 copy.
 * @param rotationDegrees Sensor rotation: clockwise degrees needed to make the
 *   ORIGINAL capture upright (one of 0, 90, 180, 270). 0 = already upright.
 * @param timestampMillis Elapsed-realtime millis (see SystemClock.elapsedRealtime);
 *   used for duplicate suppression and timeout policy. Wall-clock must NOT be used.
 * @param attemptRotation Extra clockwise physical rotation already applied to
 *   [bitmap] by the caller (0 for an unrotated capture); the still is turned
 *   upright by [effectiveRotation] and a read reports
 *   [ScanResult.Success.fromRotatedFrame][com.barcodescanner.sdk.api.ScanResult.Success.fromRotatedFrame].
 * @param allowFallbackSweep Ignored; kept for source compatibility. The
 *   Kotlin fallback sweep it gated was retired with the native frame path —
 *   every still now gets the same single native pass.
 */
data class ScanFrame(
    val bitmap: Bitmap,
    val rotationDegrees: Int = 0,
    val timestampMillis: Long = android.os.SystemClock.elapsedRealtime(),
    val attemptRotation: Int = 0,
    val allowFallbackSweep: Boolean = true,
) {
    init {
        require(rotationDegrees in setOf(0, 90, 180, 270)) {
            "rotationDegrees must be one of 0, 90, 180, 270, was $rotationDegrees"
        }
        require(attemptRotation in setOf(0, 90, 180, 270)) {
            "attemptRotation must be one of 0, 90, 180, 270, was $attemptRotation"
        }
    }

    /**
     * Clockwise rotation that turns THIS (possibly pre-rotated) bitmap upright:
     * sensor rotation minus the physical rotation already applied. E.g.
     * sensor=90, attempt=90 -> 0 (already upright). The native still
     * conversion rotates by exactly this.
     */
    val effectiveRotation: Int get() = (rotationDegrees - attemptRotation + 360) % 360

    /** True when this candidate was physically rotated from the base frame. */
    val isRotatedCandidate: Boolean get() = attemptRotation != 0

    /**
     * This candidate's rotation relative to the sensor-compensated upright view.
     * 0 = upright, 90/270 = sideways, 180 = upside-down. Use this (never a raw
     * `attemptRotation` compare) for orientation flags: for a portrait frame
     * (`rotationDegrees=90`) the upside-down candidate is attemptRotation=270.
     */
    val relativeRotation: Int get() = (attemptRotation - rotationDegrees + 360) % 360

    /** True when this candidate is the upside-down (180° relative) view. */
    val isUpsideDownCandidate: Boolean get() = relativeRotation == 180
}
