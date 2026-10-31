# BarcodeScanner — reusable Android barcode scanning module

Drop-in Android library that scans **all common symbologies plus GS1 DataBar and
MSI Plessey**, robust to **blur, rotation, and upside-down** labels, with a
point-and-scan live mode. (Inversion is covered for ML Kit/zxing symbologies;
inverted MSI remains out of scope.)

| Symbology | Engine |
|---|---|
| EAN-8/13, UPC-A/E, Code 39/93/128, ITF, Codabar, QR, Aztec, PDF-417, Data Matrix | ML Kit (primary, fast) |
| GS1 DataBar (Omni / Stacked / Limited / Expanded / Expanded Stacked) | zxing-cpp v3.1.1 prebuilt AAR (`io.github.zxing-cpp:android`) |
| MSI Plessey (Mod10 / Mod11 / Mod1010 / Mod1110) | Custom native C++ scanline decoder (`src/main/cpp/`, via `MsiNativeDecoder`); one native-prepared frame per scan, with an in-decoder skew retry (±15°) for tilted labels |
| Rotated / upside-down / blurred | Native frame prepare (rotate upright) + per-engine orientation + signal-template correlation |

Why this exists: neither ML Kit nor zxing-cpp ships an MSI Plessey reader, so MSI is
decoded by a custom engine (clean-room C++ signal-template correlation — no
binarization threshold to tune, survives the blur that defeats width
measurement). See
[`docs/MSI_PLESSEY_RESEARCH.md`](docs/MSI_PLESSEY_RESEARCH.md) for the full write-up.

## Requirements

- Android Studio with **SDK 37** (compileSdk 37; sample app targetSdk 37)
- AGP 9.4.1, Kotlin 2.4.21 (built-in — no kotlin-android plugin), Gradle 9.8.2, JDK 25 for the Gradle daemon (pinned by `gradle/gradle-daemon-jvm.properties`; Gradle downloads it if missing; app bytecode targets Java 17)
- Android NDK + CMake 4.1.2 (the SDK compiles its own MSI decoder from
  `src/main/cpp/`; zxing-cpp v3.1.1 arrives as a prebuilt Maven Central AAR —
  network fetch happens once via Gradle)
- minSdk 28 (Android 9). Native libraries ship for `armeabi-v7a`,
  `arm64-v8a` and `x86_64`. Without one (32-bit x86, or a host app whose
  `abiFilters` exclude the device ABI) the live camera runs preview-only and
  reports `CameraStatus.Failed`, and stills return `ScanResult.Failure`.

## Open in Android Studio

1. `File → Open…` → select this folder.
2. Let Gradle sync.
3. Run the `sample-app` on a device to try live scanning. While scanning,
   the **Torch** button toggles the flash (re-applied on each one-shot scan),
   pinch zooms and tap focuses; camera failures show in the status line.

## Use in any app

```kotlin
// settings.gradle.kts — include the module (or copy the barcode-scanner-sdk folder)
include(":barcode-scanner-sdk")

// app/build.gradle.kts
dependencies { implementation(project(":barcode-scanner-sdk")) }
```

Live camera mode needs the camera permission (also declared by the SDK manifest,
so it merges automatically — API 23+ hosts must still request it at runtime,
see the sample app) and a camera feature declaration in the host manifest:

```xml
<uses-feature android:name="android.hardware.camera" android:required="false" />
```

The SDK itself never vibrates or beeps — scan feedback is host UX. If you copy
the sample's beep+vibrate pattern, also declare (normal level, auto-granted,
no runtime prompt — without it `vibrate()` throws `SecurityException`):

```xml
<uses-permission android:name="android.permission.VIBRATE" />
```

```kotlin
// Live camera mode (see sample-app/.../MainActivity.kt for the full pattern)
val scanner = BarcodeScannerFactory.create(this, ScannerConfig.default())
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
`ScannerConfig`, `ScanResult`, `CameraStatus`) plus `domain/model/` (`Symbology`, `DecodedBarcode`).
No DI framework is imposed (manual `di/` container inside the SDK).

## Viewfinder overlay (host UI)

All camera work lives in the SDK (`camera/` — CameraX binding, 16:9 preview and
~1080p analysis sharing one field of view, single-flight conflation, and ONE
native pass per accepted frame that crops the aim box out of the Y plane,
contrast-stretches it and rotates it upright; every engine then reads that one
buffer in place). The host owns only the preview surface and the overlay drawing:

- Pass a `PreviewView` to `startCamera` (or any `Preview.SurfaceProvider` for
  Compose/headless — overlays are then the host's own drawing).
- What-you-see-is-what-scans: the SDK crops analysis to the displayed area and
  places `scanRegion` inside it, so draw the same centered fractions on the
  `PreviewView` (copy `sample-app/.../ViewfinderView.kt`) and the box on screen
  is exactly the box being decoded. A barcode outside the drawn box cannot
  decode — keep it generous. Without a `PreviewView`, fractions fall back to
  the sensor crop.
- Camera controls for the live session: `setTorchEnabled(on) { accepted -> }`
  with the actual state on `torchEnabled`, `scaleZoom(gestureScale)` for pinch
  zoom, and `focusAt(previewView.meteringPointFactory.createPoint(x, y))` for
  tap-to-focus (continuous autofocus resumes after ~3 s). All are no-ops (torch
  reports false) without a running camera.
- `cameraStatus` (`Idle`/`Starting`/`Running`/`InUse`/`Failed`) explains a black
  preview: show a hint on `InUse` (another app holds the camera; CameraX reopens
  it automatically) and stop + offer a retry on `Failed` (also reported once
  on `results` as a `ScanResult.Failure`). Results, status and torch state of a
  stopped session never publish into the next one.

## Configuration

```kotlin
val config = ScannerConfigBuilder()
    .only(Symbology.QR_CODE, Symbology.DATA_BAR_EXPANDED, Symbology.MSI_PLESSEY)
    .msiChecksumPolicy(MsiChecksumPolicy.MOD_10)
    .msiMinPayloadDigits(6)                  // reject short lucky MSI parses (shelf SKUs)
    .msiSymbolLength(9)                      // grocery shelf tags: 7-digit item + 2 checks
    .decodeTimeoutMillis(1500)               // whole-frame budget (all engines under one deadline)
    .duplicateSuppressionMillis(1500)        // live-mode repeat suppression
    .minConfidence(0.5f)
    .build()
