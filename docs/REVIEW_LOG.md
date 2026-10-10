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
- P1-4 DecodedBarcode.equals → FIXED (documented identity = value+symbology; fusion dedups explicitly)
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

## Pass 11 — live-camera MSI blind spot: sensor-relative orientation (2026-10-06, user report)
User: shelf images decode (DeviceMsiTest) but the sample app camera detects no
MSI. Reproduced on Pixel 6a: rotating each shelf photo into the camera's actual
frame shape (sideways buffer + `rotationDegrees=90`) and decoding via
`scanBitmap(rotated, rotationDegrees=90)` turned the 2 exact bar decodes
(quakotml, starbucks) into NotFound / a timeout-truncated OCR hit (~1.6 s).
Root cause: `OrientationCandidates` always tried 0°→180°→90°→270° regardless of
`ScanFrame.rotationDegrees`; portrait camera buffers are stored sideways, so the
first two candidates were sideways content, MSI's expensive vertical-scanline
attempts ate the 1500 ms fusion budget, and the upright 90°/270° candidates
(marked attemptRotation=90/270, skipped for MSI in default mode) came last.
Fix:
- `OrientationCandidates.priorityOrder(rotationDegrees)`: upright compensation
  first, then upside-down, then sideways (gallery stills: unchanged 0/180/90/270).
- `FusedDecoder.wantsFrame`: MSI-family "sideways" is now relative to
  `rotationDegrees`, so default-mode portrait frames attempt MSI on the upright
  candidates instead of skipping them.
- Unit tests: priority-order matrix + portrait expand/effectiveRotation cases.
Verification: probe now shows camera-shaped frames match stills exactly on the
Pixel 6a (starbucks 535 ms bars 1.0, quakotml 693 ms bars 1.0); DeviceMsiTest
results unchanged for rotationDegrees=0; JVM + connected suites green.
Perf note: this also makes default (non-robust) MSI work for portrait captures,
previously documented as 0/180°-only.

## Pass 12 — live-frame forensics: OCR truncation + short MSI false positive (2026-10-06, user retest)
Captured the user's actual live frames (temporary diagnostic dump in
DefaultBarcodeScanner; since removed). Pointing at a monitor showing
msi-starbucks-0168971.png: 1280x960 rot=90 frames produced either
`MsiOcr 016897 c=0.7` or `MsiPlessey 0128 c=1.0` — never the true `0168971`.
Two real defects (bars were too small in-frame to decode (~150 px), so both
fallbacks fired):
- OCR stripped an interpreted check digit: the printed `0168971` happens to be
  a valid Mod10 codeword (`016897` + check `1`), so the fallback emitted
  `016897`. Printers differ on printing the check digit(s); the OCR now emits
  the digit run AS PRINTED (checksum remains the precision gate only).
- Short MSI false positive: a 4-digit window with 2 correlated observations
  cleared the old spec floor (3). New `ScannerConfig.msiMinPayloadDigits`
  (default 3 = spec compatible; sample/warehouse = 6) withholds short parses;
  the sample now sets 6.
Tests: OcrSkuDecoderTest expectations updated to printed-run; new
MsiDecoderRegressionTest.minPayloadDigits case (8052 rejected at 6, accepted at
default). JVM suite green; sample reinstalled for retest.

## Pass 12b — OCR trust mode (silkalm/yakult never recognized)
User: printed SKUs "do not include the checksum" — exactly right: the check
digit(s) live in the barcode; validating the printed text usually cannot
succeed. On-device OCR read (warm): yakult `0828147` exact, dixie `0087573`
exact, silkalm `0828593` (true `0826593`, one stable misread). Changes:
- `ScannerConfig.msiOcrRequireChecksum` (default false): trust mode emits any
  plain 7+ digit run as printed at 0.5; strict mode (true) requires validation
  and earns 0.7. Bars are unaffected.
- Decoder emits the printed run, never strips (the old strip truncated
  Starbucks `0168971`→`016897`).
