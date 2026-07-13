# Android 13 Compatibility Specification

- Status: `approved`
- Product: Riddle Android
- Supported runtime range: Android 13 / API 33 through Android 16 / API 36
- Compile platform: Android API 36.1
- Target SDK: 36
- Minimum SDK: 33
- Approval: direct user request on 2026-07-14
- Parent specification: `doc/specs/android-magic-paper-app.md`

## 1. Problem statement and user value

The Android product was originally specified for Android 16 only. The user now requires the same immersive magic-paper experience on Android 13 devices without maintaining a separate legacy application or weakening Android 16 behavior.

## 2. Scope and non-goals

In scope:

- run one APK on API 33, 34, 35, and 36;
- keep compilation on API 36.1 and target SDK 36;
- lower every Android module's minimum SDK to 33;
- preserve handwriting, Provider, security, memory, settings, orientation, accessibility, and localization behavior;
- verify API 33 and API 36.1 as mandatory device gates.

Non-goals:

- lowering `compileSdk` or `targetSdk` to 33;
- supporting Android 12L/API 32 or earlier;
- creating a separate Android 13 flavor, branch, APK, or reduced feature set;
- weakening TLS, Keystore, backup, privacy, lint, or test requirements;
- changing Provider protocols or adding tool-agent execution.

## 3. User stories and use cases

- An Android 13 user can install, launch, configure, write, submit, cancel, rotate, lock portrait, recover a draft, and receive a streamed reply.
- An Android 16 user retains the same behavior already verified by the parent specification.
- A developer can build one signed APK and validate it against both boundary API levels.

## 4. Functional requirements

- `A13-FR-001`: All Android application and library modules shall use `minSdk = 33`.
- `A13-FR-002`: The project shall keep API 36.1 compilation and `targetSdk = 36`.
- `A13-FR-003`: One application ID and one APK shall support the full API 33–36 runtime range.
- `A13-FR-004`: API-dependent behavior shall use AndroidX compatibility APIs or explicit `SDK_INT` guards with a tested API 33 path.
- `A13-FR-005`: Full-screen paper, system-bar hiding, rotation, optional portrait lock, touch/stylus input, settings, and process recreation shall work on API 33.
- `A13-FR-006`: Provider streaming, cancellation, Room persistence, Android Keystore credentials, and ML Kit prerequisite handling shall retain their existing contracts on API 33.
- `A13-FR-007`: Release output shall not be split or downgraded by Android version.
- `A13-FR-008`: Android 13 shall expose the same complete feature set and user-visible behavior as Android 16; no API-level feature disabling, reduced mode, or alternate workflow is permitted.

## 5. Non-functional requirements

- `A13-NFR-001`: API 33 compatibility shall not introduce cleartext traffic, exported components, backup access, secret persistence, or logging of sensitive content.
- `A13-NFR-002`: Normal unit, lint, debug, and release gates shall remain warning-free except documented third-party native-library strip notices.
- `A13-NFR-003`: The API 33 implementation shall not add a second UI or provider architecture.
- `A13-NFR-004`: Performance-sensitive paper input shall retain the same render-cache and per-point Compose isolation used on API 36.
- `A13-NFR-005`: User-visible behavior and localized resources shall remain equivalent across API levels.
- `A13-NFR-006`: The implementation shall minimize code churn: change `minSdk` configuration first, add only narrowly scoped compatibility guards proven necessary by lint, compilation, or API 33 tests, and avoid business, Provider, persistence, or UI refactoring.

## 6. Acceptance criteria

- `A13-AC-001`: Given a clean API 33 AVD, when the release-compatible app is installed and launched, then the immersive paper screen appears without a startup crash.
- `A13-AC-002`: Given API 33 and API 36.1 AVDs, when the fake-Provider instrumentation flow runs, then both pass handwriting, inactivity commit, dissolve, streaming reply, cancellation, hidden settings, rotation, portrait lock, and draft recovery checks.
- `A13-AC-003`: Given any Android module, when its merged manifest is inspected, then its minimum SDK is 33 and the app target SDK remains 36.
- `A13-AC-004`: Given an Android API introduced after 33, when lint analyzes the project, then the API 33 path is guarded or implemented through a compatible AndroidX abstraction.
- `A13-AC-005`: Given the same Provider fixtures and Room schema, when unit and device tests run under the compatibility change, then no Provider, credential, migration, or reconstruction regression occurs.
- `A13-AC-006`: Given a temporarily signed release APK, when its manifest and signature are inspected, then it declares minSdk 33, targetSdk 36, one signer, and package `dev.riddle.magicpaper`.
- `A13-AC-007`: Given the API 33 and API 36.1 regression matrices, when all feature tests are compared, then both execute the same test cases and pass without version-specific exclusions or ignored assertions.
- `A13-AC-008`: Given the compatibility commit, when its production diff is reviewed, then it contains only minimum-SDK configuration changes and compatibility code directly justified by a failing API 33 gate.

