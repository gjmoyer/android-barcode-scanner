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
- Conclusion: MSI must be a custom scanline decoder. Ours is pure Kotlin
  (`MsiPlesseyDecoder`), testable on JVM, with a future NEON/C++ seam at the class boundary.

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
  + narrow black (3 runs). `quantize()` requires both exactly — off-phase slicing is the
  misread funnel, so there is no lenient fallback (non-robust mode also requires both
  quiet zones; robust mode allows single-sided quiet zone for edge-cropped labels).
- Checksums: Mod10 = Luhn (vectors: "1234567"→4 per Wikipedia, "8052"→3 per Morovia —
  both pinned as unit tests). Mod11 = IBM weights 2..7 repeating from the right,
  C = (11 − sum%11) % 11 (NCR 2..9 variant explicitly unsupported). Mod1010 = double
  Mod10; Mod1110 = Mod11 then Mod10 over payload+first check. NONE = debug only.

## 3. Robustness design (blur / rotation / upside-down / inversion)

**Blur / defocus / low contrast.**
1. `DownscaleTransform` (stage 0, nearest-neighbor) bounds work to ≤1280px long edge.
2. `ContrastNormalizationTransform` (2/98-percentile stretch) normalizes exposure.
3. `MsiBinarizer.binarizeVariants` tries Otsu, Otsu±18, adaptive-mean (integral image)
   — up to 4 variants in robust mode. Each variant × each scanline votes independently.
4. Per-scanline k-means (k=2) narrow/wide split absorbs ±module distortion; `deSpeckle`
   is relative (≤25% of cut, neighbors ≥3×) so far-field 1–2px narrow bars survive;
   MSI working copy downscales with `filter=false`.
5. `trySingleBitRepair` flips each of 8 bits once. Table min Hamming distance is 2
   (computed over all 45 pairs; 15 pairs at distance 2, none at 1), so a 1-bit error
   never lands exactly on another digit — but ~80/256 random patterns sit within 1 flip
   of *some* digit (≈31%), and several corruptions are ambiguous (e.g. '5'+bit3 →
   {'1','5'} → repair returns null). Hence repair + checksum + cross-scanline voting:
   2+ agreeing scanlines → confidence 1.0 (single votes are withheld; NONE policy
   requires 2 votes too).

**Rotation / upside-down.**
- Fusion `OrientationCandidates` expands 0°→180°→90°→270° (up to 4, lazy).
  Physical rotation uses nearest-neighbor; ML Kit hint = sensor − attempt (fixed sign).
- The decoder is forward-only ON PURPOSE: guards are asymmetric and mirrored digits
  are bit-reversed per digit plus digit-order-reversed, so pixel reversal cannot reuse
  the table. 180° labels decode via the 180° candidate (half the CPU of dual-direction).
- Orientation order is sensor-relative: `OrientationCandidates` starts at the
  rotation that compensates `ScanFrame.rotationDegrees` (portrait camera frames
  are stored sideways), then upside-down, then sideways. The old fixed
  0°→180°→90°→270° order made live portrait frames burn the 1500 ms fusion
  budget on sideways MSI/OCR attempts before the upright 90°/270° candidate,
  so MSI appeared camera-blind while gallery stills decoded. Sideways views
  still need vertical sampling: robust mode adds 3 vertical scanlines; default
  mode skips MSI on the two sideways candidates (relative to upright).

**Inversion (white-on-black).**
- zxing-cpp path sets TryInvert; MSI scanline path assumes dark-bars-on-light (quiet-zone
  trim seeks white margins). Inverted MSI labels are currently out of scope — listed as
  future work (polarity-flip retry of the binary image). The fusion layer already covers
  inversion for all ML Kit/zxing symbologies.

**Skew / perspective / noise.**
- 3–7 horizontal scanlines across the 30–70% band (+3 vertical in robust) tolerate skew;
  quiet-zone + guard + length + checksum gates reject text/ITF lookalikes; pool dedups on
  (value, symbology) keeping max confidence.

## 4. Verification procedure (must pass before lifting EXPERIMENTAL)

1. Render with Zint (`zint -b MSI_PLESSEY` / `MSIPLESSEY`) payloads covering each digit 0–9
   in every position + Mod10 vectors ("1234567"→4, "8052"→3) + Mod11 IBM vector from §2.
2. Feed PNGs through `decode()` (all four checksum policies incl. NONE-gating) — require
   exact payload recovery and correct guard rejection on cropped variants.
3. Attach the pairwise Hamming-distance matrix (min = 2, list the 15 distance-2 pairs) to the PR.
4. Blur/rotation ladder: Gaussian σ {0,1,2} × rotations {0,90,180,270} × inversion on/off;
   record recall @ fixed FP and the Laplacian knee (replaces NORMALIZER lore).

## 5. References

- https://en.wikipedia.org/wiki/MSI_Barcode (encoding tables, Luhn/Mod11, 1234567→4 example)
- https://www.morovia.com/kb/MSIPlessey-Specification-10637.html (Mod10 steps, 8052→3)
- zxing-cpp `core/src/BarcodeFormat.h` @master (DataBar variants; absence of MSI) + README
  (DataBar Omnidirectional/Stacked/Limited/Expanded support) + Ubuntu 2.3.0 package manifest
- ZXing.Net `BarcodeFormat.cs` (MSI/PLESSEY exist in .NET port; excluded from All_1D as FP-prone)
- Scandit `Checksum` enum (Mod10/1010/11/1110 vocabulary), Seagull MSI guide (warehouse context)

## 6. Shelf-photo evaluation (6 user-supplied labels, Oct 2026)
Harness: `MsiPlesseyDecoder` direct (temporary probe tests, since removed) over all
policies × robust on/off, plus zxing-cpp 3.1.1 (Python wheel) as an independent check.

