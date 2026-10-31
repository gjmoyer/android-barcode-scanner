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
4. Checksum gate: the host's `msiChecksumPolicy` is pushed into native
   candidate selection (only strings valid under it compete; `NONE` lets
   MOD10 → MOD11 → MOD1010 → MOD1110 compete, first validating scheme
   reported) + ≥2 agreeing scanline votes (6 below 2.5px modules) →
   confidence 1.0. Single votes never emit. A single NN 2x upscale retry
   covers far-field frames whose modules fall below ~2px (failure path only;
   success paths never pay for it).
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
- Live aim-box frames try upright and upside-down. Reversed bars can decode to
  checksum-valid garbage, so a read is confirmed against the opposite view
  and the direction with the clearer leading quiet zone wins (§8.3 step 9).
- Small tilt (up to ±15°) is handled natively by the skew retry (§8.3 step
  7b): scanlines along the measured bar angle. Before it, horizontal
  scanlines tolerated about ±5–7°.
- Larger tilt (15°, 30°, …) smears diagonal runs past scanlines: the ROI
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

All six fixtures carry the full 9-digit Mod1010 codeword (7-digit item +
Mod10 + Mod10). The host harness reads all six exactly; on-device hard
assertions cover yakult, dixie, starbucks and silkalm, while quakotml and
onedegree remain device-only gaps (matrix-logged, not asserted).

| File (SKU) | Full codeword | MOD_10 payload | MOD_10_10 payload | Notes |
|---|---|---|---|---|
| yakult `0828147` | `082814799` | `08281479` | `0828147` | Far-field tiny modules; rescued by the NN 2x upscale retry. Repeat-stable ×3 on device. |
| dixie `0087573` | `008757320` | `00875732` | `0087573` | Glare-heavy; ROI crop isolates it where full-frame sees phantoms. Repeat-stable ×3. |
| starbucks `0168971` | `016897100` | `01689710` | `0168971` | Double-check codeword; policy — not the reported scheme — decides the payload. Repeat-stable ×3. |
| silkalm `0826593` | `082659368` | `08265936` | `0826593` | Host exact both policies; the device MOD_10_10 full-resolution path is a logged gap. |
| quakotml `0186477` | `018647768` | `01864776` | `0186477` | Host exact; device full-res modules overshoot the template range (logged, not asserted). |
| ondeg `0243523` | `024352387` | `02435238` | `0243523` | Host exact; device true bars still do not resolve (logged, not asserted). |

> **Truth-label note.** The fixture filenames carry the 7-digit printed item.
> Under `MOD_10` a 9-digit codeword strips only its last check and therefore
> returns 8 digits (`08281479`, `00875732`, ...); those 8-digit values are
> truncations, not the full codeword. Assertions that used the 7-digit item as
> the `MOD_10` payload were corrected after re-checking the bars.

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
- yakult `0828147`: Mod1010 (checks 9, 9) — full codeword `082814799`.
- quakotml `0186477`: Mod1010 (checks 6, 8) — full codeword `018647768`.
- ondeg `0243523`: Mod1010 (checks 8, 7) — full codeword `024352387`.
- dixie `0087573`: Mod1010 (checks 2, 0) — full codeword `008757320`.
- starbucks `0168971`: Mod1010 (checks 0, 0) — full codeword `016897100`.
- silkalm `0826593`: Mod1010 (checks 6, 8) — full codeword `082659368`.

Scoreboard: host harness 6/6 exact full codewords; on-device hard assertions
4/6 (yakult, dixie, starbucks, silkalm) with quakotml and ondeg matrix-logged
gaps; zero wrong-value emissions in the asserted set. Under `MOD_10` the
9-digit codewords report their 8-digit truncation (`payload + first check`),
which is expected policy behaviour, not a full read.

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


## 8. Logic approaches: what was tried, what won, and why

Read this before touching the decoder. Three classification generations live
in the history; only the third runs. The others' dead code has been removed
(§8.5) and lives only in git history — do not revive it without re-reading
this section.

### 8.1 Width-threshold classification (retired with the Kotlin decoder)

