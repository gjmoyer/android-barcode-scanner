# MSI Plessey — decoding logic research note

How the SDK decodes MSI Plessey robustly (blur, rotation, upside-down, inversion),
and the spec sources behind the implementation. Code: `data/msi/`.

## 1. Why MSI needs custom logic

- ML Kit Barcode Scanning (17.3.0) has no MSI format constant — `MlKitSymbologyMapper`
  maps `MSI_PLESSEY` to null and never routes there.
- zxing-cpp (pinned v3.1.1) has no MSI reader either: `core/src/BarcodeFormat.h`
  @v3.1.1 lists DataBar/Code39/… with no MSI/Plessey entry, and the
  Ubuntu 2.3.0-era package manifests advertise "QR, DataMatrix, Aztec, PDF417, UPC/EAN, DataBar/RSS,
  Code39/93/128, Codabar, ITF" — no MSI. (ZXing.Net's Java port has MSI/Plessey flags
  but explicitly excludes them from `All_1D`: "too many false-positives".)
- Conclusion: MSI must be a custom scanline decoder. Ours is a clean-room C++
  engine (`src/main/cpp/msi_decoder.cpp`, STL only, no third-party barcode
  code) behind a thin JNI wrapper (`MsiNativeDecoder`), host-compiled and
  testable via `tools/msi-harness` with no device needed.

## 2. Symbology spec (ground truth)

Sources: Wikipedia "MSI Barcode" (tables + Luhn/Mod11 sections, fetched 2026-10-06),
Morovia KB10637 (Mod10 worked example "8052"→3), Seagull BarTender guide (usage context),
Scandit checksum enum (Mod10/1010/11/1110 naming), tc-lib-barcode (family context).

- Charset: digits 0–9 only. Variable length, continuous, NOT self-checking, no ISO standard
  (vendor conventions; readers must gate on checksum + length + votes).
- Per-digit encoding: 4 BCD bits MSB-first. Each bit prints as one bar/space pair totalling
  3 modules: bit 0 = narrow bar (1 module) + wide space (2); bit 1 = wide bar (2) +
  narrow space (1). In wide/narrow elements (8 per digit, bar-first, 1 = wide):
  `elements = [b3, !b3, b2, !b2, b1, !b1, b0, !b0]`.
- Verified table (wide/narrow strings):

| d | BCD  | elements |
|---|------|----------|
| 0 | 0000 | 01010101 |
| 1 | 0001 | 01010110 |
| 2 | 0010 | 01011001 |
| 3 | 0011 | 01011010 |
| 4 | 0100 | 01100101 |
| 5 | 0101 | 01100110 |
| 6 | 0110 | 01101001 |
| 7 | 0111 | 01101010 |
| 8 | 1000 | 10010101 |
| 9 | 1001 | 10010110 |

  Cross-check: digit 1 → pairs 100|100|100|110 = Wikipedia Map "100100100110" ✓;
  digit 5 → "100110100110" ✓. Module Maps are 12 modules/digit; Start "110"
  (wide bar + narrow space); Stop "1001" (narrow + wide + narrow).
- Guards: START = wide black + narrow white (2 runs); STOP = narrow black + wide white
  + narrow black (3 runs). The native decoder anchors digit windows at detected
  bar centers against blurred 12-module templates rather than hard
  run-length thresholding, which is what survives defocus; the checksum gate
  + multi-scanline voting below are the precision backstop.
- Checksums: Mod10 = Luhn (vectors: "1234567"→4 per Wikipedia, "8052"→3 per Morovia —
  both pinned as unit tests). Mod11 = IBM weights 2..7 repeating from the right,
  C = (11 − sum%11) % 11 (NCR 2..9 variant explicitly unsupported). Mod1010 = double
  Mod10; Mod1110 = Mod11 then Mod10 over payload+first check. NONE = debug only.

## 3. Robustness design (blur / rotation / upside-down / inversion)

**Blur / defocus / low contrast.**
1. `DownscaleTransform` (stage 0, nearest-neighbor) bounds work to ≤1280px long edge.
2. `ContrastNormalizationTransform` (2/98-percentile stretch) normalizes exposure.
3. Native signal path per ROI: averaged profile + up to 9 individual scanline
   rows (voting across rows beats per-row blur); bar centers as prominent
   luminance minima (no binarization threshold to tune); module from median
   bar spacing; each digit window correlated against Gaussian-blurred
   12-module templates, so shape — not absolute widths — decides.