- `decodeTimeoutMillis`: OCR is last, so the 1.5 s default truncated it —
  sample now uses 4 s (verified: at 5 s, silkalm/yakult/dixie all emit via
  MsiOcr; at 1.5 s, silkalm/dixie were NotFound).
On-device full-chain results (trust mode): yakult exact, dixie exact, silkalm
one-digit misread (ML Kit limit; undetectable without a check digit in print).
Sample enables trust mode + min payload 6 + 4 s. Zero confident bar changes.

## Pass 13 — adversarial review + fixes (2026-10-06)

Full-repo picky review (all source, tests, docs). Fixed:
- **Missing Gradle wrapper** (no gradlew/jar tracked, docs assumed it) → wrapper
  regenerated at Gradle 9.6.0.
- **Bitmap recycle vs in-flight ML Kit Task**: plain `Task.await()` does NOT stop
  the task on coroutine cancellation; fusion's `finally` recycled bitmaps while
  ML Kit could still read them. Fusion now skips recycling on timeout/cancel and
  both ML Kit engines ride a `CancellationTokenSource` through `await(cts)`.
- **`provider.unbindAll()`** in CameraScanManager tore down host CameraX use
  cases; now only this manager's preview/analysis are unbound.
- **`isUpsideDown` was absolute (`attemptRotation == 180`)**, wrong for portrait
  frames where the upside-down candidate is 270; all engines now use
  `ScanFrame.relativeRotation` / `isUpsideDownCandidate`.
- **OCR ran once per orientation candidate** and its band anchors were compared
  against ML Kit boxes across mismatched coordinate spaces. `OcrSkuDecoder` is
  now a `LastResortDecoder`: fusion runs it once per frame after all bar engines
  miss, on an upright-rotated copy so bands and boxes share one space.
- **Timeout didn't cover preprocessing**; pipeline now runs inside `withTimeout`
  and checks cancellation between transforms.
- `checksumStripped` was hardcoded true (lied under `NONE`); now derived from
  validation. Mod11 computed check 10 is rejected instead of guessed as 0.
- SharedFlow buffer now `DROP_OLDEST` (comment had claimed drop semantics);
  live dedup map pruned past 64 keys; `Builder.robustMode(false)` restores 2
  orientations; `DecoderRegistry.register` replaces in place (priority kept).
- `startCamera` closed-check moved inside the camera lock (TOCTOU).
- Removed dead code: `BlurScoringTransform` (scored, never consumed) and
  `ScanFrame.sharpnessScore`, `verticalScanlines`, reversed `decodeScanline`
  overload, private `deSpeckle`, unreachable branch, `@Suppress("unused")`,
  unused BuildConfig/viewBinding flags, dead API-26 check, unused
  `ErrorKind.FATAL/TIMEOUT`, `DatabarDecoder` alias; `DefaultBarcodeScanner`
  now internal; `estimateBackground` uses a histogram instead of sorting.
- Docs corrected (README MsiOcr 0.5 vs 0.7, camera permission, sharpness claims;
  stale EXPERIMENTAL/consensus comments; review-log isSameScan phantom).
- Tests added: ScannerConfig validation/builder symmetry, PreprocessingPipeline
  cancellation, FusedDecoder (early break, timeout best-so-far + no-recycle,
  deferred last-resort, MSI sideways skip), ZXing JSON parsing, facade dedup,
  MLKitDecoder mapping. GitHub Actions CI added; pristine timing assertion
  relaxed to an absurd-blowup guard.

## Pass 14 — robust-mode OCR starvation regression (2026-10-06, user report)

