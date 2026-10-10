package com.barcodescanner.sdk.data.msi

/**
 * Consecutive-observation gate for MSI bar reads.
 *
 * Live camera frames produce transient false positives (motion-blur phantoms
 * that pass checksum by luck); requiring the same payload twice kills them.
 *
 * Extracted from [MsiNativeDecoder] so the logic is unit-testable on the JVM
 * (the decoder itself can't load its native library under Robolectric) and so
 * each decode path can own its gate: the ROI-crop path and the full-frame
 * path previously shared one gate inside a single decoder instance, so a miss
 * on one path reset the count the other path was building and alternating
 * miss/hit never confirmed. Sharing is now impossible by construction — a
 * gate instance is single-path state.
 *
 * Thread-safe: every method is synchronized. The decoder runs on a shared
 * dispatcher and concurrent one-shot scans must not corrupt the count (a torn
 * update can only cost an extra withheld frame or one early emit, but there
 * is no reason to allow it at all).
 *
 * @param required consecutive agreements required before emitting. 1 disables
 *   gating (single-frame emit).
 */
internal class MsiVoteGate(val required: Int = 2) {

    private var lastPayload: String? = null
    private var count: Int = 0

    /**
     * Records one validated [payload] observation.
     *
     * @return true when the payload may emit (confirmed [required] times in a
     *   row). When true the gate auto-resets, so an unchanged value does not
     *   re-emit until it changes and returns — matching the previous inline
     *   semantics exactly.
     */
    @Synchronized
    fun observe(payload: String): Boolean {
        if (required <= 1) return true
        if (payload == lastPayload) {
            count++
        } else {
            lastPayload = payload
            count = 1
        }
        if (count < required) return false
        resetLocked()
        return true
    }

    /** A non-emit (native miss, short payload, policy mismatch) clears pending state. */
    @Synchronized
    fun reset() {
        resetLocked()
    }

    /** Consecutive agreements banked for the current pending payload (forensics only). */
    @Synchronized
    fun progress(): Pair<String?, Int> = lastPayload to count

    private fun resetLocked() {
        lastPayload = null
        count = 0
    }
}
