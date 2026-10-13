plugins {
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.android.application) apply false
}

// AGP 9.x ships built-in Kotlin (KGP) and the org.jetbrains.kotlin.android plugin
// must NOT be applied (it conflicts with the built-in extension). AGP 9.4 bundles
// KGP 2.2.10; override with a newer compiler via the buildscript classpath below
// (version from libs.versions.kotlin). Do NOT apply it in modules.
buildscript {
    dependencies {
        classpath(libs.kotlin.gradle.plugin)
    }
}

tasks.register<Delete>("clean") {
    description = "clean build dir"
    delete(rootProject.layout.buildDirectory)
}