| File (SKU) | Result | Notes |
|---|---|---|
| quakotml `0186477` | ✅ EXACT bars (`MOD_10_10` → `0186477`, 1.0) | Barcode encodes SKU + Mod1010 checks (6,8). zxing: nothing (MSI unsupported — confirms custom path needed). |
| starbucks `0168971` | ✅ EXACT bars (`MOD_10` → `0168971`, 1.0) | Tiny ~1.9px modules; needed the 1440 fallback scale. |
| ondeg `0243523` | ✅ EXACT via OCR (`MOD_11` → `024352`, 0.7) | Bars unreadable (3 systematically damaged digits); label is IBM-Mod11 (verified), text reads. |
| dixie `0087573` | ❌ honest NotFound | Heavy blur + glare (B25-class fused runs, 130px glare gaps); printed number validates under NO standard scheme (Mod10 gives 7≠3), so strict OCR also withholds. |
| silkalm `0826593` | ❌ honest NotFound | Barcode top occluded by overlaid tag + tilted; no digit text in view. |
| yakult `0828147` | ❌ honest NotFound | Plastic shelf-strip cover: reflections/scratches over bars; no clean digit text. |

No confident false positives under correct per-label policies (the rest stay silent
rather than guess). Techniques added for these samples, kept because they proved out
without regressing precision:
- windowed search with LOCAL cuts (global k-means is set by giant price text),
- subpixel gray runs (zero-crossing edges; recovers the split at ~3px modules),
- soft per-digit least squares + multi-cut voting, min-projection bands (glare),
  background-subtracted binarization (pale bars), hi-res gray (native + 960 primary,
  1440 fallback with a 6-digit minimum), length×votes ranking with ≥2-vote gate,
  true reverse-direction decode (upside-down).
- Two real bugs found by the probes and fixed: Otsu returns 0 on clean black/white
  images (tie-break by maximizing-range mean), and the leading bar was silently
  dropped (no maximum precedes the first minimum), shifting every window off-phase.

Attempted and REJECTED (manufactured confident false positives — recorded so nobody
re-tries them): checksum-guided digit repair (deterministic pipeline + 1-digit Luhn
+ correlated observations = certain collisions), prominence < 6, row-quiet relaxation,
single-vote acceptance. Rule of thumb from this evaluation: with 1-digit checksums,
any mechanism that multiplies validation lotteries must be gated by independent
multi-observation agreement, and correlated observations (overlapping bands, adjacent
rows, rescaled copies) must not each count as independent votes.

## 7. SKU text fallback (OCR) + label checksum census

Scandit's damaged-label path falls back to reading the printed digits; ours now
does the same (`data/ocr/OcrSkuDecoder`, ML Kit text-recognition 16.0.1 bundled,
offline). Design: runs LAST after all bar engines miss; requires content bands
(blank walls never invoke the model); digit runs split on non-digits; prefers
dash-free runs (case codes print dashed: `000-42000-15121`); emits `MSI_PLESSEY`
with `engineName="MsiOcr"` and the OCR box. The emitted value is the digit run
AS PRINTED — never checksum-stripped, because shelf labels print the SKU
WITHOUT its check digit(s) (those live in the barcode): Starbucks payload
`0168971` is itself a valid Mod10 codeword and the old strip reported `016897`;
Yakult `0828147` and Dixie `0087573` match no standard scheme at all. Trusting
the print is therefore the default (`msiOcrRequireChecksum = false`), emitted at
0.5; strict validation remains available (0.7) when printed numbers do carry
their check. One misread digit cannot be detected without the check digit
(ML Kit reads Silkalm `0826593` as `0828593`), so hosts should treat `MsiOcr`
hits as lower-trust and can filter them via `minConfidence`/engine inspection.
Because OCR is last in the chain, `decodeTimeoutMillis` must cover the bar
engines first (~4 s for shelf labels; the 1.5 s default truncated it).

Checksum census from the filename SKUs (machine-verified, not assumed):
- quakotml `0186477`: Mod1010 (checks 6, 8) — bars decode.
- starbucks `0168971`: single Mod10 — bars decode.
- ondeg `0243523`: IBM Mod11 (check 3) — bars unreadable, OCR accepts under MOD_11.
- dixie `0087573`: validates under NO standard scheme (Mod10 gives 7 ≠ 3; IBM
  Mod11 gives 2; NCR Mod11 gives 2) — strict OCR correctly rejects it; only NONE
  (debug) returns the printed text. Lesson recorded: never hand-assert a check
  digit; the earlier probe confusion ("0087573 must be Mod10") was my arithmetic,
  the code was right.
- silkalm/yakult: no digit text in view (occlusion/cover) — silent everywhere.

Scoreboard with correct per-label policies: bars 2/6 exact (quakotml, starbucks),
OCR 1/6 exact (ondeg via MOD_11), 3 honest silences, zero confident false positives
(dixie MOD_11 short-collision "1512" is withheld by the 6-digit floor).

- https://en.wikipedia.org/wiki/MSI_Barcode (encoding tables, Luhn/Mod11, 1234567→4 example)
- https://www.morovia.com/kb/MSIPlessey-Specification-10637.html (Mod10 steps, 8052→3)
- zxing-cpp `core/src/BarcodeFormat.h` @master (DataBar variants; absence of MSI) + README
  (DataBar Omnidirectional/Stacked/Limited/Expanded support) + Ubuntu 2.3.0 package manifest
- ZXing.Net `BarcodeFormat.cs` (MSI/PLESSEY exist in .NET port; excluded from All_1D as FP-prone)
- Scandit `Checksum` enum (Mod10/1010/11/1110 vocabulary), Seagull MSI guide (warehouse context)

