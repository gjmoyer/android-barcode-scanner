#import "MsiBridge.h"

#include <new>
#include <vector>

#include "msi_decoder.h"
#include "msi_frame.h"

namespace {

msi::ChecksumPolicy ToPolicy(int32_t code) {
    switch (code) {
        case 0: return msi::ChecksumPolicy::MOD10;
        case 1: return msi::ChecksumPolicy::MOD11;
        case 2: return msi::ChecksumPolicy::MOD1010;
        case 3: return msi::ChecksumPolicy::MOD1110;
        default: return msi::ChecksumPolicy::NONE;
    }
}

}  // namespace

@implementation MsiDecodeResult
@end

@implementation MsiBridge

+ (nullable MsiDecodeResult *)decodePlane:(const uint8_t *)plane
                                    width:(int32_t)width
                                   height:(int32_t)height
                                rowStride:(int32_t)rowStride
                          rotationDegrees:(int32_t)rotationDegrees
                           requiredPolicy:(int32_t)requiredPolicy
                                minDigits:(int32_t)minDigits
                                maxDigits:(int32_t)maxDigits
                          maxOrientations:(int32_t)maxOrientations {
    if (plane == nullptr || width <= 0 || height <= 0 || rowStride < width) return nil;
    try {
        const msi::LumaView src{plane, width, height, rowStride};
        const msi::Region full{0, 0, width, height};
        std::vector<uint8_t> compact(static_cast<size_t>(width) * static_cast<size_t>(height));

        // One native pass: compact the padded camera rows and apply the same
        // 2%/98% contrast stretch the Android live path uses before decode.
        if (!msi::prepareRegion(src, full, /*rotationDegrees=*/0, /*stretch=*/true,
                                compact.data())) {
            return nil;
        }

        msi::FrameOptions options;
        options.required = ToPolicy(requiredPolicy);
        options.rotationDegrees = rotationDegrees;
        options.maxOrientations = maxOrientations;
        options.scanTopFraction = 0.0f;
        options.minDigits = minDigits;
        options.maxDigits = maxDigits;
        options.stretchRetry = false;
        options.allowUpscale = true;
        options.allowAngleFallback = false;
        options.allowStillFallbacks = false;

        const msi::FrameResult decoded =
            msi::decodeFrame(compact.data(), width, height, options);
        if (!decoded.decode.ok) return nil;

        MsiDecodeResult *result = [MsiDecodeResult new];
        result.digits = [NSString stringWithUTF8String:decoded.decode.digits.c_str()];
        result.nativePolicy =
            [NSString stringWithUTF8String:msi::policyName(decoded.decode.policy)];
        result.votes = decoded.decode.votes;
        result.relativeRotation = decoded.relativeRotation;
        result.box = CGRectMake(
            decoded.decode.boxX0,
            decoded.decode.boxY0,
            decoded.decode.boxX1 - decoded.decode.boxX0,
            decoded.decode.boxY1 - decoded.decode.boxY0);
        result.frameSize = CGSizeMake(width, height);
        return result;
    } catch (const std::bad_alloc &) {
        return nil;
    } catch (...) {
        // Never let a C++ exception unwind through an Objective-C frame.
        return nil;
    }
}

@end
