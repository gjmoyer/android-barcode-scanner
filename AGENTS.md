# AGENTS.md

Guidance for AI agents working in this repository. Keep it current when
architecture, workflows or constraints change.

## What this is

Android barcode-scanning SDK (`:barcode-scanner-sdk`) plus a demo host
(`:sample-app`). Fusion pipeline over ML Kit, zxing-cpp and a clean-room
native C++ MSI Plessey decoder. Read `docs/ARCHITECTURE.md` first; MSI
design history, rejected approaches and evaluation data live in
`docs/MSI_PLESSEY_RESEARCH.md`. Do not re-try approaches that §6/§8 list as
rejected without new evidence.

Layout (`barcode-scanner-sdk/src/main/java/com/barcodescanner/sdk/`):
`api` (public surface), `domain` (models, `ScannerConfig`, decoder
contracts), `data` (engines; `data/msi` = JNI wrapper, vote gate, checksum
validator), `di` (internal manual container; no Hilt), `camera`.
Native code: `barcode-scanner-sdk/src/main/cpp/` (`msi_decoder.cpp/.h`,
`msi_jni.cpp`), C++17, STL only, no third-party barcode code.

## Build and test

The Gradle daemon runs on JDK 25 (`gradle/gradle-daemon-jvm.properties`;
Gradle downloads it from foojay.io if absent, which the proxy may block).
No JDK is on PATH by default on this machine — use Android Studio's bundled
JBR (25):

```sh
export JAVA_HOME="$HOME/Applications/Android Studio <version>.app/Contents/jbr/Contents/Home"
./gradlew :barcode-scanner-sdk:assembleDebug                         # Kotlin + NDK build
./gradlew :barcode-scanner-sdk:testDebugUnitTest --tests '*Msi*'     # MSI JVM tests (Robolectric)
```

The native `.so` cannot load under Robolectric; JVM tests cover the
`nativeAvailable=false` guard and contracts, not decoding. Decoder accuracy
is validated on the host:

```sh
cd tools/msi-harness && ./build.sh && ./msi_harness   # exit 0 = all asserts green
```

Device tests (`src/androidTest`, `DeviceMsiTest`) need an ARM device or
emulator image and local fixture photos.

## MSI decoder rules

- **grocery shelf tags are 9-digit Mod1010**: 7-digit item + Mod10 check +
  Mod10 over (item + first check), 39 bars. Item 0115783 → `011578333`.
  Every prefix of such a codeword is Mod10-valid and Luhn/Mod11 ignore
  leading zeros, so 8-digit truncations and dropped leading zeros validate.
  Hosts scanning these tags should use `MsiChecksumPolicy.MOD_10_10` with
  `msiSymbolLengths` 9..9.
- The host policy is pushed into native candidate selection
  (`msi::decode(..., required)` ← JNI `nativeDecode(..., requiredPolicy)` ←
  `MsiNativeDecoder.nativePolicyCode`). The JNI codes are pinned by
  `MsiNativeDecoderGuardTest`; change both sides together. Kotlin still
  re-validates under the policy — keep both layers.
- `ScannerConfig.msiSymbolLengths` (codeword length, checks included,
  policy-independent; grocery shelf tags = 9) follows the same path:
  `DecodeOptions::minDigits/maxDigits` skip foreign lengths in
  `decodeByDigitTemplates` *before* template matching (no vote, no cost),
  and `acceptNativeRead` re-checks. Hypotheses that fail only the required
  checksum (valid under another scheme) count as MSI evidence for the 2D
  small-module retry trigger — dropping that evidence cost far-field reads.
- Failure-path retries run in order: 1D 2x profile retry (re-votes failed
  ROIs with module <4.5px, no new ROI search; top level only, since inside
  the 2D recursion it compounds to 4x and bred phantoms), then the skew
  retry, then the grid retry, then the 2D NN 2x frame retry. 2D bilinear and per-ROI 2D upscales
  were measured and rejected: they re-detect ROIs and bred confident phantoms.
- Skew retry (`estimateSkew`, top level only): projection search over ±15°
  for the angle that sharpens the deskewed band average; gated by
  |angle| ≥1.5° and gain ≥1.5, first gated ROI only (`kSkewMaxRois`; more
  ROIs only cost clutter time). Scanlines run along the symbol axis over the
  deskewed extent first (the horizontal extent cuts outer digits and those
  prefixes passed every guard), then the original extent (the wider one can
  take in trailing print). Don't evidence-gate the upsample or the fallback
  extent, and don't use the structure tensor for the angle (biased about 3°):
  all were measured and lost reads.
  Measure tilt with rotated crops, not only the upright set.
- Grid retry (`decodeByDigitGrid`, top level only, after the skew retry):
  for blurred tags whose merged bars shift every bar-anchored digit window.
  It tracks windows by digit pitch; bars only seed the Start and module.
  Each line's read must pass the checksum, the grid residual,
  `kGridMinScore` 0.7, `kGridMinReadCorr` 0.6 and its own guards, then the
  normal vote. The prefix rule must also drop an equal-strength prefix when
  the longer read closes: an 8-digit prefix of a Mod1010 tag is
  Mod1010-valid one time in ten. Moving it ahead of the skew retry lost
  tilted reads. Pitches are the bar estimate ×1.0 plus `spectralModule`
  (bit-period DFT peak) ×`kGridSpecScales`: the bar estimate runs 10–14%
  high near 2px/module. Grid reads below 2.5px need 3 agreeing lines (the
  template path keeps 6), and with no caller scheme may not be Mod11-only
  (a one-digit substitution on a Luhn symbol passes Mod11 1 time in 11).
  Re-measure clutter latency (desktop 9.3 ms mean)
  and wrong reads before widening its pitches, sigmas, votes or scope (the
  2D recursion, deskewed scanlines). A split-band ROI retry was rejected
  (template prefix and Mod11 wrong reads).