Binarize (Otsu variants, adaptive mean) → measure run widths → k-means
narrow/wide split → table lookup → checksum under the configured policy.
Elegant on crisp input and fully JVM-testable, but structurally blur-fragile:
defocus moves edges, corrupted widths misclassify, and no amount of variant
binarization recovers information thresholding destroyed. Scoreboard on the
same shelf photos: 2/6 bars exact (+1 via the since-retired OCR crutch).
What survives: `MsiCodeTable` (spec table, still authoritative),
`MsiChecksumValidator` (now the Kotlin-side policy enforcer on
native-reported digits).

### 8.2 Bar-shape correlation (dead on arrival)

`classifyBarsByCorrelation` (never called; removed, see §8.5):
correlate the signal around each bar center against blurred
narrow/wide templates instead of thresholding widths. Correct instinct
(shape, not size), wrong granularity: per-bar hard decisions compound —
one flipped bar kills its digit, and there is no whole-digit evidence to
outvote it. Superseded by §8.3 before ever shipping. Leave it dead.

### 8.3 Digit-template correlation (current hot path)

`decode()` → `decodeImpl()` (`src/main/cpp/msi_decoder.cpp`):
1. `findRoiCandidates`: rows with dense thin vertical gradients form bands
   (top quarter skipped, `DecodeOptions::scanTopFraction`); columns with sustained edges
   set extents. Up to 8 ROIs, strongest first. xstep=2 subsampling is a known
   blind spot for pixel-perfect axis-aligned input (see §8.6); real captures
   always carry enough noise to break the lock — xstep=1 was tried and
   reverted for shifting real-photo band fringes.
2. Per ROI: one averaged profile + up to `kMaxScanlines`=9 individual rows.
3. `findBars` (prominent luminance minima) → `filterBars` (drop
   sub-1.8-module splits) → module = median bar spacing / 3.
4. `decodeByDigitTemplates`: for every bar as start (a former 5-bar cap
   was the dominant miss behind left-side clutter) and each plausible digit
   count, anchor 12-module windows at bar centers and
   correlate against Gaussian-blurred digit templates (two width hypotheses
   × ±0.5-module search) at three blur sigmas (0.35/0.6/0.95 × module),
   pooled. Blurred templates are cached thread-locally per sigma on exact
   float match of the module estimate (bit-identical). Each bar's best
   digit match is memoized per sigma (Starts s and s+4 share windows, and
   every length repeats the shorter ones). It is bit-identical, and halves
   open-length clutter cost (mean 6.2 → 3.4 ms, max 54 → 22 ms). Start hypotheses
   must sit on a wide bar and have a ≥7-module quiet zone before them (a
   bar closer than that means the hypothesis starts inside the digit field
   and drops leading digits). Every digit count is tried while the symbol
   fits the bars found (up to 6 missed bars). There is no floor on the bars
   left after the symbol: shelf tags often carry print or a neighbouring
   tag to its right, and a former floor (at most 6 trailing bars) never
   tried the true hypothesis there. Removing it took shelf-set MOD1010
   9-digit reads from 141/135/138 to 147/139/143 (near/far/still) and
   full-photo open-length reads from 64 to 85 ok, with 0 wrong. The guard
   gate (step 6) handles overruns and truncations. Open item: ranking still
   puts global bar fit first, so with trailing print a longer hypothesis
   that absorbs it can outrank the true read and then fail the guard. Only
   the ranked winner reaches voting, so the true read is lost (a recall
   gap, not a wrong-read risk). A shorter read only drops a longer one that
   extends it when strictly stronger AND better bar-fitting — on ties the
   longer read stays (the 8-digit prefix ending at the first check is
   Mod10-valid).
   Each digit window anchors to its own bar, so a dropped or extra bar can
   push one window off the symbol's uniform pitch onto a lucky template
   match that still closes the checksum. Checksum-valid hypotheses whose
   window starts deviate more than `kMaxDigitGridResidual`=1.5 modules from
   their least-squares line are dropped. True reads stay within 0.63 modules
   (window/alignment search jitter); a one-bar slip moves ≥2.5. Shelf set,
   bctest MOD10: scored wrong 16 → 4; NONE: about 26 → 7; MOD1010: phantom
   on unreadable `0471-4` (near) removed, one far read lost that had
   matched its check digit two digit-pitches past the symbol.
