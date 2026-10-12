// Native frame preparation and orientation-aware MSI decode (see header).
#include "msi_frame.h"

#include <algorithm>
#include <cstring>
#include <vector>

namespace msi {
namespace {

// Contrast-stretch tunables: must stay identical to the retired Kotlin
// ContrastNormalizationTransform (lowPercentile/highPercentile/MIN_RANGE) —
// the real-photo regression and every on-device tuning were done on frames
// normalized exactly this way.
constexpr float kLowPercentile = 0.02f;
constexpr float kHighPercentile = 0.98f;
constexpr int kMinStretchRange = 12;

// Tile edge for the cache-blocked 90/270 transposes (64x64 bytes = 4 KiB).
constexpr int kTile = 64;

// Writes `src` (w x h, rows `srcStride` apart) rotated `deg` clockwise into
// contiguous `dst`, mapping every pixel through `lut` when non-null.
// 90/270 swap the output dimensions to h x w.
void rotateInto(const uint8_t* src, int srcStride, int w, int h, int deg,
                const uint8_t* lut, uint8_t* dst) {
    const size_t stride = static_cast<size_t>(srcStride);
    if (deg == 0 || deg == 180) {
        const size_t n = static_cast<size_t>(w) * static_cast<size_t>(h);
        for (int y = 0; y < h; ++y) {
            const uint8_t* row = src + static_cast<size_t>(y) * stride;
            if (deg == 0) {
                uint8_t* out = dst + static_cast<size_t>(y) * w;
                if (lut == nullptr) {
                    std::memcpy(out, row, static_cast<size_t>(w));
                } else {
                    for (int x = 0; x < w; ++x) out[x] = lut[row[x]];
                }
            } else {
                uint8_t* out = dst + n - 1 - static_cast<size_t>(y) * w;
                for (int x = 0; x < w; ++x) out[-x] = lut != nullptr ? lut[row[x]] : row[x];
            }
        }
        return;
    }
    // 90 CW: dst(x'=h-1-y, y'=x); 270 CW: dst(x'=y, y'=w-1-x); dst width = h.
    for (int ty = 0; ty < h; ty += kTile) {
        const int yEnd = std::min(ty + kTile, h);
        for (int tx = 0; tx < w; tx += kTile) {
            const int xEnd = std::min(tx + kTile, w);
            for (int y = ty; y < yEnd; ++y) {
                const uint8_t* row = src + static_cast<size_t>(y) * stride;
                for (int x = tx; x < xEnd; ++x) {
                    const size_t d = deg == 90
                        ? static_cast<size_t>(x) * h + (h - 1 - y)
                        : static_cast<size_t>(w - 1 - x) * h + y;
                    dst[d] = lut != nullptr ? lut[row[x]] : row[x];
                }
            }
        }
    }
}

int normalizeRotation(int degrees) {
    const int r = ((degrees % 360) + 360) % 360;
    return r % 90 == 0 ? r : -1;
}

} // namespace

bool clampRegion(const LumaView& src, Region& region) {
    if (src.data == nullptr || src.width <= 0 || src.height <= 0 ||
        src.rowStride < src.width) {
        return false;
    }
    region.left = std::max(region.left, 0);
    region.top = std::max(region.top, 0);
    region.right = std::min(region.right, src.width);
    region.bottom = std::min(region.bottom, src.height);
    return region.right > region.left && region.bottom > region.top;
}

bool prepareRegion(const LumaView& src, const Region& region, int rotationDegrees, bool stretch,
                   uint8_t* out) {
    const int deg = normalizeRotation(rotationDegrees);
    if (out == nullptr || deg < 0) return false;
    Region r = region;
    if (!clampRegion(src, r) || r.left != region.left || r.top != region.top ||
        r.right != region.right || r.bottom != region.bottom) {
        return false;  // callers clamp first; a mismatch means a caller bug
    }
    const int w = r.width();
    const int h = r.height();
    const uint8_t* origin =
        src.data + static_cast<size_t>(r.top) * static_cast<size_t>(src.rowStride) + r.left;
    // Pass 1 (read-only, stretch only): histogram of the region.
    uint8_t lut[256];
    bool useLut = false;
    if (stretch) {
        uint32_t hist[256] = {0};
        for (int y = 0; y < h; ++y) {
            const uint8_t* row = origin + static_cast<size_t>(y) * static_cast<size_t>(src.rowStride);
            for (int x = 0; x < w; ++x) ++hist[row[x]];
        }
        // Percentile bounds, float accumulation exactly as the Kotlin
        // transform: low = last bin with cumulative share < 2%, high = last
        // bin <= 98%.
        const float n = static_cast<float>(static_cast<size_t>(w) * static_cast<size_t>(h));
        float acc = 0.0f;
        int low = 0;
        int high = 255;
        for (int i = 0; i < 256; ++i) {
            acc += static_cast<float>(hist[i]) / n;
            if (acc < kLowPercentile) low = i;
            if (acc <= kHighPercentile) high = i;
        }
        if (high - low >= kMinStretchRange) {  // flat crops are left as-is
            const float scale = 255.0f / static_cast<float>(high - low);
            for (int g = 0; g < 256; ++g) {
                const int v = static_cast<int>(static_cast<float>(g - low) * scale + 0.5f);
                lut[g] = static_cast<uint8_t>(std::min(std::max(v, 0), 255));
            }
            useLut = true;
        }
    }
    // Pass 2: the only write — crop, stretch and rotate upright in one sweep.
    rotateInto(origin, src.rowStride, w, h, deg, useLut ? lut : nullptr, out);
    return true;
}

FrameResult decodeFrame(const uint8_t* gray, int width, int height, const FrameOptions& options) {
    FrameResult result;
    if (gray == nullptr || width <= 0 || height <= 0) return result;
    const int sensor = normalizeRotation(options.rotationDegrees);
    if (sensor < 0) return result;

    // Same priority as the Kotlin OrientationCandidates: upright, upside-down,
    // then sideways.
    static constexpr int kOrder[4] = {0, 180, 90, 270};
    const int attempts = std::min(std::max(options.maxOrientations, 1), 4);

    DecodeOptions decodeOptions;
    decodeOptions.required = options.required;
    decodeOptions.scanTopFraction = options.scanTopFraction;

    // One scratch buffer per call (not thread-local): full-resolution stills
    // would otherwise pin ~12 MB per decode thread for the process lifetime.
    std::vector<uint8_t> scratch;
    for (int k = 0; k < attempts; ++k) {
        const int deg = (sensor + kOrder[k]) % 360;
        const uint8_t* view = gray;
        int vw = width;
        int vh = height;
        if (deg != 0) {
            scratch.resize(static_cast<size_t>(width) * static_cast<size_t>(height));
            rotateInto(gray, width, width, height, deg, nullptr, scratch.data());
            view = scratch.data();
            if (deg != 180) std::swap(vw, vh);
        }
        DecodeResult r = decode(view, vw, vh, decodeOptions);
        if (r.ok) {
            result.decode = std::move(r);
            result.relativeRotation = kOrder[k];
            return result;
        }
    }
    return result;
}

} // namespace msi
