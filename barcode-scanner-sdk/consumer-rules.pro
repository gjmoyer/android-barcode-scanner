# SDK consumer ProGuard — keep public API, allow shrinking everything else.
-keep public class com.barcodescanner.sdk.api.** { *; }
-keep public class com.barcodescanner.sdk.domain.model.** { *; }
# zxing-cpp ships as a prebuilt AAR with its own rules; no additional -keep needed.
# ML Kit + CameraX have their own bundled rules; no additional -keep needed.
