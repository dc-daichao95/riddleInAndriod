# Riddle Magic Paper for Android

Riddle is an immersive handwriting-first LLM application for Android 13 through Android 16. Write across the full paper, send with the magic rune, and receive a streamed answer rendered back as handwriting.

## Current status

The Android foundation, Provider setup and discovery, handwriting-language routing, deterministic reply planning/playback core, and Room v2 recovery contracts are implemented. Production reply rendering and recovery integration is the next approved slice: start at Task 3 in `doc/plans/android-completion-master-plan.md`, then complete Tasks 4–6 in order. A final signed release APK has not yet been delivered.

## Supported Android versions

One application supports Android 13/API 33 through Android 16/API 36 with the same product behavior. The project uses `minSdk = 33`, `targetSdk = 36`, and compile platform API 36.1.

## Features

- Full-screen pen or touch handwriting on an adaptive paper canvas.
- Magic-rune submission, streamed model events, deterministic handwriting reply planning, and persistent conversation recovery.
- OpenAI-compatible and DeepSeek-compatible Provider profiles, plus custom HTTPS-compatible endpoints.
- Provider capabilities drive shared UI and domain behavior; shared code never branches on Provider names.
- English and Simplified Chinese application resources, handwriting-language selection, TalkBack semantics, keyboard/switch navigation, dynamic type, and reduced-motion policy.
- Provider credentials protected by Android Keystore-backed storage. Normal tests use deterministic fakes and never require a paid endpoint.

## Repository layout

- `android-app/` — the only product and Gradle root.
- `android-app/app/` — Android entry point and composition root.
- `android-app/core-model/` — Provider-neutral models and contracts.
- `android-app/paper-engine/` — ink, geometry, reply planning, and bundled font assets.
- `android-app/conversation/`, `model-provider/`, `memory/`, `security/` — orchestration and infrastructure.
- `android-app/feature-paper/`, `feature-settings/` — Compose features.
- `doc/specs/`, `doc/plans/`, `doc/adr/`, `doc/handoff/`, `doc/prompts/` — approved requirements and development evidence.

## Quick start

Install Android Studio with JBR 21, Android SDK platforms 33 and 36.1, build-tools 36.1.0, platform-tools, and the Android emulator. Point `JAVA_HOME` at JBR 21 and set `ANDROID_HOME` and `ANDROID_SDK_ROOT` to the SDK. Put only the machine-local SDK path in untracked `android-app/local.properties`; see `android-app/README.md` for exact setup and emulator guidance.

## Build and test

Run canonical commands from `android-app/`:

```powershell
.\gradlew.bat verifyAndroidCompatibility
.\gradlew.bat test
.\gradlew.bat lint
.\gradlew.bat assembleDebug
```

Run connected tests on the API 33 and API 36.1 boundary emulators one at a time. The full command inventory and focused critical instrumentation command are in `android-app/README.md`.

## Configure a model provider

Open Settings, choose an OpenAI-compatible or DeepSeek-compatible preset (or explicitly choose a custom HTTPS-compatible endpoint), enter a display name, HTTPS base URL, model ID, and API key, review the actual destination host, then validate and save the profile. Select an enabled profile and discovered or manual model before sending handwriting. Cross-Provider fallback is never silent.

## Security and privacy

API keys are stored through Android Keystore-backed credential protection, not Room, source files, `local.properties`, logs, saved state, screenshots, or crash diagnostics. Prompts, replies, recognized handwriting, ink/image bytes, authorization values, and signing secrets are sensitive. Do not use real credentials in normal tests or CI.

## Accessibility and localization

Controls require meaningful TalkBack labels, keyboard/switch reachability, 48 dp touch targets, sufficient contrast, state cues beyond color, dynamic font support, and reduced-motion behavior. User-visible application strings live in resources; English and Simplified Chinese are supported.

## Specifications and development workflow

Start with `AGENTS.md`, `doc/specs/android-continuation-handoff-spec.md`, and `doc/plans/android-completion-master-plan.md`. Behavior changes follow Explore → Specify → Review → Plan → Red → Green → Refactor → Verify → Document. The Android-only migration is specified in `doc/specs/android-only-repository-cleanup.md`.

## Remaining work

- Task 3: connect streamed reply handwriting and Room recovery to production UI/composition.
- Task 4: finish immersive send/cancel/tool controls and deterministic stardust behavior.
- Task 5: derive and verify launcher/repository branding from the retained original assets.
- Task 6: prove API 33/API 36 parity and deliver a verified locally signed release APK.

## License and bundled fonts

The project is licensed under the root `LICENSE` (MIT). Bundled font licenses and the pinned checksum manifest live beside the Android assets in `android-app/paper-engine/src/main/assets/fonts/`.
