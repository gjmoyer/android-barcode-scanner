package com.barcodescanner.sdk.api

import com.barcodescanner.sdk.domain.model.DecodedBarcode

/**
 * Public scan result delivered to host apps.
 *
 * Sealed so `when` is exhaustive and future result types (e.g. multi-barcode
 * burst) don't break existing consumers.
 */
sealed interface ScanResult {
    data class Success(
        val barcode: DecodedBarcode,
        /** All candidates from the fused burst, best-first. */
        val allCandidates: List<DecodedBarcode> = listOf(barcode),
        /**
         * True when decoded from a physically rotated candidate or an
         * upside-down (reversed-direction) MSI scanline.
         * Fixed: previously `confidence < 1f` made every ML Kit (0.95) hit report true.
         */
        val fromRotatedFrame: Boolean = barcode.isUpsideDown,
    ) : ScanResult

    data class NotFound(val frameTimestampMillis: Long) : ScanResult
    data class Failure(
        val message: String,
        val cause: Throwable? = null,
        val kind: ErrorKind = ErrorKind.UNKNOWN,
    ) : ScanResult

    enum class ErrorKind { TRANSIENT, FATAL, TIMEOUT, UNKNOWN }
}
