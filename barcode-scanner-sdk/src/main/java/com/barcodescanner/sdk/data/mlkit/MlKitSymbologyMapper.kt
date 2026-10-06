package com.barcodescanner.sdk.data.mlkit

import com.barcodescanner.sdk.domain.model.Symbology
import com.google.mlkit.vision.barcode.common.Barcode

/**
 * Single source of truth mapping SDK [Symbology] <-> ML Kit format ints.
 *
 * DataBar + MSI have no ML Kit constant -> mapped to null (caller routes to fallback).
 */
object MlKitSymbologyMapper {

    fun toMlKit(symbology: Symbology): Int? = when (symbology) {
        Symbology.EAN_8 -> Barcode.FORMAT_EAN_8
        Symbology.EAN_13 -> Barcode.FORMAT_EAN_13
        Symbology.UPC_A -> Barcode.FORMAT_UPC_A
        Symbology.UPC_E -> Barcode.FORMAT_UPC_E
        Symbology.CODE_39 -> Barcode.FORMAT_CODE_39
        Symbology.CODE_93 -> Barcode.FORMAT_CODE_93
        Symbology.CODE_128 -> Barcode.FORMAT_CODE_128
        Symbology.ITF -> Barcode.FORMAT_ITF
        Symbology.CODABAR -> Barcode.FORMAT_CODABAR
        Symbology.QR_CODE -> Barcode.FORMAT_QR_CODE
        Symbology.AZTEC -> Barcode.FORMAT_AZTEC
        Symbology.PDF_417 -> Barcode.FORMAT_PDF417
        Symbology.DATA_MATRIX -> Barcode.FORMAT_DATA_MATRIX
        Symbology.DATA_BAR,
        Symbology.DATA_BAR_EXPANDED,
        Symbology.DATA_BAR_LIMITED,
        Symbology.MSI_PLESSEY,
        Symbology.UNKNOWN -> null
    }

    fun fromMlKit(format: Int): Symbology = when (format) {
        Barcode.FORMAT_EAN_8 -> Symbology.EAN_8
        Barcode.FORMAT_EAN_13 -> Symbology.EAN_13
        Barcode.FORMAT_UPC_A -> Symbology.UPC_A
        Barcode.FORMAT_UPC_E -> Symbology.UPC_E
        Barcode.FORMAT_CODE_39 -> Symbology.CODE_39
        Barcode.FORMAT_CODE_93 -> Symbology.CODE_93
        Barcode.FORMAT_CODE_128 -> Symbology.CODE_128
        Barcode.FORMAT_ITF -> Symbology.ITF
        Barcode.FORMAT_CODABAR -> Symbology.CODABAR
        Barcode.FORMAT_QR_CODE -> Symbology.QR_CODE
        Barcode.FORMAT_AZTEC -> Symbology.AZTEC
        Barcode.FORMAT_PDF417 -> Symbology.PDF_417
        Barcode.FORMAT_DATA_MATRIX -> Symbology.DATA_MATRIX
        else -> Symbology.UNKNOWN
    }

    /** Bitmask for BarcodeScannerOptions from enabled set; null if none are ML Kit natives. */
    fun optionsFormat(enabled: Set<Symbology>): Int? {
        var mask = 0
        var any = false
        for (s in enabled) {
            val f = toMlKit(s) ?: continue
            mask = mask or f
            any = true
        }
        return if (any) mask else null
    }
}
