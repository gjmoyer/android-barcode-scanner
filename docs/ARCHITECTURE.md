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
│ data/mlkit        MLKitDecoder (+mapper)               │  Primary engine
│ data/zxingcpp     ZXingCppDecoder (prebuilt AAR)       │  DataBar engine (native)
│ data/msi          MsiPlesseyDecoder + table/checksum/  │  Custom MSI engine
│                   binarizer                            │
│ data/ocr          OcrSkuDecoder + OcrEngine (ML Kit    │  MSI SKU text fallback
│                   text recognition, last resort)        │  (0.5 trust / 0.7 strict, engine MsiOcr)
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
  → per orientation in REGISTRY order (MLKit → MsiRoi → MSI → ZXingCpp):
  engines with internal rotation (ML Kit hint, zxing TryRotate) get the primary
  view only; MSI receives every candidate. LastResort engines (MsiOcr) run once,
  only after all bar engines miss
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

**Tune speed vs recall**:
- `ScannerConfig(maxOrientationsTried=N)` caps orientation views (default 4, lazy —
  unneeded rotations never materialize thanks to confident early-exit).
- `zxingTryHarder(false)` opts the native sweep out of thorough mode; MSI effort
  is always thorough (extra binarizations + vertical scanlines + wider voting).

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
- **Pure-Kotlin MSI first, native optional later**: tables + scanline logic stay in Kotlin for
  testability; the `MsiPlesseyDecoder` boundary is the seam for a future NEON/C++ fast path.
  The code table/guards/checksums are verified end-to-end (Zint-rendered fixtures →
  `MsiPristineTest`, all policies).
- **Fusion owns orientation**, engines own inversion/binarization: avoids N×M retry explosion.
  MSI reads upside-down via a true reverse run-direction path (STOP-first, space-first
  digits un-mirrored through the shared table), not pixel reversal.
- **Domain references Android graphics types** (Bitmap/Rect) as an accepted v1 tradeoff: an Android
  library module pays less for Robolectric than for a FrameImage abstraction layer; revisit if a
  pure-JVM artifact is ever needed.
