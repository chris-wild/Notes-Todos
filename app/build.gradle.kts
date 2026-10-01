import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    // AGP 9+ provides built-in Kotlin; no standalone org.jetbrains.kotlin.android.
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "uk.co.promptbuilt.notestodos"
    compileSdk = 36

    defaultConfig {
        // Matches the iOS bundle ID (decision D1 in ANDROID-PARITY.md, 1 October 2026). The code
        // namespace stays uk.co.promptbuilt.notestodos; only the store identity changed. Installs of
        // the old ID were migrated by copying their data (zip backup works too).
        applicationId = "uk.co.promptbuilt.hobpad"
        minSdk = 26
        targetSdk = 36
        versionCode = 6
        versionName = "0.4.2"
    }

    // Play upload key, configured in ~/.gradle/gradle.properties (never in the repo), as RiderNav does
    // (ridernav/ANDROID_RELEASE.md). Play App Signing holds the final app-signing key; this one only
    // authenticates uploads. Without the properties, release builds fall back to the debug key so a
    // machine without the keystore can still build, but such a bundle cannot be uploaded to Play.
    val uploadStoreFile = providers.gradleProperty("HOBPAD_UPLOAD_STORE_FILE").orNull
    signingConfigs {
        if (uploadStoreFile != null) {
            create("release") {
                storeFile = file(uploadStoreFile)
                storePassword = providers.gradleProperty("HOBPAD_UPLOAD_STORE_PASSWORD").orNull
                keyAlias = providers.gradleProperty("HOBPAD_UPLOAD_KEY_ALIAS").orNull
                keyPassword = providers.gradleProperty("HOBPAD_UPLOAD_KEY_PASSWORD").orNull
            }
        }
    }

    buildTypes {
        // The metering Worker (backend/ops), as on iOS: development builds talk to staging, which
        // also accepts test purchases; release builds talk to production.
        debug {
            buildConfigField("String", "OPS_WORKER_URL", "\"https://hobpad-ops-staging.chris-f50.workers.dev\"")
        }
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
            buildConfigField("String", "OPS_WORKER_URL", "\"https://hobpad-ops.chris-f50.workers.dev\"")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_21)
    }
}

dependencies {
    implementation(project(":core"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons.extended)
    implementation(libs.compose.ui.tooling.preview)
    debugImplementation(libs.compose.ui.tooling)

    implementation(libs.navigation.compose)
    implementation(libs.lifecycle.viewmodel.compose)
    implementation(libs.lifecycle.runtime.compose)

    // Recipe PDF backup to the Google Drive application data folder (backup/DriveBackup.kt).
    implementation(libs.play.services.auth)
    implementation(libs.work.runtime.ktx)
    implementation(libs.okhttp)

    // Credit packs (store/OpsStore.kt), verified server-side by the Worker.
    implementation(libs.play.billing)

    testImplementation(libs.junit)
}