4. Checksum gate (MOD10 → MOD11 → MOD1010 → MOD1110, first validating scheme
   wins) + ≥2 agreeing scanline votes → confidence 1.0. Single votes never
   emit. A single NN 2x upscale retry covers far-field frames whose modules
   fall below ~2px (failure path only; success paths never pay for it).
5. Kotlin post-gate: re-validate reported digits strictly under the host's
   `msiChecksumPolicy`, strip check digits, reject degenerate
   single-repeated-digit payloads (blank areas decode as all-zeros windows
   that still checksum-validate).

**Rotation / upside-down.**
- Fusion `OrientationCandidates` expands sensor-relative upright-first
  (compensating `ScanFrame.rotationDegrees`, so portrait camera buffers don't
  burn the budget on sideways views), then upside-down, then sideways — up to
  4 views, lazy (rotations materialize only until a confident hit stops the scan).
- The native engine reads horizontal scanlines per candidate; a 180°-relative
  candidate is flagged via `DecodedBarcode.isUpsideDown` (same convention as
  ML Kit/zxing-cpp), which feeds `ScanResult.fromRotatedFrame`.
- Arbitrary tilt (15°, 30°, …) smears diagonal runs past scanlines: the ROI
  path (`MlKitRegionLocalizer` isolation → padded crop → affine deskew in
  `MsiRegionCropper`, no OpenCV) rectifies the strip before the shared native
  decoder. Full-frame MSI runs first (intact quiet zones win ties); ROI is
  the tilted-label backup.

**Inversion (white-on-black).**
- zxing-cpp path sets TryInvert; the MSI scanline path assumes dark-bars-on-light.
  Inverted MSI labels remain out of scope (polarity-flip retry is future work).
  The fusion layer covers inversion for all ML Kit/zxing symbologies.

**Skew / perspective / noise.**
- Multiple ROI candidates (strongest edge-density first) × averaged +
  individual profiles; quiet-zone + length + checksum + vote gates reject
  text/DataBar lookalikes; fusion pool dedups on (value, symbology) keeping
  max confidence. One decoder instance per path, each with its own
  synchronized consecutive-observation gate, so ROI misses can never reset
  full-frame counts.

## 4. Verification procedure

1. Host harness (`tools/msi-harness`, exit 0 = green): spec-rendered vectors
   for all four checksum policies (literature Mod10 `1234567`→4, `8052`→3),
   scale/blur/contrast/brightness ladders, negatives (blank/black/noise must
   be honest `NotFound`), per-photo sweeps (scales × photometrics × 3
   repeats) asserted at SDK-equivalent payload level (native + Kotlin
   revalidation + strip), plus a 500 ms-mean timing bench.
2. JVM suite: checksum vectors, vote-gate semantics incl. a race test,
   ownership contracts, native-guard degradation — all green with no device.
3. On-device sweeps (`DeviceMsiTest`, real photos local-only): full-chain
   one-shots across all four policies (logged matrix) + hard assertions on
   proven-exact cases + a 3x repeat-stability sweep.
4. Shelf photos stay OUT of the repo (private labels): local `msi-*.png`
   captures are swept when present and skipped otherwise; committed
   equivalents are the generator script + the suites above.

## 5. References

- https://en.wikipedia.org/wiki/MSI_Barcode (encoding tables, Luhn/Mod11, 1234567→4 example)
- https://www.morovia.com/kb/MSIPlessey-Specification-10637.html (Mod10 steps, 8052→3)
- zxing-cpp `core/src/BarcodeFormat.h` @master (DataBar variants; absence of MSI) + README
  (DataBar Omnidirectional/Stacked/Limited/Expanded support) + Ubuntu 2.3.0 package manifest
- ZXing.Net `BarcodeFormat.cs` (MSI/PLESSEY exist in .NET port; excluded from All_1D as FP-prone)
- Scandit `Checksum` enum (Mod10/1010/11/1110 vocabulary), Seagull MSI guide (warehouse context)

## 6. Shelf-photo evaluation (6 user-supplied labels, Oct 2026)

Full production chain on device (Pixel 10a emulator + Pixel 6a, identical
verdicts) plus the host harness at SDK-equivalent payload level. Ground truth
is payload → bars; the native engine reports whichever scheme validates and
Kotlin re-validates strictly under the configured policy before stripping.

