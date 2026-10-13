// JNI stub with the same binary name and native signature as the Kotlin
// class; keep in sync (a drift is undefined behaviour at runtime, not a
// link error, because JNI resolves natives by name only).
package com.barcodescanner.sdk.data.frame;

public class NativeFrame {
    public native boolean nativePrepare(java.nio.ByteBuffer src, int srcWidth, int srcHeight,
            int srcRowStride, int left, int top, int right, int bottom, int rotationDegrees,
            boolean stretch, java.nio.ByteBuffer dst);
}
