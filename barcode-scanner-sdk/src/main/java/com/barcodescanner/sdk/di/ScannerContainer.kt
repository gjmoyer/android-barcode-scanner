package com.barcodescanner.sdk.di

import android.content.Context
import com.barcodescanner.sdk.data.fusion.LumaEngine
import com.barcodescanner.sdk.data.fusion.LumaFusion
import com.barcodescanner.sdk.data.mlkit.MLKitDecoder
import com.barcodescanner.sdk.data.msi.MsiNativeDecoder
import com.barcodescanner.sdk.data.zxingcpp.ZXingCppDecoder
import com.barcodescanner.sdk.domain.decoder.DecoderRegistry
import com.barcodescanner.sdk.domain.model.ScannerConfig
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

/**
 * Manual DI container — the SDK deliberately avoids Hilt/Koin so host apps are
 * not forced onto a DI framework. One container per [ScannerConfig].
 *
 * Internal: host apps share the facade, never this container. Engine order
 * mirrors decode priority (cheap-first): ML Kit (broad, fast) -> MSI (native
 * scanline decode, ~ms) -> zxing-cpp (broad, thorough). To add an engine:
 * construct it here, add it to [registry] (for [close]) and to [lumaFusion]
 * with its luma entry point, in priority order.
 */
internal class ScannerContainer(
    appContext: Context,
    val config: ScannerConfig,
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default,
) {
    val app: Context = appContext.applicationContext

    val mlKitDecoder: MLKitDecoder by lazy {
        MLKitDecoder(config.enabledSymbologies)
    }

    val zxingDecoder: ZXingCppDecoder by lazy {
        ZXingCppDecoder(
            config.enabledSymbologies,
            dispatcher,
            thorough = config.zxingTryHarder ?: true,
        )
    }

    /**
     * Native C++ MSI decoder (src/main/cpp). Replaces the pure-Kotlin
     * MsiPlesseyDecoder: signal-template correlation survives the blur that
     * defeats run-length thresholding. 6/6 on the shelf-tag benchmark.
     *
     * The OCR SKU fallback (OcrSkuDecoder) is retired: the native decoder
     * handles the rough captures that previously needed text backup.
     */
    val msiDecoder: MsiNativeDecoder by lazy {
        MsiNativeDecoder(
            stripChecksum = config.msiStripChecksum,
            minPayloadDigits = config.msiMinPayloadDigits,
            dispatcher = dispatcher,
            checksumPolicy = config.msiChecksumPolicy,
            // Single-observation emit: a one-shot still has no second frame
            // to agree with, and live reads already pass the facade's
            // stability gate (2 sightings) + dedup. Every hit still carries
            // the native >=2-vote checksum gate + policy gate.
            requireConsecutiveFrames = 1,
        )
    }

    /** Every engine instance, so [close] releases them all. */
    val registry: DecoderRegistry by lazy {
        DecoderRegistry(listOf(mlKitDecoder, msiDecoder, zxingDecoder))
    }

    /**
     * The one fusion for live and still scans: the same engine instances as
     * [registry], each reading the one native-prepared
     * [com.barcodescanner.sdk.data.frame.LumaFrame] in place. ML Kit -> MSI ->
     * zxing; engines without an enabled symbology are dropped. No region
     * assist: live frames are already the aim box, stills are read whole.
     */
    val lumaFusion: LumaFusion by lazy {
        LumaFusion(
            listOf(
                LumaEngine(mlKitDecoder) { mlKitDecoder.decodeLuma(it) },
                LumaEngine(msiDecoder) { msiDecoder.decodeLuma(it) },
                LumaEngine(zxingDecoder) { zxingDecoder.decodeLuma(it) },
            ),
            config,
        )
    }

    fun close() = registry.closeAll()
}
