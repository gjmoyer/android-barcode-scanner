pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
    // NOTE: gradle/libs.versions.toml is auto-imported as the `libs` catalog —
    // do NOT redeclare it here (Gradle 9 fails on duplicate from()).
}
rootProject.name = "BarcodeScanner"
include(":barcode-scanner-sdk")
include(":sample-app")
