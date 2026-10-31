# Benchmarks and evaluation

This page records what has actually been measured, how it was measured, and
what the numbers do **not** cover. It exists so performance claims can be
checked instead of taken on trust.

## Summary

- **152/162 exact reads** on real grocery shelf tags at near range under the
  shelf-tag policy (`MOD_10_10`); 146/162 far, 152/162 from stills.
- **0 wrong reads** at fixed 9-digit bounds, 0 false reads on the 14
  must-not-misread negatives, and **0 false reads across 3,430 clutter
  frames**.
- **129–133/162 reads at ±6°/±10° tilt**, with 0 wrong reads.
- **127/127 host-harness assertions green**; native decode averages
  **0.70 ms** per 1280×720 synthetic frame (Apple M2 Max, Oct 2026).

## Reproducible host harness

```sh
cd tools/msi-harness
./build.sh && ./msi_harness
```

The harness has no Android or device dependency. It renders vectors from the
MSI spec (not from the decoder's own tables), covers all four checksum
policies and the 9-digit shelf format, sweeps blur/scale/rotation and
clutter negatives, and exercises the native frame path.

Latest run (2026-10-31, macOS 27.2, Apple M2 Max):

| Check | Result |
|---|---|
| Assertions | 127 pass, 0 fail |
| Bench: 1280×720 synthetic MSI | 702 µs mean, 817 µs worst (20 iterations) |
| 1280×720 text-clutter negatives | 0 false positives (30 frames) |
| Clutter sweep | 0 false reads (3,430 frames) |

Timings are machine-dependent; the assertion count and false-positive checks
are the stable gates.

## Real shelf-photo regression

Data set: 110 user-supplied HEIC photos, 176 tags, ground truth from the
printed item line plus the tag format (7-digit item + Mod10 + Mod10 =
9-digit `MOD_10_10` codeword). 162 tags are scored; the other 14 are
must-not-misread negatives (13 cut by the photo edge, one unreadable even by
a commercial scanner). Photos are private and never committed; methodology
and per-build history are in
[`MSI_PLESSEY_RESEARCH.md`](MSI_PLESSEY_RESEARCH.md) §10.

| Input mode | Exact reads | Wrong reads |
|---|---|---|
| Near aim-box crops | 152/162 | 0 |
| Far aim-box crops | 146/162 | 0 |
| Still path | 152/162 | 0 |
| Full photos, open lengths | 92/110 photos | 0 |

Tilted tags (scored crops rotated in software, `MOD_10_10`, 9-digit bounds):

| Rotation | −10° | −6° | +6° | +10° |
|---|---|---|---|---|
| Exact reads | 129/162 | 133/162 | 131/162 | 129/162 |
| Wrong reads | 0 | 0 | 0 | 0 |

For reference, the original 141-crop tilt set read 4/81/62/4 before the skew
retry.

Checksum-less `NONE` is debug-only and is not the shelf-tag policy; it has two
known far-field wrong reads on 8-digit fragments. The production policies
(`MOD_10`, `MOD_10_10`) have zero wrong reads at 9-digit bounds.

## Third-party comparison (private)

A local head-to-head against a licensed commercial barcode SDK was run on the
same 116 crops and device, under the same MSI policy. The custom decoder
matched its SKU-level recall with zero wrong reads on the strict
independently-scanned subset. The vendor's name, per-row data and exact
tables are withheld because the license terms restrict public comparative
claims; the private report is available from the project author on request.

This is a still-image regression set used during development, not a held-out
live-camera benchmark.

## Latency

| Path | Measurement |
|---|---|
| Native MSI decode, host | 0.70 ms mean / 0.82 ms worst per 1280×720 synthetic frame |
| Native MSI decode, real shelf photo | ~0.7–1 ms (host, README bench) |
| MSI still path, 116 real crops (Pixel 10a emulator, warmed) | 5.5 ms mean / 4 ms p50 / 14 ms p95 |
| 9-digit clutter frame, host | 9.3 ms mean / 31.6 ms p95 / 70.2 ms max |
| On-device full chain, one-shot | ~15–60 ms typical (Pixel 6a / emulator) |

The on-device figures are end-to-end and device-dependent; the host figures
isolate the native MSI decoder.

A faster still path is possible by skipping or cheapening the 180-degree
confirmation (about 1.7 ms mean), but it returns wrong checksum-valid values
on blurred upside-down tags (`891801110`, `900800087`). The harness now pins
those cases, so full reverse-view retries are required; the reported 5.5 ms
keeps the confirmation intact.

## Scope boundaries

- **Commercial-SDK comparison is private.** A licensed commercial barcode
  SDK was run on the same 116 crops and device, but the repository contains
  no commercial SDK dependency, license key or per-row comparison data.
  Vendor terms restrict public comparative claims, so the named report is
  kept private and is available from the project author on request.
- **The real-photo set is private.** A clean clone reproduces the synthetic
  and frame-path results, not the 110-photo table. The real-photo numbers
  come from the local regression described in the research note.
- **Not a camera benchmark.** There are no fps, thermal, battery or
  time-to-first-scan measurements here.
- **Apple Vision is a baseline, not a paid-SDK benchmark.** The research note
  compares against macOS Vision's MSI detection on stills; it is not a
  controlled live-camera comparison against a commercial barcode SDK.
