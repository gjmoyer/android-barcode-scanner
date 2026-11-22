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
| After fallbacks | 5.92 ms | 11.54 ms | 20.7 ms |

The normal path is effectively unchanged. The fallback code runs only when
the plain/stretch/orientation passes return no read, and live frames do not
run these retries (`stretchRetry=false`).

## Full suite timing

| Condition | Mean | p95 | Max | Fail-only mean | Failures |
|---|---:|---:|---:|---:|---:|
| Native | 5.92 | 11.54 | 20.74 | — | 0 |
| Main blur+skew (610) | 22.50 | 115.97 | 314.41 | 159.0 | 20 |
| `skew25` (244) | 14.03 | 32.42 | 242.57 | 242.6 | 1 |
| `persp08` (244) | 26.65 | 98.95 | 740.71 | 425.4 | 6 |
| `blur25` (244) | 28.30 | 135.29 | 416.79 | 125.5 | 24 |
| `contrast25` (244) | 25.91 | 116.00 | 276.80 | 102.3 | 33 |
| `dark20` (244) | 13.33 | 55.73 | 154.38 | 85.3 | 9 |
| `clutter` (244) | 47.37 | 288.04 | 756.40 | 389.4 | 14 |
| `scale075` (122) | 18.04 | 35.19 | 624.52 | 624.5 | 1 |
| `scale050` (122) | 23.71 | 109.04 | 402.88 | 321.4 | 3 |
| `scale035` (122) | 119.67 | 378.03 | 813.16 | 211.9 | 43 |
| `negative` (244, all fail) | **411.93** | **595.54** | **743.01** | **411.9** | 244 |

Total decode time over the 23-set suite: **27.1 s baseline, 172.4 s final**
(the pre-safety fallback stack was 358.3 s). The all-failure negative frame
now fits the 1.5 s still budget with ~0.76 s headroom on this host.

## Size and memory bounds

| Input | Time | Peak RSS |
|---|---:|---:|
| 4000×4000 blank, all fail | 59 ms | 34 MB |
| 4000×4000 blank, sensor 90°, all fail | 59 ms | 34 MB |
| 1870×1870 noise (3.5 MP angle path), all fail | 740 ms | 68 MB |
| 1871×1871 noise (just over 3.5 MP, no angle), all fail | 150 ms | 31 MB |
| 2048×1536 noise (3.1 MP), all fail | 687 ms | 56 MB |
| 1920×1080 noise (2.1 MP), all fail | 554 ms | 46 MB |
| 2000×2000 noise (4 MP, sharpen only), all fail | 156 ms | 35 MB |
| 1000×1000 noise, all fail | 352 ms | 21 MB |
| 800×600 noise, all fail | 365 ms | 11 MB |
| 550×550 noise, all fail | 299 ms | 7 MB |
| 400×400 noise (ensemble), all fail | 640 ms | 22 MB |

The sharpened stage is capped at a **4 MP source**; the angle path is capped
at **3.5 MP**. Peak memory is highest just *under* the angle cap (a 3.5 MP
angle frame is ~68 MB); a frame just over the cap skips the angle path and
drops to ~31 MB. Larger stills (including the 16 MP app maximum) use the
normal orientation sweep plus the in-decoder 1D, skew, grid and 2D retries;
they do not allocate the full-frame angle/sharpening/2x/3x planes. This
removes the previous ~565 MB / 2.6 s all-failure spike.

Derived work is bounded separately: the 2x/3x ensemble runs only for sources
≤400 px, derived angle fallback only for derived planes ≤1 MP, and the angle
fallback's explicit 2× plane is capped at 4 MP. Sharpened frame decodes
disable their own far-field upscale; the sharpened angle pass and scale legs
honor the caller's `allowUpscale`. A 550×550 all-failure frame dropped from
~1.5 s / 98 MB to ~0.30 s / 7 MB; the worst 400 px ensemble case is
~0.64 s / 22 MB.

## Where the time goes

| Configuration | `blur25` mean | `contrast25` mean | Negative/frame | Augmented positives |
|---|---:|---:|---:|---:|
| Angle fallback only (historical) | 23.9 ms | 17.5 ms | 455 ms | 2,097 |
| Full stack, pre-safety (historical) | 22.8 ms | 15.3 ms | 865 ms | 2,319 |
| Full stack, final | 28.3 ms | 25.9 ms | 412 ms | **2,286** |

The final stack spends more time per difficult positive because fallback
reads are reverse-confirmed and weak single-view candidates are rejected.
That safety pass costs 33 positives (2,319 → 2,286) but removes the
reproducible wrong-read paths and cuts the worst all-failure frame from
1,323 ms to 743 ms.

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
- The current host all-failure cost is **412 ms mean / 596 ms p95 / 743 ms
  max**, within the 1.5 s still budget; device thermal/slower-CPU behavior
  is still unmeasured.
- Full-resolution stills are deliberately outside the heavy fallback stack;
  if full-photo small-tag recall becomes a target, add an ROI/evidence-gated
  path rather than lifting the 4 MP cap.
