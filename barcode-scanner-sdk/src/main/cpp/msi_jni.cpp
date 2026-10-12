// JNI bridge for the clean-room MSI Plessey decoder.
// Called from com.barcodescanner.sdk.data.msi.MsiNativeDecoder.
#include <jni.h>
#include <cstdint>
#include <string>
#include "msi_decoder.h"

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

} // extern "C"