5. `validateChecksum`: with a required policy (`decode(..., required)`,
   pushed from `msiChecksumPolicy` via JNI) only that scheme validates, so
   it constrains ranking and voting rather than filtering the winner;
   otherwise MOD10 → MOD11 → MOD1010 → MOD1110, first hit wins.
   ≥`kMinVotesCheck`=2 agreeing scanline profiles required, 6 when the
   module is below 2.5px (NONE is disabled: too many false positives).
6. Guard gate (`guardEvidence` → `guardFailure`), applied per vote key
   before the winner is picked. Each element is the best over the key's
   agreeing scanlines, because blur and clutter weaken single lines and a
   one-line conjunctive rule lost true reads:
   - Start ≥0.6: the Start space minus its wide bar, relative to the
     digit-field contrast. With the Start bar uncapped, phantoms score
     ≤0.49 and true reads ≥0.76.
   - Strict Stop ≥0.25 (if measured): min(Stop spaces) − max(Stop bars). A
     mean-based Stop let 180° reversed reads through at about 1.0; the strict
     form scores them ≤0.08, while true reads score ≥0.41.
   - Trailing quiet zone ≥0.3 (if measured): the darkest of modules 4..9
     after the Stop, minus the bar reference. Digits 0–7 begin with `1001`
     (the Stop pattern), so a truncated read lands on a perfect fake Stop. A
     real bar always follows within about 3 modules. The two measurable
     truncations scored ≤−1.34, while true reads score ≥0.52.
   - Frame cut: the ROI has no right padding, so every scanline carries a
     verification tail of max(48 px, ROI/6) beyond it, clamped at the frame
     edge (`appendVerifyTail`). Decoding sees only `[0, n)`; the 1D retry
     upsamples the parts separately. A mirrored verification head before the
     ROI (`prependVerifyHead`) is read only for the leading quiet zone
     (`DecodeResult::leadingQuiet`, step 9), which is never gated. If the tail is clamped before the
     quiet zone, the read is rejected unless the length bounds forbid a
     longer symbol. With open lengths (the SDK default), this is meant to
     keep a tag cut by the photo or aim-box edge silent rather than
     emitting its MOD10-valid prefix. Some cut-off tags still get through
     (below).

   Shelf set, open lengths, compared with a Start/Stop-only gate: full-photo
   MOD10/NONE wrong reads 5 → 0 (the 5-bar-cap build read none of those
   photos) and live/still crop MOD10 wrong reads on readable tags 7 → 0.
   Unreadable (scored=no) tags still give 6 near/far MOD10 false reads at
   open lengths (13 before): 8-digit MOD10-valid prefixes of tags whose bars
   run off the photo edge. This is an open item; MOD1010 and 9-digit bounds
   have none. At 9-digit bounds no true read is lost. Open lengths lose 2
   tight still crops whose quiet zone is cut, as intended.
