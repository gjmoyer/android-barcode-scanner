package com.barcodescanner.sdk.domain.model

import android.graphics.Point
import android.graphics.Rect

/**
 * A single decoded barcode, engine-agnostic.
 *
 * Plain data class with structural equality (cornerPoints is a List so content
 * equality holds; the old Array<Point> broke data-class equals semantics).
 * Fusion voting dedups explicitly on (rawValue, symbology) keeping max
 * confidence — see FusedDecoder.mergeIntoPool.
 *
 * @param rawValue Decoded payload, exactly as encoded (no checksum stripping
 *   unless [checksumStripped] is true — see checksum policy).
 * @param symbology Canonical symbology.
 * @param confidence 0..1 engine confidence. 1.0 = checksum-verified.
 * @param boundingBox Bounding box in frame coordinates, if known (null for rotated candidates).
 * @param cornerPoints Corner points in frame coordinates, if known.
 * @param engineName Which decoder produced this ("MLKit", "ZXingCpp", "MsiPlessey", ...).
 * @param checksumStripped True if a trailing check digit was validated and removed.
 * @param isUpsideDown True if the barcode was decoded after 180° re-orientation.
 * @param gtin Paired GTIN for OCR shelf-tag reads (`engineName="MsiOcr"`): the
 *   dashed case/pack code printed next to the SKU (e.g. `000-42000-15121`),
 *   normalized to digits-only (e.g. `0004200015121`). Null when no GTIN-like
 *   run was found or the engine is not OCR. Hosts should validate the pair via
 *   record lookup (`sku ↔ gtin` must belong to the same record) — that join is
 *   the checksum the printed SKU lacks. Never set for bar decodes.
 * @param gtinRaw The GTIN exactly as printed (with dashes/slashes), or null.
 *   Prefer [gtin] for lookups; use this for display.
 */
data class DecodedBarcode(
    val rawValue: String,
    val symbology: Symbology,
    val confidence: Float,
    val boundingBox: Rect? = null,
    val cornerPoints: List<Point>? = null,
    val engineName: String,
    val checksumStripped: Boolean = false,
    val isUpsideDown: Boolean = false,
    val gtin: String? = null,
    val gtinRaw: String? = null,
) {
    init {
        require(rawValue.isNotEmpty()) { "rawValue must not be empty" }
        require(confidence in 0f..1f) { "confidence must be in 0..1, was $confidence" }
        if (gtin != null) require(gtin.all { it.isDigit() }) { "gtin must be digits-only" }
    }
}
