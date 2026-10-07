package com.barcodescanner.sample

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.barcodescanner.sdk.api.BarcodeScannerFactory
import com.barcodescanner.sdk.api.BarcodeScannerFacade
import com.barcodescanner.sdk.api.MsiChecksumPolicy
import com.barcodescanner.sdk.api.ScanResult
import com.barcodescanner.sdk.api.ScannerConfigBuilder
import com.barcodescanner.sdk.domain.model.Symbology
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Sample host-app integration — copy this pattern into ANY app:
 *
 * 1. `BarcodeScannerFactory.create(this, config)` (one liner, no DI framework needed),
 * 2. `scanner.startCamera(this, previewView)` for live preview + decode,
 * 3. collect [BarcodeScannerFacade.results] for decoded barcodes,
 * 4. `scanner.close()` in onDestroy.
 *
 * Scan flow in this sample: the camera stays off until SCAN is tapped, so no
 * constant scanning / decode churn happens in the background. A tap engages
 * the camera for a one-shot capture; the next decoded barcode beeps + vibrates,
 * disengages the camera and populates the result card
 * (value / symbology / engine). Tap SCAN again for the next code.
 *
 * To use the SDK from another app module, add to that app's build.gradle.kts:
 * ```
 * dependencies { implementation(project(":barcode-scanner-sdk")) }
 * ```
 * or publish the SDK to Maven and depend on the artifact instead.
 */
class MainActivity : ComponentActivity() {

    private var scanner: BarcodeScannerFacade? = null
    private var collectJob: Job? = null
    /** Checksum policy under test (tap to cycle — labels vary by printer). */
    private var msiPolicy = MsiChecksumPolicy.MOD_10

    /** True after tapping SCAN, until the next successful decode. */
    private var awaitingScan = false

    private lateinit var status: TextView
    private lateinit var resultValue: TextView
    private lateinit var resultSymbology: TextView
    private lateinit var resultMethod: TextView
    private lateinit var resultDetail: TextView
    private lateinit var scanButton: Button
    private lateinit var togglePolicy: Button
    private lateinit var preview: PreviewView

    private var toneGenerator: ToneGenerator? = null

    private val cameraPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            status.text = if (granted) READY_TEXT else "Camera denied — enable it in Settings"
            scanButton.isEnabled = granted
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val pad = (16 * resources.displayMetrics.density).toInt()
        preview = PreviewView(this).apply {
            scaleType = PreviewView.ScaleType.FILL_CENTER
        }
        // Viewfinder box: same upright fractions as ScannerConfig.scanRegion
        // below (approximate alignment — the box is generous on purpose).
        val viewfinder = ViewfinderView(
            this,
            widthFraction = VIEWFINDER_W,
            heightFraction = VIEWFINDER_H,
        )
        val previewStack = android.widget.FrameLayout(this).apply {
            addView(preview, android.widget.FrameLayout.LayoutParams(-1, -1))
            addView(viewfinder, android.widget.FrameLayout.LayoutParams(-1, -1))
        }
        scanButton = Button(this).apply { text = "Scan" }
        togglePolicy = Button(this).apply { text = "Checksum: MOD_10" }

