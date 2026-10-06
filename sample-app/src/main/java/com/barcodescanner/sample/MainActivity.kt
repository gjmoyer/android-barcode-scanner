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
    private var robust = true

    /** True after tapping SCAN, until the next successful decode. */
    private var awaitingScan = false

    private lateinit var status: TextView
    private lateinit var resultValue: TextView
    private lateinit var resultSymbology: TextView
    private lateinit var resultMethod: TextView
    private lateinit var resultDetail: TextView
    private lateinit var robustHelp: TextView
    private lateinit var scanButton: Button
    private lateinit var toggleRobust: Button
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
        scanButton = Button(this).apply { text = "Scan" }
        toggleRobust = Button(this).apply { text = "Robust mode: ON" }

        val buttonRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(scanButton, LinearLayout.LayoutParams(0, -2, 1f))
            addView(toggleRobust, LinearLayout.LayoutParams(0, -2, 1f))
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
        robustHelp = TextView(this).apply {
            textSize = 12f
            text = robustExplanation(true)
        }

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
            addView(preview, LinearLayout.LayoutParams(-1, 0, 1f))
            addView(buttonRow)
            addView(resultCard)
            addView(status)
            addView(robustHelp)
        }
        setContentView(layout)

        // Example: all symbologies incl. DataBar + MSI, robust orientations for warehouse labels.
        recreateScanner(robust = true)

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

        toggleRobust.setOnClickListener {
            // Re-creating with a different config is the supported way to switch modes.
            robust = !robust
            recreateScanner(robust)
            toggleRobust.text = if (robust) "Robust mode: ON" else "Robust mode: OFF"
            robustHelp.text = robustExplanation(robust)
            // Re-engage the camera only if a one-shot capture was in progress.
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

    private fun recreateScanner(robust: Boolean) {
        collectJob?.cancel()
        scanner?.close()
        val config = ScannerConfigBuilder()
            .enabledSymbologies(Symbology.entries.filter { it != Symbology.UNKNOWN }.toSet())
            .robustMode(robust)
            .msiChecksumPolicy(MsiChecksumPolicy.MOD_10)
            // Shelf SKUs are 6+ digits; a lower floor lets blurry live frames
            // emit short false positives (e.g. "0128") with 2 correlated votes.
            .msiMinPayloadDigits(6)
            // Printed shelf SKUs omit the check digit (it lives in the barcode),
            // so trust the OCR read as printed (emitted at confidence 0.5).
            .msiOcrRequireChecksum(false)
            // OCR is the last engine; give the fallback room to run after the
            // bar engines on a bad label (default 1.5s cut it off).
            .decodeTimeoutMillis(4_000)
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
        resultValue.text = "Value: ${b.rawValue}"
        resultSymbology.text = "Symbology: ${b.symbology.displayName}"
        resultMethod.text = "Method: ${b.engineName}${friendlyEngineSuffix(b.engineName)}"
        val rotated = if (result.fromRotatedFrame) "rotated frame" else "upright frame"
        val checksum = if (b.checksumStripped) ", checksum verified" else ""
        resultDetail.text = "($rotated, confidence ${"%.2f".format(b.confidence)}$checksum)"

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
        "MsiOcr" -> " (OCR text fallback)"
        else -> ""
    }

    /**
     * What the Robust toggle does: OFF tries 0°/180° only (fast, fine for
     * upright retail codes); ON also tries 90°/270° plus extra MSI
     * binarizations and wider fusion voting (slower, catches sideways /
     * upside-down warehouse labels).
     */
    private fun robustExplanation(robust: Boolean): String =
        if (robust) {
            "Robust mode ON: tries all 4 rotations (0°/90°/180°/270°) + extra MSI " +
                "binarizations. Slower, best for sideways/upside-down warehouse labels."
        } else {
            "Robust mode OFF: tries 0°/180° only. Faster, best for upright retail codes. " +
                "Turn ON if sideways or upside-down labels are missed."
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
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    it.vibrate(
                        VibrationEffect.createOneShot(
                            200,
                            VibrationEffect.DEFAULT_AMPLITUDE,
                        ),
                    )
                } else {
                    @Suppress("DEPRECATION")
                    it.vibrate(200)
                }
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
        const val READY_TEXT = "Ready — tap Scan, then point at a barcode (QR, DataBar, MSI…)"
    }
}
