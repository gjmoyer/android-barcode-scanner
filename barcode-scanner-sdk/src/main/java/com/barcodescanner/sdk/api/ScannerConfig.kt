package com.barcodescanner.sdk.api

/**
 * Public re-exports of the domain-owned scanner configuration.
 *
 * The config lives in domain/ so data/ + domain/ can depend on it without an
 * api->data->api cycle. Host apps import from THIS package; the types are identical.
 *
 * Note: Kotlin cannot qualify nested classifiers through a typealias
 * (`ScannerConfig.Builder` does not resolve), so the nested Builder and policy
 * enum get their own top-level aliases below. Host pattern:
 * ```
 * val config = ScannerConfigBuilder().robustMode(true)
 *     .msiChecksumPolicy(MsiChecksumPolicy.MOD_10).build()
 * ```
 */
typealias ScannerConfig = com.barcodescanner.sdk.domain.model.ScannerConfig

typealias ScannerConfigBuilder = com.barcodescanner.sdk.domain.model.ScannerConfig.Builder

typealias MsiChecksumPolicy = com.barcodescanner.sdk.domain.model.ScannerConfig.MsiChecksumPolicy

/** Convenience: default config with every known symbology (except UNKNOWN) enabled. */
fun defaultScannerConfig() = com.barcodescanner.sdk.domain.model.ScannerConfig.default()
