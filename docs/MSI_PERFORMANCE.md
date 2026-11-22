# MSI performance — full fallback stack

Host measurements, macOS M2 Max, `validation/tools/msi_baseline`; 122
crops per set. Times are `decodeFrame` only (PNG I/O excluded). Device
timings will differ.

## Fast path

The native set has only successful reads, so it measures the normal path:

| Native 122 | Mean | p95 | Max |
|---|---:|---:|---:|
| Before fallbacks | 5.83 ms | 10.96 ms | 21.5 ms |
| After fallbacks | 5.97 ms | 13.23 ms | 20.7 ms |

The normal path is effectively unchanged. The fallback code runs only when
the plain/stretch/orientation passes return no read, and live frames do not
run these retries (`stretchRetry=false`).

## Full suite timing

| Condition | Mean | p95 | Max | Fail-only mean | Failures |
|---|---:|---:|---:|---:|---:|
| Native | 5.97 | 13.23 | 20.66 | — | 0 |
| Main blur+skew (610) | 39.37 | 150.51 | 1,121.59 | 621.1 | 18 |
| `skew25` (244) | 13.22 | 30.17 | 264.08 | — | 0 |
| `persp08` (244) | 33.64 | 93.26 | 1,136.84 | 981.0 | 3 |
| `blur25` (244) | 42.46 | 294.21 | 710.51 | 269.8 | 24 |
| `contrast25` (244) | 36.35 | 140.17 | 739.04 | 182.2 | 32 |
| `dark20` (244) | 19.18 | 60.05 | 485.01 | 223.5 | 9 |
| `clutter` (244) | 59.13 | 434.05 | 866.09 | 693.0 | 8 |
| `scale075` (122) | 17.51 | 33.19 | 598.86 | 598.9 | 1 |
| `scale050` (122) | 22.71 | 112.42 | 396.67 | 317.5 | 3 |
| `scale035` (122) | 119.65 | 374.11 | 781.28 | 213.1 | 43 |
| `negative` (244, all fail) | **621.64** | **749.89** | **846.15** | **621.6** | 244 |

Total decode time over the 23-set suite: **27.1 s baseline, 245.7 s final**
(the pre-safety fallback stack was 358.3 s). The all-failure negative frame
now fits the 1.5 s still budget with ~0.65 s headroom on this host.

## Size and memory bounds

| Input | Time | Peak RSS |
|---|---:|---:|
| 4000×4000 blank, all fail | 59.5 ms | 34 MB |
| 4000×4000 text-like strokes, all fail | 154.9 ms | 34 MB |
| 1000×1000 noise, all fail | 932.9 ms | 28 MB |
| 800×600 noise, all fail | 582.2 ms | 22 MB |

The still fallback stack is capped at **4 MP**. Larger stills (including the
16 MP app maximum) use the normal orientation sweep plus the in-decoder 1D,
skew, grid and 2D retries; they do not allocate the full-frame sharpening or
2x/3x planes. The 16 MP path also avoids the previous ~565 MB / 2.6 s
all-failure spike.

The 2x/3x ensemble is limited to frames ≤1000 px. Nested angle fallback on
derived scales is limited to ≤600 px, and derived frames disable the
decoder's own far-field upscale so 2x/3x cannot compound to 4x/6x.

## Where the time goes

| Configuration | `blur41` mean | `contrast51` mean | Negative/frame | Augmented positives |
|---|---:|---:|---:|---:|
| Angle fallback only (historical) | 23.9 ms | 17.5 ms | 455 ms | 2,097 |
| Full stack, pre-safety (historical) | 22.8 ms | 15.3 ms | 865 ms | 2,319 |
| Full stack, final | 45.1 ms | 40.7 ms | 622 ms | **2,299** |

The final stack spends more time per difficult positive because each fallback
read is reverse-confirmed and the scale path rejects low-score single-scale
phantoms. That safety pass costs 20 positives (2,319 → 2,299) but removes the
reproducible wrong-read path and cuts the worst all-failure frame from
1,323 ms to 846 ms.

## Conclusions

- Accuracy gains are concentrated in three retries: rotation for
  tilt/perspective, sharpening for blur/contrast/main, and 2x/3x agreement
  for small modules. They are not interchangeable.
- The ensemble is the dominant cost and stays gated to small frames. Running
  it on every failed still made a negative frame cost 1.7 s.
- Derived-frame decodes use `allowUpscale=false` and a score/vote gate, so a
  2x/3x frame cannot silently run a second 2x stage or accept a weak
  over-upscaled phantom.
- The current host all-failure cost is **622 ms mean / 750 ms p95 / 846 ms
  max**, within the 1.5 s still budget but still worth measuring on device
  (thermal and slower CPUs are not represented here).
- Full-resolution stills are deliberately outside the heavy fallback stack;
  if full-photo small-tag recall becomes a target, add an ROI/evidence-gated
  path rather than lifting the 4 MP cap.
