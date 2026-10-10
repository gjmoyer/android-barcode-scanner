package com.barcodescanner.sdk.device

import android.graphics.BitmapFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.barcodescanner.sdk.api.BarcodeScannerFactory
import com.barcodescanner.sdk.api.MsiChecksumPolicy
import com.barcodescanner.sdk.api.ScanResult
import com.barcodescanner.sdk.api.ScannerConfigBuilder
import com.barcodescanner.sdk.data.zxingcpp.ZXingCppDecoder
import com.barcodescanner.sdk.domain.pipeline.OrientationCandidates
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
 * (`tools/msi-harness`, 39/39 asserts green):
 * - yakult    0828147 -> 08281479    (MOD_10)
 * - quaker    0186477 -> 018647768   (MOD_10_10)
 * - onedegree 0243523 -> 02435238    (MOD_10; KNOWN GAP — phantoms validate,
 *   true bars don't resolve; swept/logged, not hard-asserted)
 * - dixie     0087573 -> 00875732    (MOD_10)
 * - starbucks 0168971 -> 016897100   (MOD_10_10)
 * - silk      0826593 -> 082659368   (bars read 8/9 digits; MOD_10 yields the
 *   payload, MOD_10_10 honestly misses)
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
        // Known-exact expectations (correct per-label policy + payload).
        val expected = mapOf(
            "msi-yakult-0828147.png" to
                Expectation(MsiChecksumPolicy.MOD_10, "0828147"),
            "msi-quakotml-0186477.png" to
                Expectation(MsiChecksumPolicy.MOD_10_10, "0186477"),
            "msi-dixie-0087573.png" to
                Expectation(MsiChecksumPolicy.MOD_10, "0087573"),
            "msi-starbucks-0168971.png" to
                Expectation(MsiChecksumPolicy.MOD_10_10, "0168971"),
            "msi-silkalm-0826593.png" to
                Expectation(MsiChecksumPolicy.MOD_10, "0826593"),
            // ondeg deliberately absent: known accuracy gap (see class KDoc).
        )
        val policies = listOf(
            MsiChecksumPolicy.MOD_10,
            MsiChecksumPolicy.MOD_11,
            MsiChecksumPolicy.MOD_10_10,
            MsiChecksumPolicy.MOD_10_11,
        )
        for (name in assetNames) {
            val expectedSku = name.substringAfterLast("-").substringBeforeLast(".")
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
                            val hit = b.rawValue == expectedSku ||
                                b.rawValue == expectedSku.dropLast(1)
                            "RESULT|$name|$policy|SUCCESS|${b.engineName}|${b.rawValue}|" +
                                "${b.confidence}|${ms}ms|MATCH=$hit"
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
                        (result as ScanResult.Success).barcode.rawValue == exp.sku,
                )
            } finally {
                scanner.close()
            }
            bmp!!.recycle()
        }
    }

    /**
     * Repeat-stability sweep over the shelf fixtures: each photo is scanned
     * three times under its ground-truth policy and every repeat must return
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
                Expectation(MsiChecksumPolicy.MOD_10, "0828147"),
            "msi-quakotml-0186477.png" to
                Expectation(MsiChecksumPolicy.MOD_10_10, "0186477"),
            "msi-dixie-0087573.png" to
                Expectation(MsiChecksumPolicy.MOD_10, "0087573"),
            "msi-starbucks-0168971.png" to
                Expectation(MsiChecksumPolicy.MOD_10_10, "0168971"),
            "msi-silkalm-0826593.png" to
                Expectation(MsiChecksumPolicy.MOD_10, "0826593"),
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
                            (result as ScanResult.Success).barcode.rawValue == exp.sku,
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
                        val raw = OrientationCandidates.rotate(upright!!, contentRotation)
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
}
