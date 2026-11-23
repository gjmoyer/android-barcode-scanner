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
  runtime ML library). The weights land as ~557 KB of rodata and account for
  roughly half the host harness build time; that is the generated blob, not
  template instantiation, and it makes the model object the largest in the
  `.so`.
- `predictAngle()` — area-averages the frame to 32×32, standardises the
  input, and predicts the tag's in-plane rotation.
- `decodeAngleFallback()` — rotates the original frame upright with
  nearest-neighbour sampling (PIL-compatible reverse-affine geometry), then,
  for each candidate angle in {predicted, ±0.35°, ±0.7°, ±1.0°, ±1.4°},
  tries four views in order: the rotated frame, its 2%/98% contrast stretch,
  its 2× nearest-neighbour upscale (when enabled), and the stretched 2×
  frame. The four variants are interleaved per offset — the offset loop
  wraps all of them — not four global sweeps; the first read wins.
- Every accepted fallback candidate is checked against its own 180° view.
  A different reverse read is arbitrated on leading quiet zone; an
  undecidable conflict is rejected. A candidate whose reverse view fails to
  read must clear score ≥0.70 (no vote escape). Stills also force at least
  two orientations, so the ordinary sweep confirms the reverse view even
  when a caller asks for one orientation. Any stretched-sweep read without
  a reverse confirmation — the paired reverse failed, or the view is odd /
  unpaired (e.g. the 90° view at `maxOrientations=3`) — is rejected below
  score 0.55.
- It runs only after the normal plain/stretch orientation sweeps fail, only
  on the still path (`FrameOptions::stretchRetry`). Live frames keep the old
  behavior. The 2× stage needs a long edge ≥330 px, caps the rotated long
  edge at 1800 px, and caps its output at 4 MP. The frame-level stack is
  capped at a 3.5 MP source for the angle path (4 MP for sharpening); larger
  stills use the normal sweep.
- A still-only autocontrast+unsharp retry handles blurred/low-contrast tags.
  It runs **before** the 2x/3x ensemble because the ensemble dominates
  all-failure latency. Degenerate/near-flat histograms return an identity
  copy and skip the retry.
- A still-only 2x/3x agreement retry handles small modules. Both scales use
  the +1px shifted nearest-neighbour mapping (plain 2x duplicates every
  sampled pair and never produces an ROI). If both scales read they must
  agree; a single-scale read needs score ≥0.70. The ensemble is limited to
  frames ≤400 px, and derived angle fallback is limited to derived planes
  ≤1 MP. Sharpened frame decodes disable their own upscale; the sharpened
  angle pass and scale legs honor the caller's `allowUpscale`. The sharpened
  and scale retries require the configured range to start at 9
  (`minDigits>=9`); default open-length stills get only the direction-safe
  angle fallback.

The existing in-ROI skew retry (±15°) remains the first choice; this fallback
handles larger rotations and the combined blur+tilt cases.

All three still-only stages run inside one blocking native call. Kotlin's
`ScannerConfig.decodeTimeoutMillis` is checked between engines, not inside
that call, so it bounds fusion coordination rather than the native work
itself; each stage has its own pixel cap. A host that needs the pre-fallback
latency profile can set `ScannerConfig.msiStillFallbacks(false)` (wired to
`FrameOptions::allowStillFallbacks`), which skips the angle fallback,
sharpening and the ensemble while keeping the plain/stretch sweeps.

## Live tilt rescue (default on)

Live aim-box crops have no full-label context for `predictAngle`: a 972×252
Pixel crop measured **+17.9°** for a true ~−6° tilt. `msi::decodeDeskew`
therefore uses the projection estimate (`estimateSkew`, gain ≥1.3) and a
coarse **bicubic** rotation sweep on the **unstretched** crop. Each winning
orientation goes through the same 180° reverse confirmation as the still
angle fallback: a reverse-failing read needs the fallback score gate
(≥0.70), and a conflicting reverse read is arbitrated on leading quiet zone.
Sweeps are capped at 1 MP and throttled to one per 250 ms measured from the
previous sweep's end; the unstretched copy is prepared on the same shared
250 ms interval, gated by the decoder's cooldown so a prepared copy is never
discarded as throttled. Candidates pay the reverse decode before the score
gate is considered (the gate only applies when the reverse fails), so a
candidate-heavy sweep can cost up to twice the angle decodes; the cap and
cooldown bound it.
It runs only after the normal live decode misses and is on by default (`ScannerConfig.msiLiveDeskew`; set false to
disable). The normal decode path and the still path are unchanged; hits pay
one unstretched prepare on the 250 ms cadence, and misses additionally pay the
sweep.

