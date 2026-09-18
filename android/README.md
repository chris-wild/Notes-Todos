# notes-todos Android

Native, client-only Android replacement for the notes-todos web app. All data lives on
the device (Room/SQLite + app-private files). The only network call the app ever makes
is to `api.anthropic.com` for the recipe ingredient-extraction feature.

## Stack

- Kotlin 2.2 / Jetpack Compose (Material 3), single-activity, three tabs (Notes / Todos / Recipes)
- Room for data, Preferences DataStore for settings, EncryptedSharedPreferences for the Anthropic key
- minSdk 26, target/compileSdk 36, AGP 8.13, Gradle 8.14.3 (wrapper)

## Building

Gradle needs a JDK 17–21. This machine's default (`/usr/bin/java`) is a stub and
Homebrew's JDK is too new, so use Android Studio's bundled JDK:

```sh
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew :app:assembleDebug        # APK at app/build/outputs/apk/debug/app-debug.apk
./gradlew :app:testDebugUnitTest    # unit tests
```

(`gradle.properties` pins `org.gradle.java.home` to the same JDK for the daemon;
opening `android/` in Android Studio also works.)

Install on a device/emulator:

```sh
~/Library/Android/sdk/platform-tools/adb install -r app/build/outputs/apk/debug/app-debug.apk
```

## Backup model

- Android Auto Backup covers the Room DB and settings; recipe PDFs and the Anthropic key
  are excluded (`res/xml/data_extraction_rules.xml`).
- Full backups (including PDFs) come from the in-app export/import zip (planned in `backup/`).
