# BarcodeScanner — reusable Android barcode scanning module

Drop-in Android library that scans **all common symbologies plus GS1 DataBar and
MSI Plessey**, robust to **blur, rotation, upside-down and inverted** labels, with a
point-and-scan live mode.

| Symbology | Engine |
|---|---|
| EAN-8/13, UPC-A/E, Code 39/93/128, ITF, Codabar, QR, Aztec, PDF-417, Data Matrix | ML Kit (primary, fast) |
| GS1 DataBar (Omni / Stacked / Limited / Expanded / Expanded Stacked) | zxing-cpp v3.1.1 prebuilt AAR (`io.github.zxing-cpp:android`) |
| MSI Plessey (Mod10 / Mod11 / Mod1010 / Mod1110) | ML Kit isolation (candidate boxes + deskew → `MsiRoi`), custom pure-Kotlin scanline decoder (`data/msi/`, full-frame fallback `MsiPlessey`) |
| Rotated / upside-down / blurred | Preprocessing + orientation fusion + multi-binarization |

Why this exists: neither ML Kit nor zxing-cpp ships an MSI Plessey reader, so MSI is
decoded by a custom engine (spec-verified tables, subpixel edge localization, true
reverse-direction reading for upside-down labels). See
[`docs/MSI_PLESSEY_RESEARCH.md`](docs/MSI_PLESSEY_RESEARCH.md) for the full write-up.

## Requirements

- Android Studio with **SDK 36+** (compileSdk 37, targetSdk 36)
- AGP 9.4.1, Kotlin 2.4.20 (built-in — no kotlin-android plugin), Gradle 9.6.0, JDK 17+
- minSdk 28 (Android 9). zxing-cpp v3.1.1 arrives as a prebuilt Maven Central AAR
  — no NDK/CMake source build, network fetch happens once via Gradle.

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
`ScannerConfig`, `ScanResult`) plus `domain/model/` (`Symbology`, `DecodedBarcode`).
No DI framework is imposed (manual `di/` container inside the SDK).

## Viewfinder overlay (host UI)

All camera work lives in the SDK (`camera/` — CameraX binding, stride-aware
YUV→ARGB, rotation, 1280px cap, single-flight conflation). The host owns only
the preview surface and the overlay drawing:

