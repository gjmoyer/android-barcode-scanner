# Review log — 3-pass critical review + fixes (2026-10-06)

Three parallel reviewer agents (clean-architecture / Android-platform / barcode-algorithms)
each returned P0/P1/P2 findings. All P0s and most P1s are fixed below. Remaining accepted
tradeoffs are documented as such.

## Pass 1 — clean architecture (principal reviewer)
- P0-4 fusion swallowing CancellationException → FIXED (rethrow, ensureActive, preprocessing catch split)
- P0-5 EarlyExit exception + non-robust break → FIXED (labeled break, pool merge dedup, no exception)
- P0-6 dedup single-key + lying one-shots → FIXED (one-shots pure; live path per-key map + Mutex + emit)
- P0-7 fromRotatedFrame always true → FIXED (isUpsideDown / isRotatedCandidate only)
- P0-9 data->api cycle → FIXED (ScannerConfig moved to domain.model; api/ keeps typealias)
- P0-10 hardcoded engine order → FIXED (fusion uses registry.snapshot() order)
- P0-11 sample dead Flow → FIXED (Job + repeatOnLifecycle + recreateScanner)
- P0-12 MLKit double rotation + stale boxes → FIXED (effectiveRotation = sensor-attempt; boxes nulled when rotated)
- P1-2 successes() cast → FIXED (filterIsInstance)
- P1-3 Builder.enable overwrite → FIXED (only/addSymbologies; enable aliases only)
- P1-4 DecodedBarcode.equals → FIXED (documented identity = value+symbology; isSameScan for full compare)
- P1-6 MLKit lifecycle race → FIXED (AtomicBoolean + synchronized init)
- P1-10 hardcoded dispatchers / public container → FIXED (dispatcher injectable; container internal)
- P1-11 bridge DCL → FIXED (synchronized init + Log.w)
- P1-13 DecoderException checked → FIXED (RuntimeException, recoverable default false)
- P1-14 close() racy → FIXED (AtomicBoolean, lock, cancel-before-unbind)
- P0-8 domain-on-Android (Bitmap/Rect in domain) → ACCEPTED TRADEOFF for v1 (Android library module;
  pure-JVM extraction would need FrameImage abstraction; recorded as future work, tests use Robolectric)

