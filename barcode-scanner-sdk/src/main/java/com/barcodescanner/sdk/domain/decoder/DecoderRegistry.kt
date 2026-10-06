package com.barcodescanner.sdk.domain.decoder

import com.barcodescanner.sdk.domain.model.Symbology

/**
 * Registry + composite router for [BarcodeDecoder] strategies.
 *
 * Responsibilities:
 *  - holds decoders in priority order (first registered = highest priority),
 *  - answers "which decoders can handle symbology X?" for the fusion pipeline,
 *  - stays open for extension: host apps or SDK updates call [register] without
 *    touching pipeline code.
 *
 * Thread-safety: copy-on-write snapshot; registration is rare, reads are hot.
 */
class DecoderRegistry(
    initial: List<BarcodeDecoder> = emptyList(),
) {
    private val lock = Any()
    private var decoders: List<BarcodeDecoder> = initial.toList()

    fun register(decoder: BarcodeDecoder) {
        synchronized(lock) {
            decoders = decoders.filterNot { it.name == decoder.name } + decoder
        }
    }

    fun unregister(name: String) {
        synchronized(lock) {
            decoders = decoders.filterNot { it.name == name }
        }
    }

    fun snapshot(): List<BarcodeDecoder> = synchronized(lock) { decoders }

    /** Decoders claiming [symbology], in priority order. */
    fun forSymbology(symbology: Symbology): List<BarcodeDecoder> =
        snapshot().filter { symbology in it.supportedSymbologies }

    fun supports(symbology: Symbology): Boolean = forSymbology(symbology).isNotEmpty()

    fun closeAll() = snapshot().forEach { runCatching { it.close() } }
}
