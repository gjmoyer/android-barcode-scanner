// JNI bridge for the still-image path: converts an android.graphics.Bitmap to
// the upright gray frame every engine reads, natively and once
// (com.barcodescanner.sdk.data.frame.NativeFrame.prepareStill). Kept out
// of msi_jni.cpp because <android/bitmap.h> exists only in the NDK: the host
// JNI smoke build compiles msi_jni.cpp, while the conversion core itself
// (msi::grayFromPixels) is host-tested by the harness.
#include <android/bitmap.h>
#include <jni.h>

#include <cstdint>
#include <new>
#include <vector>

#include "msi_frame.h"
#include "msi_jni_buffers.h"

namespace {

bool toPixelFormat(int32_t androidFormat, msi::PixelFormat& out) {
    switch (androidFormat) {
        case ANDROID_BITMAP_FORMAT_RGBA_8888: out = msi::PixelFormat::RGBA_8888; return true;
        case ANDROID_BITMAP_FORMAT_RGB_565: out = msi::PixelFormat::RGB_565; return true;
        case ANDROID_BITMAP_FORMAT_A_8: out = msi::PixelFormat::A_8; return true;
        default: return false;  // F16 / 1010102 / hardware: Kotlin copies to ARGB_8888 first
    }
}

// Holds the bitmap's pixel lock for one scope, so no exit path can leak it.
class PixelLock {
public:
    PixelLock(JNIEnv* env, jobject bitmap) : env_(env), bitmap_(bitmap) {
        if (AndroidBitmap_lockPixels(env, bitmap, &pixels_) != ANDROID_BITMAP_RESULT_SUCCESS) {
            pixels_ = nullptr;
            locked_ = false;
        } else {
            locked_ = true;
        }
    }
    ~PixelLock() {
        if (locked_) AndroidBitmap_unlockPixels(env_, bitmap_);
    }
    PixelLock(const PixelLock&) = delete;
    PixelLock& operator=(const PixelLock&) = delete;
    const uint8_t* pixels() const { return static_cast<const uint8_t*>(pixels_); }

private:
    JNIEnv* env_;
    jobject bitmap_;
    void* pixels_ = nullptr;
    bool locked_ = false;
};

} // namespace

extern "C" {

// Converts `bitmap` to gray (box-downscaled by the integer `scale`;
// `premultiplied` = Bitmap.isPremultiplied, honoured for RGBA_8888), then
// rotates it upright by `rotationDegrees` (clockwise) into the luma area of
// the direct buffer
// `dst`. `grayWidth`/`grayHeight` are the caller's expected pre-rotation
// extents and must equal msi::stillGrayDim of the bitmap's — a mismatch means
// the Kotlin and native geometry disagree and is rejected, never patched up.
// Returns false on any invalid input, unsupported format or lock failure.
JNIEXPORT jboolean JNICALL
Java_com_barcodescanner_sdk_data_frame_NativeFrame_nativePrepareBitmap(
    JNIEnv* env, jobject /*thiz*/,
    jobject bitmap, jint scale, jint grayWidth, jint grayHeight,
    jint rotationDegrees, jboolean premultiplied, jobject dst) {

    if (bitmap == nullptr || scale < 1) return JNI_FALSE;
    AndroidBitmapInfo info;
    if (AndroidBitmap_getInfo(env, bitmap, &info) != ANDROID_BITMAP_RESULT_SUCCESS) {
        return JNI_FALSE;
    }
    msi::PixelFormat format;
    if (!toPixelFormat(info.format, format)) return JNI_FALSE;
    const int width = static_cast<int>(info.width);
    const int height = static_cast<int>(info.height);
    if (width <= 0 || height <= 0) return JNI_FALSE;
    if (grayWidth <= 0 || grayHeight <= 0 || msi::stillGrayDim(width, scale) != grayWidth ||
        msi::stillGrayDim(height, scale) != grayHeight) {
        return JNI_FALSE;
    }
    const int64_t grayBytes = static_cast<int64_t>(grayWidth) * static_cast<int64_t>(grayHeight);
    uint8_t* out = msi_jni::directBytes(env, dst, grayBytes);
    if (out == nullptr) return JNI_FALSE;

    // Gray is staged once, then written rotated. bad_alloc must not unwind
    // across JNI (abort); a failed still is a clean false.
    std::vector<uint8_t> gray;
    try {
        gray.resize(static_cast<size_t>(grayBytes));
    } catch (const std::bad_alloc&) {
        return JNI_FALSE;
    }
    {
        const PixelLock lock(env, bitmap);
        if (lock.pixels() == nullptr) return JNI_FALSE;
        msi::PixelView view{lock.pixels(), width, height, static_cast<int>(info.stride), format};
        view.premultiplied = premultiplied == JNI_TRUE;
        // grayFromPixels never throws (allocation failure is false).
        if (!msi::grayFromPixels(view, scale, gray.data())) return JNI_FALSE;
    }

    const msi::LumaView luma{gray.data(), grayWidth, grayHeight, grayWidth};
    const msi::Region all{0, 0, grayWidth, grayHeight};
    // Never stretched: a 2%/98% stretch over a whole shelf photo clips the
    // bars (on the 110-photo regression it cost 7 of 43 MSI reads and added
    // wrong ones); it only pays off on small live aim-box crops.
    return msi::prepareRegion(luma, all, rotationDegrees, /*stretch=*/false, out) ? JNI_TRUE
                                                                                   : JNI_FALSE;
}

} // extern "C"