## Pass 2 — Android platform (CameraX / ML Kit / JNI / Gradle)
- P0-1 JNI OOB → FIXED (len check, jboolean != JNI_FALSE, vector<uint8_t>, cstdio, None-filter skip)
- P0-2 ML Kit double rotation → FIXED (see above)
- P0-3 YUV stride ignorance → FIXED (stride-aware NV21 pack, ResolutionSelector 1280x720, JPEG 70)
- P0-4 GlobalScope fan-out → FIXED (injected scope + AtomicBoolean single-flight + recycle)
- P0-5 testOptions nesting → FIXED (moved under android{})
- P0-6 sample dead Flow → FIXED (see above)
- P0-7 successes() cast → FIXED
- P0-8 executor shutdown kills restart → FIXED (executor per start, shutdownNow, null out)
- P1-1 PreviewView via implementation → FIXED (api() for camera-view + lifecycle-runtime)
- P1-2 no resolution cap → FIXED (ResolutionSelector + DownscaleTransform stage 0)
- P1-4 fromRotatedFrame → FIXED
- P1-5 enable() → FIXED
- P1-6 UNKNOWN default → FIXED (default excludes UNKNOWN)
- P1-7 bitmap recycle → PARTIAL (orientation/working copies recycled; camera frames recycled; contrast
  intermediates still GC'd — documented)
- P1-8 isUpsideDown 180-only → FIXED (fromRotatedFrame covers any attemptRotation != 0)

## Pass 3 — barcode algorithms (MSI / DataBar / binarization)
- P0-1 MSI table provenance void → FIXED (comment marked EXPERIMENTAL, research doc required before trust)
- P0-2 guards inverted → FIXED (START=wide+narrow, STOP=narrow+wide+narrow, strict reject)
- P0-3 reverse path broken → FIXED (forward-only; reverse overload kept for tests with warning)
- P0-4 all-white crash → FIXED (start>=size / end<=start guards + regression tests)
- P0-5 "DataBarOmni" dead filter → FIXED ("DataBar" + None-skip + full native-name mapping)
- P0-6 mapFormat test vectors → FIXED (exact v2.3.0 ToString outputs)
- P1-1 90° MSI undecodable by default → DOCUMENTED + PARTIAL FIX (vertical scanlines in robust mode;
  default 0/180° limitation stated in KDoc)
- P1-3 deSpeckle absolute 1px → FIXED (relative 25%-of-cut + 3× neighbor guard)
- P1-4 WORKING_WIDTH bilinear smear → FIXED (filter=false + recycle)
- P1-5 dead voting pool → FIXED (real merge/dedup; confident early-break; documented fixed confidences)
- P1-6 contrast skipped on stills → FIXED (DownscaleTransform stage 0)
- P1-7 blur cost → FIXED (single getPixels + stride sampling; normalizer injectable)
- P2-4 NONE policy FP → FIXED (requires 2 votes under NONE)
- P2-5 quiet-zone leniency → FIXED (strict default; lenient single-sided only in robustMode)

## Verification status
- JNI signature re-verified char-by-char (still correct after edits).
- Unit tests updated: MSI crash/guard/phase tests, v3.1.1 HRI format vectors, orientation tests unchanged.
- Full `./gradlew :barcode-scanner-sdk:testDebugUnitTest` + device run still required (no Gradle in this env).

## Pass 5 — latest-versions upgrade (2026-10-06, user request)
- AGP 8.5.2 → 9.4.0, Gradle 8.7 → 9.6.0, Kotlin 1.9.24 → 2.4.20 (via AGP 9 built-in
  Kotlin: kotlin-android plugin removed, `kotlinOptions{}` → `kotlin.compilerOptions{}`,
  KGP overridden on root buildscript classpath), CameraX 1.3.4 → 1.6.2,
  core-ktx 1.13.1 → 1.19.1, activity-ktx (new, required) 1.13.0,
  lifecycle 2.8.3 → 2.11.0, coroutines 1.8.1 → 1.11.0 (+ play-services artifact),
  robolectric 4.13 → 4.17, mockk 1.13.12 → 1.14.11 (Kotlin 2.x compatible).
  ML Kit barcode-scanning stays 17.3.0 (still current); junit4 4.13.2 stays (final).
  Unused test deps (espresso, androidx-junit) removed.
- compileSdk/targetSdk 34 → compileSdk 37 + targetSdk 36 (Android 16, Play requirement
  since Aug 2026; core-ktx 1.19.x AAR metadata mandates compiling against API 37+,
  so compileSdk 37 is compile-only while target stays on the stable level);
  minSdk 24 → 28 (Android 9): nothing used needs newer (CameraX/ML Kit need 21+).
- zxing-cpp v2.3.0 → v3.1.1: FetchContent tag, C++17 → C++20, `ZXING_WRITERS OFF`
  (STRING option in v3), dropped unused jnigraphics link. ReaderOptions setters,
  ImageView, `Barcode::text/format/orientation/isValid` verified unchanged against
  v3.1.1 headers. v3 behavior changes handled: `BarcodeFormatFromString` now throws
  (native try/catch kept + comment fixed), `ToString` returns HRI with spaces
  ("DataBar Expanded", "QR Code"), format enum split into variants — filter now sends
  precise v3 identifiers (Omni/Stk/StkOmni, Exp/ExpStk, Ltd) and `mapFormat`
  normalizes spaces too, with Micro/rMQR, Telepen, MaxiCode, add-ons → UNKNOWN.

## Pass 5b — full build verification (Gradle 9.6.0, JDK 21, NDK 28.2, cmake 3.22)
`testDebugUnitTest` + `assembleDebug` green: 27/27 tests pass, all 3 ABIs link.
Real bugs the build caught (all fixed):
- settings declared the `libs` catalog explicitly while Gradle auto-imports
  gradle/libs.versions.toml → duplicate from() fatal. Fix: delete the explicit block.
- `kotlinx.coroutines.tasks.await` (GMS Task) needs the play-services artifact —
  added `coroutines-play-services` (was silently uncompiled before).
- Bare `ensureActive()` in a non-scope suspend catch → `currentCoroutineContext().ensureActive()`.
- CameraX 1.6 added member `ImageProxy.toBitmap()`, shadowing our same-named private
  extension (member wins — wrong conversion + dead Elvis). Renamed ours to
  `toStrideAwareBitmap()`.
- JNI used `Get/ReleaseIntElements` (don't exist) → `Get/ReleaseIntArrayElements`.
- v3 headers are flat (`BarcodeFormat.h`, `ReadBarcode.h` singular — was `ReadBarcodes.h`)
  with no `ZXing/` dir; `BarcodeFormats` lost `push_back` → vector + move-construct.
- Kotlin cannot qualify nested classifiers through a typealias, so
  `ScannerConfig.Builder()` (api alias) doesn't resolve cross-module. Added
  `ScannerConfigBuilder` + `MsiChecksumPolicy` top-level aliases; sample/docs use them.
- Sample missed `activity-ktx` (ComponentActivity) — now explicit 1.13.0.
- core-ktx 1.19.x mandates compileSdk 37+ → compileSdk 37 / targetSdk 36 split.
- NDK: AGP 9.4 defaults to 28.2.13676358 (installed via sdkmanager); CMake 3.22.1 likewise.

## Pass 4 — verification + spec-grounded MSI correction (2026-10-06)
- Downscale/Orientation bilinear → nearest-neighbor (filter=false) for MSI narrow bars.
- Contrast: raised MAX_PIXELS to 1280x1920 (tall portraits), removed dead grayToBitmap/runCatching.
- DecodedBarcode: Array<Point> → List<Point>, restored structural data-class equality
  (fusion keeps explicit (value, symbology) pool dedup).
- Fusion CONFIDENT_THRESHOLD 0.9 → 0.95 (ZXing 0.9 no longer short-circuits MSI 1.0).
- MSI: removed first-variant early return (all binarizations vote, max wins).
- Camera: stopped-gate before onFrame emission; start() shuts down stale executor first.
- ErrorKind: recoverable → TRANSIENT mapping (TIMEOUT reserved for future timeout errors).
- MSI TABLE CORRECTED from Wikipedia "MSI Barcode" + Morovia KB10637: each digit = 4 BCD
  bits MSB-first, bit→bar/space pair (0=narrow-bar/wide-space, 1=wide-bar/narrow-space);
  all 10 entries replaced (e.g. 5: 11011010 → 01100110), guards confirmed
  (START wide+narrow, STOP narrow+wide+narrow). Added MOD_10_10 (Mod1010), documented NCR
  Mod11 + inverted-MSI limitations. Tests: Wikipedia 1234567→4, Morovia 8052→3, double-Mod10,
  module-Map end-to-end "125" scanline, unambiguous/ambiguous repair cases. Hamming analysis:
  min distance 2 (15 pairs at 2, none at 1) — repair sound but ambiguous-prone, hence
  checksum + 2-vote gating. Full write-up: docs/MSI_PLESSEY_RESEARCH.md.

## Pass 6 — shelf-photo evaluation (2026-10-06, user samples)
Ran the MSI decoder against 6 tough user shelf labels (SKUs from file names).
Result: 2/6 exact (quakotml MOD_10_10, starbucks MOD_10), 0 confident false positives
(damaged labels stay silent). Techniques added along the way (all kept: they cracked
the two winners and never regressed precision): windowed search with local cuts,
subpixel gray runs (zero-crossing edges), soft per-digit least squares + multi-cut,
min-projection bands (glare), background-subtracted binarization, hi-res gray
(960 primary + 1440 fallback with 6-digit minimum), per-observation-longest +
≥2-vote gate, cross-observation consensus (majority per position, checksum-gated).
Forensics that shaped the design: global k-means is set by giant price text
(windows fix it); leading bar was silently dropped (no preceding maximum — bound
edges by quiet trim, fixing off-phase slicing); Otsu returns 0 on clean B/W
(tie-break by maximizing-range mean). Attempted and REJECTED (manufactured confident
false positives — do not retry): checksum-guided digit repair (deterministic pipeline
+ 1-digit Luhn + correlated observations = certain collisions), prominence < 6,
row-quiet relaxation, single-vote acceptance. Remaining misses are physical, not
algorithmic: heavy blur+glare merging (dixie), scratched digits (ondeg), occlusion
(silkalm), cover reflections (yakult). Full analysis: docs/MSI_PLESSEY_RESEARCH.md §6.
Test suite: 36/36 green incl. new MsiDecoderRegressionTest (synthetic render
end-to-end, guards, soft, consensus, subpixel, fused pipeline). Temporary
sample-image probes removed (user-local paths, not for CI).

## Pass 7 — pristine-barcode performance (2026-10-06, user request)
User: pointing the phone at (usually pristine) barcodes must feel fast; hard labels
may take longer. Rendered independent pristine fixtures with Zint 2.16
(`MSI_PLESSEY --quietzones`, checksums from Wikipedia/Morovia vectors) and measured:
upright all-policies incl. 2px modules decode exactly in 10–65 ms desktop JVM
(~30–250 ms device estimate); upside-down and sideways were NotFound at decoder
level. Changes:
- True reverse-direction MSI decode (mirror = STOP-first + space-first digits;
  un-mirror chunks reuse table/soft machinery; print-order string; rev-majority
  `isUpsideDown`). Upside-down pristine now exact in ~30–60 ms. Previous
  forward-only design could never read 180° labels despite claiming to.
- Lazy orientation expansion (`expandLazy`): rotations materialize on demand, so a
  first-orientation hit skips remaining bitmap allocs (common live case).
- `ZXingCppDecoder.thorough` (`TryHarder`): `thorough = robustMode` from the
  container — snappier default live scans, full effort in robust mode.
- Committed 9 Zint fixtures + `MsiPristineTest`: exact decode per policy, tiny
  modules, upside-down (reverse path + flag), sideways (robust vertical lines),
  wrong-policy rejection, <5 s blowup guard. This is also the first INDEPENDENT
  verification of the MSI code table/guards/checksums (Zint encoder → our decoder).
- Found via fixtures: consensus-as-competing-voter let a lucky short consensus
  outvote exact evidence (mod1110 regression). Fix: consensus is now fallback-only
  (consulted when exact voting yields nothing ≥2).
Perf note: MSI path is 10–100 ms of the live budget; ML Kit (~30–80 ms device) and
ZXing native (~100–300 ms worst case) dominate. Fusion order + early exit + lazy
orientations + single-flight camera gate keep point-and-scan at a few fps worst case.
Test suite: 36/36 green (temporary probes/benchmarks removed).

## Pass 8 — poor-quality success-rate check + vote-ranking fix (2026-10-06)
Re-ran all 6 shelf labels on final code: same 2/6 exact (quakotml MOD_10_10,
starbucks MOD_10), rest honestly silent — no recall regression from the perf work.
Fixed a ranking bug the re-run exposed: per-observation-longest let a junk-extended
window ("01689710") steal its observation's vote from the true sub-window, so the
long FP outvoted truth. Winner is now score = payload length × votes (longer
validated parses explain more modules; shorts validate by luck far more often),
still gated on ≥2 agreeing observations with votes/length tie-breaks. Removed the
cross-observation consensus path entirely (0 true positives across all samples;
correlated mush groups manufactured high-weight false positives like "80"=497).
Also removed checksum-guided digit repair for the same reason (deterministic
pipeline + 1-digit checksums + correlated observations = certain collisions).
Voter is now exact-evidence-only: windows → guards/table/soft → checksum →
length×votes. Test suite: 40/40 green.

## Pass 9 — MSI SKU text fallback OCR (2026-10-06, user request)
User shelves carry the SKU in print next to the barcode (Scandit-style fallback).
Added `data/ocr/`: `OcrEngine` seam + `MlKitOcrEngine` (bundled Latin text-recognition
16.0.1, offline, lazy client) + `OcrSkuDecoder` (MSI-only, registered LAST after all
bar engines). Flow: content-band gate (blank walls never invoke the model) → one
full-frame OCR → digit runs split on non-digits → checksum validation (non-NONE) →
prefer dash-free runs (case codes print dashed), longer, nearer a band; emits
`MSI_PLESSEY` at 0.7 as `MsiOcr` with the OCR box. Single-checksum hits need ≥6
payload digits (one OCR pass has no cross-observation agreement behind it).
Checksum census (machine-verified, replacing hand arithmetic that twice misled):
quakotml=Mod1010, starbucks=single-Mod10, ondeg=IBM-Mod11, dixie=NO standard scheme.
Retest on user photos (transcribed OCR text; ML Kit inference itself needs a device):
fused bars still exact on quakotml/starbucks; OCR accepts ondeg under MOD_11 and
rejects everything under wrong policies; dixie/silkalm/yakult honestly silent.
Unit tests: 10 (fake engine; ML Kit adapter is device-verified surface).

## Pass 10 — on-device full-chain verification (2026-10-06, emulator-5554 API 37 arm64)
New `DeviceMsiTest` (androidTest, full production chain: ML Kit + real zxing-cpp
`.so` + MSI + OCR) with runner deps + `testInstrumentationRunner` in
`build.gradle.kts`. Shelf photos stay user-local (`.gitignore`d `msi-*.png`,
vacuous pass when absent); synthetic `databar-*.png` fixtures committed.
Results (deterministic across runs):
- `NATIVE_ZXING_AVAILABLE=true`; DataBar Omni/Ltd/Expanded all exact on device
  (550–1150 ms; Expanded pays TryHarder).
- MSI results match the JVM suite exactly (quakotml MOD_10_10, starbucks MOD_10;
  same ondeg near-misses "024812"/"024"; dixie/silkalm/yakult silent) — validates
  the JVM-first test strategy end to end.
- ML Kit OCR reads better than the tesseract probe: dixie
  "0087573 000-42000-15121 10-48 CT" and yakult "0828147 006-99235-00100"
  exact; silkalm misreads one digit unstably ("0828593" vs true "0826593").
- OCR selection correctly silent everywhere: dixie/yakult print numbers validate
  under NO standard scheme (checksum census), silkalm misread fails checksum —
  zero confident OCR false positives on device.
- Cold-start note: the very first OCR run post-install returned 0 lines/photo
  (model warm-up); warm runs return 4–13 lines. Decoder treats both identically.
- Timings: MSI 180–760 ms, DataBar 550–1150 ms, full-chain worst case ~1.5 s
  (timeout cap by design). TEMP-DIAG logs removed; duplicate single-check guard
  removed. Suite: 50/50 JVM green.
