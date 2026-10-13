plugins {
    alias(libs.plugins.android.application)
    // NOTE (AGP 9): no kotlin-android plugin — Kotlin support is built into AGP.
}

android {
    namespace = "com.barcodescanner.sample"
    // 37: required by core-ktx 1.19.x AAR metadata (compile only).
    compileSdk = 37

    defaultConfig {
        applicationId = "com.barcodescanner.sample"
        // Android 9 (API 28), same as the SDK: nothing used needs newer.
        minSdk = 28
        // Android 17 (API 37), the current stable level; Play has required
        // API 36+ for new apps and updates since Aug 2026.
        targetSdk = 37
        versionCode = 1
        versionName = "1.0.0"
        // Matches the SDK's ABI filters (ARM devices + x86_64 emulators);
        // strips the 32-bit x86 slices prebuilt AARs (zxing-cpp) would
        // otherwise package.
        ndk {
            abiFilters += listOf("armeabi-v7a", "arm64-v8a", "x86_64")
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(project(":barcode-scanner-sdk"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.ktx)
    implementation(libs.lifecycle.runtime)
    implementation(libs.camera.view)
    implementation(libs.coroutines.android)
}
