// Native frame preparation and orientation-aware MSI decode.
//
// Every pixel operation of the scan pipeline lives here (no Kotlin pixel
// loops): one pass crops the aim region out of a camera luma plane (or a
// still image converted to gray once) and contrast-stretches it into a
// contiguous buffer that every engine then reads; decodeFrame() runs the MSI
// decoder on that buffer, trying orientations natively instead of
// materializing rotated bitmaps in Kotlin.
#pragma once

#include <cstdint>

#include "msi_decoder.h"

namespace msi {

// Read-only 8-bit luminance plane (0=black, 255=white), row-major.
// rowStride >= width (camera planes pad rows).
struct LumaView {
    const uint8_t* data = nullptr;
    int width = 0;
    int height = 0;
    int rowStride = 0;
};

// Half-open pixel rectangle [left, right) x [top, bottom) in view coordinates.
struct Region {
    int left = 0;
    int top = 0;
    int right = 0;
    int bottom = 0;
    int width() const { return right - left; }
    int height() const { return bottom - top; }
};

// Clamps `region` to the view. False when the view is invalid or the clamped
// region is empty.
bool clampRegion(const LumaView& src, Region& region);

// Writes `region` (already clamped) of `src` into `out` in ONE pass: crop,
// optional contrast stretch and rotation upright happen together, so the
// camera plane is read once and written once per frame. `out` is contiguous
// (stride = output width) and must hold width*height bytes; for
// rotationDegrees 90/270 the output is region.height() wide and
// region.width() tall. `rotationDegrees` is the clockwise rotation that makes
// the source upright (ImageInfo.rotationDegrees). When `stretch`, applies the
// SDK's 2%/98% percentile contrast stretch (skipped for flat crops whose
// clipped range is under 12 levels); semantics match the retired Kotlin
// ContrastNormalizationTransform exactly. False on invalid input.
bool prepareRegion(const LumaView& src, const Region& region, int rotationDegrees, bool stretch,
                   uint8_t* out);

struct FrameResult {
    DecodeResult decode;
    // Clockwise rotation, relative to the upright view, under which the read
    // succeeded: 0 upright, 180 upside-down, 90/270 sideways. -1 on failure.
    int relativeRotation = -1;
};

struct FrameOptions {
    ChecksumPolicy required = ChecksumPolicy::NONE;
    // Clockwise degrees (0/90/180/270) that make the buffer upright — the
    // camera's sensor rotation (ImageInfo.rotationDegrees).
    int rotationDegrees = 0;
    // Orientations to try (1..4, clamped) in the SDK priority order relative
    // to upright: 0, 180, 90, 270. Live aim-box frames use 1: in the
    // simulated aim-box regression every 180-degree read of an upright tag
    // whose forward read failed was a false MOD 10 pass (reversed bars decode
    // to garbage), and sideways tags cannot fit a wide box. Stills use 4
    // (gallery photos arrive in any orientation).
    int maxOrientations = 1;
    // See DecodeOptions::scanTopFraction: 0 for aim-box crops, 0.25 for
    // full frames.
    float scanTopFraction = 0.0f;
};

// Orientation-aware decode of a contiguous gray buffer (stride = width).
// Tries up to options.maxOrientations rotations relative to upright, in the
// order 0, 180, 90, 270. Each attempt is one msi::decode() on a rotated view
// (the upright attempt on an upright buffer reads `gray` in place; others
// rotate into one per-call scratch buffer). First success wins. Does not
// retain `gray`. Thread-safe.
FrameResult decodeFrame(const uint8_t* gray, int width, int height, const FrameOptions& options);

} // namespace msi
