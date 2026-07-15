# Magic Paper UX, Provider Setup, and Text Pipeline Repair Plan

> **Historical plan:** Completed R0–R5b foundations and original acceptance evidence remain here. Unfinished repair and release work is governed by `doc/plans/android-completion-master-plan.md`.

> Required execution method: `superpowers:subagent-driven-development`. Each slice follows Red → Green → Refactor → Verify → Review and receives its own scoped commit.

**Goal:** Replace the conflicting settings gesture, make Provider setup validation-first, and implement the real text-model magic-paper pipeline without vendor branching, lost text, or reduced Android 13 behavior.

**Architecture:** Preserve the state machine, router, Provider SPI boundary, Keystore, Room, and custom View. Compose owns controls and semantics; `MagicPaperView` owns user/reply pixels; the Activity-owned ViewModel coordinates cancellable generation-scoped effects. The approved specification is `doc/specs/magic-paper-ux-provider-text-pipeline.md`.

**Order:** This plan reopens Task 9. R0 moves only the `minSdk` configuration portion of compatibility work forward so all later slices can compile/test on API 33. Any production compatibility guard still requires a real API 33 failure. Android 13 Task 10 remains responsible for final compatibility audit and closure after R6.

## R0: Configuration-only API 33 prerequisite

**Files:** nine Android module `build.gradle.kts` files; compatibility configuration test; Android setup docs.

- [ ] Add a configuration test/task that requires every Android module `minSdk = 33` while compile API 36.1 and target 36 remain unchanged.
- [ ] Run `./gradlew verifyAndroidCompatibility`; RED is the current nine `minSdk = 36` declarations.
- [ ] Change only those nine declarations to 33.
- [ ] Run `./gradlew verifyAndroidCompatibility test lint assembleDebug`; GREEN is exit 0 with no unguarded post-33 API use.
- [ ] Install `platforms;android-33` and `system-images;android-33;google_apis;x86_64`, accept licenses, create `Riddle_API_33`, boot it alone, install/launch the Debug APK, and record API/package metadata. Preserve `Riddle_API_36_1` for boundary comparison.
- [ ] Review the production diff; it must contain no behavior code. Commit `build(android): establish API 33 compatibility baseline`.

## R1a: Entry-mode migration and rune navigation contract

**Files:** entry policy/preferences, `PaperViewModel.kt`, resources, unit tests.

- [ ] Add failing tests: missing/unknown/corrupt mode → `MAGIC_RUNE_BUTTON`; explicitly stored legacy ID → `THREE_FINGER_LONG_PRESS`; rune intent pauses inactivity, preserves draft, and emits one navigation effect; default three-finger frames do not navigate.
- [ ] Run `./gradlew :paper-engine:testDebugUnitTest :feature-paper:testDebugUnitTest --tests '*SettingsEntry*'`; RED is missing mode/rune behavior.
- [ ] Implement `SettingsEntryMode` and inject it instead of hard-coding `ThreeFingerLongPressPolicy` in the View. Keep the legacy policy implementation unchanged for explicit mode.
- [ ] Re-run the exact command; commit only entry contract/migration files.

## R1b: Safe magical rune UI and settings back behavior

**Files:** `MagicPaperScreen.kt`, `AppSettingsScreen.kt`, `MainActivity.kt`, resources, Compose/instrumentation tests.

- [ ] Add failing tests asserting rune role/label, visual 28–32 dp bounds, touch bounds at least 48 dp, and all rune/settings controls inside injected nonzero safe/IME inset rectangles at compact width and 200% font scale.
- [ ] Add a failing Activity test proving Back closes settings and restores the unchanged draft instead of finishing the Activity.
- [ ] Run `./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=dev.riddle.magicpaper.MagicRuneSettingsTest`; RED is the missing rune/inset/back behavior.
- [ ] Implement the upper-right rune and safe/IME inset layout. Normal mode uses one short shimmer; reduced motion uses a static brightness change. The rune remains available in every rollback configuration.
- [ ] Re-run the targeted class plus `:app:lintDebug :app:assembleDebug`; commit only this UI slice.

## R1c: Monotonic timing and lifecycle ownership

**Files:** `AppClock`, `AppContainer.kt`, `PaperViewModel.kt`, timing/lifecycle tests.