- Stills retry stretched (`FrameOptions.stretchRetry`, set only by
  `NativeFrame.prepareStill`): `decodeFrame` sweeps every orientation
  plain, then once more through the 2%/98% contrast stretch. Never stretch
  stills up front (a whole-photo stretch clips bars that already read), and
  keep it a retry so existing reads and orientations cannot change. Live
  aim-box crops are already stretched; never enable it on the live path,
  where nearly every frame misses and would pay a second sweep.
- With 1-digit checksums, any mechanism that multiplies validation
  lotteries (more hypotheses, derived variants, single-vote acceptance)
  must be gated by independent multi-scanline agreement.
- Digit windows anchor per-bar, so `decodeByDigitTemplates` drops
  checksum-valid hypotheses whose windows leave the uniform digit grid
  (`kMaxDigitGridResidual`, 1.5 modules). It runs after the checksum on
  purpose so off-grid MSI-shaped hypotheses still feed the retry trigger.
  Vote-dominance, template-margin, source-module vote rules and retry vote
  floors were measured and rejected: none separated phantoms from true reads.
- Every bar may host a Start hypothesis (a 5-bar cap was the dominant miss
  behind left-side clutter), so the guard gate (`guardFailure`) is what
  keeps phantoms out. A vote key that meets the vote count must also show
  Start ≥0.6, strict Stop ≥0.25 and a 6-module trailing quiet zone ≥0.3.
  Each value is the best over the key's agreeing scanlines; requiring all
  three on one line lost true reads.
- There is no floor on the bars left after a symbol: shelf tags carry print
  or neighbouring tags to the right. Only overruns (more than 6 missed bars)
  are pruned. Per-bar digit matches are memoized across hypotheses
  (bit-identical); keep the memo when changing the template search.
- Digits 0–7 begin with the Stop pattern `1001`, so a truncated read sits on
  a perfect fake Stop; only the quiet zone after it exposes the cut.
  Scanlines carry a verification tail past the ROI (`appendVerifyTail`;
  decode still sees only `[0, n)`). Where the tail hits the frame edge the
  quiet zone is unseen, and the read is rejected unless the length bounds
  forbid a longer symbol. Open lengths (the SDK default) therefore reject
  symbols touching the photo or aim-box edge, by design.
- Reversed (180°) bars can decode to checksum-valid garbage that clears every
  guard. `decodeFrame` confirms a read against the opposite view and keeps
  the clearer leading quiet zone (`pickDirection`; `leadingQuiet` read from
  the verification head, `prependVerifyHead`). Live frames try 2
  orientations; never drop the confirm decode when raising orientations.
- Fixture truth labels in `shelfSuite`/`DeviceMsiTest` that are 8-digit
  `MOD_10` may be truncations of 9-digit tags (research doc §6 caveat).

## Live camera invariants

- Every live publication (results, `cameraStatus`, `torchEnabled`) carries
  its camera session and publishes only under `cameraLock` while that session
  is current (`acceptLiveSuccess`, `publishForSession`): a decode that
  outlives `stopCamera` (or a host's Retry restart) must never finish or
  prime the next session. Keep emission non-suspending (`tryEmit`) so it can
  stay inside that critical section.
- `CameraStatus.Failed` is final for its session (`publishCameraStatus`);
  async bind/provider failures and a missing native library report it plus
  one `ScanResult.Failure`. Never throw from CameraX listener callbacks.
- CameraX unbind and LiveData observer removal run on the main thread
  (`CameraScanManager.stop` defers off-main calls); unbind only the
  scanner's own use cases, never `unbindAll()`.

## Validating decoder changes

Every change to `msi_decoder.cpp` needs, before commit:
1. Host harness green (`ASSERTS ... fail=0`; `bleedSuite` and `smallBleedSuite` cover the grid
   retry), verdict lines diffed against
   the previous build (only `BENCH` timings may differ unless intended).
2. Real-photo regression when a local photo set is available (research doc
   §10): score against the 9-digit truth as ok / 8-digit truncation /
   wrong / none, for both no required policy and `MOD1010`. Refactors and
   perf changes must change zero rows. Also time 1280×720 text-clutter
   negatives (must stay 0 false positives). Count wrong reads on unreadable
   (scored=no) tags too: they must never misread. Score every tag with a
   full bar field, including ones Apple Vision misses (the SDK out-reads
   it); only symbols cut by the photo edge or unreadable by a commercial
   scanner stay unscored.
3. `assembleDebug` for the NDK build.

Shelf photos, OCR output and derived crops are private data: never commit
them (`src/test/resources/msi-shelf/.gitignore` blocks `msi-*.png`).

## Conventions

- Conventional commits with scope: `fix(msi):`, `feat(msi):`,
  `perf(msi):`, `refactor(msi):`, `test(msi):`. One validated component per
  commit; put measured before/after numbers in the body.
- Branches: `features/…`, `bugs/…`, `topics/…` from up-to-date `main`.
- Comments explain *why* (evidence, rejected alternatives); keep the
  research doc in sync when decoder behaviour changes.
- Commit locally; push only when the user confirms.
