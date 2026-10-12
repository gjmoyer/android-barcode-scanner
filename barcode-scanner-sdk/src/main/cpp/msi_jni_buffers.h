// Direct-buffer access shared by the JNI translation units.
#pragma once

#include <jni.h>
#include <cstdint>

namespace msi_jni {

// Base address of a direct ByteBuffer holding at least `need` bytes, else
// null (heap buffer, null reference, or too small). 64-bit math throughout:
// bogus Kotlin dimensions must never pass as a small product.
inline uint8_t* directBytes(JNIEnv* env, jobject buffer, int64_t need) {
    if (buffer == nullptr || need <= 0) return nullptr;
    void* address = env->GetDirectBufferAddress(buffer);
    const jlong capacity = env->GetDirectBufferCapacity(buffer);
    if (address == nullptr || capacity < 0 || static_cast<int64_t>(capacity) < need) {
        return nullptr;
    }
    return static_cast<uint8_t*>(address);
}

} // namespace msi_jni