| File (SKU) | Result | Notes |
|---|---|---|
| yakult `0828147` | ✅ EXACT (`MOD_10` → `0828147`, 1.0, MsiNative) | Far-field tiny modules; rescued by the NN 2x upscale retry. Repeat-stable ×3 on device. |
| dixie `0087573` | ✅ EXACT (`MOD_10` → `0087573`, 1.0, MsiRoi) | Glare-heavy; ROI crop isolates it where full-frame sees phantoms. Repeat-stable ×3. |
| starbucks `0168971` | ✅ EXACT (`MOD_10_10` → `0168971`, 1.0) | Full 9-digit codeword `016897100`; note it also validates as Mod10 (every double-check codeword does by construction), so policy — not the reported scheme — decides the payload. Repeat-stable ×3. |
| silkalm `0826593` | ✅ EXACT (`MOD_10` → `0826593`, 1.0, MsiNative) | Bars carry 8/9 digits; full-frame-first ordering fixed this (ROI crops phantomized). Repeat-stable ×3. |
| quakotml `0186477` | ❌ honest NotFound | Full-res modules overshoot the template range (truncated read, policy-gated). Resolves at 0.75 scale on the host harness; tracked gap. |
| ondeg `0243523` | ❌ phantoms validate | True bars never resolve; shifted windows checksum-validate by luck. Tracked gap; live stability gate is the remaining defense. |

No wrong-value emission inside the asserted set: truncated reads are gated to
`NotFound` by policy revalidation, and blank areas (all-zeros windows that
validate) are rejected by the degenerate-payload rule. Live protection layers
beyond one-shot: per-path consecutive-observation gates, the 2-sighting live
stability gate for MSI bar reads, and per-key dedup.

Attempted and REJECTED (recorded so nobody re-tries them): xstep=1 ROI
sampling (rescues phase-locked synthetics but flips real-photo outcomes both
ways); score-tiebreak flips and consumption bonuses in template selection
(true/shifted alignments correlate ~0.96 alike on pixel-perfect input — any
fixed bias just relocates the error between dropped and prepended digits;
real captures separate via sensor texture); checksum-guided digit repair;
single-vote acceptance. Rule of thumb: with 1-digit checksums, any mechanism
that multiplies validation lotteries must be gated by independent
multi-observation agreement, and correlated observations (overlapping bands,
adjacent rows, rescaled copies) must not each count as independent votes.

## 7. Printed-payload census + retired OCR fallback

Checksum census from the filename SKUs (machine-verified, not assumed).
Remember the printed payload is NOT the codeword — the check digit(s) live
only in the bars:
- yakult `0828147`: Mod10 (check 9) — full codeword `08281479`, bars decode.
- quakotml `0186477`: Mod1010 (checks 6, 8) — full codeword `018647768`.
- ondeg `0243523`: Mod10 (check 8) — full codeword `02435238`.
- dixie `0087573`: Mod10 (check 2) — full codeword `00875732`, bars decode.
  (The old census note "validates under NO scheme" was about the bare
  7-digit payload treated as a codeword — my arithmetic at the time, and a
  good lesson: never hand-assert a check digit; verify the full codeword.)
- starbucks `0168971`: Mod1010 (checks 0, 0) — full codeword `016897100`,
  bars decode.
- silkalm `0826593`: Mod1010 (checks 6, 8) — full codeword `082659368`.

Scoreboard with correct per-label policies: bars 4/6 exact (yakult, dixie,
starbucks, silkalm), 2 honest gaps (quakotml, ondeg), zero wrong-value
emissions in the asserted set.

The OCR SKU-text fallback (`OcrSkuDecoder`, retired) used to read printed
digits as a last resort when bars failed. It was removed: the native bar
engine covers the rough captures that previously needed text backup, and
un-checksummed text reads trade recall for misread risk (one wrong digit is
undetectable without the check digit). Its config knobs
(`msiOcrRequireChecksum`, `debugOcrFrameDump`) and result fields (`gtin`,
`engineName="MsiOcr"`) remain as inert API surface for now.

- https://en.wikipedia.org/wiki/MSI_Barcode (encoding tables, Luhn/Mod11, 1234567→4 example)
- https://www.morovia.com/kb/MSIPlessey-Specification-10637.html (Mod10 steps, 8052→3)
- zxing-cpp `core/src/BarcodeFormat.h` @master (DataBar variants; absence of MSI) + README
  (DataBar Omnidirectional/Stacked/Limited/Expanded support) + Ubuntu 2.3.0 package manifest
- ZXing.Net `BarcodeFormat.cs` (MSI/PLESSEY exist in .NET port; excluded from All_1D as FP-prone)
- Scandit `Checksum` enum (Mod10/1010/11/1110 vocabulary), Seagull MSI guide (warehouse context)

