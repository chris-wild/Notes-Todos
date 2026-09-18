// Top-level build file. Plugins are declared here and applied in module build files.
plugins {
    // AGP 9+ has built-in Kotlin support, so no standalone kotlin-android plugin.
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.multiplatform) apply false
    alias(libs.plugins.ksp) apply false
}
