package com.barcodescanner.sdk.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class ScannerConfigTest {

    @Test
    fun default_excludesUnknown_andTriesAllOrientations() {
        val config = ScannerConfig.default()
        assertFalse(Symbology.UNKNOWN in config.enabledSymbologies)
        // No fast/degraded mode: cheap-first ordering + confident early-exit keep
        // easy frames fast, so the default is always the thorough 4 views.
        assertEquals(4, config.maxOrientationsTried)
    }

    @Test
    fun explicitOrientationCount_respected() {
        assertEquals(2, ScannerConfig.Builder().maxOrientationsTried(2).build().maxOrientationsTried)
        assertEquals(4, ScannerConfig.Builder().maxOrientationsTried(4).build().maxOrientationsTried)
    }

    @Test
    fun builder_onlyReplaces_addAppends() {
        val config = ScannerConfig.Builder()
            .only(Symbology.QR_CODE)
            .addSymbologies(Symbology.DATA_BAR)
            .build()
        assertEquals(setOf(Symbology.QR_CODE, Symbology.DATA_BAR), config.enabledSymbologies)
    }

    @Test(expected = IllegalArgumentException::class)
    fun emptySymbologies_rejected() {
        ScannerConfig.Builder().only().build()
    }

    @Test(expected = IllegalArgumentException::class)
    fun unknownAlone_rejected() {
        ScannerConfig.Builder().only(Symbology.UNKNOWN).build()
    }

    @Test(expected = IllegalArgumentException::class)
    fun orientationCount_outOfRange_rejected() {
        ScannerConfig.Builder().maxOrientationsTried(5).build()
    }

    @Test(expected = IllegalArgumentException::class)
    fun timeout_outOfRange_rejected() {
        ScannerConfig.Builder().decodeTimeoutMillis(50).build()
    }

    @Test
    fun scanRegion_defaultNull() {
        assertEquals(null, ScannerConfig.default().scanRegion)
    }

    @Test
    fun scanRegion_buildsCenteredRect() {
        val config = ScannerConfig.Builder().scanRegion(0.9f, 0.5f).build()
        assertEquals(ScannerConfig.ScanRegion(0.9f, 0.5f), config.scanRegion)
    }

    @Test
    fun scanRegion_fullFrame_clears() {
        val config = ScannerConfig.Builder()
            .scanRegion(0.9f, 0.5f)
            .fullFrame()
            .build()
        assertEquals(null, config.scanRegion)
    }

    @Test(expected = IllegalArgumentException::class)
    fun scanRegion_tooSmall_rejected() {
        ScannerConfig.Builder().scanRegion(0.1f, 0.5f).build()
    }

    @Test(expected = IllegalArgumentException::class)
    fun scanRegion_overFull_rejected() {
        ScannerConfig.Builder().scanRegion(1.1f, 0.5f).build()
    }
}