- [ ] Add failing fake-clock tests proving wall-clock changes do not affect the inactivity deadline or reusable deadline calculation/waiting, and final `ViewModel.onCleared()` cancels active work.
- [ ] Run `./gradlew :feature-paper:testDebugUnitTest --tests '*Clock*' --tests '*Lifecycle*'`; RED is use of `currentTimeMillis` and missing final-teardown evidence.
- [ ] Inject `SystemClock.elapsedRealtime()` through `AppClock`; keep configuration recreation on the Activity ViewModelStore.
- [ ] R1c introduces and verifies the reusable `MonotonicDeadline` boundary for inactivity only. No dissolve, reply-playback, or linger deadline exists yet; do not claim animation-deadline coverage in this slice.
- [ ] Re-run targeted tests and commit only clock/lifecycle changes.

## R2a: Typed discovery SPI and both adapter contracts

**Files:** provider SPI/domain types, OpenAI/DeepSeek adapters, shared contract suite, `doc/adr/` Provider discovery ADR.

- [ ] Add failing shared tests for both adapters: authenticated success, case-sensitive first-wins dedupe, locale-independent sort, blank ID rejection, empty catalog, unsupported endpoint, 401/403/429/5xx/network/timeout/malformed/cancellation, resource cleanup, and every 2 MiB/2,000-entry/256-byte-ID/512-byte-name/64 KiB-error limit boundary plus one-over-limit typed failure.
- [ ] Run `./gradlew :model-provider:testDebugUnitTest --tests '*ModelDiscoveryContract*'`; RED is the ambiguous `Result<List<ModelDescriptor>>` contract.
- [ ] Introduce the sealed `ModelDiscoveryResult` from the specification and update both adapters. Do not branch in UI/domain on Provider names.
- [ ] Write the required Provider-SPI ADR including compatibility/migration rationale.
- [ ] Re-run the contract plus `:model-provider:lintDebug`; commit only SPI/adapters/ADR.

## R2b: Crash-safe candidate credential protocol

**Files:** credential/profile transaction coordinator, journal storage, startup composition, security/settings unit tests.

- [ ] Add table-driven failing tests at every journal boundary: before/after candidate write, journal write, discovery, profile commit, selection, prior-alias deletion, and journal deletion; cover new profile, edited profile with new secret, metadata-only edit, cancellation, abandonment, and process restart.
- [ ] Add sentinel tests proving no key enters StateFlow, SavedState, profile storage, logs, or journal.
- [ ] Run `./gradlew :security:testDebugUnitTest :feature-settings:testDebugUnitTest --tests '*CredentialTransaction*'`; RED is the missing journal protocol.
- [ ] Implement the specification's idempotent candidate-alias protocol. A durable profile referencing the candidate is the commit point; never delete a referenced alias.
- [ ] Re-run targeted tests and commit only security/transaction files.

## R2c: Validation-first responsive Provider screen

**Files:** `ProviderSettingsViewModel.kt`, `ProviderSettingsScreen.kt`, `MainActivity.kt`, resources, ViewModel/Compose tests.

- [ ] Add failing tests for: confirmed HTTPS host; discovery before persistence; typed errors; no selectable profile before validation/model selection; explicit unsupported/empty manual fallback; exact one `ping`/one-token/10-second/no-retry/no-context request; stable selector order; narrow/large-font layout; and Back cleanup.
- [ ] Add failing privacy tests for a non-saveable local password buffer, clearing on acceptance/dispose/background/navigation, disabled copy/cut/autofill/suggestions, and `FLAG_SECURE` only while credential settings is visible.
- [ ] Run `./gradlew :feature-settings:testDebugUnitTest :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=dev.riddle.magicpaper.ProviderSetupTest`; RED is save-before-validate/free-text model behavior.
- [ ] Implement one responsive three-stage screen. Discovery success avoids a completion request; only explicit unsupported/empty fallback may use the disclosed minimum request.
- [ ] Re-run targeted tests, `:feature-settings:lintDebug`, and secret sentinel scans; commit only editor/UI files.

## R3: Shared ML Kit recognition prerequisite provisioning

**Files:** recognition provisioning port/coordinator, `MlKitHandwritingRecognizer.kt`, router/ViewModel wiring, tests.

- [ ] Replace the old “missing model fails immediately” expectation with failing tests for installed, missing→download→recognize, concurrent same-key waiters, one-waiter cancellation, final-waiter cancellation/late completion, process restart installed recheck, locale/version separation, failure, and no Provider request on failure.
- [ ] Run `./gradlew :conversation:testDebugUnitTest --tests '*MlKitHandwritingRecognizerTest*' --tests '*RecognitionProvisioningTest*'`; RED is no download coordinator.
- [ ] Implement application-scope shared work exactly as specified; never block the main thread or infer behavior from Provider/model names.
- [ ] Add localized visible preparation/recognition/failure states while retaining source ink.
- [ ] Recognition provisioning adds no animation deadline. The later dissolve and reply/linger slices must reuse R1c `MonotonicDeadline`; do not add an independent wall-clock timer here.
- [ ] Re-run conversation/feature-paper tests and commit only recognition slice files.

