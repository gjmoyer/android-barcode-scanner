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
        // Android 16 (API 36): Play requirement for new apps/updates since Aug 2026.
        // (compileSdk 37 is compile-only; target stays on the stable level.)
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"
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
    buildFeatures {
        viewBinding = true
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
