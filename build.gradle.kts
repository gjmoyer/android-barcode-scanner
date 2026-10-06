plugins {
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.android.application) apply false
}

// AGP 9.x ships built-in Kotlin (KGP) and the org.jetbrains.kotlin.android plugin
// must NOT be applied (it conflicts with the built-in extension). AGP 9.4 bundles
// KGP 2.2.10; override with a newer compiler via the buildscript classpath below
// (keep in sync with libs.versions.kotlin = 2.4.20). Do NOT apply it in modules.
buildscript {
    dependencies {
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:2.4.20")
    }
}

tasks.register<Delete>("clean") {
    delete(rootProject.layout.buildDirectory)
}