## R4a: Real 14-stage input dissolve

**Files:** render model/cache/View/ViewModel and dissolve tests.

- [ ] Add failing stage tests observing exactly 0..13, retaining strokes through stage 13, and clearing after it; add bitmap tests for monotonically decreasing nontransparent coverage and failure/cancellation restoration.
- [ ] Before implementation, add RED tests for dissolve deadlines covering wall-clock forward/backward jumps, elapsed-time just-before/at-boundary behavior, cancellation, and recreation recovery. Every dissolve wait must reuse R1c `MonotonicDeadline`.
- [ ] Run `./gradlew :paper-engine:testDebugUnitTest :feature-paper:testDebugUnitTest --tests '*Dissolve*'`; RED is the current static stage-0 empty model.
- [ ] Implement generation-scoped staged dissolve. Normal delay is controlled by fake clock; reduced motion emits all stages with zero inter-stage delay.
- [ ] Re-run tests and commit only dissolve files.

## R4b: Commit-time raster geometry

**Files:** page geometry model/source, rasterizer, ViewModel/container wiring, property/instrumentation tests.

- [ ] Add failing portrait→landscape, split-size, fold/wide, and recreation tests proving a page uses geometry captured at commit, not container construction.
- [ ] Run `./gradlew :paper-engine:testDebugUnitTest --tests '*PageRasterizer*' :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=dev.riddle.magicpaper.PageGeometryTest`; RED is stale display metrics.
- [ ] Supply normalized safe page aspect/bounds per commit and keep View caches render-only.
- [ ] Re-run tests and commit only geometry files.

## R5a: Pinned fonts, Unicode graphemes, and pagination

**Files:** existing Dancing Script/OFL, official LXGW WenKai Regular v1.522/OFL, font manifest/checksum, Unicode segmenter, `ReplyStrokePlanner.kt`, pure tests.

- [ ] Inspect official v1.522 font and Unicode 15.0.0 conformance sources outside the worktree; record intended URLs/licenses but do not add assets yet.
- [ ] Add failing tests for absent pinned font/checksum/OFL and absent pinned Unicode corpus/license/checksum, plus Latin, CJK, combining marks, variation selectors, ZWJ emoji, RTL paragraphs, unsupported code points, delta splits inside clusters, wrapping, and multi-page no-gap/no-overlap source ranges. The conformance test must enumerate every official `GraphemeBreakTest.txt` case. API 33/36 golden output compares normalized metadata, not antialiased bitmap bytes.
- [ ] Run `./gradlew :paper-engine:testDebugUnitTest --tests '*ReplyStrokePlanner*'`; RED is the missing planner/segmenter.
- [ ] During GREEN, download only the unmodified official LXGW WenKai Regular v1.522 TTF and Unicode 15.0.0 `GraphemeBreakTest.txt`; record URLs/SHA-256, verify internal font name/version, and include their OFL/Unicode licenses. Do not subset/rename/modify the font.
- [ ] Port rasterize/thin/trace concepts from `src/script.rs`; use internal pinned Unicode-15.0 grapheme rules. Dancing Script serves supported Latin, LXGW serves supported CJK, and one labeled boxed rune represents each unsupported cluster while exact source text remains accessible.
- [ ] Plan pages from current safe bounds without discarding text; first-wins append planning never replays completed graphemes.
- [ ] Re-run pure tests, verify font checksum/license tasks, and commit assets/planner/license evidence only.

## R5b: Reply playback, page lifecycle, and recovery

**Files:** render model/View/ViewModel/screen; memory Room v2 entity/DAO/database/schema/migration/repository; interrupted-run mapping; playback/lifecycle/unit/migration tests.

- [ ] Before implementation, add RED tests for reply-playback and linger deadlines covering wall-clock forward/backward jumps, elapsed-time just-before/at-boundary behavior, cancellation, configuration recreation, and process recovery. Every playback/linger wait must reuse R1c `MonotonicDeadline`.

