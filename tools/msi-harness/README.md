# MSI decoder host harness

Accuracy + speed tests for the clean-room C++ MSI Plessey decoder
(`barcode-scanner-sdk/src/main/cpp/msi_decoder.cpp`) and the native frame
path that feeds it (`msi_frame.cpp`), runnable on any dev
machine — no device, no emulator, no Android SDK.

The decoder is pure C++17 (STL only), so it compiles for the host as-is.

## Build & run

```sh
cd tools/msi-harness
./build.sh        # builds ./msi_harness against shipped sources
./msi_harness [path/to/msi-shelf]
```

Default shelf dir is
`../../barcode-scanner-sdk/src/test/resources/msi-shelf` — which in a public
checkout holds NO photos (real shelf captures stay out of the repo; see
`docs/MSI_PLESSEY_RESEARCH.md` §6). With no photos the shelf suite prints per-file
SKIPs and stays green; the committed coverage is the runtime synthetic suite
+ bench above it. Drop local `msi-<label>-<sku>.png` captures in any dir and
pass it as argv[1] to sweep those (the six-SKU ground truth + policies live
in `shelfSuite`, and `gen_shelf_synthetics.py` regenerates equivalent
full-codeword PNGs in `build/shelf-synthetics` by default).
Exit code = number of asserted failures (0 = green).

## What it runs

1. **Synthetic vectors** rendered from the *spec* (bit→`110`/`100`,
   START `110`, STOP `1001`) — deliberately not from the decoder's own
   digit tables, so a table bug fails loudly. Covers all four checksum
   policies (incl. literature vectors `1234567→4`, `8052→3`), a scale ladder
   (4px → 12px close-up, the `tmplLen` clamp regression), blur/contrast/
   brightness ladder, off-center bands, and negatives (blank/black/noise must
   be honest NotFound, never crash).
2. **Shelf-format vectors** (`shelfFormatSuite`, ASSERTED): the real
   shelf-tag format — 9-digit Mod1010 (7-digit item + two check digits, 39 bars),
   leading-zero SKUs at 5/4/3 px modules, blurred, clean and with
   glyph-height text around the symbol, decoded with no required policy and
   with `ChecksumPolicy::MOD1010` pushed down. Every prefix of these
   codewords is Mod10-valid and Luhn ignores leading zeros, so they catch
   8-digit truncations and dropped leading zeros (base 4c9be3b failed 9 of
   15). Also pins the required-policy contract (unsatisfied scheme =
   NotFound, satisfied scheme reported as required).
3. **Shelf-photo sweeps**: each local `msi-<label>-<sku>.png` shelf photo
   (absent in a clean checkout) decoded across scales {1.0, 0.75, 0.5} ×
   photometric variants × 3 repeats. Pristine + mild variants are ASSERTED;
   harsh scale+photometric combos are reported as recall signal (not gates).
4. **Native frame path** (`frameSuite`, ASSERTED): `msi_frame`'s one-pass
   crop + contrast stretch + rotate-upright is byte-identical to the Kotlin
   `ContrastNormalizationTransform` semantics (mirrored by `contrastStretch`)
   and to an explicit 90/180/270 rotation, on an offset region of a strided
   (padded-row) plane; off-plane/unclamped regions and non-right-angle
   rotations are rejected; a portrait sensor plane (`rotationDegrees=90`)
   decodes after one prepare; `decodeFrame` tries orientations in the order
   0, 180, 90, 270 up to its cap and reports the winning relative rotation;
   aim-box crops (`scanTopFraction=0`) read a tag in the top quarter the
   full-frame band search skips.
5. **Bench**: 20 timed iterations on a 1280×720 synthetic + the largest
   photo; fails over a 500 ms mean (devices do this in single-digit ms).

## A/B behavior checks

The template cache + `tmplLen` clamp must not change decode outputs. To
verify: copy pristine sources to /tmp, build both, diff:

```sh
git show HEAD:barcode-scanner-sdk/src/main/cpp/msi_decoder.cpp > /tmp/msi-base/msi_decoder.cpp
cp barcode-scanner-sdk/src/main/cpp/msi_decoder.h barcode-scanner-sdk/src/main/cpp/msi_frame.* /tmp/msi-base/
OUT=msi_harness_base ./build.sh /tmp/msi-base
./msi_harness_base > /tmp/base.txt; ./msi_harness > /tmp/fixed.txt
diff <(grep -E '^(PASS|FAIL)' /tmp/base.txt) <(grep -E '^(PASS|FAIL)' /tmp/fixed.txt)
```

Only `BENCH` timings (and the fixed close-up case) should differ.

## JNI smoke test

`jni-smoke/run.sh` builds `libmsi_decoder` for the host and drives the JNI
glue in `msi_jni.cpp` from a real JVM (Robolectric unit tests cannot load the
`.so`, and this harness calls C++ directly): direct-buffer capacity, row-stride
and unpadded-last-row handling, rejection of heap/short/out-of-plane inputs,
the `digits|policy|votes|relativeRotation` result format. The Java stubs under
`jni-smoke/src` mirror the Kotlin `external fun` signatures of `NativeFrame`
and `MsiNativeDecoder` — update both together (JNI binds by name only, so a
signature drift is silent undefined behaviour, not a link error). Needs a JDK
(`JAVA_HOME`, else Android Studio's JBR) and an Android NDK (for `jni.h`).

## Apple Vision baseline (macOS only)

`vision_bench.swift` measures `VNDetectBarcodesRequest` per image (median of
three requests, image load excluded) and prints `file<TAB>ms<TAB>payload`.
Compile and run on a local shelf crop dir:

```sh
swiftc -O vision_bench.swift -o vision_bench
./vision_bench /path/to/msi-tags/*.png > vision.tsv
```

The 116-crop comparison in `docs/BENCHMARKS.md` used the same local crops and
truth set; the images/truth are not committed. Apple Vision runs on macOS, so
its latency is not directly comparable to the Pixel-emulator rows.
