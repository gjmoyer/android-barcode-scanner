# BarcodeScanner — reusable Android barcode scanning module

Drop-in Android library that scans **all common symbologies plus GS1 DataBar and
MSI Plessey**, robust to **blur, rotation, upside-down and inverted** labels, with a
point-and-scan live mode.

| Symbology | Engine |
|---|---|
| EAN-8/13, UPC-A/E, Code 39/93/128, ITF, Codabar, QR, Aztec, PDF-417, Data Matrix | ML Kit (primary, fast) |
| GS1 DataBar (Omni / Stacked / Limited / Expanded / Expanded Stacked) | zxing-cpp v3.1.1 via JNI (`libzxing_bridge.so`) |
| MSI Plessey (Mod10 / Mod11 / Mod1010 / Mod1110) | Custom pure-Kotlin scanline decoder (`data/msi/`) |
| Rotated / upside-down / blurred | Preprocessing + orientation fusion + multi-binarization |

Why this exists: neither ML Kit nor zxing-cpp ships an MSI Plessey reader, so MSI is
decoded by a custom engine (spec-verified tables, subpixel edge localization, true
reverse-direction reading for upside-down labels). See
[`docs/MSI_PLESSEY_RESEARCH.md`](docs/MSI_PLESSEY_RESEARCH.md) for the full write-up.

## Requirements

- Android Studio with **SDK 36+** (compileSdk 37, targetSdk 36), **NDK 28.2+**,
  **CMake 3.22+** (SDK Manager → SDK Tools → NDK, CMake)
- AGP 9.4.0, Kotlin 2.4.20 (built-in — no kotlin-android plugin), Gradle 9.6.0, JDK 17+
- minSdk 28 (Android 9). First build fetches **zxing-cpp v3.1.1** via CMake
  `FetchContent` — needs network once (readers-only C++20 build, no extra deps).

## Open in Android Studio

1. `File → Open…` → select this folder.
2. Let Gradle sync.
3. Run the `sample-app` on a device to try live scanning.

## Use in any app

```kotlin
// settings.gradle.kts — include the module (or copy the barcode-scanner-sdk folder)
include(":barcode-scanner-sdk")

// app/build.gradle.kts
dependencies { implementation(project(":barcode-scanner-sdk")) }
```

```kotlin
// Live camera mode (see sample-app/.../MainActivity.kt for the full pattern)
val scanner = BarcodeScannerFactory.create(this, ScannerConfig.robust())
scanner.startCamera(this, previewView)
lifecycleScope.launch {
    scanner.results.collect { result ->
        if (result is ScanResult.Success) {
            val b = result.barcode // rawValue, symbology, confidence, engineName, ...
        }
    }
}
// onDestroy: scanner.close()

// One-shot (gallery import, tests)
val result = scanner.scanBitmap(bitmap)
```

Host apps only touch `api/` (`BarcodeScannerFacade`, `BarcodeScannerFactory`,
`ScannerConfig`, `ScanResult`) plus `domain/model/` (`Symbology`, `DecodedBarcode`).
No DI framework is imposed (manual `di/` container inside the SDK).

## Configuration

```kotlin
val config = ScannerConfigBuilder()
    .only(Symbology.QR_CODE, Symbology.DATA_BAR_EXPANDED, Symbology.MSI_PLESSEY)
    .robustMode(true)                        // 4 orientations + extra MSI passes
    .msiChecksumPolicy(MsiChecksumPolicy.MOD_10)
    .decodeTimeoutMillis(1500)
    .duplicateSuppressionMillis(1500)        // live-mode repeat suppression
    .minConfidence(0.5f)
    .build()
```

- `robustMode(false)` (default): 0°+180° orientations, faster per-frame scans;
  `robustMode(true)`: all 4 orientations, vertical MSI scanlines, extra
  binarizations, zxing `TryHarder`.
- MSI checksum must match the label spec; a wrong policy yields `NotFound`
  (never a misread presented as valid — see policy matrix below).
- Duplicate suppression only affects the live `results` flow; one-shot
  `scanBitmap`/`scanFrame` always return the decode result.

## Layout