- Pass a `PreviewView` to `startCamera` (or any `Preview.SurfaceProvider` for
  Compose/headless — overlays are then the host's own drawing).
- What-you-see-is-what-scans: the SDK crops analysis to the displayed area and
  places `scanRegion` inside it, so draw the same centered fractions on the
  `PreviewView` (copy `sample-app/.../ViewfinderView.kt`) and the box on screen
  is exactly the box being decoded. A barcode outside the drawn box cannot
  decode — keep it generous. Without a `PreviewView`, fractions fall back to
  the sensor crop.

## Configuration

```kotlin
val config = ScannerConfigBuilder()
    .only(Symbology.QR_CODE, Symbology.DATA_BAR_EXPANDED, Symbology.MSI_PLESSEY)
    .msiChecksumPolicy(MsiChecksumPolicy.MOD_10)
    .msiMinPayloadDigits(6)                  // reject short lucky MSI parses (shelf SKUs)
    .msiOcrRequireChecksum(false)            // trust printed SKU (print omits check digit)
    .decodeTimeoutMillis(4000)               // leave room for the last-in-line OCR fallback
    .duplicateSuppressionMillis(1500)        // live-mode repeat suppression
    .minConfidence(0.5f)
    .build()
```

- There is no fast/degraded mode: the pipeline is always thorough (4 lazy
  orientations, extra MSI binarizations, vertical MSI scanlines, zxing
  `TryHarder`) with cheap-first ordering + confident early-exit, so easy frames
  stay fast. `maxOrientationsTried` (default 4) only bounds the view count for
  fixed-geometry hosts; `zxingTryHarder(false)` opts the native sweep out of
  thorough mode (MSI effort stays thorough).
- MSI checksum must match the label spec; a wrong policy yields `NotFound`
  (never a misread presented as valid — see policy matrix below).
- `msiMinPayloadDigits` (default 3, spec floor): raise to ~6 for shelf SKUs;
  short MSI parses validate by luck and can win blurry live frames with only
  two correlated observations.
- Printed shelf SKUs usually omit the check digit (it lives in the barcode), so
  the OCR fallback trusts the printed digits by default (`msiOcrRequireChecksum
  = false`) and emits them as `MsiOcr` at confidence 0.5. Set true to require
  validation (confidence 0.7) only if printed numbers carry their check digit.
  Because OCR runs last, budget `decodeTimeoutMillis` accordingly (~4 s).
- Duplicate suppression only affects the live `results` flow; one-shot
  `scanBitmap`/`scanFrame` always return the decode result.

## Layout

```
barcode-scanner-sdk/          # the reusable library (only depend on this)
  src/main/java/com/barcodescanner/sdk/
    api/                      # public surface: facade, factory, config, result
    domain/model/             # Symbology, ScannerConfig, ScanFrame, DecodedBarcode
    domain/decoder/           # BarcodeDecoder strategy + DecoderRegistry
    domain/pipeline/          # downscale/contrast/orientation preprocessing
    data/mlkit/               # primary engine
    data/zxingcpp/            # DataBar engine (prebuilt zxing-cpp AAR)
    data/msi/                 # custom MSI Plessey engine
    data/ocr/                 # MSI SKU text fallback (ML Kit text recognition)
    data/fusion/              # multi-engine, multi-orientation voter
    camera/                   # CameraX frame producer (single-flight, conflated)
    di/                       # manual DI (internal)
  src/test/                   # unit tests incl. Zint-rendered MSI fixtures
sample-app/                   # demo host app
docs/                         # ARCHITECTURE.md, MSI_PLESSEY_RESEARCH.md, REVIEW_LOG.md
```

## Robustness recipe (blur / rotation / upside-down)

1. **Bounded scale + contrast normalization** per frame (downscale to ≤1280,
   histogram stretch).
2. **Orientation expansion** — upright first (sensor-compensated), then
   upside-down, then sideways, materialized lazily so an early hit never
   allocates unneeded rotations.
3. **Per-engine retries** — zxing `TryRotate`/`TryInvert`/`TryHarder`; MSI
   multi-binarization (Otsu, background-subtraction, adaptive) × scanlines ×
   subpixel gray runs × min-projection bands, forward + true reverse-direction
   decode, soft least-squares digits.
4. **Fusion voting** — score = payload length × agreeing observations (≥2 required);
   checksum-verified hits win at confidence 1.0.

## Performance (point-and-scan latency)

Design rule: pristine labels — the overwhelmingly common case — take the fast path;
difficult labels may take the thorough path. JVM numbers below are desktop
(Robolectric); on-device full-chain measurements (Pixel 6a / emulator-5554,
see `docs/REVIEW_LOG.md` Pass 10) dominate in practice:

| Stage | Desktop JVM (Zint fixtures) | On-device (arm64) |
|---|---|---|
| Preprocessing (downscale + contrast) | ~8 ms | ~15–30 ms |
| MSI decode (pristine, incl. 2px modules) | 10–100 ms (always thorough) | 180–760 ms |
| DataBar via zxing-cpp (native TryRotate/TryHarder) | n/a (native absent on JVM) | 550–1150 ms (Expanded pays TryHarder) |
| Full-chain worst case (capped by `decodeTimeoutMillis`) | — | ~1.5 s at default 1500 ms |

Live-mode recipe (already wired, no host code needed):
- Fusion tries engines in registration order with first-confident-wins: a pointed
  QR/EAN/Code128 resolves on ML Kit alone (~1 frame, no fallback cost).
- MSI frames go through ML Kit isolation first (candidate boxes + deskewed
  crops via `MsiRoi`); an empty frame costs ~200ms and the next camera frame
  arrives — the full-frame sweep waits for one-shot scans and stale aim.
- Orientation work is routed per engine: ML Kit (rotation hint), zxing-cpp
  (native `TryRotate`) and `MsiRoi` (deskew) resolve orientation themselves and
  get the primary view once — physically rotated copies are never duplicate
  work. Full-frame MSI still receives every candidate on sweep frames.
- Orientation bitmaps materialize lazily — an upright hit never allocates rotations.
- `ZXingCppDecoder` runs `TryHarder` unless `ScannerConfig.zxingTryHarder(false)`
  opts out; rotation/inversion retries stay on either way.
- `CameraScanManager` converts YUV→ARGB directly (no JPEG round-trip, so narrow
  bars survive) and is single-flight (`STRATEGY_KEEP_ONLY_LATEST` + decode gate):
  analysis runs at 1/latency fps instead of queueing — a slow frame delays the next
  attempt, never piles up work.
- Per-frame worst case is capped by `ScannerConfig.decodeTimeoutMillis` (default
  1500 ms; preprocessing + orientations + engines all run under it). Lower it
  (~500 ms) if your live UI must never wait on a pathological frame.
- When a last-resort engine is registered (MSI OCR), the primary bar phase gets
  the first 60% of `decodeTimeoutMillis` and the rest is **reserved** for the
  fallback, which runs at most once per frame and only after every bar engine
  missed. Without the reservation, the thorough passes (orientations,
  binarizations, `TryHarder`) could consume the whole budget and OCR would
  never run on a bar miss. Budget ~4 s when relying on the OCR fallback.
- Why a budget at all: the camera gate is single-flight, so a pathological frame
  that takes 8 s freezes live scanning for 8 s. "Exhaust every option" is the
  intent — the sweep engines do all run on one-shot and stale frames — but
  cheap-first ordering plus a deadline keeps point-and-scan usable.
- `MsiPristineTest` pins the contract: Zint-rendered fixtures (all checksums, tiny
  modules, upside-down, sideways) must decode exactly, each under a 30 s
  absurd-blowup guard.

## MSI Plessey notes

Checksum policies: `MOD_10` (default), `MOD_11` (IBM weights 2..7; a computed
check of 10 is unrepresentable and rejected — no 0-mapping guess), `MOD_10_10`
(Mod1010 double-Mod10), `MOD_10_11` (Mod1110: Mod11 then Mod10), `NONE` (debug
only; all policies require ≥2 agreeing observations regardless).
Encoding table + guards verified against Wikipedia "MSI Barcode" + Morovia KB10637
(see [`docs/MSI_PLESSEY_RESEARCH.md`](docs/MSI_PLESSEY_RESEARCH.md)); NCR Mod11
(weights 2..9) and white-on-black MSI are documented limitations. Vertical
scanlines cover 90° labels (always on); upside-down labels decode via the
reverse run-direction path. Off-cardinal tilts (15°/30°/…) decode through the
ML Kit ROI path (`MsiRoi`: isolate + deskew, then the same decoder).

**SKU text fallback** (`MsiOcr`, bundled ML Kit text recognition, offline): when MSI
is enabled and every bar engine misses, the decoder reads the printed SKU next to
the barcode — digit runs anchored to content bands, preferring dash-free runs
(shelf case codes print dashed: `000-42000-15121`). Shelf labels where the bars
are unreadable but the text survives (e.g. scratched/occluded barcodes) still
scan. Shelf labels print the SKU WITHOUT the check digit(s), so by default
(`msiOcrRequireChecksum = false`) any plain 7+ digit run is emitted as printed at
**confidence 0.5**; set true to require validation (confidence 0.7) when printed
numbers do carry their check digit. Hits report `engineName="MsiOcr"` with the OCR
line's bounding box, so hosts can tell bar reads from text reads and gate
accordingly (raising `minConfidence` above 0.5 is the simplest filter for
unvalidated text reads).

## Testing

```bash
./gradlew :barcode-scanner-sdk:testDebugUnitTest   # unit tests (Robolectric + JUnit)
./gradlew :sample-app:assembleDebug                # sample APK
```

## License

Our code: MIT — see [LICENSE](LICENSE).

The built SDK links third-party libraries (notably zxing-cpp, Apache-2.0);
see [THIRD-PARTY-NOTICES.md](THIRD-PARTY-NOTICES.md) and pass those notices
on if you distribute the built artifacts.