Pass 13 deferred `LastResortDecoder`s (OCR) to after the orientation loop under
one shared `withTimeout`. Robust mode's 4 orientations × (ML Kit + zxing
TryHarder + 4-variant MSI) can consume the whole 4 s budget, so OCR never ran
(default mode finished its 2 orientations early enough, which is why only
robust mode broke). Fix: when a last-resort engine is registered the bar phase
stops at `BAR_PHASE_PERCENT` (60%) of `decodeTimeoutMillis`; the remainder is
reserved for the once-per-frame fallback. Deadline checks sit before each
orientation and each bar engine, so a single slow native call cannot overrun
far. Regression test:
`FusedDecoderTest.lastResort_runsWhenRobustBarPhaseExhaustsItsBudget`.
README documents the split and why the per-frame budget exists (single-flight
camera gate: an unbounded decode freezes live scanning).

## Pass 15 — duplicated orientation work removed (2026-10-06)

Fusion was feeding physically rotated candidates to engines that already resolve
rotation internally:
- ML Kit receives `effectiveRotation = sensor − attempt` on every candidate, so
  all 2/4 candidates present the SAME upright image to the detector — every extra
  call was an identical inference.
- zxing-cpp runs native `TryRotate` (a full 0/90/180/270 sweep) inside every
  call, so 2/4 candidates re-ran the same 4-orientation search; robust mode paid
  `TryHarder` on each duplicate. A robust frame could do ~16 native sweeps.
Fix: `BarcodeDecoder.resolvesOrientationInternally` (default false). ML Kit and
zxing-cpp declare true and now receive the primary (relative rotation 0)
candidate only; the expansion is skipped entirely for registries where every
engine is internal. MSI stays false deliberately: its 3-column vertical fallback
is thinner than a physically rotated 9-scanline horizontal pass, so rotations are
real signal there, not duplication. Tests:
`FusedDecoderTest.orientationInternalEngines_getPrimaryCandidateOnly`,
`builtInEnginesDeclareInternalRotation`.

## Pass 16 — rotated-image end-to-end test coverage (2026-10-06)

Rotation coverage was previously partial: `MsiPristineTest` decoded committed
r90/r180 FIXTURES but directly through the MSI decoder, `OrientationCandidatesTest`
only covered ordering/dimensions, and under the default Robolectric graphics mode
`Matrix` transforms do not move pixels, so fusion's compensated-rotation path was
untested. Added:
- `RotatedImageFusionTest` (JVM, `@GraphicsMode(NATIVE)` — real Skia pixels):
  full fused pipeline with genuinely rotated content — sensor compensation for
  0/90/180/270 buffers, reverse-path upside-down with `isUpsideDown`, and the
  documented default-mode sideways NotFound vs robust success.
- `DeviceMsiTest.databarFixtures_decodeAtEveryRotation`: on-device matrix over
  the committed DataBar fixtures (real ML Kit + native zxing TryRotate), rotating
  pixels and reporting the compensating sensor rotation for each combination.
All JVM suites green (92 tests).

## Pass 17 — MSI-before-ZXing order + fusion timing diagnostics (2026-10-07)

User: the Starbucks MSI shelf tag returns OCR 99% of the time live, although the
same tag decodes from bars as a still (`DeviceMsiTest` proven-exact). Since OCR
only runs when the bar pool is empty, this is an MSI miss on live frames, not
budget starvation (a started engine call always runs to completion; the bar
deadline only stops *starting* new work). Changes:
- Registry order ML Kit -> MSI -> zxing-cpp (was ML Kit -> zxing-cpp -> MSI):
  cheap-first — MSI tags resolve without paying for the zxing TryHarder sweep
  first (~0.5-1 s saved per live frame). QR/EAN (ML Kit early-exit) unaffected;
  DataBar frames pay one MSI miss before the native hit.
- `FusedDecoder.TAG = "FusedDecoder"` with unconditional `Log.d` per-engine
  timings (engine/relative-rotation/ms/hit-or-miss, plus skip reasons and a
  final frame line). No `setprop` needed: `adb logcat -s FusedDecoder` either
  shows lines (current build running) or is empty (stale APK) — a binary
  build discriminator. Absent `MsiPlessey` engine lines with present MLKit/ZXing
  lines would mean MSI filtered via `wantsFrame` (logged as `skip=...`); absent
  tag output entirely means the old build is installed.
