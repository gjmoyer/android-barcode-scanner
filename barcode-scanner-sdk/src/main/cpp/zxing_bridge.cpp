#include <jni.h>
#include <string>
#include "scan_image.h"

extern "C" {

JNIEXPORT jint JNICALL
Java_com_barcodescanner_sdk_data_zxingcpp_ZXingCppBridge_nativePing(JNIEnv*, jobject) {
    return 42;
}

// pixels: ARGB int[] from Bitmap.getPixels, row-major.
// JNI signature verified against ZXingCppBridge.kt:
//   (IntArray, Int, Int, Boolean x3, String): String
//   <-> (jintArray, jint, jint, jboolean x3, jstring). Instance (jobject) is
//   correct because the Kotlin declaration lives in an `object`.
JNIEXPORT jstring JNICALL
Java_com_barcodescanner_sdk_data_zxingcpp_ZXingCppBridge_nativeDecode(
    JNIEnv* env, jobject,
    jintArray pixels, jint width, jint height,
    jboolean tryRotate, jboolean tryInvert, jboolean tryHarder,
    jstring enabledFormats) {
    if (!pixels || width <= 0 || height <= 0) return env->NewStringUTF("[]");

    jsize len = env->GetArrayLength(pixels);
    // Never trust the caller's dimensions: validate against the array length
    // before the native loop reads width*height ints (heap OOB otherwise).
    // 64-bit multiply: width*height as jint would overflow for bogus dims.
    {
        int64_t need = static_cast<int64_t>(width) * static_cast<int64_t>(height);
        if (need <= 0 || need > 16'000'000 || static_cast<int64_t>(len) < need)
            return env->NewStringUTF("[]");
    }

    jint* data = env->GetIntArrayElements(pixels, nullptr);
    if (!data) return env->NewStringUTF("[]");

    std::string formatsStr;
    if (enabledFormats) {
        const char* c = env->GetStringUTFChars(enabledFormats, nullptr);
        if (c) {
            formatsStr = c;
            env->ReleaseStringUTFChars(enabledFormats, c);
        }
    }

    ScanOptions opts;
    opts.tryRotate = tryRotate != JNI_FALSE;
    opts.tryInvert = tryInvert != JNI_FALSE;
    opts.tryHarder = tryHarder != JNI_FALSE;
    opts.enabledFormats = formatsStr;

    std::string json = DecodeImage(
        reinterpret_cast<const int32_t*>(data),
        static_cast<int>(width), static_cast<int>(height), opts);

    env->ReleaseIntArrayElements(pixels, data, JNI_ABORT);
    return env->NewStringUTF(json.c_str());
}

} // extern "C"
