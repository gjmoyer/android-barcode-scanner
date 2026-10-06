package com.barcodescanner.sdk.domain.decoder

import com.barcodescanner.sdk.domain.model.DecodedBarcode
import com.barcodescanner.sdk.domain.model.ScanFrame
import com.barcodescanner.sdk.domain.model.Symbology

/**
 * Strategy interface for every decode engine.
 *
 * Each implementation wraps exactly one engine (ML Kit, zxing-cpp,
 * custom MSI Plessey, future engines) and must:
 *  - be thread-safe (pipelines call decoders off the main thread, possibly in parallel),
 *  - never throw for "barcode not found" (return empty list / failure result),
 *  - only throw [DecoderException] for unrecoverable errors (native lib missing, closed client).
 *
 * To add a new symbology or engine: implement this interface and register it
 * with [DecoderRegistry]. No existing code changes required (Open/Closed Principle).
 */
interface BarcodeDecoder {
    /** Stable engine identifier used for logging, metrics and result attribution. */
    val name: String

    /** Symbologies this decoder claims to support. Used for routing + fast skip. */
    val supportedSymbologies: Set<Symbology>

    /**
     * Attempts to decode [frame].
     *
     * @return [DecodeOutcome] — success carries one or more barcodes, failure carries a reason
     *   (used by [FusedDecoder][com.barcodescanner.sdk.data.fusion.FusedDecoder] to decide fallback).
     */
    suspend fun decode(frame: ScanFrame): DecodeOutcome

    /** Releases native clients / executors. Idempotent. */
    fun close() = Unit
}

/** Result of a single decoder attempt. */
sealed interface DecodeOutcome {
    data class Success(val barcodes: List<DecodedBarcode>) : DecodeOutcome {
        init {
            require(barcodes.isNotEmpty()) { "Success must carry at least one barcode" }
        }
    }

    data class NotFound(val reason: String = "no barcode detected") : DecodeOutcome
    data class Error(val cause: DecoderException, val recoverable: Boolean = false) : DecodeOutcome
}

/**
 * Decoder failure (native crash, misconfiguration, cancelled scope).
 * RuntimeException so Kotlin callers are not forced to catch; fusion maps
 * recoverable=true to "try next engine" and recoverable=false to abort.
 */
class DecoderException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)
