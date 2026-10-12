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

Gradle needs a JDK 17+; none is on PATH by default on this machine — use
Android Studio's bundled JBR:

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
  Hosts scanning these tags should use `MsiChecksumPolicy.MOD_10_10`.
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
  ROIs with module <4.5px, no new ROI search), then the 2D NN 2x frame
  retry. 2D bilinear and per-ROI 2D upscales were measured and rejected:
  they re-detect ROIs and bred confident phantoms.
- With 1-digit checksums, any mechanism that multiplies validation
  lotteries (more hypotheses, derived variants, single-vote acceptance)
  must be gated by independent multi-scanline agreement.
- Digit windows anchor per-bar, so `decodeByDigitTemplates` drops
  checksum-valid hypotheses whose windows leave the uniform digit grid
  (`kMaxDigitGridResidual`, 1.5 modules). It runs after the checksum on
  purpose so off-grid MSI-shaped hypotheses still feed the retry trigger.
  Vote-dominance, template-margin, source-module vote rules and retry vote
  floors were measured and rejected: none separated phantoms from true reads.
- Fixture truth labels in `shelfSuite`/`DeviceMsiTest` that are 8-digit
  `MOD_10` may be truncations of 9-digit tags (research doc §6 caveat).

## Validating decoder changes

Every change to `msi_decoder.cpp` needs, before commit:
1. Host harness green (`ASSERTS ... fail=0`), verdict lines diffed against
   the previous build (only `BENCH` timings may differ unless intended).
2. Real-photo regression when a local photo set is available (research doc
   §10): score against the 9-digit truth as ok / 8-digit truncation /
   wrong / none, for both no required policy and `MOD1010`. Refactors and
   perf changes must change zero rows. Also time 1280×720 text-clutter
   negatives (must stay 0 false positives).
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