- Follow-up (live logcat: MSI runs all 4 orientations, misses in 82-334 ms while
  OCR hits `0168971`): absolute-px quiet minimums (edge 6, window-boundary 4)
  veto small in-frame barcodes before any window forms. The 1440 fallback pass
  now runs relaxed (edge 3, boundary 2; 6-digit floor retained), and each MSI
  pass logs `MsiPlessey pass=… runLists=… struct=… validated=… keys=… best=…`
  to separate "no windows" from "no checksum agreement" on device.
- Follow-up (pulled failing frame via first-`MsiOcr`-hit PNG dump, new
  `ScannerConfig.debugOcrFrameDump`): the frame shows truth/junk TIED
  (`016897`/`0168971`/`01689710` all @4 — length×votes emits the 8-digit junk
  extension). A center-crop retry was tried: it isolates truth on a hand-fitted
  bbox but floods the pool with correlated short-junk votes in production
  geometry (live forensic `best=0128@45`), so it was reverted, as were
  unique-top early-exit and extension-tie silence — the pristine fixture itself
  ties truth `1234567` with suffix-luck `4567` at equal votes, so ties are
  NORMAL (sub-windows of truth always co-validate) and any tie rule breaks
  clean renders. Conclusion: a tied longer-vs-shorter vote is internally
  undecidable (length resolves pristine correctly and moiré frames wrongly
  with identical structure); only cross-geometry persistence separates them.
  Kept: shared-pool removal reverted to tested behavior + per-pass forensics
  (`MsiPlessey pass=… runLists/struct/validated/keys/top`). 98/98 green.

## Pass 18 — viewfinder ROI (2026-10-07)

User: would a smaller scan area help? Verdict from forensics: crop alone doesn't
change barcode px, but it drops competing print/moiré (a hand-fitted native-res
crop let truth win where full-frame tied) and halves pixel-bound stage costs;
crop+upscale hurts (bilinear ripples fuse narrow bars, probe-verified). The
reliable win is user-aimed: the box gets the human to fill the frame.
- `ScannerConfig.ScanRegion(widthFraction, heightFraction)` (immutable fraction
  pair, centered by construction so portrait-buffer transposition is just an
  axis swap; null = full frame) + `CameraScanManager.roiToBuffer` (unit-tested
  mapping incl. sensor-crop offsets) applied in the direct YUV→ARGB conversion,
  so every engine shares the ROI. Sample draws the matching `ViewfinderView`
  box and configures 0.9×0.5. 109/109 unit green (6 ROI + 5 config tests).

## Pass 19 — crash/hang/leak/perf review fixes + shelf-sample sweeps (2026-10-10)

Systematic review of barcode-scanner-sdk for crashes, hangs, leaks,
inefficiencies (see branch `review-fixes/crash-leak-perf`). Fixed:

Crashes:
- P0 `System.loadLibrary` in `MsiNativeDecoder` companion init threw
  `ExceptionInInitializerError` (an Error; fusion's `catch (Exception)` cannot
  contain it) on ABIs without the .so. Now guarded (`nativeAvailable` flag);
  decode degrades to NotFound. JVM regression test added.
- P1 post-close live frame threw `IllegalStateException` out of the shared
  decode scope (uncaught coroutine exception = app crash). Live body extracted
  to `handleLiveFrame()` (tested) with closed-guard + drop-and-log; one-shots
  still throw as documented.
- P2 JNI length check used `jint` multiplication (overflow) + no null guard;
  now int64 + null check. `ContrastNormalizationTransform` pixel-count check
  likewise widened to Long.
- Latent stack OOB in `decodeByDigitTemplates`: `tmplLen` was recorded from
  the UNCLAMPED `12*module` length, so wide close-up modules indexed past
  `seg[96]`/`tmpl[d]`. Clamped before recording (behavior-identical whenever
  tmplLen <= 96, i.e. on every input that ever decoded).