7. Failure path only, four retries in order. (a) 1D: every failed ROI with
   module <4.5px (`kProfileRetryMaxModule`) re-votes its own scanlines
   upsampled 2x at quarter-sample phase; no new ROI search, same vote and
   guard gates. Top level only: inside the 2D recursion it compounds to 4x
   interpolation and bred a phantom on an unreadable tag.
   (b) Skew, top level only. A tilted symbol defeats horizontal scanlines:
   `0540-1` (about 7°, 310×50 px) drifts a row 38 px, off the bar field, and
   row averaging smears the bars. `estimateSkew` shifts each row of the ROI
   band along a trial slope and maximises the edge energy of the band
   average, over ±15° in 1° steps then ±0.5°. Gain = best energy / energy at
   0°. ROIs below 1.5° or gain 1.5 skip; tilted shelf tags gain 1.65–21
   (median 6.6), while 92% of clutter ROIs fall below 1.5, so 84% of clutter
   miss frames skip the skew scanlines. Only the first gated
   ROI is scanned (`kSkewMaxRois`): every shelf skew read came from it,
   while slanted clutter gated 2–5 ROIs and scanning them all raised the
   worst clutter frames from 18–25 to 30–36 ms. Scanlines then follow the
   symbol axis: a sub-pixel deskewed band average plus up to 9 lines with
   linear interpolation, each with its verification tail along the same
   line. They run over the deskewed extent first, then the original ROI
   extent. The horizontal extent cuts a tilted symbol's outer digits:
   rotated crops gave 8-digit MOD10-valid prefixes that passed every guard.
   The deskewed extent alone takes in trailing print on full photo
   `IMG_0540`, where it yields no checksum-valid read (fit-ranking gap,
   step 4). Small modules get the 1D 2x upsample.
   Measured and rejected:
   - a structure-tensor angle estimate: biased about 3° on 0540-1;
   - an evidence gate on the retry, the fallback extent or the upsample: each
     lost reads;
   - running it inside the 2D recursion: cost.
   (c) Grid, top level only, after the skew retry. Blur can fill a thin
   space and merge two bars. The template decode pins digit d's window to
   bar 4d+1 after the Start, so one lost bar shifts every later window:
   `0452-1` finds 35 of 39 bars on its best lines and no line reads, while a
   commercial scanner reads it. `decodeByDigitGrid` re-votes each failed
   ROI's own 1x scanlines and places each digit window one pitch (12
   modules) after the previous window's best match, ±0.5 module; the bars
   only seed the Start. The bar-spacing module estimate is quantised (3.00
   for a true 2.85), so several pitches are tried (first bar ×0.94/1.0/1.06;
   now see "Pitch and votes" below), with 2 blur sigmas. A line's read needs the checksum, the grid residual, a mean
   digit correlation ≥0.7, every digit ≥0.6 and its own Start, Stop and
   quiet-zone guards; then the normal vote. Tracking stops below 0.4, and a
   track whose first 7 digits include one below 0.6 is dropped at once (no
   read can pass, so the cut is exact). A read is a prefix, and dropped,
   when its track continues to a longer valid string with a stronger
   checksum, or an equally strong one whose own guards pass: the 8-digit
   prefix of a 9-digit Mod1010 tag is always Mod10-valid and is Mod1010-valid
   one time in ten (`0475-1` read `01742279`).
   The gates come from forcing the grid on all 11.8k ROIs of the shelf set
   (crops at 0/±6°, every photo, MOD10 open lengths). True lines score ≥0.52
   (1st percentile, median 0.82); chance strings that pass the checksum and
   guards score 0.65 median. The chosen gates keep 300 of 306 true ROI
   reads, every grid-only read among them, with 0 wrong ROI winners.
   Placed ahead of the skew retry, an ungated grid lost two +6° crops that
   the skew retry reads. Gains: `0452-1` and `0488-1` near and still, 3 full
   photos and 2–3 crops at −6°, with 0 new wrong reads. Synthetic ink bleed
   (one or two 1-module spaces filled, `bleedSuite`): the previous build
   misses 256 of 864 renders, the grid retry none. Not run inside the 2D
   recursion; far views of `0452-1` and `0488-1` read only after the pitch
   and vote change below.
   Pitch and votes. At ~2px/module the bar-spacing estimate runs
   10–14% high (2.33 for a true 2.14 on `0444-4`), outside the 0.94/1.06
   retries, and the 6-vote small-module rule (<2.5px) held back far reads
   that the grid reached on fewer than 6 lines. The grid now tries the bar
   estimate ×1.0 plus the spectral estimate ×0.97/1.0/1.03:
   `spectralModule` takes the strongest Hann-windowed DFT bin at period
   3 modules (one MSI bit), scanning 0.75–1.10× the bar estimate in 1%
   steps. On all 162 near crops it lands within −4.1%..+1.0% of the true
   pitch (5th..95th percentile; median −0.2%). Grid reads need 3 agreeing
   lines below 2.5px instead of 6; the template path keeps 6. Each grid
   line already passes its own guards and digit gates, unlike the
   template reads the 6-vote rule was set for. Spectral pitches alone
   lost two far reads (`0444-4`, `0506-4`); keeping bar ×0.94/1.06
   too read nothing more for +0.8 ms clutter. A split-band ROI retry
   (text line merged into the barcode band, `0452-3`) was measured and
   rejected: its template decode added 8-digit prefix and Mod11 wrong reads,
   and a grid-only split still misread one rotated crop.
   Synthetic check (`smallBleedSuite`: 5 tags at 1.9–2.5px fractional
   modules, area-downscaled, with and without bleed, blur 0/1): MOD1010
   reads 196 → 220 of 240 with 0 wrong. On 12 more shelf values (576
   renders) MOD10 reads 464 → 521 and MOD1010 468 → 525, with the same 6
   and 2 pre-existing wrong reads (none new). With no caller scheme the
   spectral pitch at first added 4 Mod11-only wrong reads (`027996669` →
   `027896669`): a track can substitute one digit, Luhn rejects every
   single substitution, but a Luhn symbol with one wrong digit is
   Mod11-valid about 1 time in 11. Grid reads may therefore not be
   Mod11-only unless the caller requires MOD11; open-scheme wrong reads are
   then 7 → 7 (463 → 520 ok). Open item: the pre-existing synthetic wrong
   reads sit at 2.35–2.5px, half of them on clean renders (`001285790` →
   `111395711` under MOD10, a template-path read, not the grid).
   (d) 2D: one NN 2x upscale retry, single depth, +1px source
   shift so the xstep=2 sampler sees true boundaries. Fires on small frames
   (`kUpscaleMaxLongEdge`=800) or on small observed modules (<3.5px, long
   edge <1600) when at least one scanline produced a checksum-valid read or
   a hypothesis failed only the required scheme —
   without that evidence, text clutter triggered it on every miss frame.
   Bilinear and per-ROI 2D upscales were measured and rejected: re-detecting
   ROIs on the upscaled frame bred confident phantoms (shelf set, MOD1010).