```
barcode-scanner-sdk/          # the reusable library (only depend on this)
  src/main/cpp/               # JNI bridge → zxing-cpp (DataBar path)
  src/main/java/com/barcodescanner/sdk/
    api/                      # public surface: facade, factory, config, result
    domain/model/             # Symbology, ScannerConfig, ScanFrame, DecodedBarcode
    domain/decoder/           # BarcodeDecoder strategy + DecoderRegistry
    domain/pipeline/          # downscale/blur/contrast/orientation preprocessing
    data/mlkit/               # primary engine
    data/zxingcpp/            # DataBar engine (JNI)
    data/msi/                 # custom MSI Plessey engine
    data/fusion/              # multi-engine, multi-orientation voter
    camera/                   # CameraX frame producer (single-flight, conflated)
    di/                       # manual DI (internal)
  src/test/                   # unit tests incl. Zint-rendered MSI fixtures
sample-app/                   # demo host app
docs/                         # ARCHITECTURE.md, MSI_PLESSEY_RESEARCH.md, REVIEW_LOG.md
```

## Robustness recipe (blur / rotation / upside-down)

1. **Contrast normalization** per frame (histogram stretch).
2. **Sharpness scoring** (Laplacian variance) — blurry frames still attempted, sharpest wins.
3. **Orientation expansion** — 0° → 180° → 90° → 270°, materialized lazily so an
   early hit never allocates unneeded rotations.
4. **Per-engine retries** — zxing `TryRotate`/`TryInvert`/`TryHarder`; MSI
   multi-binarization (Otsu, background-subtraction, adaptive) × scanlines ×
   subpixel gray runs × min-projection bands, forward + true reverse-direction
   decode, soft least-squares digits.
5. **Fusion voting** — score = payload length × agreeing observations (≥2 required);
   checksum-verified hits win at confidence 1.0.

## Performance (point-and-scan latency)

Design rule: pristine labels — the overwhelmingly common case — take the fast path;
difficult labels may take the thorough path. Measured on desktop JVM (devices run
~2–4× for CPU-bound Kotlin; ML Kit/ZXing inference dominates on device):

| Stage (pristine MSI, 762×300) | Default | Robust |
|---|---|---|
| Preprocessing (downscale + blur score + contrast) | ~11 ms | ~11 ms |
| MSI decode (Zint-rendered fixtures, incl. 2px modules) | 10–30 ms | 20–100 ms |
| Upside-down / sideways pristine | — (fusion 180°/90°) | 30–90 ms |

Live-mode recipe (already wired, no host code needed):
- Fusion tries engines in registration order with first-confident-wins: a pointed
  QR/EAN/Code128 resolves on ML Kit alone (~1 frame, no fallback cost).
- Orientation bitmaps materialize lazily — an upright hit never allocates rotations.
- `ZXingCppDecoder` runs `TryHarder` only in robust mode (`thorough = robustMode`);
  rotation/inversion retries stay on in both modes.
- `CameraScanManager` is single-flight (`STRATEGY_KEEP_ONLY_LATEST` + decode gate):
  analysis runs at 1/latency fps instead of queueing — a slow frame delays the next
  attempt, never piles up work.
- Per-frame worst case is capped by `ScannerConfig.decodeTimeoutMillis` (default
  1500 ms); lower it (~500 ms) if your live UI must never wait on a pathological frame.
- `MsiPristineTest` pins the contract: Zint-rendered fixtures (all checksums, tiny
  modules, upside-down, sideways) must decode exactly, each in < 5 s (blowup guard).

## MSI Plessey notes

Checksum policies: `MOD_10` (default), `MOD_11` (IBM weights 2..7),
`MOD_10_10` (Mod1010 double-Mod10), `MOD_10_11` (Mod1110: Mod11 then Mod10),
`NONE` (debug only; all policies require ≥2 agreeing observations regardless).
Encoding table + guards verified against Wikipedia "MSI Barcode" + Morovia KB10637
(see [`docs/MSI_PLESSEY_RESEARCH.md`](docs/MSI_PLESSEY_RESEARCH.md)); NCR Mod11
(weights 2..9) and white-on-black MSI are documented limitations. Robust mode adds
vertical scanlines for 90° labels; upside-down labels decode via the reverse
run-direction path.

## Testing

```bash
./gradlew :barcode-scanner-sdk:testDebugUnitTest   # unit tests (Robolectric + JUnit)
./gradlew :sample-app:assembleDebug                # sample APK (builds native lib too)
```

## License

MIT — see [LICENSE](LICENSE).
