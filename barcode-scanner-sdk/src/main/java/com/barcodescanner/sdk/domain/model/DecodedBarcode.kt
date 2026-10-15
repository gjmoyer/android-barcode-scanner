package com.barcodescanner.sdk.domain.model

import android.graphics.Point
import android.graphics.Rect

/**
 * A single decoded barcode, engine-agnostic.
 *
 * Plain data class with structural equality (cornerPoints is a List so content
 * equality holds; the old Array<Point> broke data-class equals semantics).
 * Fusion voting dedups explicitly on (rawValue, symbology) keeping max
 * confidence — see LumaFusion.mergeIntoPool.
 *
 * @param rawValue Decoded payload, exactly as encoded (no checksum stripping
 *   unless [checksumStripped] is true — see checksum policy).
 * @param symbology Canonical symbology.
 * @param confidence 0..1 engine confidence. 1.0 = checksum-verified.
 * @param boundingBox Bounding box, if known, in the coordinates of the prepared
 *   upright frame the engines read: the aim-box crop for live scans, the
 *   upright (downscaled only above 16 MP) still for one-shots.
 * @param cornerPoints Corner points in the same coordinates, if known.
 * @param engineName Which decoder produced this ("MLKit", "ZXingCpp", "MsiNative").
 * @param checksumStripped True if a trailing check digit was validated and removed.
 * @param isUpsideDown True if the barcode was decoded after 180° re-orientation.
 * @param gtin Retired, always null: the paired GTIN for the removed OCR
 *   shelf-tag reads (`engineName="MsiOcr"` — that engine was deleted). Kept
 *   for source compatibility; no shipped engine sets it.
 * @param gtinRaw Retired, always null (see [gtin]).
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