        val buttonRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(scanButton, LinearLayout.LayoutParams(0, -2, 1f))
            addView(togglePolicy, LinearLayout.LayoutParams(0, -2, 1f))
        }

        resultValue = TextView(this).apply {
            text = "Value: —"
            textSize = 16f
            setTextIsSelectable(true)
        }
        resultSymbology = TextView(this).apply { text = "Symbology: —" }
        resultMethod = TextView(this).apply { text = "Method: —" }
        resultDetail = TextView(this).apply { text = "" }
        val resultCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad / 2, pad, pad / 2)
            addView(resultValue)
            addView(resultSymbology)
            addView(resultMethod)
            addView(resultDetail)
        }

        status = TextView(this).apply { text = "Initializing…" }

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
            addView(previewStack, LinearLayout.LayoutParams(-1, 0, 1f))
            addView(buttonRow)
            addView(resultCard)
            addView(status)
        }
        setContentView(layout)

        // Example: all symbologies incl. DataBar + MSI.
        recreateScanner()

        // Or limit to what you need for speed:
        // val config = ScannerConfigBuilder()
        //     .only(Symbology.QR_CODE, Symbology.DATA_BAR_EXPANDED, Symbology.MSI_PLESSEY)
        //     .build()

        scanButton.setOnClickListener {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) !=
                PackageManager.PERMISSION_GRANTED
            ) {
                cameraPermission.launch(Manifest.permission.CAMERA)
                return@setOnClickListener
            }
            awaitingScan = true
            clearResultCard()
            scanButton.text = "Scanning… tap a barcode"
            scanButton.isEnabled = false
            status.text = "Scanning… point at a barcode (QR, DataBar, MSI…)"
            // Engage the camera only now — no constant scanning.
            startScanner()
        }

        togglePolicy.setOnClickListener {
            // Cycle the MSI checksum policy: printers vary (single Mod10 vs
            // double Mod1010), and the emitted value depends on it — compare
            // the result card against the printed SKU on each setting.
            msiPolicy = when (msiPolicy) {
                MsiChecksumPolicy.MOD_10 -> MsiChecksumPolicy.MOD_11
                MsiChecksumPolicy.MOD_11 -> MsiChecksumPolicy.MOD_10_10
                MsiChecksumPolicy.MOD_10_10 -> MsiChecksumPolicy.MOD_10_11
                MsiChecksumPolicy.MOD_10_11 -> MsiChecksumPolicy.MOD_10
                // NONE is debug-only and never selected by the cycler.
                MsiChecksumPolicy.NONE -> MsiChecksumPolicy.MOD_10
            }
            recreateScanner()
            togglePolicy.text = "Checksum: $msiPolicy"
            if (awaitingScan) startScanner() else status.text = READY_TEXT
        }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            status.text = READY_TEXT
        } else {
            status.text = "Waiting for camera permission…"
            cameraPermission.launch(Manifest.permission.CAMERA)
        }
    }

    private fun recreateScanner() {
        collectJob?.cancel()
        scanner?.close()
        val config = ScannerConfigBuilder()
            .enabledSymbologies(Symbology.entries.filter { it != Symbology.UNKNOWN }.toSet())
            .msiChecksumPolicy(msiPolicy)
            // Shelf SKUs are 6+ digits; a lower floor lets blurry live frames
            // emit short false positives (e.g. "0128") with 2 correlated votes.
            .msiMinPayloadDigits(6)
            // Printed shelf SKUs omit the check digit (it lives in the barcode),
            // so trust the OCR read as printed (emitted at confidence 0.5).
            .msiOcrRequireChecksum(false)
            // OCR is the last engine; give the fallback room to run after the
            // bar engines on a bad label (default 1.5s cut it off).
            .decodeTimeoutMillis(4_000)
            // Viewfinder ROI: decode only the aimed box (faster frames, less
            // competing print). Fractions match the ViewfinderView overlay.
            .scanRegion(VIEWFINDER_W, VIEWFINDER_H)
            // Debug: persist the first live MsiOcr frame as PNG for offline
            // bar-miss forensics (adb pull .../files/ocr-debug).
            .debugOcrFrameDump(true)
            .build()
        scanner = BarcodeScannerFactory.create(this, config)
        bindResults()
    }

    private fun bindResults() {
        collectJob?.cancel()
        val current = scanner ?: return
        collectJob = lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                current.results.collect { result ->
                    if (result is ScanResult.Success) {
                        onScanSuccess(result)
                    }
                }
            }
        }
    }

    private fun onScanSuccess(result: ScanResult.Success) {
        // Only beep/vibrate/report while a SCAN is armed — otherwise the live
        // preview would chime on every passing barcode.
        if (!awaitingScan) return
        awaitingScan = false

        val b = result.barcode
        // OCR shelf-tag reads carry the paired GTIN (sku↔gtin must be validated
        // against the same backend record — that join is the checksum the
        // printed SKU lacks). Bar reads never set it.
        resultValue.text = if (b.gtin != null) {
            "SKU: ${b.rawValue}  GTIN: ${b.gtin}"
        } else {
            "Value: ${b.rawValue}"
        }
        resultSymbology.text = "Symbology: ${b.symbology.displayName}"
        resultMethod.text = "Method: ${b.engineName}${friendlyEngineSuffix(b.engineName)}"
        val rotated = if (result.fromRotatedFrame) "rotated frame" else "upright frame"
        val checksum = if (b.checksumStripped) ", checksum verified" else ""
        val pair = if (b.gtin != null) ", pair-check via record lookup" else ""
        resultDetail.text = "($rotated, confidence ${"%.2f".format(b.confidence)}$checksum$pair)"

        beep()
        vibrate()

        // One-shot capture complete: disengage the camera until the next Scan.
        scanner?.stopCamera()

        status.text = "Scanned ${b.symbology.displayName} via ${b.engineName} — tap Scan for the next code."
        scanButton.text = "Scan Again"
        scanButton.isEnabled = true
    }

    private fun clearResultCard() {
        resultValue.text = "Value: —"
        resultSymbology.text = "Symbology: —"
        resultMethod.text = "Method: —"
        resultDetail.text = ""
    }

    /** Human hint for the raw [com.barcodescanner.sdk.domain.model.DecodedBarcode.engineName]. */
    private fun friendlyEngineSuffix(engineName: String): String = when (engineName) {
        "MLKit" -> " (Google ML Kit)"
        "ZXingCpp" -> " (ZXing C++ fallback — e.g. DataBar)"
        "MsiPlessey" -> " (custom MSI scanline decoder)"
        "MsiRoi" -> " (ML Kit ROI + MSI)"
        "MsiOcr" -> " (OCR text fallback)"
        else -> ""
    }

    private fun beep() {
        try {
            if (toneGenerator == null) {
                toneGenerator = ToneGenerator(AudioManager.STREAM_MUSIC, 100)
            }
            toneGenerator?.startTone(ToneGenerator.TONE_PROP_BEEP, 150)
        } catch (_: Exception) {
            // Sample-app feedback must never crash a scan.
        }
    }

    private fun vibrate() {
        try {
            val vibrator: Vibrator? =
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    getSystemService(VibratorManager::class.java)?.defaultVibrator
                } else {
                    @Suppress("DEPRECATION")
                    getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
                }
            vibrator?.let {
                // minSdk 28 > O: VibrationEffect is always available here.
                it.vibrate(
                    VibrationEffect.createOneShot(
                        200,
                        VibrationEffect.DEFAULT_AMPLITUDE,
                    ),
                )
            }
        } catch (_: Exception) {
            // Sample-app feedback must never crash a scan.
        }
    }

    private fun startScanner() {
        try {
            scanner?.startCamera(this, preview)
        } catch (t: Throwable) {
            status.text = "Start failed: ${t.message}"
            awaitingScan = false
            scanButton.text = "Scan"
            scanButton.isEnabled = true
        }
    }

    override fun onPause() {
        scanner?.stopCamera()
        super.onPause()
    }

    override fun onResume() {
        super.onResume()
        val granted = ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED
        // Camera stays off unless a capture is armed; resume restarts mid-scan.
        if (awaitingScan && granted) {
            startScanner()
        } else if (granted) {
            // Re-enable after the user granted the permission in Settings.
            scanButton.isEnabled = true
        }
    }

    override fun onDestroy() {
        collectJob?.cancel()
        scanner?.close()
        scanner = null
        runCatching { toneGenerator?.release() }
        toneGenerator = null
        super.onDestroy()
    }

    private companion object {
        const val READY_TEXT = "Ready — tap Scan, aim inside the box, then point at a barcode (QR, DataBar, MSI…)"
        /** Viewfinder fractions (upright): must match scanRegion() in recreateScanner. */
        const val VIEWFINDER_W = 0.9f
        const val VIEWFINDER_H = 0.5f
    }
}
