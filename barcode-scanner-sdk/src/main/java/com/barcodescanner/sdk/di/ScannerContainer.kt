package com.barcodescanner.sdk.di

import android.content.Context
import com.barcodescanner.sdk.data.fusion.FusedDecoder
import com.barcodescanner.sdk.data.mlkit.MLKitDecoder
import com.barcodescanner.sdk.data.msi.MsiPlesseyDecoder
import com.barcodescanner.sdk.data.ocr.OcrSkuDecoder
import com.barcodescanner.sdk.data.zxingcpp.ZXingCppDecoder
import com.barcodescanner.sdk.domain.decoder.DecoderRegistry
import com.barcodescanner.sdk.domain.model.ScannerConfig
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
 * (cheap-first): ML Kit (broad, fast) -> MSI bar decode (narrow scanline,
 * must precede the slow native sweep so MSI tags resolve without paying for
 * zxing TryHarder first) -> zxing-cpp (broad, thorough) -> MSI OCR text
 * fallback. To add an engine: construct it, call `registry.register(it)` —
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
            thorough = config.zxingTryHarder ?: config.robustMode,
        )
    }

    val msiDecoder: MsiPlesseyDecoder by lazy {
        MsiPlesseyDecoder(
            checksumPolicy = config.msiChecksumPolicy,
            robustMode = config.robustMode,
            dispatcher = dispatcher,
            minPayloadDigits = config.msiMinPayloadDigits,
        )
    }

    /**
     * SKU text fallback, deliberately LAST: runs only after every bar engine
     * missed, and only when MSI is enabled at all.
     */
    val ocrDecoder: OcrSkuDecoder by lazy {
        OcrSkuDecoder(
            enabledSymbologies = config.enabledSymbologies,
            checksumPolicy = config.msiChecksumPolicy,
            dispatcher = dispatcher,
            requireChecksum = config.msiOcrRequireChecksum,
        )
    }

    val registry: DecoderRegistry by lazy {
        DecoderRegistry(
            listOf(mlKitDecoder, msiDecoder, zxingDecoder, ocrDecoder),
        )
    }

    val fusedDecoder: FusedDecoder by lazy {
        FusedDecoder(registry, pipeline, config)
    }

    fun close() = registry.closeAll()
}
