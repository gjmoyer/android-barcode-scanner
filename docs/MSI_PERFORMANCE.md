# MSI performance — full fallback stack

Host measurements, macOS M2 Max, `validation/tools/msi_baseline`; 122
crops per set. Times are `decodeFrame` only (PNG I/O excluded). Device
timings will differ.

## Fast path

The native set has only successful reads, so it measures the normal path:

| Native 122 | Mean | p95 | Max |
|---|---:|---:|---:|
| Before fallbacks | 5.83 ms | 10.96 ms | 21.5 ms |
| After fallbacks | 6.01 ms | 12.61 ms | 21.2 ms |

The normal path is effectively unchanged. The fallback code runs only when
the plain/stretch/orientation passes return no read, and live frames do not
run these retries (`stretchRetry=false`).

## Full suite timing

| Condition | Before mean | After mean | Before p95 | After p95 | Fail-only mean |
|---|---:|---:|---:|---:|---:|
| Native | 5.83 | 6.01 | 10.96 | 12.61 | — |
| Main blur+skew | 5.18 | 21.54 | 11.41 | 128.27 | 1,314 ms |
| `skew25` | 5.28 | 9.83 | 10.89 | 23.39 | — |
| `persp08` | 6.22 | 23.00 | 13.31 | 35.74 | 2,050 ms |
| `blur25` | 5.72 | 22.78 | 12.09 | 118.17 | 791 ms |
| `contrast25` | 5.95 | 15.29 | 11.15 | 72.13 | 608 ms |
| `dark20` | 5.75 | 11.58 | 11.39 | 53.37 | 423 ms |
| `clutter` | 15.81 | 44.93 | 35.39 | 129.09 | 1,007 ms |
| `scale075` | 4.73 | 8.52 | 9.28 | 15.52 | 1,836 ms |
| `scale050` | 2.83 | 42.20 | 5.56 | 127.86 | 614 ms |
| `scale035` | 2.33 | 115.64 | 4.43 | 467.36 | 462 ms |
| `negative` (all fail) | — | — | — | — | **865 ms/frame** |

Total decode time over the 23-set suite: **27.1 s before, 358.3 s after**.
The old ordering (ensemble before sharpening) was 688.6 s; moving sharpening
first and capping the ensemble at 1000 px removed most of that cost, at a
4-read accuracy difference (2,319 vs 2,323 positives).

## Where the time goes

| Configuration | blur41 mean | contrast51 mean | negative/frame | Augmented positives |
|---|---:|---:|---:|---:|
| Angle fallback only | 23.9 ms | 17.5 ms | 455 ms | 2,097 |
| + sharpened retry (selected sets: 110/103 reads) | 36.2 ms | 37.1 ms | 866 ms | not full-suite |
| + 2×/3× ensemble | 182.6 ms | 142.4 ms | 1,326 ms | 2,323 |
| Full, old order | 192.5 ms | 163.0 ms | 1,733 ms | 2,323 |
| Full, reordered/gated | 22.8 ms | 15.3 ms | 865 ms | **2,319** |

In the final ordered pipeline, sharpening handles blur/contrast/main and the
ensemble is reserved for small frames.

## Conclusions

- Accuracy gains are concentrated in three different retries: rotation for
  tilt/perspective, sharpening for blur/contrast/main, and 2×/3× agreement
  for small modules. They are not interchangeable.
- The ensemble is the dominant cost and should stay gated to small frames.
  Running it on every failed still made a negative frame cost 1.7 s.
- The current reordered stack measures **865 ms mean / 1,122 ms p95 /
  1,323 ms max** per all-failure negative frame on the host, so it fits the
  local 1.5 s still budget but only with limited headroom. Device timing and
  thermal behavior still need measuring.
- A next safe optimization is an evidence gate (`sawCandidate`/ROI count)
  before the heavy still fallbacks, so text-only frames skip them entirely.