Correctness (decode-affecting, A/B-verified on 6 shelf photos, zero regressions):
- `msiChecksumPolicy` was a dead knob (native auto-detects, Kotlin never
  gated). Restored the retired decoder's contract: native-reported digits are
  re-validated strictly under the configured policy via
  `MsiChecksumValidator` (name-matching would mis-route double-check labels:
  every Mod1010/Mod1110 codeword also validates as Mod10 by construction).
- ROI and full-frame paths shared one temporal voter, so a miss on one path
  reset the count the other was building. Extracted synchronized `MsiVoteGate`
  (unit-tested incl. 20-thread race); ROI gets its own decoder instance with
  single-frame emit (native >=2-vote + live stability gate already protect
  it), full-frame keeps 2-frame agreement.
- MSI never set `DecodedBarcode.isUpsideDown` (ML Kit/zxing-cpp do); now set
  from the candidate, same convention. Fixed the broken-at-HEAD
  `upsideDownContent_decodesThroughFusion` by making its fake emulate the
  native orientation contract (was an always-hit flagless fake tripping the
  confident early-exit on candidate 0).
- Native far-field fallback: single NN 2x retry (with +1px shift so the xstep=2
  sampler sees true boundaries) when every ROI fails on frames < 800px long
  edge. Failure-path-only: success-path outputs provably unchanged (A/B diff).
  Rescues yakult (`08281479` exact, was silent miss).
- xstep=1 ROI sampling was tried for the phase-lock blind spot (crisp
  axis-aligned captures score 0 under xstep=2) and REVERTED: it flips
  real-photo outcomes both ways (dixie full-res lost). Blind spot documented
  in code; real captures carry enough noise to break the lock.

Leaks/hygiene:
- Timeout-path non-recycling is INTENTIONAL and kept (use-after-recycle from
  in-flight engines is worse; Bitmaps are GC-finalized so this is delayed
  free, not a true leak — covered by the keeps-alive unit test).
- `MsiRegionCropper` deskew `flat` bitmap leaked on draw failure — fixed.
- `previewView` now `@Volatile` (main-thread write, analyzer-thread read).
- ML Kit bounding boxes are copied into results (Rect is mutable).
- Raw barcode values redacted from hot-path logs (lengths kept).

Perf (all bit-identical on hits):
- Thread-local exact-match digit-template cache in `decodeByDigitTemplates`
  (rebuilds dominated multi-profile decodes). Bench: 1280x720 synthetic
  ~2.1ms mean, dixie photo ~0.7-1.0ms on host.
- Deliberately NOT changed (risk > gain, documented): per-frame zxing-cpp
  `BarcodeReader` (stateless wrapper, no native peer — verified via javap;
  sharing one instance would risk wrapper races), merged ML Kit
  decode+localize pass (would alter ML Kit inputs), column-rescan restructure.

Shelf samples (user-supplied 6 PNGs -> `src/test/resources/msi-shelf/`,
~2MB committed as fixtures):
- `tools/msi-harness` (host C++17+zlib, no device needed): spec-rendered
  vectors (all 4 policies, literature 1234567->4 / 8052->3), scale/blur/
  contrast/brightness ladders, negatives, per-photo sweeps (scales x
  photometric x 3 repeats) asserted at SDK-equivalent payload level
  (native + Kotlin revalidation + strip), plus 500ms-budget bench.
  39/39 asserts green (baseline: 33/39 — the 6 yakult rescues). 87
  informational probes track known gaps (ondeg phantoms; crisp-phase-lock;
  sub-scale partials).
- JVM: `MsiShelfVectorsTest` (all six codewords through the real validator),
  `MsiVoteGateTest` (9), `MsiNativeDecoderGuardTest` (2), live-frame guard
  tests (2). Full suite 117/117 green (incl. the HEAD-broken rotation test).
- Device: `DeviceMsiTest` expectations corrected to ground truth for all six
  (starbucks was wrongly MOD_10->payload; now MOD_10_10->0168971) + new
  3x repeat-stability sweep test. Needs `connectedDebugAndroidTest` run.
