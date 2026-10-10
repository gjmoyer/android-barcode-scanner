plugins {
    alias(libs.plugins.android.library)
    // NOTE (AGP 9): no kotlin-android plugin — Kotlin support is built into AGP.
    // Compiler options go in kotlin { compilerOptions { } } below; the KGP version
    // comes from the root buildscript classpath (libs.versions.kotlin).
}

android {
    namespace = "com.barcodescanner.sdk"
    // 37: core-ktx 1.19.x AAR metadata requires compiling against API 37+.
    // (No API-37 calls are used, so behavior on older devices is unchanged.)
    compileSdk = 37

    defaultConfig {
        // Android 9 (API 28). Nothing in the SDK needs newer: CameraX/ML Kit need
        // API 21+, and all platform calls used (PreviewView, SystemClock, Bitmap,
        // YuvImage, repeatOnLifecycle via lifecycle-runtime) exist since API 28.
        minSdk = 28
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        consumerProguardFiles("consumer-rules.pro")

        // Native MSI decoder (src/main/cpp): ARM only. Physical devices are
        // ARM (arm64-v8a; armeabi-v7a kept for 32-bit hardware); x86/x86_64
        // emulator slices are deliberately not shipped — the decoder degrades
        // to NotFound without its .so (see MsiNativeDecoder.nativeAvailable),
        // and modern emulators (e.g. the arm64 Pixel 10a AVD) are unaffected.
        externalNativeBuild {
            cmake {
                cppFlags("-std=c++17")
                abiFilters("armeabi-v7a", "arm64-v8a")
            }
        }
    }

    externalNativeBuild {
        cmake {
            path("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    // NOTE: testOptions lives directly under android{} in AGP 8.x+
    // (nesting it inside defaultConfig{} breaks configuration).
    testOptions {
        unitTests {
            isIncludeAndroidResources = true
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

// AGP 9 built-in Kotlin: jvmTarget defaults to compileOptions.targetCompatibility,
// set explicitly here for clarity; opt-in replaces the old freeCompilerArgs flag.
kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        optIn.add("kotlinx.coroutines.ExperimentalCoroutinesApi")
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    // api(): BarcodeScannerFacade.startCamera exposes LifecycleOwner + PreviewView,
    // so hosts compiling against the facade need these on the compile classpath.
    api(libs.lifecycle.runtime)
    api(libs.camera.view)
    implementation(libs.camera.core)
    implementation(libs.camera.camera2)
    implementation(libs.camera.lifecycle)
    implementation(libs.mlkit.barcode.scanning)
    implementation(libs.coroutines.core)
    implementation(libs.coroutines.android)
    // Task<T>.await() for ML Kit's GMS Task API.
    implementation(libs.coroutines.play.services)
    // Prebuilt zxing-cpp reader (bundles native lib + BarcodeReader JNI wrapper).
    implementation(libs.zxingcpp.android)

    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.mockk)
    testImplementation(libs.coroutines.core)

    androidTestImplementation("androidx.test:runner:1.7.0")
    androidTestImplementation("androidx.test:rules:1.7.0")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    androidTestImplementation(libs.coroutines.android)
}
