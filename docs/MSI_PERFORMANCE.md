# MSI performance — full fallback stack

Host measurements, macOS M2 Max, `validation/tools/msi_baseline`; 122
crops per set. Times are `decodeFrame` only (PNG I/O excluded). Device
timings will differ.

The accuracy baseline is the shelf configuration used by the local suite:
`MOD1010`, exact 9-digit bounds, `scanTopFraction=0`, `maxOrientations=4`,
`stretchRetry=true`. The default SDK still configuration is narrower: open
lengths, `MOD10`, `scanTopFraction=0.25`. The sharpened and scale fallbacks
require `minDigits>=9`; default open-length stills get the direction-safe
angle fallback only.

## Fast path

The native set has only successful reads, so it measures the normal path:

| Native 122 | Mean | p95 | Max |
|---|---:|---:|---:|
| Before fallbacks | 5.83 ms | 10.96 ms | 21.5 ms |
| After fallbacks | 5.98 ms | 11.45 ms | 21.4 ms |

The normal path is effectively unchanged. The fallback code runs only when
the plain/stretch/orientation passes return no read, and live frames do not
run these retries (`stretchRetry=false`).

## Full suite timing

| Condition | Mean | p95 | Max | Fail-only mean | Failures |
|---|---:|---:|---:|---:|---:|
| Native | 5.98 | 11.45 | 21.40 | — | 0 |
| Main blur+skew (610) | 22.91 | 118.43 | 323.32 | 162.1 | 20 |
| `skew25` (244) | 14.11 | 33.04 | 277.83 | 277.8 | 1 |
| `persp08` (244) | 26.31 | 96.13 | 736.31 | 422.6 | 6 |
| `blur25` (244) | 28.11 | 128.95 | 404.07 | 124.7 | 24 |
| `contrast25` (244) | 25.77 | 109.87 | 270.32 | 102.1 | 33 |
| `dark20` (244) | 13.43 | 56.25 | 156.24 | 85.6 | 9 |
| `clutter` (244) | 46.92 | 291.32 | 755.44 | 430.7 | 12 |
| `scale075` (122) | 17.67 | 33.76 | 601.35 | 601.3 | 1 |
| `scale050` (122) | 23.74 | 110.29 | 399.76 | 321.7 | 3 |
| `scale035` (122) | 118.20 | 370.48 | 784.29 | 208.9 | 43 |
| `negative` (244, all fail) | **414.67** | **601.47** | **741.72** | **414.7** | 244 |

Total decode time over the 23-set suite: **27.1 s baseline, 173.1 s final**
(the pre-safety fallback stack was 358.3 s). The all-failure negative frame
now fits the 1.5 s still budget with ~0.76 s headroom on this host.

## Size and memory bounds

| Input | Time | Peak RSS |
|---|---:|---:|
| 4000×4000 blank, all fail | 56 ms | 34 MB |
| 4000×4000 blank, sensor 90°, all fail | 69 ms | 50 MB |
| 2000×2000 noise (4 MP), all fail | 800 ms | 32 MB |
| 1000×1000 noise, all fail | 348 ms | 14 MB |
| 800×600 noise, all fail | 369 ms | 9 MB |
| 550×550 noise, all fail | 299 ms | 6 MB |

The frame-level heavy stack is capped at a **4 MP source**. Larger stills
(including the 16 MP app maximum) use the normal orientation sweep plus the
in-decoder 1D, skew, grid and 2D retries; they do not allocate the
full-frame sharpening or 2x/3x planes. This removes the previous ~565 MB /
2.6 s all-failure spike.

Derived work is bounded separately: the 2x/3x ensemble runs only for sources
≤400 px, derived angle fallback only for derived planes ≤1 MP, the angle
fallback's explicit 2× plane is capped at 4 MP, and sharpened decodes disable
their own far-field upscale. A 550×550 all-failure frame dropped from
~1.5 s / 98 MB to ~0.30 s / 6 MB.

## Where the time goes

| Configuration | `blur25` mean | `contrast25` mean | Negative/frame | Augmented positives |
|---|---:|---:|---:|---:|
| Angle fallback only (historical) | 23.9 ms | 17.5 ms | 455 ms | 2,097 |
| Full stack, pre-safety (historical) | 22.8 ms | 15.3 ms | 865 ms | 2,319 |
| Full stack, final | 28.1 ms | 25.8 ms | 415 ms | **2,288** |

The final stack spends more time per difficult positive because fallback
reads are reverse-confirmed and weak single-view candidates are rejected.
That safety pass costs 31 positives (2,319 → 2,288) but removes the
reproducible wrong-read paths and cuts the worst all-failure frame from
1,323 ms to 742 ms.

## Conclusions

- Accuracy gains are concentrated in three retries: rotation for
  tilt/perspective, sharpening for blur/contrast/main, and 2x/3x agreement
  for small modules. They are not interchangeable.
- The ensemble is gated to ≤400 px sources and the angle fallback to ≤1 MP
  derived planes. Running the old unbounded stack made a 550×550 all-failure
  frame cost 1.5 s and 98 MB.
- Derived-frame decodes disable their own far-field upscale; the scale legs
  honor the caller's `allowUpscale`. A single derived-scale read needs
  score ≥0.70.
- The current host all-failure cost is **415 ms mean / 601 ms p95 / 742 ms
  max**, within the 1.5 s still budget; device thermal/slower-CPU behavior
  is still unmeasured.
- Full-resolution stills are deliberately outside the heavy fallback stack;
  if full-photo small-tag recall becomes a target, add an ROI/evidence-gated
  path rather than lifting the 4 MP cap.
