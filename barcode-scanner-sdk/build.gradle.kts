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
        consumerProguardFiles("consumer-rules.pro")

        // zxing-cpp native bridge. ABIs needed for Play distribution.
        ndk {
            abiFilters += listOf("armeabi-v7a", "arm64-v8a", "x86_64")
        }
        externalNativeBuild {
            cmake {
                cppFlags += listOf("-std=c++20", "-frtti", "-fexceptions")
                arguments += listOf("-DZXING_EXAMPLES=OFF", "-DZXING_WRITERS=OFF")
            }
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

    externalNativeBuild {
        cmake {
            path = file("CMakeLists.txt")
            version = "3.22.1+"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        buildConfig = true
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
    // Bundled Latin OCR model (offline, no Play dependency) for the MSI SKU
    // text fallback (see data/ocr). Runs last, only on MSI bar-miss.
    implementation(libs.mlkit.text.recognition)
    implementation(libs.coroutines.core)
    implementation(libs.coroutines.android)
    // Task<T>.await() for ML Kit's GMS Task API.
    implementation(libs.coroutines.play.services)

    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.mockk)
    testImplementation(libs.coroutines.core)
}
