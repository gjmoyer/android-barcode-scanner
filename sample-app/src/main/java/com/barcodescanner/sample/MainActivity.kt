package com.barcodescanner.sample

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
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
 * Minimal host-app integration — copy this pattern into ANY app:
 *
 * 1. `BarcodeScannerFactory.create(this, config)` (one liner, no DI framework needed),
 * 2. `scanner.startCamera(this, previewView)` for live mode,
 * 3. collect [BarcodeScannerFacade.results] for decoded barcodes,
 * 4. `scanner.close()` in onDestroy.
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
    private lateinit var status: TextView
    private lateinit var preview: PreviewView

    private val cameraPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            status.text = if (granted) "Camera granted — starting…" else "Camera denied"
            if (granted) startScanner()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        status = TextView(this).apply { text = "Initializing…" }
        preview = PreviewView(this).apply {
            scaleType = PreviewView.ScaleType.FILL_CENTER
        }
        val toggleRobust = Button(this).apply { text = "Robust mode: ON" }
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(preview, LinearLayout.LayoutParams(-1, 0, 1f))
            addView(toggleRobust)
            addView(status)
        }
        setContentView(layout)

        // Example: all symbologies incl. DataBar + MSI, robust orientations for warehouse labels.
        recreateScanner(robust = true)

        // Or limit to what you need for speed:
        // val config = ScannerConfigBuilder()
        //     .only(Symbology.QR_CODE, Symbology.DATA_BAR_EXPANDED, Symbology.MSI_PLESSEY)
        //     .build()

        toggleRobust.setOnClickListener {
            // Re-creating with a different config is the supported way to switch modes.
            robust = !robust
            recreateScanner(robust)
            toggleRobust.text = if (robust) "Robust mode: ON" else "Robust mode: OFF"
            startScanner()
        }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            startScanner()
        } else {
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
                        val b = result.barcode
                        val rot = if (result.fromRotatedFrame) " (rotated)" else ""
                        status.text = "${b.symbology.displayName}: ${b.rawValue} (${b.engineName})$rot"
                    }
                }
            }
        }
    }

    private fun startScanner() {
        try {
            scanner?.startCamera(this, preview)
            status.text = "Scanning… point at a barcode (QR, DataBar, MSI…)"
        } catch (t: Throwable) {
            status.text = "Start failed: ${t.message}"
        }
    }

    override fun onPause() {
        scanner?.stopCamera()
        super.onPause()
    }

    override fun onResume() {
        super.onResume()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            startScanner()
        }
    }

    override fun onDestroy() {
        collectJob?.cancel()
        scanner?.close()
        scanner = null
        super.onDestroy()
    }
}
