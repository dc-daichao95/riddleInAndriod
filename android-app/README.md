# Riddle for Android

This directory contains the Android 13 through Android 16 application. It is a separate Gradle build and does not replace the Rust product at the repository root.

## Required toolchain

- Android Studio 2026.1.1 with its bundled JBR 21
- Android SDK Platform 36.1 (`platforms;android-36.1`)
- Android SDK Platform 33 (`platforms;android-33`)
- Android SDK Build Tools 36.1.0
- Android SDK Platform Tools
- Android SDK Command-line Tools 21.0
- Android Emulator plus API 33 and API 36.1 Google APIs x86_64 system images

The project compiles against API 36.1, has `minSdk = 33`, and targets API 36. The boundary emulators are named `Riddle_API_33` and `Riddle_API_36_1`.

Set `JAVA_HOME` to Android Studio's bundled JBR and set both `ANDROID_HOME` and `ANDROID_SDK_ROOT` to the Android SDK directory. Machine-specific SDK paths may be placed in an untracked `local.properties` file:

```properties
sdk.dir=C\:\\path\\to\\Android\\Sdk
```

`local.properties` must contain only local SDK location information. Never put provider credentials, API keys, production endpoints containing credentials, or signing secrets in it or any committed file.

## Build and test

From `android-app/`:

```powershell
.\gradlew.bat --version
.\gradlew.bat verifyAndroidCompatibility
.\gradlew.bat test
.\gradlew.bat lint
.\gradlew.bat assembleDebug
```

Run device tests on both boundary emulators when a slice adds instrumentation tests. Boot only one emulator at a time so `connectedCheck` cannot silently target the wrong API level:

```powershell
emulator -avd Riddle_API_33
.\gradlew.bat connectedCheck

emulator -avd Riddle_API_36_1
.\gradlew.bat connectedCheck
```

Normal tests use deterministic fakes and require no live provider credentials or paid model calls.

The device flow uses `ActivityScenario.recreate()` plus rotation to verify ViewModel ownership,
normalized geometry, and repository-backed recovery boundaries. Android instrumentation cannot
deterministically simulate an operating-system process kill while retaining the test connection;
the interrupted-stream marker is therefore covered by deterministic ViewModel/persistence tests,
with an actual OS-kill recovery check remaining a manual release smoke test.
