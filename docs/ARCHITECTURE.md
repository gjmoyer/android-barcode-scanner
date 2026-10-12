# Architecture — barcode-scanner-sdk

Clean-architecture, enterprise-style layering. Dependency rule: **outer layers depend inward**;
`domain/` knows nothing about ML Kit, CameraX, or zxing-cpp.

```
┌────────────────────────────────────────────────────────┐
│ api/  BarcodeScannerFacade, BarcodeScannerFactory,     │  PUBLIC — host apps touch only this
│       ScannerConfig, ScanResult                        │
├────────────────────────────────────────────────────────┤
│ domain/model      Symbology, ScanFrame, DecodedBarcode │  Stable value types
│ domain/decoder    BarcodeDecoder (strategy),           │  Open/Closed: add engine =>
│                   DecoderRegistry, DecodeOutcome       │  implement interface + register
│ domain/pipeline   PreprocessingPipeline,               │  Pure, testable transforms
│                   Contrast/Downscale/Orientation       │
├────────────────────────────────────────────────────────┤
│ data/mlkit        MLKitDecoder (+mapper, region localizer) │  Primary engine + ROI isolation
│ data/zxingcpp     ZXingCppDecoder (prebuilt AAR)       │  DataBar engine (native)
│ data/msi          MsiNativeDecoder (JNI clean-room     │  Custom MSI engine
│                   C++ engine) + MsiVoteGate,           │
│                   MsiRegionAssistDecoder,              │
│                   MsiRegionCropper, table/checksum     │
│ data/fusion       FusedDecoder                         │  Orchestrates all engines
├────────────────────────────────────────────────────────┤
│ camera/           CameraScanManager (CameraX)          │  Frame producer only
│ di/               ScannerContainer (manual DI)         │  Wiring; no Hilt imposed
└────────────────────────────────────────────────────────┘
```

## Decode flow (one frame)

```
CameraX / Bitmap
  → ScanFrame(bitmap, sensorRotation)
  → PreprocessingPipeline stage 0: DownscaleTransform (≤1280 long edge)
  → ContrastNormalizationTransform
  → OrientationCandidates.expand (upright-first, sensor-compensated, up to 4 views;
  lazy: rotations materialize only until a confident hit stops the scan)
  → per orientation in REGISTRY order (MLKit → MSI full-frame → MsiRoi →
  ZXingCpp): engines with internal rotation (ML Kit hint, zxing TryRotate, MSI
  ROI deskew) get the primary view only; the scanline MSI decoder receives
  every candidate. No last-resort engines are registered in the default
  container (the OCR text fallback was retired), but the `LastResortDecoder`
  seam remains: any registered ones run once per frame, only after all bar
  engines miss, inside a reserved slice of the frame budget
  → confident hit (≥0.95) stops scan; else pool/dedup and best-confidence wins
  → per-engine timings + final result go to logcat under `FusedDecoder`
  (always emitted at DEBUG; filter with `adb logcat -s FusedDecoder`)
  → live path: per-key duplicate suppression → ScanResult.Success
  → one-shot path: pure return, never touches the results Flow
```

## Extension recipes

**Add a symbology handled by an existing engine** — add to `Symbology`, extend the
engine's mapper (`MlKitSymbologyMapper` / `ZXingCppDecoder.mapFormat`), add a test.
No pipeline change.

**Add a new engine** (e.g. native MSI v2, Cognex-style 2D):
1. Implement `BarcodeDecoder` (thread-safe, `NotFound` not exception on miss).
2. `registry.register(MyDecoder())` in `ScannerContainer` (or at runtime from the host).
3. Done — fusion iterates `registry.snapshot()` order; no fusion edit needed.

**Add a new engine** (e.g. Cognex-style 2D):
1. Implement `BarcodeDecoder` (thread-safe, `NotFound` not exception on miss).
2. `registry.register(MyDecoder())` in `ScannerContainer` (or at runtime from the host).
3. Done — fusion iterates `registry.snapshot()` order; no fusion edit needed.

**Tune speed vs recall**:
- `ScannerConfig(maxOrientationsTried=N)` caps orientation views (default 4, lazy —
  unneeded rotations never materialize thanks to confident early-exit).
