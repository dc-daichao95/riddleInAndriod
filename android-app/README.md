# Riddle for Android

This directory contains the Android 16 application. It is a separate Gradle build and does not replace the Rust product at the repository root.

## Required toolchain

- Android Studio 2026.1.1 with its bundled JBR 21
- Android SDK Platform 36.1 (`platforms;android-36.1`)
- Android SDK Build Tools 36.1.0
- Android SDK Platform Tools
- Android SDK Command-line Tools 21.0
- Android Emulator and an API 36.1 system image

The project compiles against API 36.1 and uses Android 16/API 36 as both its minimum and target runtime. The development emulator is named `Riddle_API_36_1`.

Set `JAVA_HOME` to Android Studio's bundled JBR and set both `ANDROID_HOME` and `ANDROID_SDK_ROOT` to the Android SDK directory. Machine-specific SDK paths may be placed in an untracked `local.properties` file:

```properties
sdk.dir=C\:\\path\\to\\Android\\Sdk
```

`local.properties` must contain only local SDK location information. Never put provider credentials, API keys, production endpoints containing credentials, or signing secrets in it or any committed file.

## Build and test

From `android-app/`:

```powershell
.\gradlew.bat --version
.\gradlew.bat test
.\gradlew.bat lint
.\gradlew.bat assembleDebug
```

Run device tests on the Android 16 emulator when a slice adds instrumentation tests:

```powershell
emulator -avd Riddle_API_36_1
.\gradlew.bat connectedCheck
```

Normal tests use deterministic fakes and require no live provider credentials or paid model calls.
