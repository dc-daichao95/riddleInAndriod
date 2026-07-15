# Magic Paper Controls and Language Repair Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development. Steps use checkbox syntax for tracking.

**Goal:** Repair provider setup/null parsing, preserve Chinese handwriting/response language, and complete immersive send/tool/dissolve/reply playback behavior.

**Architecture:** Normalize protocol and language decisions below UI, keep feature ViewModels as generation owners, and render immutable ink snapshots in `MagicPaperView`. Land four vertical commits so each defect class has RED/GREEN evidence and independent review.

**Tech Stack:** Kotlin, Jetpack Compose, Android View/Canvas, coroutines/Flow, Room v2, ML Kit handwriting, JUnit/Robolectric/Android instrumentation.

## Global Constraints

- Android minSdk 33, targetSdk 36, compile SDK 36.1.
- No new runtime dependencies or vendor-name branching.
- No live provider keys in tests/logs/source.
- Preserve existing user modifications to `.superpowers/sdd/task-3-report.md`, `README.md`, `doc/TODO.md`, and `doc/detailed-design.md` unless explicitly updating the additive TODO tracking section.
- Every behavior change uses observed RED, minimal GREEN, refactor, full module gate and independent review.

---

### Task 1: Provider discovery fallback and null-safe protocol

**Files:**
- Modify: `android-app/model-provider/src/main/kotlin/dev/riddle/magicpaper/provider/ProviderJson.kt`
- Modify: `android-app/app/src/main/kotlin/dev/riddle/magicpaper/AppContainer.kt`
- Modify: `android-app/feature-settings/src/main/kotlin/dev/riddle/magicpaper/settings/ProviderSettingsViewModel.kt`
- Modify: `android-app/feature-settings/src/main/kotlin/dev/riddle/magicpaper/settings/ProviderSettingsScreen.kt`
- Tests: provider contract, profile round-trip, settings ViewModel and Compose instrumentation tests.

**Interfaces:**
- Consume existing `ModelDiscoveryResult` and candidate credential journal.
- Produce typed discovery state with `discoveredModels`, `manualFallbackReason`, and preserved `defaultModelId`.

- [ ] Add fixtures where `content`, `reasoning_content`, tool name/arguments and persisted model are JSON null; assert no literal `"null"` event/value.
- [ ] Run focused provider/profile tests; expect failure containing `TextDelta(text=null)` or model `"null"`.
- [ ] Replace `optString` presence checks with explicit JSON-null-safe access and nullable profile encoding/decoding.
- [ ] Add settings RED tests for successful selector and unsupported/empty catalog preserving preset manual model.
- [ ] Implement visible typed fallback and minimum completion validation without saving before success.
- [ ] Run `:model-provider:testDebugUnitTest :feature-settings:testDebugUnitTest :app:compileDebugAndroidTestKotlin` and relevant provider setup instrumentation.
- [ ] Independently review and commit only Task 1 files.

### Task 2: Handwriting language and same-language response

**Files:**
- Modify/create language policy types in `android-app/conversation/src/main/kotlin/dev/riddle/magicpaper/conversation/`.
- Modify: `android-app/feature-settings/.../ProviderSettingsViewModel.kt` and screen/resources for preference selection.
- Modify: `android-app/feature-paper/src/main/kotlin/dev/riddle/magicpaper/paperui/PaperViewModel.kt`.
- Modify: `android-app/app/src/main/kotlin/dev/riddle/magicpaper/AppContainer.kt` composition/persistence.
- Tests: conversation policy, ML Kit routing, settings persistence, paper pipeline request capture.

**Interfaces:**

```kotlin
enum class HandwritingLanguage { AUTOMATIC, SIMPLIFIED_CHINESE, TRADITIONAL_CHINESE, ENGLISH }
interface HandwritingLanguagePreference {
    val language: Flow<HandwritingLanguage>
    suspend fun setLanguage(value: HandwritingLanguage)
}
fun HandwritingLanguage.resolve(system: Locale): Locale
```

- [ ] Add RED canonicalization tests for zh-CN/SG→zh-Hans, zh-TW/HK/MO→zh-Hant, explicit override under Locale.US, and unknown persisted value→AUTOMATIC.
- [ ] Implement the enum/policy/preference with stable ASCII persistence and English/Chinese UI resources.
- [ ] Add RED pipeline test: recognizer returns `你好`, captured `ModelRequest` preserves it exactly and contains provider-neutral same-language instruction.
- [ ] Implement text and vision request language policy; remove hard-coded English-only vision instruction.
- [ ] Add RED test for no selected provider: typed configuration-required state and zero fake/recognizer/network calls.
- [ ] Remove production fake fallback from selection composition while retaining explicit test/preview injection.
- [ ] Run conversation, feature-settings, feature-paper unit tests and app compilation; independently review and commit Task 2 files.