## Validation

Host baseline, 122 native crops and the local augmented suite:

| Set | Before | After | Anonymized commercial baseline |
|---|---:|---:|---:|
| Native 122 | 121 | **122** | 120 |
| Main blur+skew (5×122) | 450 | **590** | 575 |
| `skew25` (2×122) | 155 | **243** | 240 |
| `persp08` (2×122) | 191 | **238** | 232 |
| `blur25` (2×122) | 176 | **220** | 207 |
| `contrast25` (2×122) | 175 | **211** | 199 |
| `dark20` (2×122) | 207 | **235** | 222 |
| `clutter` (2×122) | 216 | **230** | 231 |
| `scale075/050/035` | 227 | **319** | 305 |

Totals: **122/122 native** and **2,286/2,440 augmented positives** versus
2,211 for the anonymized commercial baseline. Every augmented family still
exceeds the baseline. The only wrong read is the pre-existing
`clutter-seed71` target-selection case; the safety pass added zero new wrong
reads.

The safety pass trades 33 reads for the direction-arbitration and score
gates above (2,319 → 2,286). The main loss is the 0.35 scale set, where the
old single-view over-upscaled reads were also the source of a checksum-valid
phantom.

## Guardrails

- `tools/msi-harness`: **189 assertions pass, 0 fail** (170 public, 189 with
  the local fixture photos).
- Fast path: 486 µs mean / 494 µs worst synthetic 1280×720; 524 µs / 631 µs
  on the fixture shelf photo (Apple M2 Max).
- Predicted angles outside ±60° are treated as 0°. The rotated canvas has a
  64 MP guard, but that is defense-in-depth: the 3.5 MP source cap bounds a
  real canvas to ~7 MP, and only extreme-aspect strips reach the guard. The
  angle path is capped at a 3.5 MP source, sharpening at 4 MP, with separate
  derived-plane caps (ensemble source ≤400 px, derived angle ≤1 MP, explicit
  angle 2× plane ≤4 MP).
- The harness includes the exact 180° fallback phantom, the 181° open/9-digit
  fallback phantom, five reversed-phantom cases under the still path, a
  3-orientation (0/180/90) stretched-sweep gate case, a
  direct angle-fallback contract check, a positive 22° rescue (the plain
  sweeps miss and a zeroed predictor fails it), golden vectors that pin the
  committed regressor weights, a small-module scale read, a
  sensor-rotation fallback case, and single-shot 16 MP/4 MP/1000 px/550 px/
  400 px ensemble all-failure budgets. The budgets are host-tuned and
  advisory in the harness (overruns print WARN and keep the exit code green)
  unless `MSI_PERF_STRICT=1` is set; the all-failure correctness assertion
  always gates.
- The angle model must not be enabled for live frames without re-measuring
  latency; the current gate keeps it on stills.
- The training script is local-only and not committed. Keep
  `msi_angle_model.h` in sync with it, record provenance here when
  regenerating, and do not train on the local augmented evaluation sets.
  The generated header must keep its `namespace msi { ... }` wrapper (the
  harness includes it only through the decoder) and a re-train must
  deliberately update the golden vectors in `tools/msi-harness`.
- Failure-path cost is measured separately in
  [`MSI_PERFORMANCE.md`](MSI_PERFORMANCE.md): the full fallback stack is
  ~0.412 s mean per all-failure negative still on the host (p95 0.60 s,
  max 0.74 s), against a 1.5 s still budget. A 16 MP all-failure still costs
  ~59 ms / 34 MB because the 4 MP gate skips the heavy retries; 4 MP noise
  costs ~0.16 s / 35 MB, 1000×1000 ~0.35 s / 21 MB, 550×550 ~0.30 s / 7 MB,
  and the worst 400 px ensemble case ~0.64 s / 22 MB. The angle path peaks
  near its 3.5 MP cap (~0.74 s / 68 MB). Fast-path reads are unchanged.
  These are Apple M2 Max host numbers: shared CI VMs and mid-tier phone
  cores can be several times slower, so treat them as regression baselines,
  not device latency guarantees — the pixel caps bound the work, not the
  wall time.