8. Stills only, outside `msi::decode`: when every orientation misses,
   `decodeFrame` sweeps them again through the SDK's 2%/98% contrast
   stretch (`FrameOptions.stretchRetry`, set by `NativeFrame.prepareStill`).
   Stills are not stretched up front, because a whole-photo stretch clipped
   bars that already read (7 of 43 full-photo reads lost). Washed-out or
   soft tags read only after it: `0481-2` spans 92 gray levels, `0533-1`
   has almost no edge over 24 levels. Run as a retry, it cannot lose a
   read or change the orientation. On the shelf set it adds 4 still crops
   and 4–6 full photos, with no new wrong reads at 9 digits. It also gains
   3–8 rotated crops per angle. It doubles only the time of a still that
   reads nothing: the full-photo median went from 117 to 241 ms on the host,
   within the 1500 ms decode budget. The photo's stretch range did not
   separate helped from unhelped photos (177–237 levels in both groups), so
   there is no skip threshold. Live aim-box crops are stretched up front
   and never retry.
9. Direction arbitration, outside `msi::decode`. `decodeFrame` pairs each
   view with its reverse (0/180, 90/270). When the first view of a pair
   reads and the reverse is within `maxOrientations`, the reverse is decoded
   too. A different read there means one of the two is a reversed phantom:
   MSI bars read backwards give zero-heavy strings (`901000000`,
   `04210100`) that pass MOD10 about one time in ten and can clear every
   guard (strict Stop 0.28–0.42). The read with the larger leading quiet
   zone (darkest of the 6 modules before the Start bar vs the Start bar,
   ≥3 visible) wins by a margin of 0.3; a near tie or an unseen zone
   rejects the frame, skipping later views and the stretch retry. Shelf
   still crops at 0/±6/±10°, MOD10 open lengths: 17 conflicts, all
   resolved correctly; true reads 1.91–3.04 vs phantoms 0.85–1.66, each
   pair by ≥0.61. Absolute lead does not separate (true reads from 1.43,
   phantoms to 1.66), nor do votes or score, so it is not a gate. The ROI
   keeps only 20 px before its first strong column, so the verification
   head is required: without it one rotated true read (`0526-1` at +6°) was
   undecidable. A frame with no read pays nothing extra; a read pays one
   reverse decode.
10. Kotlin (`MsiNativeDecoder`): re-validate strictly under
   `msiChecksumPolicy`, strip check digits, reject degenerate
   single-repeated-digit payloads (blank areas decode as validating zeros).

### 8.4 Direct profile search (removed)

`decodeByDirectSearch` (sweep module 4.0–5.2 × offsets, greedy per-position
template picks) fired only on <7-digit template reads. Once `kMinDigits`
became 7 it was unreachable (0 calls across the synthetic sweep and 755 real
tag crops) and was deleted together with the Path B derivation variants
(prepend-0 / strip-first / strip-last: zero net effect on real photos) and
the double-check leading-zero gate (prepending `0` never changes Luhn/Mod11
validity, so it carried no evidence).

