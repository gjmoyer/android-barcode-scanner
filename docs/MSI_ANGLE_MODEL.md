# Rotation-aware MSI fallback

This branch adds a clean-room C++ orientation fallback for tilted shelf tags.
It is the first learned component in the MSI path, and it is trained only on
synthetic rotations of the private source crops — not on the evaluation
augmentations.

The regressor is the **only** learned part of MSI decoding. Bars, digit
templates, checksums, scanline voting, contrast stretching, unsharp masking
and 2x/3x agreement are all deterministic hand-written C++. No commercial
SDK code, weights, outputs, or training data are used. The validation table
includes an anonymized commercial-scanner benchmark as evaluation context;
those numbers are not training labels or implementation inputs.

## Runtime

- `msi_angle_model.h` — a 32×32 → 128 → 64 → 1 MLP regressor (~140k
  weights, 1.7 MB as the generated C++ header). Inference is plain C++ (no
  runtime ML library).
- `predictAngle()` — area-averages the frame to 32×32, standardises the
  input, and predicts the tag's in-plane rotation.
- `decodeAngleFallback()` — rotates the original frame upright with
  nearest-neighbour sampling (PIL-compatible reverse-affine geometry), then
  retries the normal decoder:
  1. predicted angle;
  2. ±0.35°, ±0.7°, ±1.0°, ±1.4° refinement;
  3. 2%/98% contrast stretch of the rotated frame;
  4. 2× nearest-neighbour retry of the rotated frame (plus the same stretch).
- Every accepted fallback candidate is checked against its own 180° view.
  A different reverse read is arbitrated on leading quiet zone; an
  undecidable conflict is rejected. Stills also force at least two
  orientations, so the ordinary sweep confirms the reverse view even when a
  caller asks for one orientation.
- It runs only after the normal plain/stretch orientation sweeps fail, only
  on the still path (`FrameOptions::stretchRetry`). Live frames keep the old
  behavior. The 2× stage needs a long edge ≥330 px and caps the rotated
  long edge at 1800 px. The whole fallback stack is capped at 4 MP; larger
  stills use the normal sweep.
- A still-only autocontrast+unsharp retry handles blurred/low-contrast tags.
  It runs **before** the 2x/3x ensemble because the ensemble dominates
  all-failure latency. Degenerate/near-flat histograms are returned as an
  identity copy, not blacked out.
- A still-only 2x/3x agreement retry handles small modules. Both scales use
  the +1px shifted nearest-neighbour mapping (plain 2x duplicates every
  sampled pair and never produces an ROI). If both scales read they must
  agree; a single-scale read needs a strong template score (≥0.65) or
  ≥3 votes. The ensemble is limited to frames ≤1000 px and nested angle
  fallback is bounded to derived frames ≤600 px.

The existing in-ROI skew retry (±15°) remains the first choice; this fallback
handles larger rotations and the combined blur+tilt cases.

## Validation

Host baseline, 122 native crops and the local augmented suite:

| Set | Before | After | Anonymized commercial baseline |
|---|---:|---:|---:|
| Native 122 | 121 | **122** | 120 |
| Main blur+skew (5×122) | 450 | **592** | 575 |
| `skew25` (2×122) | 155 | **244** | 240 |
| `persp08` (2×122) | 191 | **241** | 232 |
| `blur25` (2×122) | 176 | **220** | 207 |
| `contrast25` (2×122) | 175 | **212** | 199 |
| `dark20` (2×122) | 207 | **235** | 222 |
| `clutter` (2×122) | 216 | **236** | 231 |
| `scale075/050/035` | 227 | **319** | 305 |

Totals: **122/122 native** and **2,299/2,440 augmented positives** versus
2,211 for the anonymized commercial baseline. Every augmented family still
exceeds the baseline. The only wrong read is the pre-existing
`clutter-seed71` target-selection case; the safety pass added zero new wrong
reads.

The safety pass trades 20 reads for the direction-arbitration and score
gates above (2,319 → 2,299). The main loss is the 0.35 scale set, where the
old single-view over-upscaled reads were also the source of a checksum-valid
phantom.

## Guardrails

- `tools/msi-harness`: **160 assertions pass, 0 fail** (141 public, 160 with
  the local fixture photos).
- Fast path: 491 µs mean / 536 µs worst synthetic 1280×720; 524 µs / 601 µs
  on the fixture shelf photo (Apple M2 Max).
- Predicted angles outside ±60° are treated as 0°. The rotated canvas is
  capped at 64 MP, and the whole still fallback stack is capped at 4 MP.
- The harness includes the exact 180° fallback phantom, five existing
  reversed-phantom cases under the still path, a small-module scale read,
  and a sensor-rotation fallback case.
- The angle model must not be enabled for live frames without re-measuring
  latency; the current gate keeps it on stills.
- The training script is local-only and not committed. Keep
  `msi_angle_model.h` in sync with it, record provenance here when
  regenerating, and do not train on the local augmented evaluation sets.
- Failure-path cost is measured separately in
  [`MSI_PERFORMANCE.md`](MSI_PERFORMANCE.md): the full fallback stack is
  ~0.62 s mean per all-failure negative still on the host (p95 0.75 s,
  max 0.85 s), against a 1.5 s still budget. A 16 MP all-failure still costs
  ~60 ms / 34 MB because the 4 MP gate skips the heavy retries; a
  1000×1000 noise frame costs ~0.93 s. Fast-path reads are unchanged.
