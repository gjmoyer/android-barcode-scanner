package com.barcodescanner.sdk.data.msi

import com.barcodescanner.sdk.domain.model.ScannerConfig

/**
 * MSI Plessey checksum validation.
 *
 * Variants (Wikipedia "MSI Barcode", Morovia KB10637):
 *  - Mod10 (most common): Luhn over the payload. Vectors: "1234567"→4, "8052"→3.
 *  - Mod11 (IBM weights 2..7 repeating from the right; NCR 2..9 variant NOT
 *    supported — documented limitation, IBM is the warehouse default).
 *  - Mod1010 (double Mod10: Mod10 over payload, then Mod10 over payload+first check).
 *  - Mod1110 (Mod11 over payload, then Mod10 over payload+first check).
 *  - NONE (debugging only — accepts anything; decoder requires 2 agreeing
 *    scanlines under this policy to bound false accepts).
 */
object MsiChecksumValidator {

    data class Validation(val payloadWithoutChecksum: String, val valid: Boolean)

    /**
     * Validates [full] (payload + check digit(s)) under [policy].
     * Returns payload with checksum stripped when valid; valid=false otherwise.
     */
    fun validate(full: String, policy: ScannerConfig.MsiChecksumPolicy): Validation {
        if (full.any { !it.isDigit() }) return Validation(full, false)
        return when (policy) {
            ScannerConfig.MsiChecksumPolicy.NONE ->
                Validation(full, true)
            ScannerConfig.MsiChecksumPolicy.MOD_10 -> {
                if (full.length < 2) return Validation(full, false)
                val payload = full.dropLast(1)
                val expected = mod10Check(payload)
                Validation(payload, full.last().digitToInt() == expected)
            }
            ScannerConfig.MsiChecksumPolicy.MOD_11 -> {
                if (full.length < 2) return Validation(full, false)
                val payload = full.dropLast(1)
                val expected = mod11Check(payload)
                Validation(payload, full.last().digitToInt() == expected)
            }
            ScannerConfig.MsiChecksumPolicy.MOD_10_10 -> {
                if (full.length < 3) return Validation(full, false)
                val payload = full.dropLast(2)
                val c1 = mod10Check(payload)
                if (full[full.length - 2].digitToInt() != c1) return Validation(full, false)
                val c2 = mod10Check(payload + full[full.length - 2])
                Validation(payload, full.last().digitToInt() == c2)
            }
            ScannerConfig.MsiChecksumPolicy.MOD_10_11 -> {
                if (full.length < 3) return Validation(full, false)
                val payload = full.dropLast(2)
                val c1 = mod11Check(payload)
                if (full[full.length - 2].digitToInt() != c1) return Validation(full, false)
                val c2 = mod10Check(payload + full[full.length - 2])
                Validation(payload, full.last().digitToInt() == c2)
            }
        }
    }

    /** Mod10 (Luhn): double every second digit from the right of the payload. */
    fun mod10Check(payload: String): Int {
        var sum = 0
        var double = true
        for (i in payload.indices.reversed()) {
            var d = payload[i].digitToInt()
            if (double) {
                d *= 2
                if (d > 9) d -= 9
            }
            sum += d
            double = !double
        }
        return (10 - (sum % 10)) % 10
    }

    /** IBM Mod11: weights 2..7 repeating from the right, C = (11 - sum%11) % 11. */
    fun mod11Check(payload: String): Int {
        var sum = 0
        var weight = 2
        for (i in payload.indices.reversed()) {
            sum += payload[i].digitToInt() * weight
            weight = if (weight == 7) 2 else weight + 1
        }
        val r = (11 - (sum % 11)) % 11
        return if (r == 10) 0 else r // some printers map 10 -> 0; strict variants reject, we accept-as-0
    }
}
