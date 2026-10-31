package com.barcodescanner.sdk.device

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.barcodescanner.sdk.api.BarcodeScannerFactory
import com.barcodescanner.sdk.api.MsiChecksumPolicy
import com.barcodescanner.sdk.api.ScanResult
import com.barcodescanner.sdk.api.ScannerConfigBuilder
import com.barcodescanner.sdk.data.zxingcpp.ZXingCppDecoder
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * On-device verification suite: runs the FULL production chain (ML Kit + real
 * zxing-cpp native library + MSI + OCR, all real models) against shelf photos.
 *
 * Six shelf-label fixtures live in `src/test/resources/msi-shelf/` (mirrored
 * here under `src/androidTest/assets/msi_samples/` — the assets copy is
 * git-ignored by default; `git add -f` it to run this suite in CI).
 * Ground truth (payload -> bars), verified with the host harness
 * (`tools/msi-harness`, 133/133 asserts green; 152/152 with local photos):
 * - yakult    0828147 -> 082814799   (MOD_10 -> 08281479; full Mod1010 bars)
 * - quaker    0186477 -> 018647768   (host exact; device matrix-logged gap)
 * - onedegree 0243523 -> 024352387   (host exact; device matrix-logged gap)
 * - dixie     0087573 -> 008757320   (MOD_10 -> 00875732)
 * - starbucks 0168971 -> 016897100   (MOD_10_10)
 * - silk      0826593 -> 082659368   (MOD_10 -> 08265936; MOD_10_10 device
 *   full-resolution gap)
 *
 * Requires an emulator or device (API 28+):
 * `./gradlew :barcode-scanner-sdk:connectedDebugAndroidTest`.
 *
 * Results are both asserted (known-exact cases, incl. a 3x repeat-stability
 * sweep) and written to the test app's external files dir
 * (`msi_device_results.txt`) for full review.
 */
@RunWith(AndroidJUnit4::class)
class DeviceMsiTest {

    private data class Expectation(val policy: MsiChecksumPolicy, val sku: String)

