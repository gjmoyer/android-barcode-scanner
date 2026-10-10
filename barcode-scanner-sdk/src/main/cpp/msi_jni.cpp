// JNI bridge for the clean-room MSI Plessey decoder.
// Called from com.barcodescanner.sdk.data.msi.MsiNativeDecoder.
#include <jni.h>
#include <string>
#include "msi_decoder.h"

extern "C" {

// Returns "digits|policy|votes" on success, null on failure.
// gray: grayscale bytes (0=black, 255=white), row-major, width*height bytes.
JNIEXPORT jstring JNICALL
Java_com_barcodescanner_sdk_data_msi_MsiNativeDecoder_nativeDecode(
    JNIEnv* env, jobject /*thiz*/,
    jbyteArray grayBytes, jint width, jint height) {

    jsize len = env->GetArrayLength(grayBytes);
    if (len < width * height || width <= 0 || height <= 0) {
        return nullptr;
    }
    jbyte* bytes = env->GetByteArrayElements(grayBytes, nullptr);
    if (!bytes) {
        return nullptr;
    }

    msi::DecodeResult r = msi::decode(
        reinterpret_cast<const uint8_t*>(bytes),
        static_cast<int>(width),
        static_cast<int>(height));

    env->ReleaseByteArrayElements(grayBytes, bytes, JNI_ABORT);

    if (!r.ok) {
        return nullptr;
    }
    std::string out = r.digits + "|" + msi::policyName(r.policy)
                    + "|" + std::to_string(r.votes);
    return env->NewStringUTF(out.c_str());
}

} // extern "C"
