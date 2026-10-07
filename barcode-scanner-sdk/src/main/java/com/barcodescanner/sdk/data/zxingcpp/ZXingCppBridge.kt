package com.barcodescanner.sdk.data.zxingcpp

/**
 * JNI bridge to zxing-cpp (loaded from `libzxing_bridge.so` built via CMake).
 *
 * The native side (`src/main/cpp/zxing_bridge.cpp`) uses
 * `ZXing::ReadBarcodes` with `TryRotate=true, TryInvert=true` and returns
 * JSON: `[{"text":"...","format":"DataBar Expanded",...}]`
 * (v3.x `ToString` emits human-readable names with spaces).
 *
 * If the .so is missing (unit tests, unsupported ABI), [isAvailable] is false
 * and the decoder returns NotFound so fusion can continue to the next engine
 * instead of crashing the app.
 */
internal object ZXingCppBridge {
    private val lock = Any()

    @Volatile
    private var loaded: Boolean? = null

    /** True if native lib loaded successfully. Thread-safe lazy init. */
    val isAvailable: Boolean
        get() {
            if (loaded == null) {
                synchronized(lock) {
                    if (loaded == null) {
                        loaded = runCatching {
                            System.loadLibrary("zxing_bridge")
                            // Verify the JNI entry point exists.
                            nativePing() == 42
                        }.onFailure {
                            android.util.Log.w("ZXingCppBridge", "native lib unavailable", it)
                        }.getOrDefault(false)
                    }
                }
            }
            return loaded == true
        }

    /**
     * Decodes a bitmap.
     *
     * @param pixels ARGB pixels, row-major.
     * @param width Height in px.
     * @param tryRotate/Invert/harder mirror the ZXing ReaderOptions we want for
     *   rotated / white-on-black labels.
     * @return JSON array string (possibly "[]").
     */
    fun decodeBitmap(
        pixels: IntArray,
        width: Int,
        height: Int,
        tryRotate: Boolean = true,
        tryInvert: Boolean = true,
        tryHarder: Boolean = true,
        enabledFormats: String = "",
    ): String {
        if (!isAvailable) return "[]"
        return nativeDecode(pixels, width, height, tryRotate, tryInvert, tryHarder, enabledFormats)
    }

    private external fun nativePing(): Int

    private external fun nativeDecode(
        pixels: IntArray,
        width: Int,
        height: Int,
        tryRotate: Boolean,
        tryInvert: Boolean,
        tryHarder: Boolean,
        enabledFormats: String,
    ): String
}