### Task 3: Send/tool controls and magical dissolve

**Files:**
- Modify: `PaperRenderModel.kt`, `MagicPaperScreen.kt`, `PaperViewModel.kt`, `MagicPaperView.kt`, `InkBitmapCache.kt`/dissolve primitive and resources.
- Tests: feature-paper ViewModel, Compose instrumentation, paper-engine pixel/cache tests.

**Interfaces:**

```kotlin
enum class PaperToolSelection { PEN, ERASER }
data object SendDraft : PaperUiIntent
data class SelectTool(val tool: PaperToolSelection) : PaperUiIntent
```

- [ ] Add RED tests: send starts one generation and cancels inactivity duplicate; active phases reject draw/erase; cancel restores writable authoritative state.
- [ ] Implement safe-inset send rune, cancel state and generation-scoped input lock.
- [ ] Add RED Compose tests for 48 dp send/tool targets, labels, selected state, pen/eraser behavior and keyboard/TalkBack semantics.
- [ ] Implement expandable magical tool chooser without multi-finger gestures.
- [ ] Add RED deterministic particle tests asserting bounded count, stage-derived coordinates, monotonic source coverage, no stale final particles and no reduced-motion motion.
- [ ] Implement a cached star-dust edge layer derived from existing dissolve stage/position/generation; do not rerasterize source per particle.
- [ ] Run paper-engine/feature-paper tests, lint and app compile; independently review and commit Task 3 files.

### Task 4: Production reply stroke playback and recovery integration

**Files:**
- Modify: `PaperRenderModel.kt`, `MagicPaperView.kt`, `PaperViewModel.kt`, `MagicPaperScreen.kt`, `AppContainer.kt`.
- Reuse: committed `ReplyStrokePlanner`, `ReplyPlayback`, Room v2 active run and `MIGRATION_1_2`.
- Tests: Canvas pixels, ViewModel deadlines, process/config recovery, Compose accessibility, API 33/36 instrumentation.

**Interfaces:**

```kotlin
data class ReplyRenderSnapshot(
    val page: ReplyPage,
    val visibleGlyphCount: Int,
    val dissolveStage: Int?,
    val accessibilityText: String,
)
```

- [ ] Register `MIGRATION_1_2` in app composition and add upgrade-open regression.
- [ ] Add RED Canvas test where first/second visible glyph counts increase reply pixels while the written prefix stays identical.
- [ ] Extend render model/View with immutable reply snapshot and cached reply bitmap/dissolve rendering.
- [ ] Add RED ViewModel tests for first delta before completion, A→AB append without reset, stream finalize, 4–20 s monotonic linger, stages 0..9, queued pages and final Listening.
- [ ] Wire planner/playback/deadlines with generation checks and durable ACTIVE/partial updates before exposure.
- [ ] Add RED recreation/process tests: retained cursor/deadline resumes; process recovery replans partial reply non-animated, marks interrupted and performs zero network requests.
- [ ] Remove visible Compose reply text only after pixel tests pass; expose one exact accessibility node per page.
- [ ] Run paper-engine, feature-paper, memory and app gates plus identical API 33/API 36 instrumentation inventory; independently review and commit Task 4 files.

### Task 5: Icon adaptation, release verification, and documentation

**Files:**
- Use master: `android-app/app/src/main/assets/branding/riddle-icon-original.png`.
- Create Android adaptive icon resources and GitHub icon/social-preview variants.
- Update approved specs/README/progress only after all gates.

- [ ] Derive foreground/background assets from the original abstract mark; no third-party logos/text.
- [ ] Verify adaptive-icon safe zone, 48 px legibility and launcher rendering on API 33/36.
- [ ] Run `test lint assembleDebug assembleRelease`, sign with temporary local RSA-3072 key outside source control, verify manifest/signature, install/launch on API 36.
- [ ] Run identical connected inventory on API 33 and API 36 with zero failures/errors/skips.
- [ ] Mark specifications implemented, update traceability/TODO, perform final whole-branch review, and commit remaining approved files without staging unrelated user changes.

## Self-review

- All MCL requirements map to Tasks 1–5.
- No task introduces a new runtime library or vendor branch.
- The production fake-provider removal precedes language/UI integration.
- Visible Compose reply removal is gated by Canvas pixel evidence.
- Room migration registration is explicitly included before release.
- No placeholder/TBD step remains.
