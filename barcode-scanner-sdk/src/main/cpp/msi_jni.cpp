// JNI bridge for the clean-room MSI Plessey decoder and the native frame
// path (msi_frame). Called from com.barcodescanner.sdk.data.msi.MsiNativeDecoder
// and com.barcodescanner.sdk.data.frame.NativeFrame. Bitmap conversion lives
// in msi_bitmap_jni.cpp (NDK-only header).
#include <jni.h>
#include <cstdint>
#include <new>
#include <string>
#include "msi_decoder.h"
#include "msi_frame.h"
#include "msi_jni_buffers.h"

namespace {

// Stable JNI codes for the caller-required scheme; mirrors
// MsiNativeDecoder.nativePolicyCode. Unknown codes fall back to NONE (accept
// any scheme) — the Kotlin side re-validates under its policy regardless.
msi::ChecksumPolicy toRequiredPolicy(jint code) {
    switch (code) {
        case 0: return msi::ChecksumPolicy::MOD10;
        case 1: return msi::ChecksumPolicy::MOD11;
        case 2: return msi::ChecksumPolicy::MOD1010;
        case 3: return msi::ChecksumPolicy::MOD1110;
        default: return msi::ChecksumPolicy::NONE;
    }
}

} // namespace

extern "C" {

// One pass per camera frame: crops [left,right) x [top,bottom) out of a luma
// plane (direct buffer, `srcRowStride` bytes per row), optionally contrast
// stretches it and rotates it upright by `rotationDegrees` (clockwise) into
// the caller-owned direct buffer `dst` (contiguous, stride = output width).
// The region must already lie inside the plane (callers clamp; a mismatch is
// rejected, never clamped silently). Returns false on any invalid input.
JNIEXPORT jboolean JNICALL
Java_com_barcodescanner_sdk_data_frame_NativeFrame_nativePrepare(
    JNIEnv* env, jobject /*thiz*/,
    jobject src, jint srcWidth, jint srcHeight, jint srcRowStride,
    jint left, jint top, jint right, jint bottom,
    jint rotationDegrees, jboolean stretch, jobject dst) {

    if (srcWidth <= 0 || srcHeight <= 0 || srcRowStride < srcWidth) return JNI_FALSE;
    // Camera planes may end at the last pixel instead of a full final stride.
    const int64_t srcNeed =
        static_cast<int64_t>(srcRowStride) * (srcHeight - 1) + static_cast<int64_t>(srcWidth);
    const uint8_t* plane = msi_jni::directBytes(env, src, srcNeed);
    if (plane == nullptr) return JNI_FALSE;
    const msi::LumaView view{plane, srcWidth, srcHeight, srcRowStride};
    const msi::Region region{left, top, right, bottom};
    if (region.width() <= 0 || region.height() <= 0) return JNI_FALSE;
    const int64_t dstNeed =
        static_cast<int64_t>(region.width()) * static_cast<int64_t>(region.height());
    uint8_t* out = msi_jni::directBytes(env, dst, dstNeed);
    if (out == nullptr) return JNI_FALSE;
    return msi::prepareRegion(view, region, rotationDegrees, stretch == JNI_TRUE, out)
               ? JNI_TRUE
               : JNI_FALSE;
}

// MSI decode of a prepared upright luma buffer (direct, stride = width), read
// in place. Returns "digits|policy|votes|relativeRotation" on success, null
// on failure. maxOrientations / scanTopFraction / minDigits / maxDigits: see
// msi::FrameOptions (digit bounds count check digits; 0 = open).
JNIEXPORT jstring JNICALL
Java_com_barcodescanner_sdk_data_msi_MsiNativeDecoder_nativeDecodeLuma(
    JNIEnv* env, jobject /*thiz*/,
    jobject gray, jint width, jint height, jint maxOrientations,
    jfloat scanTopFraction, jint requiredPolicy, jint minDigits, jint maxDigits) {

    if (width <= 0 || height <= 0) return nullptr;
    const uint8_t* px =
        msi_jni::directBytes(env, gray, static_cast<int64_t>(width) * static_cast<int64_t>(height));
    if (px == nullptr) return nullptr;
    msi::FrameOptions options;
    options.required = toRequiredPolicy(requiredPolicy);
    options.rotationDegrees = 0;  // prepared buffers are already upright
    options.maxOrientations = maxOrientations;
    options.scanTopFraction = scanTopFraction;
    options.minDigits = minDigits;
    options.maxDigits = maxDigits;
    // decodeFrame allocates (a full-resolution still needs a ~12 MB rotation
    // scratch); bad_alloc unwinding across JNI would abort the process, so a
    // failed allocation is reported as no read.
    std::string out;
    try {
        const msi::FrameResult r = msi::decodeFrame(px, width, height, options);
        if (!r.decode.ok) return nullptr;
        out = r.decode.digits + "|" + msi::policyName(r.decode.policy) + "|" +
              std::to_string(r.decode.votes) + "|" + std::to_string(r.relativeRotation);
    } catch (const std::bad_alloc&) {
        return nullptr;
    }
    return env->NewStringUTF(out.c_str());
}

} // extern "C"
