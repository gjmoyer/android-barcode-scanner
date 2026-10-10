package com.barcodescanner.sdk.di

import android.content.Context
import com.barcodescanner.sdk.data.fusion.FusedDecoder
import com.barcodescanner.sdk.data.mlkit.MLKitDecoder
import com.barcodescanner.sdk.data.mlkit.MlKitRegionLocalizer
import com.barcodescanner.sdk.data.msi.MsiNativeDecoder
import com.barcodescanner.sdk.data.msi.MsiRegionAssistDecoder
import com.barcodescanner.sdk.data.zxingcpp.ZXingCppDecoder
import com.barcodescanner.sdk.domain.decoder.DecoderRegistry
import com.barcodescanner.sdk.domain.decoder.BarcodeDecoder
import com.barcodescanner.sdk.domain.model.ScannerConfig
import com.barcodescanner.sdk.domain.model.Symbology
import com.barcodescanner.sdk.domain.pipeline.ContrastNormalizationTransform
import com.barcodescanner.sdk.domain.pipeline.DownscaleTransform
import com.barcodescanner.sdk.domain.pipeline.PreprocessingPipeline
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

/**
 * Manual DI container — the SDK deliberately avoids Hilt/Koin so host apps are
 * not forced onto a DI framework. One container per [ScannerConfig].
 *
 * Internal: host apps share the facade, never this container (prevents
 * bypassing the fusion pipeline). Assembly order mirrors decode priority
 * (cheap-first): ML Kit (broad, fast) -> MSI-ROI (ML Kit isolation: tilted
 * labels via deskewed crops) -> MSI bar decode (narrow scanline full-frame
 * fallback, must precede the slow native sweep so MSI tags resolve without
 * paying for zxing TryHarder first) -> zxing-cpp (broad, thorough) -> MSI OCR
 * text fallback. To add an engine: construct it, call `registry.register(it)` —
 * fusion respects registration order, no other change needed.
 */
internal class ScannerContainer(
    appContext: Context,
    val config: ScannerConfig,
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default,
    /** Override for tests / custom preprocessing. */
    pipelineOverride: PreprocessingPipeline? = null,
) {
    val app: Context = appContext.applicationContext

    val pipeline: PreprocessingPipeline = pipelineOverride ?: PreprocessingPipeline.of(
        DownscaleTransform(),
        ContrastNormalizationTransform(),
    )

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
        )
    }

    val registry: DecoderRegistry by lazy {
        // MSI-ROI runs BEFORE full-frame MSI: a tilted label that only decodes
        // from a deskewed crop resolves here without paying for the full-frame
        // scanline sweep first. A miss is a cheap NotFound and fusion falls
        // through to the standard engines. ML Kit isolation is best-effort
        // (MSI is not a supported format), so the full-frame fallback stays.
        val engines = mutableListOf<BarcodeDecoder>(mlKitDecoder)
        if (Symbology.MSI_PLESSEY in config.enabledSymbologies) {
            // The ROI path gets its OWN native decoder instance with
            // single-frame emit: each instance owns its MsiVoteGate, so ROI
            // misses can never reset the full-frame path's consecutive count
            // (and vice versa). ROI hits already carry the native ≥2-vote
            // checksum gate plus the live stability gate, so the extra
            // consecutive-frame layer only added latency on jittery crops.
            // The full-frame instance below keeps requireConsecutiveFrames=2.
            val roiMsi = MsiNativeDecoder(
                stripChecksum = config.msiStripChecksum,
                minPayloadDigits = config.msiMinPayloadDigits,
                dispatcher = dispatcher,
                checksumPolicy = config.msiChecksumPolicy,
                requireConsecutiveFrames = 1,
            )
            engines += MsiRegionAssistDecoder(MlKitRegionLocalizer(), roiMsi)
        }
        engines += msiDecoder
        engines += zxingDecoder
        DecoderRegistry(engines)
    }

    val fusedDecoder: FusedDecoder by lazy {
        FusedDecoder(registry, pipeline, config)
    }

    fun close() = registry.closeAll()
}
