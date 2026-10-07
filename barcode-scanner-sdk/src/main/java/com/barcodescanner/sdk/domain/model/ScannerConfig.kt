package com.barcodescanner.sdk.domain.model

/**
 * Scanner configuration — lives in domain (not api/) so data/ + domain/ can
 * depend on it without creating an api->data->api cycle.
 * Host apps should import the api/ typealias which re-exports this type.
 *
 * Immutable; use [Builder].
 */
data class ScannerConfig(
    val enabledSymbologies: Set<Symbology>,
    /** Max orientations attempted per frame: 1..4 (see OrientationCandidates). */
    val maxOrientationsTried: Int,
    /** When true: try all 4 rotations + extra MSI binarizations + wider voting. */
    val robustMode: Boolean,
    /** Per-frame decode timeout; fusion returns best-so-far after this. */
    val decodeTimeoutMillis: Long,
    /** Suppress identical results within this window (live mode dedup). */
    val duplicateSuppressionMillis: Long,
    /** MSI Plessey checksum policy (Mod10 default per spec). */
    val msiChecksumPolicy: MsiChecksumPolicy,
    /**
     * Minimum MSI payload (checksum-stripped) length to emit. The spec floor is
     * 3; short parses validate by luck far more often (a 4-digit window needs
     * just 2 correlated observations to win a blurry frame), so hosts scanning
     * shelf SKUs should raise this (typically 6). 3 = spec-compatible default.
     */
    val msiMinPayloadDigits: Int,
    /**
     * OCR text fallback (MsiOcr). False (default): emit the best plain 7+ digit
     * run exactly as printed at confidence 0.5. Shelf labels print the SKU
     * WITHOUT the check digit(s) (those live in the barcode), so validation is
     * usually impossible; set true only when printed numbers carry their check.
     * Bars stay strictly checksum-gated either way.
     */
    val msiOcrRequireChecksum: Boolean,
    /** Minimum confidence to emit a result. NOTE: >0.9 disables ZXing, >0.95 disables ML Kit. */
    val minConfidence: Float,
) {
    init {
        require(enabledSymbologies.isNotEmpty()) { "At least one symbology must be enabled" }
        require(Symbology.UNKNOWN !in enabledSymbologies || enabledSymbologies.size > 1) {
            "UNKNOWN alone is not a valid enabled set"
        }
        require(maxOrientationsTried in 1..4) { "maxOrientationsTried must be 1..4" }
        require(decodeTimeoutMillis in 100..10_000L) { "decodeTimeoutMillis out of range" }
        require(duplicateSuppressionMillis >= 0) { "duplicateSuppressionMillis must be >= 0" }
        require(msiMinPayloadDigits in 3..32) { "msiMinPayloadDigits must be 3..32" }
        require(minConfidence in 0f..1f) { "minConfidence must be 0..1" }
    }

    enum class MsiChecksumPolicy {
        MOD_10,
        MOD_11,
        /** Double Mod10 (Mod1010): Mod10, then Mod10 over payload+first check. */
        MOD_10_10,
        /** Mod11 then Mod10 (Mod1110): dual-check warehouse variant. */
        MOD_10_11,
        /** Accept any checksum (debugging only — higher false-positive rate). */
        NONE,
    }

    class Builder {
        private var enabled: Set<Symbology> =
            Symbology.entries.filter { it != Symbology.UNKNOWN }.toSet()
        private var maxOrientations = 2
        private var robust = false
        private var timeout = 1_500L
        private var dedup = 1_500L
        private var msi = MsiChecksumPolicy.MOD_10
        private var msiMinDigits = 3
        private var msiOcrChecksum = false
        private var minConf = 0.5f

        fun enabledSymbologies(v: Set<Symbology>) = apply { enabled = v.toSet() }

        /** Replaces the enabled set (use [addSymbologies] to append). */
        fun only(vararg s: Symbology) = apply { enabled = s.toSet() }

        /** Adds to the enabled set (unlike [only] which replaces). */
        fun addSymbologies(vararg s: Symbology) = apply { enabled = enabled + s.toSet() }

        /** Backward-compat alias for [only]. Prefer [only]/[addSymbologies]. */
        fun enable(vararg s: Symbology) = only(*s)

        fun maxOrientationsTried(v: Int) = apply { maxOrientations = v }

        /** Also sets [maxOrientationsTried] (4 on, 2 off) — keep calls ordered. */
        fun robustMode(v: Boolean) = apply {
            robust = v
            maxOrientations = if (v) 4 else 2
        }
        fun decodeTimeoutMillis(v: Long) = apply { timeout = v }
        fun duplicateSuppressionMillis(v: Long) = apply { dedup = v }
        fun msiChecksumPolicy(v: MsiChecksumPolicy) = apply { msi = v }
        fun msiMinPayloadDigits(v: Int) = apply { msiMinDigits = v }
        fun msiOcrRequireChecksum(v: Boolean) = apply { msiOcrChecksum = v }
        fun minConfidence(v: Float) = apply { minConf = v }
        fun build() = ScannerConfig(
            enabledSymbologies = enabled.toSet(),
            maxOrientationsTried = maxOrientations,
            robustMode = robust,
            decodeTimeoutMillis = timeout,
            duplicateSuppressionMillis = dedup,
            msiChecksumPolicy = msi,
            msiMinPayloadDigits = msiMinDigits,
            msiOcrRequireChecksum = msiOcrChecksum,
            minConfidence = minConf,
        )
    }

    companion object {
        fun default() = Builder().build()
        fun robust() = Builder().robustMode(true).build()
    }
}
