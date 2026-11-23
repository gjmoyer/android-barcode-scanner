# MSI Scanner Sample (iOS)

A minimal SwiftUI/AVFoundation demo that scans **MSI Plessey only**. It does not
use ML Kit, zxing-cpp, Vision, or the Android fusion layer.

The native decoder is **not copied**. The Xcode target compiles the canonical
portable C++ sources directly from the Android SDK module:

```
../barcode-scanner-sdk/src/main/cpp/msi_decoder.cpp
../barcode-scanner-sdk/src/main/cpp/msi_frame.cpp
../barcode-scanner-sdk/src/main/cpp/msi_angle_model.h   (included by the decoder)
```

Android-only files (`msi_jni.cpp`, `msi_bitmap_jni.cpp`, the Kotlin layer) are
not built into the iOS target.

## Requirements

- Xcode with the iOS 26 SDK or newer
- iOS 26+ on the device (deployment target is 26.0)
- A physical iPhone; the simulator has no camera
- [XcodeGen](https://github.com/yonaskolb/XcodeGen) only if you change `project.yml`

## Build

```sh
cd ios-sample
xcodegen generate
open MsiScannerSample.xcodeproj
```

Select your iPhone and run. The project uses automatic signing with the
`RQQ8P4P75E` team and bundle id `com.barcodescanner.msiscanner`; change
`DEVELOPMENT_TEAM`/`PRODUCT_BUNDLE_IDENTIFIER` in `project.yml` for your team.

Command-line build:

```sh
xcodebuild \
  -project MsiScannerSample.xcodeproj \
  -scheme MsiScannerSample \
  -destination 'id=<your-device-udid>' \
  build
```

## What it demonstrates

- One-shot scanning: the camera session and decoder are off while idle; the
  large Scan button arms a scan, the first valid read shows the highlight for
  about a second, then the camera stops again
- `AVCaptureVideoDataOutput` Y-plane access with row-stride handling
- One native pass (`msi::prepareRegion`) to compact rows and contrast-stretch
- `msi::decodeFrame` with policy, digit bounds, orientation and live-only options
- The decoded bounding box mapped into the preview layer for a green highlight
- All four checksum policies plus `NONE` (debugging only)
- The same checksum re-validation, check-digit stripping and degenerate-payload
  guard as the Android SDK's `MsiNativeDecoder`

The Android SDK already pushes the policy and digit bounds into native candidate
selection; this sample keeps that contract and then re-validates the reported
codeword in Swift (`MsiChecksum.swift`).

## Camera/pipeline choices

- The app is portrait-only. The camera data output stays in sensor orientation;
  the C++ decoder receives `rotationDegrees = 90` and maps its result box back
  into the sensor buffer. Only the preview layer is rotated for display.
- The sample decodes the full 720p frame (`scanTopFraction = 0`) so the bridge
  stays small. Android's live path crops to an aim box first; add that later if
  the sample needs the same framing.
- `maxOrientations = 2` mirrors the Android live path (upright and
  upside-down). Still-only fallbacks and the angle fallback are disabled.
- Identical payloads are suppressed for 500 ms to keep the UI from flickering.

## Tests

`MsiScannerSampleTests` covers the Swift checksum port with the same vectors as
`MsiChecksumValidatorTest.kt`. The native C++ decoder remains covered by
`tools/msi-harness` on macOS.

Verified on an iPhone 14 (iOS 27.2): the app installs, launches, reads a
9-digit Mod1010 tag, and reports the stripped `01157833` payload under Mod10.

## Later cleanup

When the iOS sample grows, move the portable files to a shared `native/msi/`
directory and update both this project and the Android CMake file. That removes
the Android-specific path from the iOS project without duplicating the decoder.