- `zxingTryHarder(false)` opts the native sweep out of thorough mode. The MSI
  engine is always thorough: multi-ROI scanline voting, digit-template
  correlation, and small-module 1D/2D 2x retries.

## MSI decode path (detail)

```
Grayscale frame (row-major bytes via JNI)
  → ROI band search: rows with dense thin vertical gradients form bands
    (top quarter skipped by design); columns with sustained edges set extents.
    Up to 8 ROIs, strongest edge-density first
  → per ROI: averaged profile + up to 9 individual scanline rows
    → bar centers as prominent luminance minima; module = median spacing / 3
    → digit windows correlated against blurred 12-module templates
    → checksum gate: host policy pushed down (only that scheme competes);
      NONE = MOD10/MOD11/MOD1010/MOD1110 in order; + ≥2-vote agreement
  → winner = most votes, ties by average score
  → 1D 2x profile retry if every ROI failed: re-vote each failed ROI with
    module <4.5px on its own scanlines upsampled 2x (no new ROI search)
  → single 2x NN upscale retry if still nothing, on a small frame (<800px
    edge) or on small modules with checksum-valid evidence
  → Kotlin: re-validate reported digits strictly under `msiChecksumPolicy`,
    strip check digits, reject degenerate (single-digit) payloads
```

- **Full-frame MSI runs before ROI MSI** (measured more accurate: intact quiet
  zones beat tight-crop edge artifacts; easy frames early-exit before
  localization costs). `MsiRegionAssistDecoder` (ML Kit isolation → pad +
  deskew crop → shared native decoder) stays the tilted-label backup.
- **One decoder instance per path**, each owning a synchronized `MsiVoteGate`,
  so ROI misses can never reset the full-frame consecutive count.
- **Native library is optional at runtime**: `MsiNativeDecoder.nativeAvailable`
  degrades to `NotFound` when the `.so` is absent (unsupported ABI, JVM
  tests). Packaging is ARM-only (`armeabi-v7a`, `arm64-v8a`) in both the SDK
  and the sample app.

## Decisions (ADRs, condensed)

- **SDK-owned `Symbology` enum** instead of re-exporting ML Kit's: one stable type across engines.
- **`ScannerConfig` in domain.model, re-exported from api**: lets data/ depend on config without an
  api->data->api cycle; hosts import from `api` (identical type via typealias).
- **Manual DI, internal container** over Hilt: libraries must not force a DI framework on hosts,
  and hosts must not bypass fusion via public decoder handles.
- **Prebuilt zxing-cpp v3.1.1 AAR** (not the Java `zxing` port, not a source
  build): DataBar-Omni/Stacked/Limited/Expanded/Expanded-Stacked + maintained
  C++ core via the `io.github.zxing-cpp:android` artifact's public
  `BarcodeReader` API; graceful `NotFound` fallback when the native lib is
  absent (tests). Native format filter/mapping use the wrapper `Format` enum.
- **Native C++ MSI engine** (`src/main/cpp/msi_decoder.cpp`, no third-party
  barcode code): signal-template correlation survives the blur that defeats
  width thresholding. `MsiCodeTable`/`MsiChecksumValidator` stay in Kotlin —
  the host's `msiChecksumPolicy` is pushed into native candidate selection
  (under `MOD_10_10` a Mod10-valid truncation cannot outvote the full read) and the
  validator re-enforces it on native-reported digits. Verified by the host
  harness (`tools/msi-harness`, host-compiled, no device needed), JVM
  contract tests, and on-device sweeps (`DeviceMsiTest`).
- **Fusion owns orientation**, engines own inversion/binarization: avoids N×M retry explosion.
  Upside-down MSI resolves via the 180°-relative candidate and is flagged
  through `DecodedBarcode.isUpsideDown` (same convention as ML Kit/zxing-cpp).
- **Domain references Android graphics types** (Bitmap/Rect) as an accepted v1 tradeoff: an Android
  library module pays less for Robolectric than for a FrameImage abstraction layer; revisit if a
  pure-JVM artifact is ever needed.
