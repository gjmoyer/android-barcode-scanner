# Rotation-aware MSI fallback

This branch adds a clean-room C++ orientation fallback for tilted shelf tags.
It is the first learned component in the MSI path, and it is trained only on
synthetic rotations of the private source crops — not on the evaluation
augmentations.

## Runtime

- `msi_angle_model.h` — a 32×32 → 128 → 64 → 1 MLP regressor (1.7 MB of
  float weights).
- `predictAngle()` — area-averages the frame to 32×32 and predicts the tag's
  in-plane rotation.
- `decodeAngleFallback()` — rotates the original frame upright with
  nearest-neighbour sampling (PIL-compatible reverse-affine geometry), then
  retries the normal decoder:
  1. predicted angle;
  2. ±0.35°, ±0.7°, ±1.0°, ±1.4° refinement;
  3. 2%/98% contrast stretch of the rotated frame;
  4. 2× nearest-neighbour retry of the rotated frame.
- It runs only after the normal plain/stretch orientation sweeps fail, only
  on the still path (`FrameOptions::stretchRetry`), and only for frames with
  a long edge ≥330 px. Live frames keep the old behavior.
- A still-only 2x/3x agreement retry handles small modules; when both scales
  read they must agree (or only one may read).
- A still-only autocontrast+unsharp retry handles blurred/low-contrast tags.

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
| `clutter` (2×122) | 216 | **241** | 231 |
| `scale075/050/035` | 227 | **324** | 305 |

Totals: **122/122 native** and **2,323/2,440 augmented positives** versus
2,211 for the external reference. Every augmented family now matches or
exceeds the reference; the only wrong read is the pre-existing
`clutter-seed71` target-selection case.

No new wrong reads were introduced by the fallback. The one wrong read on
`clutter-seed71` predates this branch (a target-selection false positive).

## Guardrails

- `tools/msi-harness`: **152 assertions pass, 0 fail**.
- Synthetic bench: ~0.57 ms mean, 0.59 ms worst (angle fallback is failure
  path only).
- The angle model must not be enabled for live frames without re-measuring
  latency; the current gate keeps it on stills.
- Keep `msi_angle_model.h` in sync with its training script; do not train on
  the local augmented evaluation sets.
