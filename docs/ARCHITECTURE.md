# Architecture — barcode-scanner-sdk

Clean-architecture, enterprise-style layering. Dependency rule: **outer layers depend inward**;
`domain/` knows nothing about ML Kit, CameraX, or JNI.

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
│ data/zxingcpp     ZXingCppDecoder + ZXingCppBridge     │  DataBar engine (native)
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
  → OrientationCandidates.expand (upright-first, sensor-compensated: 2 default, 4 robust)
  → per orientation in REGISTRY order (MLKit → MSI → ZXingCpp):
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

**Tune robustness vs speed**:
- `ScannerConfig(maxOrientationsTried=2)` default: upright + upside-down, fastest.
- `ScannerConfig.robust()` → 4 orientations + extra MSI binarizations + wider voting.

## Decisions (ADRs, condensed)

- **SDK-owned `Symbology` enum** instead of re-exporting ML Kit's: one stable type across engines.
- **`ScannerConfig` in domain.model, re-exported from api**: lets data/ depend on config without an
  api->data->api cycle; hosts import from `api` (identical type via typealias).
- **Manual DI, internal container** over Hilt: libraries must not force a DI framework on hosts,
  and hosts must not bypass fusion via public decoder handles.
- **JNI to zxing-cpp v3.1.1** (not the Java `zxing` port): DataBar-Omni/Stacked/Limited/
  Expanded/Expanded-Stacked + maintained C++ core; graceful `isAvailable=false`
  fallback when the `.so` is absent (tests). Native filter/mapping use v3 identifiers
  and space-tolerant HRI matching (`ToString` returns "DataBar Expanded", "QR Code").
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
