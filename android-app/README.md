# Riddle Magic Paper Android project

This directory is the canonical Gradle root for the Riddle Magic Paper Android application. Product orientation is in the [root README](../README.md); repository rules are in [AGENTS.md](../AGENTS.md).

## Required toolchain

- Android Studio 2026.1.1 with its bundled JBR 21
- Android SDK Platform 36.1 (`platforms;android-36.1`)
- Android SDK Platform 33 (`platforms;android-33`)
- Android SDK Build Tools 36.1.0
- Android SDK Platform Tools
- Android SDK Command-line Tools 21.0
- Android Emulator with API 33 and API 36.1 Google APIs x86_64 system images

The project compiles against API 36.1, has `minSdk = 33`, and targets API 36. The boundary AVDs are `Riddle_API_33` and `Riddle_API_36_1`.

Set `JAVA_HOME` to Android Studio's bundled JBR 21 and set `ANDROID_HOME` and `ANDROID_SDK_ROOT` to the Android SDK directory. Put the machine-specific SDK path in untracked `local.properties`:

```properties
sdk.dir=C\:\\path\\to\\Android\\Sdk
```

`local.properties` is only for the SDK path. Never place Provider credentials, API keys, credential-bearing endpoints, or signing secrets in it, Gradle properties, source control, logs, or test fixtures.

## Configure a Provider

In application Settings:

1. Choose an OpenAI-compatible or DeepSeek-compatible preset, or explicitly choose a custom HTTPS-compatible endpoint.
2. Enter the profile display name, HTTPS base URL, model ID, and API key.
3. Review the displayed destination host before continuing to credential validation.
4. Validate and save, then select the enabled profile and a discovered or manual model.

Credentials are protected through Android Keystore-backed storage and never belong in Room. Failed setup preserves the prior valid profile and credential. Normal tests inject fakes and do not call live or paid Providers.

## Build and test

Run all commands from this directory:

```powershell
.\gradlew.bat --version
.\gradlew.bat verifyAndroidCompatibility
.\gradlew.bat test
.\gradlew.bat lint
.\gradlew.bat assembleDebug
```

For the current critical Android 16 settings flow:

```powershell
.\gradlew.bat :app:connectedDebugAndroidTest "-Pandroid.testInstrumentationRunnerArguments.class=dev.riddle.magicpaper.MagicRuneSettingsTest" --rerun-tasks
```

Boot and test one boundary emulator at a time so device selection is unambiguous:

```powershell
emulator -avd Riddle_API_33
.\gradlew.bat connectedCheck

emulator -avd Riddle_API_36_1
.\gradlew.bat connectedCheck
```

The device flow uses `ActivityScenario.recreate()` and rotation to verify ViewModel ownership, normalized geometry, and repository-backed recovery boundaries. Instrumentation cannot deterministically simulate an operating-system process kill while retaining its test connection; deterministic ViewModel/persistence tests cover the interrupted-stream marker, and actual OS-kill recovery remains a manual release smoke test.

## Development references

- [Android-only cleanup specification](../doc/specs/android-only-repository-cleanup.md)
- [Continuation specification](../doc/specs/android-continuation-handoff-spec.md)
- [Completion master plan](../doc/plans/android-completion-master-plan.md)

Current continuation starts at master-plan Task 3. Execute Tasks 3–6 in order with requirement traceability, observed RED, minimal GREEN, full verification, independent review, and a scoped commit for each task.
