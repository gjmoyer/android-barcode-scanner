# Architecture — barcode-scanner-sdk

Clean-architecture, enterprise-style layering. Dependency rule: **outer layers depend inward**;
`domain/` knows nothing about ML Kit, CameraX, or zxing-cpp.

```
┌────────────────────────────────────────────────────────┐
│ api/  BarcodeScannerFacade, BarcodeScannerFactory,     │  PUBLIC — host apps touch only this
│       ScannerConfig, ScanResult, CameraStatus          │
├────────────────────────────────────────────────────────┤
│ domain/model      Symbology, ScanFrame, DecodedBarcode │  Stable value types
│ domain/decoder    BarcodeDecoder (strategy),           │  Open/Closed: add engine =>
│                   DecoderRegistry, DecodeOutcome       │  implement interface + register
├────────────────────────────────────────────────────────┤
│ data/frame        LumaFrame, NativeFrame, buffer pool  │  The one prepared frame per scan
│                   (crop/gray/stretch/rotate, native)   │
├────────────────────────────────────────────────────────┤
│ data/mlkit        MLKitDecoder (+mapper)               │  Primary engine
│ data/zxingcpp     ZXingCppDecoder (prebuilt AAR)       │  DataBar engine (native)
│ data/msi          MsiNativeDecoder (JNI clean-room     │  Custom MSI engine
│                   C++ engine) + MsiVoteGate,           │
│                   table and checksum validator         │
│ data/fusion       LumaFusion                           │  Orchestrates all engines
├────────────────────────────────────────────────────────┤
│ camera/           CameraScanManager (CameraX)          │  Frames + camera state/controls
│ di/               ScannerContainer (manual DI)         │  Wiring; no Hilt imposed
└────────────────────────────────────────────────────────┘
```

## Decode flow (one frame)

```
CameraX / Bitmap
  → ScanFrame(bitmap, sensorRotation)                     # one-shot
  → live:  CameraScanManager crops the aim box out of the camera Y plane
    still: NativeFrame.prepareStill (gray, box-downscale >16 MP, rotate upright)
  → ONE upright, even-sized LumaFrame (NV21: luma + neutral chroma), contrast-
  stretched for live aim-box crops only — a still is never stretched up front
  → LumaFusion: engines in REGISTRY order (MLKit → MsiNative → ZXingCpp) read
  that one buffer in place; nothing converts, crops, rotates or copies pixels,
  and no engine ever sees a second variant of the frame
  → whole frame under one decodeTimeoutMillis budget, best-so-far on expiry;
  hits below minConfidence or outside the enabled set are dropped
  → pool deduped on (value, symbology) keeping max confidence; a hit at
  CONFIDENT_THRESHOLD (0.95) stops the frame
  → per-engine timings + final result go to logcat under `LumaFusion`
  (always emitted at DEBUG; filter with `adb logcat -s LumaFusion`)
  → live path: MSI bar stability gate (2 consecutive sightings) → per-key
  duplicate suppression → ScanResult.Success
  → one-shot path: pure return, never touches the results Flow
```

Orientation is never expanded in Kotlin. Frames are prepared upright natively,
ML Kit and zxing resolve rotation internally, and MSI tries orientations inside
the native decoder (`LumaFrame.maxOrientations`: 2 for live aim-box frames,
`ScannerConfig.maxOrientationsTried` for stills). There is no last-resort engine
stage: every registered engine runs in priority order on the one frame, and the
OCR text fallback was retired.

## Extension recipes

**Add a symbology handled by an existing engine** — add to `Symbology`, extend the
engine's mapper (`MlKitSymbologyMapper` / `ZXingCppDecoder.mapFormat`), add a test.
No pipeline change.

**Add a new engine** (e.g. native MSI v2, Cognex-style 2D):
1. Implement `BarcodeDecoder` (thread-safe, `NotFound` not exception on miss).
2. In `ScannerContainer`, add the instance to [registry] (so `close()` releases
   it) *and* to [lumaFusion] as a `LumaEngine` with its luma entry point, in
   priority order — fusion iterates that list, not the registry.
3. Done. No other pipeline change.

