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
2. `BlurScoringTransform` (single-getPixels Laplacian variance) scores, never drops.
3. `ContrastNormalizationTransform` (2/98-percentile stretch) normalizes exposure.
4. `MsiBinarizer.binarizeVariants` tries Otsu, Otsu±18, adaptive-mean (integral image)
   — up to 4 variants in robust mode. Each variant × each scanline votes independently.
5. Per-scanline k-means (k=2) narrow/wide split absorbs ±module distortion; `deSpeckle`
   is relative (≤25% of cut, neighbors ≥3×) so far-field 1–2px narrow bars survive;
   MSI working copy downscales with `filter=false`.
6. `trySingleBitRepair` flips each of 8 bits once. Table min Hamming distance is 2
   (computed over all 45 pairs; 15 pairs at distance 2, none at 1), so a 1-bit error
   never lands exactly on another digit — but ~80/256 random patterns sit within 1 flip
   of *some* digit (≈31%), and several corruptions are ambiguous (e.g. '5'+bit3 →
   {'1','5'} → repair returns null). Hence repair + checksum + cross-scanline voting:
   2+ agreeing scanlines → confidence 1.0; single vote → 0.75; NONE policy requires 2 votes.

**Rotation / upside-down.**
- Fusion `OrientationCandidates` expands 0°→180°→90°→270° (default 2, robust 4).
  Physical rotation uses nearest-neighbor; ML Kit hint = sensor − attempt (fixed sign).
- The decoder is forward-only ON PURPOSE: guards are asymmetric and mirrored digits
  are bit-reversed per digit plus digit-order-reversed, so pixel reversal cannot reuse
  the table. 180° labels decode via the 180° candidate (half the CPU of dual-direction).
- 90°/270° bars need vertical sampling: robust mode adds 3 vertical scanlines and
  allows MSI on 90/270° candidates; default mode documents 0/180°-only MSI.

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

All labels are blurry shelf photos with the barcode small in frame; SKUs from file names.
Harness: `MsiPlesseyDecoder` direct (temporary probe tests, since removed) over all
policies × robust on/off, plus zxing-cpp 3.1.1 (Python wheel) as an independent check.

| File (SKU) | Result | Notes |
|---|---|---|
| quakotml `0186477` | ✅ EXACT (`MOD_10_10` → `0186477`, 1.0) | Barcode encodes SKU + Mod1010 checks (6,8). zxing: nothing (MSI unsupported — confirms custom path needed). |
| starbucks `0168971` | ✅ EXACT (`MOD_10` → `0168971`, 1.0) | Tiny ~1.9px modules; needed the 1440 fallback scale. |
| dixie `0087573` | ❌ honest NotFound | Heavy blur + glare; narrow spaces merge (B25-class fused runs), W130+ glare gaps fragment every row. |
| ondeg `0243523` | ❌ honest NotFound | Best window measures 6/9 digits right; 3 digits systematically damaged (likely scratch zone); shifted framings defeat consensus. |
| silkalm `0826593` | ❌ honest NotFound | Barcode top occluded by overlaid tag + tilted. |
| yakult `0828147` | ❌ honest NotFound | Plastic shelf-strip cover: reflections/scratches over bars. |

No confident false positives under any checksum policy (the remaining 4 stay silent
rather than guess). Techniques added for these samples, kept because they proved out
on quakotml/starbucks and never regressed precision:
- windowed search with LOCAL cuts (global k-means is set by giant price text),
- subpixel gray runs (zero-crossing edges; recovers the split at ~3px modules),
- soft per-digit least squares + multi-cut voting, min-projection bands (glare),
  background-subtracted binarization (pale bars), hi-res gray (960 primary + 1440
  fallback with a 6-digit minimum), per-observation-longest + ≥2-vote gate,
  cross-observation consensus (majority per position, checksum-validated).
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