### 8.5 Dead-code removal

Removed with the grid retry (§8.3 step 7c), behavior-neutral (photo matrix and harness bit-identical,
`-Wall -Wextra` now clean): `clusterWidths`, `tryDecodeBars`, `bcdToChar`,
`classifyBarsByCorrelation` (§8.2), `findRoi` (legacy single-ROI wrapper),
`g_relaxedBars` and `findBars`' `sensitive` mode (the flag was never set),
`kMinVotesNone` (checksum-less reads are never voted) and the
`kScanTopFraction` duplicate of `DecodeOptions::scanTopFraction`. Recover
them from history (`2a38aca`) if an experiment needs them.

### 8.6 The crisp-input tie (investigated, deliberately unfixed)

On pixel-perfect renders, the true alignment and a shifted-by-one-bar
alignment correlate ~0.96 alike — the score carries no information, so any
fixed tiebreak just relocates the error: `+sIdx` amputates leading digits
(`08281479`→`8281479`), prefer-earlier prepends phantom zeros
(`12345674`→`012345674`, verified), and a consumption bonus breaks a green
case. All three variants were prototyped against the host harness and
reverted. Real captures separate properly via sensor texture (all six shelf
photos read correctly), so this is a synthetic-only characteristic — but it
rules the committed-fixture policy: fixtures must read green as committed,
so the repo pins runtime synthetics + honest-NotFound gates, never
luck-decided reads. Revisit ONLY on a real-world misparse with this
signature (leading/trailing digit drop on a crisp gallery import): the
principled fix would be structural guard verification around the winning
window, never another score tiebreak. (§8.3 step 6 now gates real
truncations this way; the crisp synthetic tie itself was not re-tested.)

## 9. Photo-trip handoff: ground-truth + fixture protocol

Context for a fresh session after a store visit. Aim: ~40 distinct MSI labels
× 5 shots (normal / far / close / ~30° tilt / glare-or-shadow), portrait and
landscape mixed — distinct labels matter, repeats are free in-test.

1. **Filename convention** (do this IN THE STORE, from the printed digits):
   `msi-<label>-<payload>.png`, where payload is the human-readable digits
   EXACTLY as printed. Never rename later from memory.
2. **Payload ≠ codeword.** The bars carry payload + check digit(s); the
   printed number is the payload. Verify each with the checksum math before
   it enters any expectation (Python one-liners; Luhn vectors `1234567`→4,
   `8052`→3 as sanity anchors):
   Mod10 = Luhn; Mod11 = IBM weights 2..7 from the right, `(11-sum%11)%11`;
   Mod1010 = Mod10 then Mod10 over payload+first check; Mod1110 = Mod11
   then Mod10. Reference full codewords: `0828147`→`082814799`,
   `0186477`→`018647768`, `0243523`→`024352387`, `0087573`→`008757320`,
   `0168971`→`016897100`, `0826593`→`082659368`. The 8-digit
   `payload + first check` forms are truncations. A SKU validating under NO
   scheme is data, not an error — record it as such (do not hand-invent a
   check digit; that exact mistake is documented in §7).
3. **Where files go (all git-ignored, all auto-consumed):** harness sweep dir
   (passed as argv[1]) and `src/androidTest/assets/msi_samples/`. Both
   suites skip absent files and sweep present ones; `DeviceMsiTest` logs a
   per-policy matrix plus hard assertions + a 3x repeat sweep for entries in
   its expectation maps.
4. **What "done" looks like per photo:** either EXACT at SDK payload level
   under its true policy (assert it), or honest-`NotFound` where truncated
   reads are policy-rejected (assert the silence — it fails loud on future
   phantom emission). Never bake a misparse as expected. Current gaps to
   beat: quakotml (modules overshoot), ondeg (phantoms validate).
5. **Privacy rule (non-negotiable):** real photos never enter the repo. Only
   derived artifacts may be committed: spec-rendered vectors reproducing a
   failure, aggregate pass/fail tables, and the generator script.

## 10. Real-photo regression (110 shelf photos, Oct 2026)