    @Test
    fun decodeAllSamplesFullChain() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val appContext = instrumentation.targetContext
        val assetNames = (instrumentation.context.assets.list("msi_samples") ?: emptyArray())
            .filter { it.endsWith(".png") }
            .sorted()
        assumeTrue(
            "drop sample PNGs in src/androidTest/assets/msi_samples/ to run on-device verification",
            assetNames.isNotEmpty(),
        )
        val out = StringBuilder()
        out.appendLine("NATIVE_ZXING_AVAILABLE=${ZXingCppDecoder.isAvailable}")
        // Known-exact expectations (policy + payload). quakotml and ondeg are
        // matrix-logged only on device: their full-res modules are a logged
        // gap, while the host harness reads both exactly. The three databar
        // fixtures are committed, so they assert in CI too.
        val expected = mapOf(
            "msi-yakult-0828147.png" to
                Expectation(MsiChecksumPolicy.MOD_10, "08281479"),
            "msi-dixie-0087573.png" to
                Expectation(MsiChecksumPolicy.MOD_10, "00875732"),
            "msi-starbucks-0168971.png" to
                Expectation(MsiChecksumPolicy.MOD_10_10, "0168971"),
            "msi-silkalm-0826593.png" to
                Expectation(MsiChecksumPolicy.MOD_10, "08265936"),
            "databar-omni.png" to
                Expectation(MsiChecksumPolicy.MOD_10, "(01)01234567890128"),
            "databar-ltd.png" to
                Expectation(MsiChecksumPolicy.MOD_10, "(01)01234567890128"),
            "databar-exp.png" to
                Expectation(MsiChecksumPolicy.MOD_10, "(01)12345678901231"),
        )
        val policies = listOf(
            MsiChecksumPolicy.MOD_10,
            MsiChecksumPolicy.MOD_11,
            MsiChecksumPolicy.MOD_10_10,
            MsiChecksumPolicy.MOD_10_11,
        )
        for (name in assetNames) {
            val exp = expected[name]
            val stream = instrumentation.context.assets.open("msi_samples/$name")
            val bmp = BitmapFactory.decodeStream(stream)
            stream.close()
            org.junit.Assert.assertNotNull("cannot decode asset $name", bmp)
            for (policy in policies) {
                val config = ScannerConfigBuilder()
                    .msiChecksumPolicy(policy)
                    .build()
                val scanner = BarcodeScannerFactory.create(appContext, config)
                try {
                    val t = System.nanoTime()
                    val result = runBlocking { scanner.scanBitmap(bmp!!) }
                    val ms = (System.nanoTime() - t) / 1_000_000
                    val line = when (result) {
                        is ScanResult.Success -> {
                            val b = result.barcode
                            val hit = if (exp != null && policy == exp.policy) {
                                b.rawValue == exp.sku
                            } else {
                                null  // other policies are matrix evidence only
                            }
                            "RESULT|$name|$policy|SUCCESS|${b.engineName}|${b.rawValue}|" +
                                "${b.confidence}|${ms}ms|MATCH=${hit ?: "UNKNOWN"}"
                        }
                        is ScanResult.NotFound -> "RESULT|$name|$policy|NOTFOUND|${ms}ms"
                        is ScanResult.Failure ->
                            "RESULT|$name|$policy|FAILURE|${result.message}|${ms}ms"
                    }
                    out.appendLine(line)
                    android.util.Log.d("DeviceMsiTest", line)
                } finally {
                    scanner.close()
                }
            }
            bmp!!.recycle()
        }
        val outFile = File(appContext.getExternalFilesDir(null), "msi_device_results.txt")
        outFile.writeText(out.toString())
        // Hard assertions only on the proven-exact cases (same as JVM suite).
        for ((name, exp) in expected) {
            if (name !in assetNames) continue
            val stream = instrumentation.context.assets.open("msi_samples/$name")
            val bmp = BitmapFactory.decodeStream(stream)
            stream.close()
            val config = ScannerConfigBuilder()
                .msiChecksumPolicy(exp.policy)
                .build()
            val scanner = BarcodeScannerFactory.create(appContext, config)
            try {
                val result = runBlocking { scanner.scanBitmap(bmp!!) }
                org.junit.Assert.assertTrue(
                    "expected ${exp.sku} for $name, got $result",
                    result is ScanResult.Success &&
                        result.barcode.rawValue == exp.sku,
                )
            } finally {
                scanner.close()
            }
            bmp!!.recycle()
        }
    }

    /**
     * The bars carry a full 9-digit Mod1010 codeword. Under MOD_10 the native
     * engine reports the 8-digit payload (08281479), and the same value must
     * come back when the pixels are fed upside-down; this exercises the
     * reverse-view confirmation on the production chain.
     */
    @Test
    fun yakult_upsideDown() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val appContext = instrumentation.targetContext
        val name = "msi-yakult-0828147.png"
        val have = (instrumentation.context.assets.list("msi_samples") ?: emptyArray()).contains(name)
        assumeTrue("drop $name in src/androidTest/assets/msi_samples/ to run", have)
        val upright = instrumentation.context.assets.open("msi_samples/$name").use {
            BitmapFactory.decodeStream(it)
        }
        org.junit.Assert.assertNotNull("cannot decode asset $name", upright)
        val flipped = Bitmap.createBitmap(
            upright!!, 0, 0, upright.width, upright.height,
            Matrix().apply { postRotate(180f) }, false,
        )
        val config = ScannerConfigBuilder()
            .msiChecksumPolicy(MsiChecksumPolicy.MOD_10)
            .msiSymbolLength(9)
            .decodeTimeoutMillis(10_000)
            .build()
        val scanner = BarcodeScannerFactory.create(appContext, config)
        try {
            val result = runBlocking { scanner.scanBitmap(flipped) }
            org.junit.Assert.assertTrue(
                "expected 08281479 upside-down, got $result",
                result is ScanResult.Success &&
                    result.barcode.rawValue == "08281479" &&
                    result.barcode.isUpsideDown,
            )
        } finally {
            scanner.close()
            upright.recycle()
            flipped.recycle()
        }
    }

    /**
     * Repeat-stability sweep over the shelf fixtures: each photo is scanned
     * three times under its expected policy and every repeat must return
     * the same expected payload. Catches non-determinism (voting/tie-break
     * flips) that a single-shot assertion would miss. ondeg is swept in the
     * policy matrix above (logged) but excluded here — known gap.
     */
    @Test
    fun shelfSamples_repeatSweepIsStable() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val appContext = instrumentation.targetContext
        val expected = mapOf(
            "msi-yakult-0828147.png" to
                Expectation(MsiChecksumPolicy.MOD_10, "08281479"),
            "msi-dixie-0087573.png" to
                Expectation(MsiChecksumPolicy.MOD_10, "00875732"),
            "msi-starbucks-0168971.png" to
                Expectation(MsiChecksumPolicy.MOD_10_10, "0168971"),
            "msi-silkalm-0826593.png" to
                Expectation(MsiChecksumPolicy.MOD_10, "08265936"),
        )
        val assetNames = (instrumentation.context.assets.list("msi_samples") ?: emptyArray())
            .filter { it.endsWith(".png") }
            .sorted()
        assumeTrue("drop sample PNGs in src/androidTest/assets/msi_samples/ to run", assetNames.isNotEmpty())
        for ((name, exp) in expected) {
            if (name !in assetNames) continue
            val stream = instrumentation.context.assets.open("msi_samples/$name")
            val bmp = BitmapFactory.decodeStream(stream)
            stream.close()
            org.junit.Assert.assertNotNull("cannot decode asset $name", bmp)
            val config = ScannerConfigBuilder()
                .msiChecksumPolicy(exp.policy)
                .decodeTimeoutMillis(10_000)
                .build()
            val scanner = BarcodeScannerFactory.create(appContext, config)
            try {
                repeat(3) { rep ->
                    val t = System.nanoTime()
                    val result = runBlocking { scanner.scanBitmap(bmp!!) }
                    val ms = (System.nanoTime() - t) / 1_000_000
                    android.util.Log.d("DeviceMsiTest", "SWEEP|$name|rep$rep|${ms}ms|$result")
                    org.junit.Assert.assertTrue(
                        "$name rep$rep: expected ${exp.sku} under ${exp.policy}, got $result",
                        result is ScanResult.Success &&
                            result.barcode.rawValue == exp.sku,
                    )
                }
            } finally {
                scanner.close()
            }
            bmp!!.recycle()
        }
    }

    /**
     * Rotation matrix over the committed DataBar fixtures: physically rotate each
     * fixture's pixels (as a camera buffer would be stored) and tell the scanner
     * the rotation needed to restore upright content. The full production chain
     * (ML Kit + native zxing-cpp) must decode every combination. This is the
     * on-device counterpart of `RotatedImageFusionTest` (JVM native-graphics) and
     * verifies zxing's internal TryRotate routing on real hardware.
     */
    @Test
    fun databarFixtures_decodeAtEveryRotation() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val appContext = instrumentation.targetContext
        assumeTrue(
            "zxing-cpp native lib required for DataBar rotation checks",
            ZXingCppDecoder.isAvailable,
        )
        val fixtures = listOf("databar-omni.png", "databar-ltd.png", "databar-exp.png")
            .filter { instrumentation.context.assets.list("msi_samples")?.contains(it) == true }
        assumeTrue("no committed databar fixtures found", fixtures.isNotEmpty())

        val config = ScannerConfigBuilder()
            .decodeTimeoutMillis(10_000)
            .build()
        val scanner = BarcodeScannerFactory.create(appContext, config)
        try {
            for (name in fixtures) {
                val stream = instrumentation.context.assets.open("msi_samples/$name")
                val upright = BitmapFactory.decodeStream(stream)
                stream.close()
                org.junit.Assert.assertNotNull("cannot decode asset $name", upright)
                try {
                    for (contentRotation in listOf(0, 90, 180, 270)) {
                        val raw = rotate(upright!!, contentRotation)
                        val sensorRotation = (360 - contentRotation) % 360
                        val result = runBlocking { scanner.scanBitmap(raw, sensorRotation) }
                        org.junit.Assert.assertTrue(
                            "$name contentRotation=$contentRotation sensorRotation=$sensorRotation " +
                                "must decode, got $result",
                            result is ScanResult.Success,
                        )
                        if (raw !== upright) raw.recycle()
                    }
                } finally {
                    upright!!.recycle()
                }
            }
        } finally {
            scanner.close()
        }
    }

    // Pixel-exact quarter turns (nearest neighbour): bilinear filtering would
    // blur the narrow MSI bars this test depends on.
    private fun rotate(src: Bitmap, degrees: Int): Bitmap {
        if (degrees % 360 == 0) return src
        val m = Matrix().apply { postRotate(degrees.toFloat()) }
        return Bitmap.createBitmap(src, 0, 0, src.width, src.height, m, false)
    }
}
