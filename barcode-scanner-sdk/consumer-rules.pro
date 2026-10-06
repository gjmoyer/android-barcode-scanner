# SDK consumer ProGuard — keep public API, allow shrinking everything else.
-keep public class com.barcodescanner.sdk.api.** { *; }
-keep public class com.barcodescanner.sdk.domain.model.** { *; }
# JNI bridge called by reflection-free direct binding; keep native method names.
-keepclasseswithmembernames class com.barcodescanner.sdk.data.zxingcpp.ZXingCppBridge {
    native <methods>;
}
# ML Kit + CameraX have their own bundled rules; no additional -keep needed.
