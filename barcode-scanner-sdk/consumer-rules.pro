# SDK consumer ProGuard — keep public API, allow shrinking everything else.
-keep public class com.barcodescanner.sdk.api.** { *; }
-keep public class com.barcodescanner.sdk.domain.model.** { *; }
# Native frame path and MSI decoder: keep the JNI bridges (bound from C++ by
# name), independent of whether the host uses the default ProGuard files.
-keepclasseswithmembernames class com.barcodescanner.sdk.data.frame.NativeFrame {
    native <methods>;
}
-keepclasseswithmembernames class com.barcodescanner.sdk.data.msi.MsiNativeDecoder {
    native <methods>;
}
# zxing-cpp ships as a prebuilt AAR with its own rules; no additional -keep needed.
# ML Kit + CameraX have their own bundled rules; no additional -keep needed.
