package com.barcodescanner.sdk.domain.model

/**
 * Canonical symbology set exposed by the SDK.
 *
 * This is intentionally SDK-owned (not ML Kit's or ZXing's enum) so:
 *  - host apps depend on one stable type,
 *  - new engines (ML Kit, zxing-cpp, custom MSI) are mapped inward,
 *  - unsupported-on-one-engine symbologies still have a home.
 */
enum class Symbology(
    /** Human-readable display name. */
    val displayName: String,
    /** True for symbologies ML Kit cannot decode natively. */
    val requiresFallbackEngine: Boolean,
) {
    // ---- ML Kit native ----
    EAN_8("EAN-8", false),
    EAN_13("EAN-13", false),
    UPC_A("UPC-A", false),
    UPC_E("UPC-E", false),
    CODE_39("Code 39", false),
    CODE_93("Code 93", false),
    CODE_128("Code 128", false),
    ITF("ITF", false),
    CODABAR("Codabar", false),
    QR_CODE("QR Code", false),
    AZTEC("Aztec", false),
    PDF_417("PDF-417", false),
    DATA_MATRIX("Data Matrix", false),

    // ---- Fallback engines (zxing-cpp / custom) ----
    /** GS1 DataBar family (Omnidirectional, Stacked, Expanded, Limited). ML Kit does not support it. */
    DATA_BAR("GS1 DataBar", requiresFallbackEngine = true),
    DATA_BAR_EXPANDED("GS1 DataBar Expanded", requiresFallbackEngine = true),
    DATA_BAR_LIMITED("GS1 DataBar Limited", requiresFallbackEngine = true),

    /**
     * MSI Plessey (MSI / Plessey / Modified Plessey).
     * Not supported by ML Kit nor zxing-cpp -> decoded by [com.barcodescanner.sdk.data.msi.MsiNativeDecoder].
     */
    MSI_PLESSEY("MSI Plessey", requiresFallbackEngine = true),

    /** Catch-all for forward compatibility. */
    UNKNOWN("Unknown", requiresFallbackEngine = true),
    ;

    companion object {
        /** Symbologies ML Kit can handle without a fallback. */
        val mlKitNatives: Set<Symbology> by lazy {
            entries.filter { !it.requiresFallbackEngine }.toSet()
        }

        /** Symbologies that must go through zxing-cpp or custom decoders. */
        val fallbackOnly: Set<Symbology> by lazy {
            entries.filter { it.requiresFallbackEngine }.toSet()
        }
    }
}
