// JNI stub with the same binary name and native signature as the Kotlin
// class; keep in sync (a drift is undefined behaviour at runtime, not a
// link error, because JNI resolves natives by name only).
package com.barcodescanner.sdk.data.msi;

public class MsiNativeDecoder {
    public native String nativeDecodeLuma(java.nio.ByteBuffer gray, int width, int height,
            int maxOrientations, float scanTopFraction, int requiredPolicy);
}
