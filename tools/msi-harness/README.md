# MSI decoder host harness

Accuracy + speed tests for the clean-room C++ MSI Plessey decoder
(`barcode-scanner-sdk/src/main/cpp/msi_decoder.cpp`), runnable on any dev
machine — no device, no emulator, no Android SDK.

The decoder is pure C++17 (STL only), so it compiles for the host as-is.

## Build & run

```sh
cd tools/msi-harness
./build.sh        # builds ./msi_harness against shipped sources
./msi_harness [path/to/msi-shelf]
```

Default shelf dir is
`../../barcode-scanner-sdk/src/test/resources/msi-shelf` (committed fixtures).
Exit code = number of asserted failures (0 = green).

## What it runs

1. **Synthetic vectors** rendered from the *spec* (bit→`110`/`100`,
   START `110`, STOP `1001`) — deliberately not from the decoder's own
   digit tables, so a table bug fails loudly. Covers all four checksum
   policies (incl. literature vectors `1234567→4`, `8052→3`), a scale ladder
   (4px → 12px close-up, the `tmplLen` clamp regression), blur/contrast/
   brightness ladder, off-center bands, and negatives (blank/black/noise must
   be honest NotFound, never crash).
2. **Shelf-photo sweeps**: each of the 6 committed shelf photos decoded
   across scales {1.0, 0.75, 0.5} × photometric variants × 3 repeats.
   Pristine + mild variants are ASSERTED; harsh scale+photometric combos are
   reported as recall signal (not gates).
3. **Bench**: 20 timed iterations on a 1280×720 synthetic + the largest
   photo; fails over a 500 ms mean (devices do this in single-digit ms).

## A/B behavior checks

The template cache + `tmplLen` clamp must not change decode outputs. To
verify: copy pristine sources to /tmp, build both, diff:

```sh
git show HEAD:barcode-scanner-sdk/src/main/cpp/msi_decoder.cpp > /tmp/msi-base/msi_decoder.cpp
cp barcode-scanner-sdk/src/main/cpp/msi_decoder.h /tmp/msi-base/
OUT=msi_harness_base ./build.sh /tmp/msi-base
./msi_harness_base > /tmp/base.txt; ./msi_harness > /tmp/fixed.txt
diff <(grep -E '^(PASS|FAIL)' /tmp/base.txt) <(grep -E '^(PASS|FAIL)' /tmp/fixed.txt)
```

Only `BENCH` timings (and the fixed close-up case) should differ.
