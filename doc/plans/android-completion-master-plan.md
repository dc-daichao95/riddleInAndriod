# Android Magic Paper Completion Master Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use `superpowers:subagent-driven-development` to execute this plan task-by-task with specification and code-quality review after every task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Status:** authoritative execution plan updated 2026-07-16. Tasks 1–2 are complete and independently approved; resume at Task 3. The three older Android plans remain historical design and RED/GREEN evidence.

**Goal:** Finish one Android 13/API 33 through Android 16/API 36 Magic Paper application with validated multi-provider configuration, Chinese handwriting and same-language replies, immersive magic controls, reply stroke playback, adaptive branding, and a verified signed Release APK.

**Architecture:** Keep provider JSON, language policy, recognition, playback, persistence, and UI rendering behind their existing module boundaries. `PaperViewModel` owns one generation and immutable render state, `MagicPaperView` owns user/reply pixels, Room persists recovery metadata, and Compose owns controls, settings, localization, and accessibility semantics.

**Tech Stack:** Kotlin, Gradle Kotlin DSL, compile SDK 36.1, target SDK 36, min SDK 33, Jetpack Compose/Material 3, Android View/Canvas, coroutines/Flow, Room v2, ML Kit Digital Ink Recognition, OkHttp, JUnit/Robolectric/instrumentation.

## Global Constraints

- Preserve the Rust implementation and keep all Android work under `android-app/`.
- Preserve identical product behavior on API 33 and API 36; do not add a reduced Android 13 mode.
- Keep provider-neutral domain/UI contracts; provider-specific differences stay inside adapters and capability metadata.
- Never persist or log raw API keys, authorization headers, complete prompts, complete responses, or user ink.
- Do not use a production `FakeModelProvider`; it remains available only through explicit test/preview injection.
- Every behavior change follows RED → GREEN → Refactor → Verify → independent review → scoped commit.
- Do not stage pre-existing user changes in `.superpowers/sdd/task-3-report.md`, `README.md`, `doc/TODO.md`, or `doc/detailed-design.md` unless the user explicitly approves that exact diff.
- Tool-capable Agent execution remains Phase 2 and is not part of this release.

## Approved specifications

- `doc/specs/android-magic-paper-app.md`
- `doc/specs/magic-paper-ux-provider-text-pipeline.md`
- `doc/specs/magic-paper-controls-language-repair.md`

## Completed baseline (do not reimplement)

| Slice | Result | Evidence |
|---|---|---|
| Foundation Tasks 1–8 | API 36.1 scaffold, paper engine, conversation, providers, credentials, recognition routing, Room v1 | `161f78e` through `d7602e3` |
| App integration and repair baseline | Immersive app, real conversation pipeline, Android 13 configuration, magic settings rune | `48c7423`, `95724e6`, `78ca98c`, `878db09`, `b32fce4`, `27c936b` |
| Provider setup foundations | Typed discovery, crash-safe credential transaction, validation-first setup | `ad185e2`, `103a5bf`, `d2705e2` |
| Input pipeline | Shared recognition provisioning, 14-stage dissolve, commit-time geometry | `eafd4da`, `ac7c888`, `3affbe9` |
| Reply planning/playback core | Pinned fonts/Unicode planning and provider-neutral playback state machine | `8506746`, `678e226` |
| Recovery persistence | Room v2 active-run persistence, v1→v2 migration, interrupted no-replay recovery | `e900ca2` |
| Repair specification | Provider/null/language/controls/reply requirements approved | `c1e2e1a` |
| Provider/null setup repair | Null-safe protocol, deterministic catalog/manual model selection | `147120f`, `ac344d3` |
| Language/configuration repair | Chinese routing, alternate-language priority, valid-model gate, large-font settings | `09fda56`, `2cf0a88` |

## File map for remaining work

- `android-app/app/.../AppContainer.kt`: Room migration registration and recovery composition.
- `android-app/feature-paper/.../PaperViewModel.kt`: generation lock, explicit send/cancel, playback lifecycle, durable recovery coordination.
- `android-app/feature-paper/.../MagicPaperScreen.kt`: magic controls and accessibility semantics.
- `android-app/paper-engine/.../MagicPaperView.kt`: immutable reply snapshot, cached stroke/cursor rendering, magical dissolve particles.
- `android-app/app/src/main/res/mipmap-*` and `drawable-*`: adaptive launcher icon derived from the original asset.
- `android-app/app/src/androidTest/...`: end-to-end API 33/API 36 behavior and rendering tests.