110 user-supplied HEIC shelf photos (each with ≥1 MSI tag; never committed).
Ground truth: macOS Vision OCR of the printed item line (`NNNNNNN 000-…`),
151 tags in 97 photos. Bar counts settle the format: 101 of 126 measurable
tags have 39 bars = 9 digits, i.e. **item (7) + Mod10 + Mod10 (Mod1010)**;
hand decode of item 0115783 gives `011578333`. Non-39 counts were crop
clipping or noise.

Consequences:
- The 8-digit prefix ending at the first check is Mod10-valid, so that
  truncation (`01157833`) is indistinguishable from a real Mod10 code
  unless the policy is pushed down.
- Luhn and Mod11 ignore leading zeros, so a dropped leading `0`
  (`11578333`) still validates.
- Under `MOD_10` 9-digit shelf tags emit the 8-digit `item+check1`; hosts
  scanning such tags should configure `MOD_10_10` to get the 7-digit item.

Inputs: per tag, tight crops below the item line at 1.0/1.5/2.0/3.0× and a
1.5× context crop (755 crops), with the SDK's 2/98 contrast stretch.
Scored against the 9-digit truth (ok / 8-digit truncation / wrong / none):

| Decoder | ok | trunc8 | wrong | none |
|---|---|---|---|---|
| main (d1a8e17) | 68 | 145 | 311 | 231 |
| shelf-truth refinements (4c9be3b) | 237 | 33 | 200 | 285 |
| + prefix-drop tie fix | 298 | 7 | 168 | 282 |
| + quiet-zone gate (NONE policy) | 378 | 16 | 55 | 306 |
| + `MOD1010` pushed down | **392** | **3** | **20** | 340 |
| same, `MOD10` pushed down | 381 | 16 | 48 | 310 |

Removing Path B, direct search and the lead-0 gate swapped 4 rows (totals
unchanged); the per-sigma cache and evidence-gated upscale change no row.
0/30 false positives on 1280×720 text-clutter negatives for every variant;
miss frames 3.1 → 0.4 ms after the upscale gate. Ablations (MOD1010
pushed down): single sigma −8 ok / −3 wrong but 2.25× faster; dropping the
6-vote small-module gate +2 wrong; dropping the module-triggered upscale
−3 ok.

### 10.1 Shelf-tag truth set (176 tags, aim-box live + still)

A later pass rebuilt the truth per tag (176 tags with verified SKUs).
162 are scored. The other 14 are must-not-misread negatives: 13 have bars
off the photo edge, and `0471-4` is unreadable even by a commercial
scanner. Tags Apple Vision missed were first excluded as unusable; once the
SDK out-read Vision (all 136 scored tags Vision reads, plus 14 of the 15 it
missed), the 21 with a full bar field became scored (141 → 162). It drives the live aim box near and far plus the still path
with 9-digit bounds, and also runs the full photos with open lengths.
Ok / wrong per mode (near / far / still):

| Build | MOD10 | MOD1010 | NONE |
|---|---|---|---|
| grid-residual gate (f02fffc) | 124/125/123, wrong 2/1/3 | 126/126/124, wrong 0/0/1 | 123/119/122, wrong 2/2/5 |
| + uncapped Start, guard gate | 141/135/138, wrong 0 | 141/135/138, wrong 0 | 140/132/137, wrong 0/2/0 |
| + no trailing-bar floor | 147/139/143, wrong 0 | 147/139/143, wrong 0 | 146/136/142, wrong 0/2/0 |
| + skew retry | 148/140/144, wrong 0 | 148/140/144, wrong 0 | 147/137/143, wrong 0/2/0 |
| + still stretch retry | 148/140/148, wrong 0 | 148/140/148, wrong 0 | 147/137/147, wrong 0/2/0 |
| + grid retry | 150/140/150, wrong 0 | 150/140/150, wrong 0 | 149/137/149, wrong 0/2/0 |
| + spectral grid pitch, 3 grid votes | **152/145/152, wrong 0** | **152/146/152, wrong 0** | 151/142/151, wrong 0/2/0 |

