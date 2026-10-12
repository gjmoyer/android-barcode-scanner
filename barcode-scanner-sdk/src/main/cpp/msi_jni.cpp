// JNI bridge for the clean-room MSI Plessey decoder and the native frame
// path (msi_frame). Called from com.barcodescanner.sdk.data.msi.MsiNativeDecoder
// and com.barcodescanner.sdk.data.frame.NativeFrame.
#include <jni.h>
#include <cstdint>
#include <string>
#include "msi_decoder.h"
#include "msi_frame.h"

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

// Base address of a direct ByteBuffer holding at least `need` bytes, else
// null (heap buffer, null reference, or too small). 64-bit math throughout:
// bogus Kotlin dimensions must never pass as a small product.
uint8_t* directBytes(JNIEnv* env, jobject buffer, int64_t need) {
    if (buffer == nullptr || need <= 0) return nullptr;
    void* address = env->GetDirectBufferAddress(buffer);
    const jlong capacity = env->GetDirectBufferCapacity(buffer);
    if (address == nullptr || capacity < 0 || static_cast<int64_t>(capacity) < need) {
        return nullptr;
    }
    return static_cast<uint8_t*>(address);
}

} // namespace

extern "C" {

// Returns "digits|policy|votes" on success, null on failure.
// gray: grayscale bytes (0=black, 255=white), row-major, width*height bytes.
// requiredPolicy: see toRequiredPolicy.
JNIEXPORT jstring JNICALL
Java_com_barcodescanner_sdk_data_msi_MsiNativeDecoder_nativeDecode(
    JNIEnv* env, jobject /*thiz*/,
    jbyteArray grayBytes, jint width, jint height, jint requiredPolicy) {

    // Defensive validation in 64-bit math: width*height as jint can
    // overflow for bogus dimensions and pass the length check, causing an
    // OOB read in msi::decode. Null arrays (GetArrayLength(NULL) crashes
    // the VM) are rejected too. Kotlin never sends these; this is the last
    // line of defense at the native boundary.
    if (grayBytes == nullptr || width <= 0 || height <= 0) {
        return nullptr;
    }
    jsize len = env->GetArrayLength(grayBytes);
    const int64_t need = static_cast<int64_t>(width) * static_cast<int64_t>(height);
    if (static_cast<int64_t>(len) < need) {
        return nullptr;
    }
    jbyte* bytes = env->GetByteArrayElements(grayBytes, nullptr);
    if (!bytes) {
        return nullptr;
    }

    msi::DecodeResult r = msi::decode(
        reinterpret_cast<const uint8_t*>(bytes),
        static_cast<int>(width),
        static_cast<int>(height),
        toRequiredPolicy(requiredPolicy));

    env->ReleaseByteArrayElements(grayBytes, bytes, JNI_ABORT);

    if (!r.ok) {
        return nullptr;
    }
    std::string out = r.digits + "|" + msi::policyName(r.policy)
                    + "|" + std::to_string(r.votes);
    return env->NewStringUTF(out.c_str());
}

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
    const uint8_t* plane = directBytes(env, src, srcNeed);
    if (plane == nullptr) return JNI_FALSE;
    const msi::LumaView view{plane, srcWidth, srcHeight, srcRowStride};
    const msi::Region region{left, top, right, bottom};
    if (region.width() <= 0 || region.height() <= 0) return JNI_FALSE;
    const int64_t dstNeed =
        static_cast<int64_t>(region.width()) * static_cast<int64_t>(region.height());
    uint8_t* out = directBytes(env, dst, dstNeed);
    if (out == nullptr) return JNI_FALSE;
    return msi::prepareRegion(view, region, rotationDegrees, stretch == JNI_TRUE, out)
               ? JNI_TRUE
               : JNI_FALSE;
}

// MSI decode of a prepared upright luma buffer (direct, stride = width), read
// in place. Returns "digits|policy|votes|relativeRotation" on success, null
// on failure. maxOrientations / scanTopFraction: see msi::FrameOptions.
JNIEXPORT jstring JNICALL
Java_com_barcodescanner_sdk_data_msi_MsiNativeDecoder_nativeDecodeLuma(
    JNIEnv* env, jobject /*thiz*/,
    jobject gray, jint width, jint height, jint maxOrientations,
    jfloat scanTopFraction, jint requiredPolicy) {

    if (width <= 0 || height <= 0) return nullptr;
    const uint8_t* px =
        directBytes(env, gray, static_cast<int64_t>(width) * static_cast<int64_t>(height));
    if (px == nullptr) return nullptr;
    msi::FrameOptions options;
    options.required = toRequiredPolicy(requiredPolicy);
    options.rotationDegrees = 0;  // prepared buffers are already upright
    options.maxOrientations = maxOrientations;
    options.scanTopFraction = scanTopFraction;
    const msi::FrameResult r = msi::decodeFrame(px, width, height, options);
    if (!r.decode.ok) return nullptr;
    const std::string out = r.decode.digits + "|" + msi::policyName(r.decode.policy) + "|" +
                            std::to_string(r.decode.votes) + "|" +
                            std::to_string(r.relativeRotation);
    return env->NewStringUTF(out.c_str());
}

} // extern "C"