---

### Task 1: Null-safe provider protocol and deterministic model selection

**Files:** `ProviderJson.kt`, `AppContainer.kt`, `ProviderSettingsViewModel.kt`, `ProviderSettingsScreen.kt`, their unit tests, and `ProviderSetupTest.kt`.

**Interfaces:** Consume `ModelDiscoveryResult` and existing profile storage. Produce a profile whose `defaultModelId: String?` is never literal `"null"`, plus UI state that distinguishes catalog selection from disclosed manual fallback.

- [x] Add Android/JVM parser and profile fixtures; reproduce and repair JSON null at nullable boundaries.
- [x] Add catalog/manual-fallback tests, preserve preset, explain fallback, and require validation before save.
- [x] Implement explicit catalog/manual-fallback UI states without provider-name branching or save-before-validation.
- [x] Run 111 JVM tests, instrumentation compilation, and API 36 `ProviderSetupTest` 6/6.
- [x] Independently review and commit Task 1 as `147120f` + `ac344d3`.

### Task 2: Chinese handwriting and same-language provider requests

**Files:** create `HandwritingLanguage.kt` and `ResponseLanguagePolicy.kt`; modify `MlKitHandwritingRecognizer.kt`, settings, `PaperViewModel.kt`, `AppContainer.kt`, resources, and tests.

**Interfaces:** Produce `HandwritingLanguage { AUTOMATIC, SIMPLIFIED_CHINESE, TRADITIONAL_CHINESE, ENGLISH }`, canonical tags `zh-Hans`, `zh-Hant`, `en-US`, and a provider-neutral same-language instruction.

- [x] Add RED canonicalization, exact recognized-text, language routing and missing-provider tests.
- [x] Implement stable language preference, ML Kit routing, provider-neutral text/vision policy, and remove production fake fallback.
- [x] Add RED review fixes for explicit alternate-language priority, null/blank selected model, and 200% font short-window reachability.
- [x] Restore the Task 2 boundary by reverting the out-of-scope rune motion initial behavior.
- [x] Run 185 JVM tests, instrumentation compilation, API 36 `MagicRuneSettingsTest` 8/8, and `lintDebug`.
- [x] Independently review and commit Task 2/2R as `09fda56` + `2cf0a88`; no Critical/Important/Minor findings remain.

### Task 3: Production reply stroke rendering and Room recovery integration

**Files:** `AppContainer.kt`, `PaperViewModel.kt`, `MagicPaperScreen.kt`, `MagicPaperView.kt`, playback/lifecycle/migration/flow tests.

**Interfaces:** Consume committed `ReplyStrokePlanner`, `ReplyPlayback`, `MonotonicDeadline`, and Room v2 APIs. Produce immutable reply snapshots with exact accessibility page text and generation-scoped lifecycle events.

- [ ] Register `RiddleDatabase.MIGRATION_1_2` in every production database builder and add app-composition upgrade-open regression.
- [ ] Add Canvas RED test: prefix A creates pixels before completion; AB preserves A pixels and adds pixels/cursor without reset.
- [ ] Add ViewModel RED tests for split deltas, append, finalization, 4–20 s monotonic linger, stages 0..9, page queue, final Listening, stale-generation rejection, and reduced motion.
- [ ] Add recreation/process RED tests: retained cursor/deadline resumes; process recovery renders partial text non-animated, marks ACTIVE→INTERRUPTED, restores draft, and sends zero requests.
- [ ] Run paper/feature/memory focused suites; expect new integration tests to fail while committed core/schema tests remain green.
- [ ] Wire immutable render state, planner/playback/deadlines, and durable ACTIVE/partial/terminal updates before UI exposure.
- [ ] Cache reply rendering in `MagicPaperView`; remove visible Compose reply text only after pixel tests pass; expose exactly one exact-text accessibility node per page.
- [ ] Run focused suites, lints, memory migration instrumentation, and `MagicPaperFlowTest`; independently review and commit as `feat(android): render and recover magic replies`.

