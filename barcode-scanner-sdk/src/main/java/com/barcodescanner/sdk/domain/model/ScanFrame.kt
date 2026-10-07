package com.barcodescanner.sdk.domain.model

import android.graphics.Bitmap

/**
 * A single camera frame entering the decode pipeline.
 *
 * Ownership: the [bitmap] is owned by the pipeline while the frame is in flight;
 * decoders must not mutate or recycle it. Orientation candidates share the base
 * bitmap for attemptRotation=0 and own their rotated copies otherwise — the
 * fusion decoder recycles rotated copies after use.
 *
 * @param bitmap Grayscale or ARGB frame. Decoders must not mutate it.
 * @param rotationDegrees Sensor rotation: clockwise degrees needed to make the
 *   ORIGINAL capture upright (one of 0, 90, 180, 270). 0 = already upright.
 * @param timestampMillis Elapsed-realtime millis (see SystemClock.elapsedRealtime);
 *   used for duplicate suppression and timeout policy. Wall-clock must NOT be used.
 * @param attemptRotation Extra clockwise physical rotation already applied to
 *   [bitmap] by the multi-orientation retry loop (0 for the base frame).
 */
data class ScanFrame(
    val bitmap: Bitmap,
    val rotationDegrees: Int = 0,
    val timestampMillis: Long = android.os.SystemClock.elapsedRealtime(),
    val attemptRotation: Int = 0,
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
     * Rotation hint to pass to ML Kit's InputImage.fromBitmap for THIS (possibly
     * pre-rotated) bitmap: sensor rotation minus the physical rotation already
     * applied. E.g. sensor=90, attempt=90 -> 0 (already upright, no hint needed).
     *
     * Previously this was (rotation + attempt) which double-rotated ML Kit frames;
     * fixed per review (P0-2).
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