- [ ] Add failing tests proving the first delta creates View pixels before completion; later deltas preserve cursor/pixels; pages write→linger 4–20 s→stages 0..9→next page; final page returns Listening; new writing/cancel rejects late events.
- [ ] Add failing recreation tests during input dissolve, reply playback, and linger. Add process-death tests proving no request replay, draft restoration, interrupted state, and non-animated partial reply strokes.
- [ ] Add a failing populated Room v1→v2 migration test that preserves existing rows and creates the interrupted-run table; add transactional write/recovery tests for stable run/page ID, draft reference, partial reply source text, interrupted status, and timestamp.
- [ ] Run `./gradlew :paper-engine:testDebugUnitTest :feature-paper:testDebugUnitTest --tests '*Reply*' --tests '*Recreation*' :memory:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=dev.riddle.magicpaper.memory.MigrationV1ToV2Test`; RED includes the missing playback lifecycle and missing Room v2 migration/recovery schema.
- [ ] Render reply strokes/cursor in `MagicPaperView`; remove visible Compose reply text only after pixel tests pass, retaining one exact accessibility-text node per page.
- [ ] Implement reduced motion as immediate complete-page rendering with normal 4-second minimum linger and announcements.
- [ ] Implement Room schema v2 and exported schema/migration. Write the ACTIVE recovery row before exposing submitted/partial-reply render state; startup atomically marks it INTERRUPTED; clean completion/cancellation resolves it only after durable terminal state. Do not persist render paths, active coroutine generation, cursor, or credentials.
- [ ] Re-run the exact unit and `:memory:connectedDebugAndroidTest` migration command, then commit only playback/recovery/migration files.

## R6: Honest boundary-device verification and review closure

**Files:** instrumentation, fake composition hooks, specs, detailed design, README, progress ledger.

- [ ] Replace stage-non-null/Compose-text assertions with exact stage sequences, rendered pixel progress, pagination source ranges, and final cleanup.
- [ ] Use a non-vision fake path: touch → 2.8 s inactivity → recognition provisioning/recognition → Provider request → multiple split deltas → reply pages/pixels → linger/dissolve → Listening.
- [ ] Verify rune entry, Provider setup, rotation/current geometry, active-animation recreation, process interruption, cancel/write interruption, safe/IME insets, reduced motion, and sentinel secrecy.
- [ ] Ensure only `Riddle_API_33` is booted; clear app data; set `ANDROID_SERIAL`; run `./gradlew :app:connectedDebugAndroidTest --rerun-tasks`; archive XML and sorted test names/count/failure/error/skip totals.
- [ ] Repeat with only `Riddle_API_36_1` booted. Diff sorted test names; require identical inventory and zero failures/errors/skips on both.
- [ ] Generate a 30-day RSA-3072 keystore under `C:\tmp` with a random in-memory password; pass store/key paths, alias, and passwords only through environment-backed Gradle properties read by release signing configuration. Run `./gradlew test lint assembleDebug assembleRelease` without printing secrets.
- [ ] Run SDK `apksigner verify --verbose --print-certs <apk>` and `apkanalyzer manifest application-id|min-sdk|target-sdk <apk>`; require one signer, package `dev.riddle.magicpaper`, min 33, target 36. Delete the temporary keystore/password variables after verification and scan source/logs/artifacts/database/SavedState for sentinel secrets.
- [ ] Mark the child specification implemented only after all evidence passes. Update parent traceability/detailed design and request independent specification/code-quality review; any critical/important finding reopens its slice.

Temporary release verification uses environment names consumed by the app signing block only when all are present:

```powershell
$password = [Guid]::NewGuid().ToString('N')
$keystore = 'C:\tmp\riddle-release-preview.p12'
keytool -genkeypair -storetype PKCS12 -keystore $keystore -alias riddle-preview -keyalg RSA -keysize 3072 -validity 30 -dname 'CN=Riddle Local Preview' -storepass $password -keypass $password
$env:RIDDLE_RELEASE_STORE_FILE = $keystore
$env:RIDDLE_RELEASE_STORE_PASSWORD = $password
$env:RIDDLE_RELEASE_KEY_ALIAS = 'riddle-preview'
$env:RIDDLE_RELEASE_KEY_PASSWORD = $password
./gradlew assembleRelease
$apk = 'app\build\outputs\apk\release\app-release.apk'
& "$env:ANDROID_HOME\build-tools\36.1.0\apksigner.bat" verify --verbose --print-certs $apk
apkanalyzer manifest application-id $apk
apkanalyzer manifest min-sdk $apk
apkanalyzer manifest target-sdk $apk
Remove-Item Env:RIDDLE_RELEASE_STORE_FILE,Env:RIDDLE_RELEASE_STORE_PASSWORD,Env:RIDDLE_RELEASE_KEY_ALIAS,Env:RIDDLE_RELEASE_KEY_PASSWORD
Remove-Item -LiteralPath $keystore -Force
$password = $null
```

No completion claim may rely on stale XML, an up-to-date task without current inputs, visible Compose reply text, phase labels without corresponding pixels, or API 36-only evidence.