```

- There is no fast/degraded mode: the pipeline is always thorough (multi-ROI MSI
  scanline voting with template correlation, zxing `TryHarder`) with
  cheap-first ordering + confident early-exit, so easy frames stay fast.
  `maxOrientationsTried` (default 4) bounds the MSI orientations tried natively
  on stills (live frames always try 2); `zxingTryHarder(false)` opts the native
  sweep out of thorough mode.
- MSI results are re-validated strictly under `msiChecksumPolicy` and short or
  degenerate reads are withheld — a wrong policy yields `NotFound`, never a
  misread presented as valid. The policy is also pushed into the native
  engine's candidate selection. Grocery shelf tags are 9-digit Mod1010 (item +
  two checks): use `MOD_10_10`; under `MOD_10` they emit the 8-digit
  `item + check` and lose truncation protection.
- `msiMinPayloadDigits` (default 3, spec floor): raise to ~6 for shelf SKUs;
  short MSI parses validate by luck and can win blurry live frames.
- `msiSymbolLength(n)` / `msiSymbolLengths(range)` (default null = any):
  accepted MSI symbol lengths, check digits included, independent of the
  checksum policy. Enforced in native candidate selection before voting, so a
  phantom of another length can't outvote the true read. Grocery shelf tags are
  9 digits; set it whenever your labels have a fixed length.
- Duplicate suppression only affects the live `results` flow; one-shot
  `scanBitmap`/`scanFrame` always return the decode result.

## Layout

```
barcode-scanner-sdk/          # the reusable library (only depend on this)
  src/main/java/com/barcodescanner/sdk/
    api/                      # public surface: facade, factory, config, result
    domain/model/             # Symbology, ScannerConfig, ScanFrame, DecodedBarcode
    domain/decoder/           # BarcodeDecoder strategy + DecoderRegistry
    data/frame/               # the one native-prepared LumaFrame per scan
    data/mlkit/               # primary engine
    data/zxingcpp/            # DataBar engine (prebuilt zxing-cpp AAR)
    data/msi/                 # custom MSI engine (native decoder, vote gate, tables)
    data/fusion/              # one LumaFrame read in place by every engine
    camera/                   # CameraX frame producer (single-flight, conflated)
    di/                       # manual DI (internal)
  src/test/                   # unit tests (Robolectric + JUnit)