### Task 4: Immersive magic send/tool controls and star-dust dissolve

**Files:** `PaperViewModel.kt`, `MagicPaperScreen.kt`, `MagicPaperView.kt`, localized strings, reducer/ViewModel/Compose/bitmap/instrumentation tests.

**Interfaces:** Produce typed Send/Cancel/SelectPen/SelectEraser intents, one generation-scoped writable lock, and deterministic particles derived from existing dissolve generation/stage/position.

- [ ] Add RED tests: explicit send starts one generation and cancels inactivity duplicate; active phases reject drawing/erasing; cancellation restores authoritative draft; late events cannot unlock a newer generation.
- [ ] Add RED UI tests for safe-inset 48 dp targets, send/cancel state, expandable pen/eraser chooser, selected semantics, TalkBack, keyboard, compact width, and 200% font scale.
- [ ] Add RED particle tests for bounded count, deterministic stage positions, monotonic source coverage, no stale final particles, and reduced-motion behavior.
- [ ] Run paper-engine and feature-paper tests; expect failures for missing intents/tool mode/particle layer.
- [ ] Implement send/cancel rune and input lock from accepted send through Completed/Cancelled/Failed, keeping cancel/settings reachable.
- [ ] Implement configurable magic tool entry with pen and eraser modes; keep alternate future gesture/entry configuration possible.
- [ ] Render cached amber star-dust at the dissolving edge on the existing 14-stage timeline without per-particle rerasterization.
- [ ] Run tests/lints/app compile; independently review and commit as `feat(android): add immersive magic paper controls`.

### Task 5: Original icon adaptation and repository branding

**Files:** source `android-app/app/src/main/assets/branding/riddle-icon-original.png`; adaptive launcher resources; GitHub variants under `doc/assets/branding/`.

**Interfaces:** Derive every asset from the original abstract paper/question-mark/amber-stars artwork; no third-party logos, text, or copyrighted characters.

- [ ] Create adaptive foreground/background/monochrome resources inside the safe zone with no chroma-key fringe.
- [ ] Add a resource regression that resolves launcher/round icons for API 33 and API 36.
- [ ] Generate square GitHub avatar and 1280×640 social preview variants and document their original provenance.
- [ ] Inspect 48 px and adaptive-mask rendering; require recognizable mark and no clipped stars.
- [ ] Run app resource processing/lint/assembleDebug; review and commit branding only as `feat(android): add original Riddle branding`.

### Task 6: API 33/API 36 parity, Release APK, and specification closure

**Files:** Android README/specifications/traceability only after evidence passes. Signing key stays temporary outside source control.

**Interfaces:** Produce reproducible evidence and signed package `dev.riddle.magicpaper`, min SDK 33, target SDK 36.

- [ ] Run `./gradlew test lint assembleDebug assembleRelease --rerun-tasks`; require exit 0 with no ignored failure.
- [ ] Boot only `Riddle_API_33`, clear data, install, run connected tests, and archive XML plus sorted inventory/counts.
- [ ] Repeat on only `Riddle_API_36_1`; require identical test names and zero failures/errors/skips on both.
- [ ] Smoke-test provider/model setup, Chinese text-only flow, no visible null, controls/lock/cancel, pen/eraser, fades, reply strokes, portrait lock/recreation/reduced motion/settings rune.
- [ ] Generate a 30-day RSA-3072 key under `C:\tmp`, pass values via environment-backed Gradle properties without printing secrets, and rebuild Release.
- [ ] Verify with `apksigner` and `apkanalyzer`: one signer, package `dev.riddle.magicpaper`, min 33, target 36.
- [ ] Install/launch signed Release on Android 16, retain absolute APK path, scan for secrets, then delete temporary key/password variables.
- [ ] Mark specs implemented, update traceability/TODO/README, request final independent review, and commit documentation as `docs(android): close magic paper release verification`.

## Execution order and self-review

Resume strictly at Task 3, then execute Task 4 → 5 → 6. Tasks 1–2 are complete. Remaining tasks require observed RED/GREEN evidence and two-stage independent review. Every approved repair requirement maps to a task; process death, no replay, cancellation, privacy, localization, accessibility, reduced motion, and API parity have explicit gates. Phase 2 tool-capable Agent work remains deferred.
