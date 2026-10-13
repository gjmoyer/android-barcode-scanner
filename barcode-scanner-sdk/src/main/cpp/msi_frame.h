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

// Still-image pixel layouts converted natively (the Android bitmap formats
// ANDROID_BITMAP_FORMAT_RGBA_8888 / RGB_565 / A_8), bytes as laid out in
// memory: RGBA_8888 is R,G,B,A; RGB_565 is a little-endian uint16 with red in
// the top 5 bits; A_8 is one byte read as luma (the zxing-cpp convention).
enum class PixelFormat { RGBA_8888, RGB_565, A_8 };

// Read-only still image. rowStride is in BYTES (>= width * bytes per pixel).
// `premultiplied` applies to RGBA_8888 only (android.graphics.Bitmap
// .isPremultiplied): such pixels are unpremultiplied before weighting.
struct PixelView {
    const uint8_t* data = nullptr;
    int width = 0;
    int height = 0;
    int rowStride = 0;
    PixelFormat format = PixelFormat::RGBA_8888;
    bool premultiplied = false;
};

// Gray output extent of a still dimension after an integer box-downscale by
// `scale` (>= 1), trimmed to even (the NV21 frame every engine reads):
// (dim / scale) rounded down to even. 0 for invalid input.
int stillGrayDim(int dim, int scale);

// Converts `src` to 8-bit gray, (77R + 150G + 29B) >> 8 per pixel (the SDK's
// historical luma, so MSI tuning carries over), averaging each scale x scale
// block (rounded) into `out`: contiguous, stillGrayDim(width) x
// stillGrayDim(height) bytes. The odd remainder at the right/bottom edge is
// dropped. Alpha is otherwise ignored: RGBA reads as the unpremultiplied
// colour, exactly what Bitmap.getPixels returned to the retired Kotlin path
// (fully transparent pixels read as 0). False on invalid input or allocation
// failure; never throws (it runs while JNI holds the bitmap's pixel lock).
bool grayFromPixels(const PixelView& src, int scale, uint8_t* out);

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
    // to upright: 0, 180, 90, 270. Live aim-box frames use 2 (a tag held
    // upside-down reads; sideways tags cannot fit a wide box). Stills use 4
    // (gallery photos arrive in any orientation). Reversed bars can decode
    // to checksum-valid garbage, so a read whose reverse view is within
    // maxOrientations is confirmed against it (see decodeFrame).
    int maxOrientations = 1;
    // See DecodeOptions::scanTopFraction: 0 for aim-box crops, 0.25 for
    // full frames.
    float scanTopFraction = 0.0f;
    // Symbol length bounds (check digits included; 0 = open). See
    // DecodeOptions::minDigits.
    int minDigits = 0;
    int maxDigits = 0;
    // When every orientation fails, retry them once more through the SDK's
    // 2%/98% contrast stretch (see prepareRegion), computed over the whole
    // buffer. For stills, which are never stretched up front; live aim-box
    // crops are already stretched and leave this off.
    bool stretchRetry = false;
};

// Orientation-aware decode of a contiguous gray buffer (stride = width).
// Tries up to options.maxOrientations rotations relative to upright, in the
// order 0, 180, 90, 270. Each attempt is one msi::decode() on a rotated view
// (the upright attempt on an upright buffer reads `gray` in place; others
// rotate into one per-call scratch buffer). The first read wins, except that
// a read at 0 or 90 is confirmed against its reverse view (180 or 270) when
// that view is within maxOrientations: a different read there is a reversed
// phantom of one of them, and the one with the clearer leading quiet zone
// is kept (a near tie rejects the frame). With options.stretchRetry a
// second, contrast-stretched sweep follows a miss. Does not retain `gray`.
// Thread-safe.
FrameResult decodeFrame(const uint8_t* gray, int width, int height, const FrameOptions& options);

} // namespace msi