The two NONE far wrong reads are checksum-less digit errors with strong
guards, inherent to `NONE`. At open lengths NONE adds a third
(`0504-3` far, a MOD11-valid blur misread on the 2x-upscaled frame). Full
photos with open lengths: MOD10 52 → 62 → 82 → 83 → 88 → 90 → 92 ok, MOD1010
52 → 64 → 85 → 86 → 90 → 91 → 92 and NONE 62 → 78 → 79 → 85 → 88 → 90, all with 0 wrong
(score photos against `barcodes.tsv`: the older OCR `truth.tsv` lacks `0444-1`). Unreadable tags give 0 false reads at
9-digit bounds; at open lengths see §8.3 step 6 (cut-off tags, MOD10/NONE).
There are 0 false reads on 3430 clutter frames. Removing the trailing-bar
floor raised 9-digit MOD10 clutter latency from 2.5/8.0/18.3 to
3.9/12.7/29.6 ms (mean/p95/max); the skew retry moves it to
4.1/13.4/28.5 ms.

Scoring caveat: count wrong reads over every row, not just scored ones.
Unreadable tags must never misread.

Tilt. `0540-1` (about 7° clockwise, a 310×50 px symbol) drifts a
horizontal scanline about 38 px, off the bars, and smears the averaged
profile; a commercial scanner reads it. The skew retry (§8.3 step 7b) reads
it in every mode. Tilt tolerance is measured on the scored crops rotated
in software (median-colour fill), MOD1010, 9-digit bounds; 141 crops, and
162 from the grid retry on (the re-scored crops read at no angle in either
build):

| Rotation | −10° | −6° | +6° | +10° |
|---|---|---|---|---|
| no floor (2f5406b) | 4 | 81 | 62 | 4 |
| + skew retry | 122 | 123 | 125 | 118 |
| + still stretch retry | 128 | 126 | 128 | 125 |
| still stretch retry, 162 crops | 129 | 129 | 130 | 129 |
| + grid retry, 162 crops | 129 | 131 | 130 | 129 |
| + spectral grid pitch, 162 crops | 129 | 133 | 131 | 129 |

Every row has 0 wrong reads. At open lengths the rotated MOD10 wrong reads
go from 6 to 5 over the four angles (mostly prefix truncations near the
crop edge) and stay at 5 with the stretch retry, the grid retry and the
spectral grid pitch. NONE goes from 6 to 7 (unchanged by the grid retry
and the spectral pitch):
`0481-2` at +10° reads a checksum-less 8-digit fragment. The host harness asserts the synthetic shelf
format at ±3–12° with and without text clutter (`tiltSuite`; 2f5406b missed
12 of those 48), plus tilted text strokes that must stay silent.

Upside-down. Live frames try upright and upside-down
(`CameraScanManager.LIVE_ORIENTATIONS` = 2). The aim-box crops rotated 180°
read exactly as upright under every policy and length bound (MOD10 and
MOD1010 148/140, NONE 147/137 with the same 2 far wrong reads). Before
direction arbitration (§8.3 step 9), MOD10 at open lengths misread 5 near
and 1 far upside-down tags: the reversed view read first. Upright results,
full photos and the rotated sets are unchanged. The second orientation
doubles the cost of a frame with no read: 9-digit MOD10 clutter latency
went from 4.0/13.5/28.2 to 8.0/26.7/63.5 ms (mean/p95/max, host). The grid
retry (§8.3 step 7c) moves it to 8.1/27.4/63.1 ms, and its spectral pitch
to 9.3/31.6/70.2 ms.

Scored misses left at 9-digit bounds (MOD1010): near and still, `0452-3`
(its ROI band merges a text line with the bars) plus 9 of the 11
Vision-missed tags re-scored last (`0438-4` at 1.4px/module, `0444-3`,
`0445-3`, `0446-2`, `0487-2`, `0505-2`, `0510-1`, `0526-2`, `0547-3`; most
are too blurred or faint for the ROI row score to find); far adds 6 more
(`0445-2`, `0504-4`, `0507-1/3/4`, `0522-1`). The still-only `0446-6`, `0481-2` and `0533-1` read
with the still stretch retry (§8.3 step 8): they were contrast problems,
not angle problems.

Known limitation: full 12 MP stills through the SDK path read 0–4 of 110
for every variant — `DownscaleTransform` caps the long edge at 1280 before
`MsiRegionAssistDecoder` crops, leaving ~0.75px modules. Crop from the
full-resolution frame (or skip the downscale for stills) to fix.

Reproduce locally (photos and OCR output stay out of the repo): convert to
upright grayscale, OCR the item lines, crop below each, run the host decoder
over the manifest, and score against `item + Luhn + Luhn`.