**Tune speed vs recall**:
- `ScannerConfig(maxOrientationsTried=N)` caps the MSI orientations tried natively
  on stills (1..4; live aim-box frames always try 2). A hit at the confident
  threshold still ends the frame early.
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
    → every bar a Start candidate, every digit count that fits the bars
      (no floor on trailing bars, since tags carry print to the right)
    → digit windows correlated against blurred 12-module templates
    → digit-grid gate: window starts must lie within 1.5 modules of a line
      (uniform pitch); off-grid checksum-valid hypotheses are dropped
    → checksum gate: host policy pushed down (only that scheme competes);
      NONE = MOD10/MOD11/MOD1010/MOD1110 in order; + ≥2-vote agreement
  → guard gate per vote key: Start / strict Stop / 6-module trailing quiet
    zone, each the best over agreeing scanlines. Scanlines carry a
    verification tail past the ROI; a quiet zone cut by the frame edge
    rejects the read unless the length bounds forbid a longer symbol
  → winner = most votes, ties by average score
  → 1D 2x profile retry if every ROI failed (top level only): re-vote each
    failed ROI with module <4.5px on its own scanlines upsampled 2x (no new
    ROI search)
  → skew retry if still nothing (top level only): projection search finds
    the bar angle (±15°) on the first ROI tilted ≥1.5° whose deskew sharpens
    the band ≥1.5x; scanlines along the symbol axis, deskewed extent first,
    then the original extent; same vote and guard gates
  → single 2x NN upscale retry if still nothing, on a small frame (<800px
    edge) or on small modules with checksum-valid evidence
  → Kotlin: re-validate reported digits strictly under `msiChecksumPolicy`,
    strip check digits, reject degenerate (single-digit) payloads
```

- **One frame per scan, no ROI pass.** The live frame is already the aim-box
  crop; a still is read whole, and the MSI band search skips the top quarter
  (`LumaFrame.scanTopFraction`). The retired ML Kit isolation assist left no
  second-chance ROI path for tilts beyond the native skew retry's ±15°,
  so widen the aim box instead.
- **Stills retry contrast-stretched**: `decodeFrame` sweeps every orientation
  plain, then (`FrameOptions.stretchRetry`, stills only) once more through the
  2%/98% stretch. Stills are never stretched up front: a whole-photo
  stretch clips bars that already read. Live aim-box crops are stretched
  once in the native prepare and never retry.
- **The MSI decoder owns a synchronized `MsiVoteGate`**, so scanline agreement
  is counted per decoder instance. Live MSI bar reads additionally pass the
  facade's 2-sighting stability gate (`DefaultBarcodeScanner.confirmLiveBarHit`).
- **Native library is optional at runtime**: `MsiNativeDecoder.nativeAvailable`
  degrades to `NotFound` when the `.so` is absent (unsupported ABI, JVM
  tests). Packaging is `armeabi-v7a`, `arm64-v8a` and `x86_64` in both the SDK
  and the sample app; 32-bit x86 is the one unpackaged ABI.

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
- **Engines own orientation**, with no Kotlin orientation expansion to avoid an
  N×M retry explosion: ML Kit and zxing rotate internally and MSI tries
  orientations natively. Upside-down MSI resolves inside the native decoder
  (confirmed against the opposite view) and is flagged through
  `DecodedBarcode.isUpsideDown` (same convention as ML Kit/zxing-cpp).
- **Domain references Android graphics types** (Bitmap/Rect) as an accepted v1 tradeoff: an Android
  library module pays less for Robolectric than for a FrameImage abstraction layer; revisit if a
  pure-JVM artifact is ever needed.
- **Live publications are session-scoped**: `DefaultBarcodeScanner` numbers
  camera sessions and publishes results, `cameraStatus` and `torchEnabled`
  only under `cameraLock` while the publishing session is current, with
  non-suspending emission so the check and the publish are atomic with stop.
  Collectors may run inline (re-entrant lock), so every flow update in
  start/stop/failure is followed by a session re-check. A decode or CameraX
  callback outliving `stopCamera` (or a host Retry) can therefore never
  finish the next session; `Failed` is final per session.