## 7. Architecture and affected modules

The architecture remains unchanged. Compatibility is a platform boundary concern, not a new feature layer.

Affected files/modules:

- every `android-app/*/build.gradle.kts` Android block;
- `app`, `paper-engine`, `feature-paper`, `feature-settings`, `security`, `memory`, and their API-sensitive tests;
- Android setup documentation and device matrix;
- the parent specification and final traceability document.

## 8. Data model and persistence

Room schema version 1 remains unchanged. API 33 and API 36 use the same database schema, migration baseline, draft format, catalog revision, and memory limit. No downgrade migration or version-specific database is permitted.

## 9. API, Provider, streaming, and tool-call contracts

Provider-neutral requests, SSE parsing, OpenAI-compatible mapping, DeepSeek mapping, retry behavior, cancellation, and tool-call parsing remain unchanged. Tool calls remain parsed but not executed. API 33 must not cause cross-provider fallback or alternate endpoints.

## 10. Error, retry, cancellation, and offline behavior

Existing typed errors remain authoritative. Platform compatibility failures must be surfaced as typed initialization or prerequisite failures rather than assistant text. Cancellation must still propagate through ViewModel, orchestrator, Provider, transport, raster cache, and recognition work.

## 11. Security and privacy analysis

Android Keystore AES/GCM remains the credential mechanism on API 33. The compatibility task must verify Keystore round-trip/tamper behavior on an API 33 emulator or document an emulator-provider limitation. No permission broadening, backup enablement, cleartext network configuration, or signing material may enter source control.

## 12. Accessibility and localization

TalkBack semantics, live-region announcements, paper label, minimum touch targets, font scaling, and English/Chinese resources remain required. API 33 instrumentation shall verify critical semantics nodes exist; manual TalkBack quality remains a documented device check.

## 13. Observability and redaction

Compatibility diagnostics may record API level, device profile, test name, and typed failure category. They must not record credentials, authorization headers, complete handwriting images, prompts, replies, or tool arguments.

## 14. Migration and compatibility impact

This change expands the runtime range from API 36-only to API 33–36. It does not change application ID, database schema, Provider profiles, credentials, or signing identity. Existing Android 16 installations remain upgrade-compatible when signed with the same final key.

## 15. Test strategy and traceability

| Requirement | Evidence |
| --- | --- |
| A13-FR-001–004 | Gradle configuration assertions, lint, merged-manifest inspection |
| A13-FR-005 | API 33 Compose/instrumentation magic-paper flow |
| A13-FR-006 | API 33 Room, Keystore, Provider cancellation, and ML Kit prerequisite tests |
| A13-FR-007 | signed release APK metadata and signature verification |
| A13-FR-008 | identical API 33/API 36.1 feature matrix with no exclusions |
| A13-AC-001–002 | API 33 and API 36.1 install/launch/connected gates |
| A13-AC-003–006 | manifest, full unit/lint/build gates, release artifact checks |
| A13-AC-007–008 | cross-version test comparison and scoped production diff review |

Mandatory device matrix:

- API 33 phone profile;
- API 36.1 phone profile;
- responsive wide/tablet geometry coverage through device profiles or instrumentation-controlled window sizes.

## 16. Rollout and rollback

Rollout occurs in one compatibility commit after the Android 16 feature pipeline is green. Rollback restores `minSdk = 36` and removes only API 33-specific guards/tests; it must not revert unrelated Android 16 behavior or persistence data.

## 17. Decisions

- Keep `compileSdk` API 36.1.
- Keep `targetSdk = 36`.
- Set `minSdk = 33` in all Android modules.
- Ship one APK and one application ID.
- Require API 33 and API 36.1 gates before the specification becomes implemented.
- Preserve full feature parity; no Android 13 reduced mode is allowed.
- Prefer configuration-only compatibility and add production code changes only when a failing gate demonstrates they are required.

## 18. Unresolved questions

None. The user selected Android 13 support using the single-APK, target-36 approach.
