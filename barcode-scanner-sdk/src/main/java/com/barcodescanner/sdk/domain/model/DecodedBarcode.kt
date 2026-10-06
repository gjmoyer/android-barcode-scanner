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
) {
    init {
        require(rawValue.isNotEmpty()) { "rawValue must not be empty" }
        require(confidence in 0f..1f) { "confidence must be in 0..1, was $confidence" }
    }
}
