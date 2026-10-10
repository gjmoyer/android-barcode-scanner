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
            // Single-observation emit: the full-frame sweep NEVER runs on
            // fresh live frames (FusedDecoder gates it out), so a 2-count
            // here only ever gated one-shot/stale scans — withholding the
            // only observation a hard barcode yields. Stale-live frames keep
            // the live stability gate (2 sightings) + dedup on top; every hit
            // still carries the native >=2-vote checksum gate + policy gate.
            requireConsecutiveFrames = 1,
        )
    }

    val registry: DecoderRegistry by lazy {
        // Engine order: full-frame MSI BEFORE ROI MSI. Measured on the six
        // shelf photos (host harness + on-device sweeps): the full-frame
        // scanline read carries intact quiet zones and wins ties against ROI
        // crops, whose tight edges birth truncated/shifted windows that still
        // checksum-validate by luck (quakotml "186477", silkalm 9-digit
        // phantoms, ondeg "0486247"). A confident full-frame hit early-exits,
        // so easy frames never pay for localization+crops; tilted labels the
        // scanlines cannot read fall through to the deskewed ROI backup.
        // ML Kit isolation stays best-effort (MSI is not a supported format).
        val engines = mutableListOf<BarcodeDecoder>(mlKitDecoder)
        engines += msiDecoder
        if (Symbology.MSI_PLESSEY in config.enabledSymbologies) {
            // The ROI path gets its OWN native decoder instance: each instance
            // owns its MsiVoteGate, so ROI misses can never reset the
            // full-frame path's consecutive count (and vice versa). Both
            // instances emit on a single validated observation; live
            // protection stays in the stability gate + dedup, and every hit
            // still carries the native >=2-vote checksum gate + policy gate.
            val roiMsi = MsiNativeDecoder(
                stripChecksum = config.msiStripChecksum,
                minPayloadDigits = config.msiMinPayloadDigits,
                dispatcher = dispatcher,
                checksumPolicy = config.msiChecksumPolicy,
                requireConsecutiveFrames = 1,
            )
            engines += MsiRegionAssistDecoder(MlKitRegionLocalizer(), roiMsi)
        }
        engines += zxingDecoder
        DecoderRegistry(engines)
    }

    val fusedDecoder: FusedDecoder by lazy {
        FusedDecoder(registry, pipeline, config)
    }

    fun close() = registry.closeAll()
}
