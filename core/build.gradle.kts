plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kmp.library)
    alias(libs.plugins.ksp)
    alias(libs.plugins.room)
}

kotlin {
    // Android uses this target (NOT jvm()) so androidMain keeps the framework
    // SQLite driver and the installed notes-todos.db opens byte-for-byte as before.
    android {
        namespace = "uk.co.promptbuilt.notestodos.core"
        compileSdk = 36
        minSdk = 26
        // Runs commonTest on the host JVM.
        withHostTest {}
    }

    // iOS targets + XCFramework arrive in the next step.

    sourceSets {
        commonMain.dependencies {
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.serialization.json)
            api(libs.room.runtime)
            implementation(libs.sqlite.bundled)
            api(libs.datastore.preferences.core)
        }
        androidMain.dependencies {
            implementation(libs.security.crypto)
            implementation(libs.okhttp)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.kotlinx.coroutines.test)
        }
    }
}

room {
    schemaDirectory("$projectDir/schemas")
}

dependencies {
    add("kspAndroid", libs.room.compiler)
}
