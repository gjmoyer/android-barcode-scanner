# Rotation-aware MSI fallback

This branch adds a clean-room C++ orientation fallback for tilted shelf tags.
It is the first learned component in the MSI path, and it is trained only on
synthetic rotations of the private source crops — not on the evaluation
augmentations.

The regressor is the **only** learned part of MSI decoding. Bars, digit
templates, checksums, scanline voting, contrast stretching, unsharp masking
and 2x/3x agreement are all deterministic hand-written C++. No commercial
SDK weights are used.

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
- It runs only after the normal plain/stretch orientation sweeps fail, only
  on the still path (`FrameOptions::stretchRetry`). Live frames keep the old
  behavior. The 2× stage of the rotated-frame retry needs a long edge ≥330 px
  (and caps the rotated long edge at 1800 px).
- A still-only autocontrast+unsharp retry handles blurred/low-contrast tags.
  It runs **before** the 2x/3x ensemble because the ensemble dominates
  all-failure latency.
- A still-only 2x/3x agreement retry handles small modules; when both scales
  read they must agree (or only one may read). It is limited to frames
  ≤1000 px.

The existing in-ROI skew retry (±15°) remains the first choice; this fallback
handles larger rotations and the combined blur+tilt cases.

## Validation

Host baseline, 122 native crops and the local augmented suite:

| Set | Before | After | External reference |
|---|---:|---:|---:|
| Native 122 | 121 | **122** | 120 |
| Main blur+skew (5×122) | 450 | **599** | 575 |
| `skew25` (2×122) | 155 | **244** | 240 |
| `persp08` (2×122) | 191 | **241** | 232 |
| `blur25` (2×122) | 176 | **222** | 207 |
| `contrast25` (2×122) | 175 | **214** | 199 |
| `dark20` (2×122) | 207 | **238** | 222 |
| `clutter` (2×122) | 216 | **237** | 231 |
| `scale075/050/035` | 227 | **324** | 305 |

Totals: **122/122 native** and **2,319/2,440 augmented positives** versus
2,211 for the external reference. Every augmented family now matches or
exceeds the reference; the only wrong read is the pre-existing
`clutter-seed71` target-selection case.

No new wrong reads were introduced by the fallback. The one wrong read on
`clutter-seed71` predates this branch (a target-selection false positive).

## Guardrails

- `tools/msi-harness`: **152 assertions pass, 0 fail**.
- Synthetic bench: ~0.57 ms mean, 0.59 ms worst for angle-regressor inference
  (the fallback itself is failure path only).
- Predicted angles outside ±60° are treated as 0°, and the rotated canvas is
  capped at 64 MP; both guards prevent a bad prediction from allocating a
  huge frame.
- The angle model must not be enabled for live frames without re-measuring
  latency; the current gate keeps it on stills.
- The training script is local-only and not committed. Keep
  `msi_angle_model.h` in sync with it, record provenance here when
  regenerating, and do not train on the local augmented evaluation sets.
- Failure-path cost is measured separately in
  [`MSI_PERFORMANCE.md`](MSI_PERFORMANCE.md): the full fallback stack is
  ~0.87 s mean per all-failure negative still on the host (p95 1.12 s,
  max 1.32 s), against a 1.5 s still budget. Fast-path reads are unchanged.
