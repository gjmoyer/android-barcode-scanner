package com.barcodescanner.sdk.device

import android.graphics.BitmapFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.barcodescanner.sdk.api.BarcodeScannerFactory
import com.barcodescanner.sdk.api.MsiChecksumPolicy
import com.barcodescanner.sdk.api.ScanResult
import com.barcodescanner.sdk.api.ScannerConfigBuilder
import com.barcodescanner.sdk.data.zxingcpp.ZXingCppBridge
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * On-device verification suite: runs the FULL production chain (ML Kit + real
 * zxing-cpp native library + MSI + OCR, all real models) against shelf photos.
 *
 * Photos are NOT committed (user-supplied): drop PNGs named like
 * `msi-<label>-<sku>.png` into `src/androidTest/assets/msi_samples/` to run.
 * With an empty dir the test passes vacuously. Requires an emulator or device
 * (API 28+): `./gradlew :barcode-scanner-sdk:connectedDebugAndroidTest`.
 *
 * Results are both asserted (known-exact cases) and written to the test app's
 * external files dir (`msi_device_results.txt`) for full review.
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
        out.appendLine("NATIVE_ZXING_AVAILABLE=${ZXingCppBridge.isAvailable}")
        // Known-exact expectations (robust mode, correct per-label policy).
        val expected = mapOf(
            "msi-quakotml-0186477.png" to
                Expectation(MsiChecksumPolicy.MOD_10_10, "0186477"),
            "msi-starbucks-0168971.png" to
                Expectation(MsiChecksumPolicy.MOD_10, "0168971"),
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
                    .robustMode(true)
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
                .robustMode(true)
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
}