sample-app/                   # demo host app
docs/                         # ARCHITECTURE.md, MSI_PLESSEY_RESEARCH.md
```

## Robustness recipe (blur / rotation / upside-down)

1. **Native frame prepare** — the aim box (live) or the whole still is cropped,
   gray-converted, contrast-stretched (live only) and rotated upright in one
   native pass; stills above 16 MP are box-downscaled by the smallest integer
   factor that fits. No Kotlin pixel work at all.
2. **Per-engine orientation** — frames arrive upright; ML Kit and zxing resolve
   rotation internally and MSI tries orientations natively (2 live, up to
   `maxOrientationsTried` for stills).
3. **Per-engine retries** — zxing `TryRotate`/`TryInvert`/`TryHarder`; MSI
    multi-ROI scanline voting with blurred digit-template correlation
    (shape matching, no binarization threshold), checksum gating, and
    small-module 1D/2D 2x retries.
4. **Fusion voting** — every engine reads the same frame; hits are deduped on
    value+symbology (max confidence kept), and a hit at confidence ≥0.95
    stops the scan immediately. MSI hits additionally require ≥2 agreeing
    scanlines, the configured checksum policy, and a non-degenerate payload,
    and emit at confidence 1.0.

## Performance (point-and-scan latency)

Design rule: pristine labels — the overwhelmingly common case — take the fast path;
difficult labels may take the thorough path. Measured numbers (host bench +
on-device full chain, Pixel 6a / emulator):

| Stage | Host (macOS, `tools/msi-harness` bench) | On-device, full chain (arm64) |
|---|---|---|
| Native MSI decode | ~0.7 ms mean (1280×720 synthetic, Apple M2 Max), ~0.7–1 ms (shelf photo) | single-digit ms inside the scan below |
| One-shot MSI `scanBitmap` | n/a (native absent on JVM) | ~15–60 ms typical |
| DataBar via zxing-cpp (native TryRotate/TryHarder) | n/a (native absent on JVM) | ~35–55 ms |
| Full-chain worst case | — | capped by `decodeTimeoutMillis` (default 1500 ms) |

Methodology, the full real-photo accuracy/tilt tables and scope boundaries
(including the private commercial-SDK comparison) are in
[`docs/BENCHMARKS.md`](docs/BENCHMARKS.md).

Live-mode recipe (already wired, no host code needed):
- Fusion tries engines in registration order with first-confident-wins: a pointed
  QR/EAN/Code128 resolves on ML Kit alone (~1 frame, no fallback cost).
- Every live frame and every still run the same engines on the one
  native-prepared frame — there is no separate ROI sweep and no ML Kit
  isolation pass. Live frames are already the aim-box crop; stills are read whole.
- Orientation work is per engine: ML Kit and zxing-cpp resolve rotation
  internally and MSI tries orientations natively, so no engine ever sees a
  second, physically rotated copy of the frame.
- `ZXingCppDecoder` runs `TryHarder` unless `ScannerConfig.zxingTryHarder(false)`
  opts out; rotation/inversion retries stay on either way.
- `CameraScanManager` crops, contrast-stretches and rotates the camera Y plane
  natively (no JPEG round-trip and no Kotlin pixel loop, so narrow bars survive)
  and is single-flight (`STRATEGY_KEEP_ONLY_LATEST` + decode gate):
  analysis runs at 1/latency fps instead of queueing — a slow frame delays the next
  attempt, never piles up work.
- Per-frame worst case is capped by `ScannerConfig.decodeTimeoutMillis` (default
  1500 ms; every engine runs under it). Lower it (~500 ms) if your live UI must
  never wait on a pathological frame.
- Why a budget at all: the camera gate is single-flight, so a pathological frame
  that takes 8 s freezes live scanning for 8 s. "Exhaust every option" is the
  intent — the engines all run on one-shot and stale frames — but
  cheap-first ordering plus a deadline keeps point-and-scan usable.
- `tools/msi-harness` pins the native contract on any dev machine (no device
  needed): spec-rendered vectors for all four checksum policies, degradation
  ladders, honest-`NotFound` negatives, per-photo sweeps at SDK-equivalent
  payload level, and a timing bench — exit 0 is green. Real shelf photos stay
  out of the repo; the harness sweeps local captures when present.

## MSI Plessey notes

Checksum policies: `MOD_10` (default), `MOD_11` (IBM weights 2..7; a computed
check of 10 is unrepresentable and rejected — no 0-mapping guess), `MOD_10_10`
(Mod1010 double-Mod10), `MOD_10_11` (Mod1110: Mod11 then Mod10), `NONE` (debug
only — accepted by the Kotlin validator but never emitted by the native
engine, which tries only the four real schemes).
Encoding table + guards verified against Wikipedia "MSI Barcode" + Morovia KB10637
(see [`docs/MSI_PLESSEY_RESEARCH.md`](docs/MSI_PLESSEY_RESEARCH.md)); NCR Mod11
(weights 2..9) and white-on-black MSI are documented limitations. Upside-down
reads are flagged via `DecodedBarcode.isUpsideDown`. Off-cardinal tilts up to
±15° decode through the native skew retry (projection-search deskew inside
the decoder); steeper tilts need a straighter aim, since the ML Kit ROI
assist was retired.

**Retired: SKU text fallback.** An OCR fallback (`MsiOcr`, bundled ML Kit text
recognition) used to read the printed SKU when bars failed. It was removed —
the native bar engine covers the rough captures that needed text backup, and
un-checksummed text reads trade recall for misread risk. A few inert API
remnants (`msiOcrRequireChecksum`, `debugOcrFrameDump`, `gtin` fields) remain
for source compatibility and do nothing.

## Testing

```bash
./gradlew :barcode-scanner-sdk:testDebugUnitTest   # unit tests (Robolectric + JUnit)
./gradlew :sample-app:assembleDebug                # sample APK
(cd tools/msi-harness && ./build.sh && ./msi_harness)  # native accuracy + speed (no device)
./gradlew :barcode-scanner-sdk:connectedDebugAndroidTest  # on-device full chain (needs device)
```

## License

Our code: MIT — see [LICENSE](LICENSE).

The built SDK links third-party libraries (notably zxing-cpp, Apache-2.0);
see [THIRD-PARTY-NOTICES.md](THIRD-PARTY-NOTICES.md) and pass those notices
on if you distribute the built artifacts.
