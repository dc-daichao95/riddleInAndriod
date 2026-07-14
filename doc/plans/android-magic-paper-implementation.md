# Android Magic Paper App Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build one full-feature Android 13 through Android 16 application that preserves Riddle's full-screen enchanted-paper handwriting experience, supports responsive phone/foldable/tablet layouts, and streams replies from OpenAI-compatible and DeepSeek-compatible model services.

**Architecture:** Keep the existing Rust app untouched and add a modular Kotlin project under `android-app/`. Compose owns navigation/settings/accessibility; a custom `MagicPaperView` owns low-latency input and Canvas animation; pure Kotlin state machines coordinate page submission, OCR/vision routing, Provider streams, and Room memory behind provider-neutral contracts.

**Tech Stack:** Android Studio 2026.1.1, Android Studio JBR 21, Gradle 9.4.1, Android Gradle Plugin 9.2.1 with built-in Kotlin, Android compile platform API 36.1, `minSdk = 33`, `targetSdk = 36`, Compose BOM 2025.06.01, Material 3, coroutines 1.10.2, lifecycle 2.9.1, Room 2.7.2, OkHttp 4.12.0, kotlinx-serialization 1.8.1, ML Kit Digital Ink Recognition 18.1.0, JUnit 4.13.2, Turbine 1.2.1, Robolectric 4.14.1.

## Global Constraints

- Android 13 through Android 16: `targetSdk = 36`, `minSdk = 33`; compile against API 36.1 with the AGP minor-API DSL.
- Ship one APK with identical features on API 33 and API 36; no reduced Android 13 mode or version-specific test exclusions.
- Keep compatibility changes minimal: adjust build configuration first and add a local API guard only when a failing API 33 gate proves it necessary.
- Preserve the Rust implementation and root Cargo files unchanged.
- Use `dev.riddle.magicpaper` as the initial namespace/application ID and `Riddle` as the display name.
- No production behavior without a failing test first; configuration/generated wrapper files are the only scaffolding exception.
- Do not call live paid Providers from normal tests or CI.
- Do not store API keys in Room, logs, saved state, resources, or source control.
- Do not execute model tool calls in phase 1.
- Do not silently fall back across Providers.
- Keep fake-Provider end-to-end behavior runnable after every vertical slice.

---

## File map

```text
android-app/
├── settings.gradle.kts                 # module registry and repositories
├── build.gradle.kts                    # root plugins
├── gradle/libs.versions.toml            # pinned versions
├── gradle.properties                    # Android/Gradle settings
├── app/                                 # application, activity, composition root
├── core-model/                          # pure domain models and Provider SPI
├── paper-engine/                        # transforms, gestures, custom Canvas View
├── conversation/                        # testable state machine and orchestration
├── model-provider/                      # transport, SSE, JSON and adapters
├── security/                            # Keystore credential store and redaction
├── memory/                              # Room persistence and repositories
├── feature-paper/                       # immersive paper Compose host/ViewModel
└── feature-settings/                    # Provider/memory/orientation settings
```

## Task 1: Android API 36.1 build scaffold and domain contracts

**Files:**
- Create: `android-app/settings.gradle.kts`
- Create: `android-app/build.gradle.kts`
- Create: `android-app/gradle/libs.versions.toml`
- Create: `android-app/gradle.properties`
- Create: `android-app/app/build.gradle.kts`
- Create: `android-app/core-model/build.gradle.kts`
- Create: `android-app/core-model/src/test/kotlin/dev/riddle/magicpaper/model/ModelContractTest.kt`
- Create: `android-app/core-model/src/main/kotlin/dev/riddle/magicpaper/model/PaperModels.kt`
- Create: `android-app/core-model/src/main/kotlin/dev/riddle/magicpaper/model/ProviderModels.kt`
- Create: `android-app/core-model/src/main/kotlin/dev/riddle/magicpaper/model/ModelProvider.kt`
- Create: `android-app/README.md`

**Interfaces:**
- Produces: `NormalizedPoint`, `PaperStroke`, `PaperTool`, `ProviderConfiguration`, `ModelCapabilities`, `ModelRequest`, `ModelEvent`, `ModelProvider`.
- Consumes: none.

- [ ] **Step 1: Install or locate the required toolchain**

Install Android Studio 2026.1.1 with API 36.1, Build Tools 36.1.0, platform-tools, Command-line Tools 21.0, emulator, and the bundled JBR 21. Set `ANDROID_HOME` and `JAVA_HOME`; do not commit machine paths.

Run:

```powershell
java -version
adb version
sdkmanager --list_installed
```

Expected: Java is available, `platforms;android-36.1` is listed, and `Riddle_API_36_1` appears in `emulator -list-avds`. If this gate fails, stop implementation and report the missing toolchain.

- [ ] **Step 2: Create Gradle configuration and wrapper**

Use the pinned versions in the header. Register modules `:app`, `:core-model`, `:paper-engine`, `:conversation`, `:model-provider`, `:security`, `:memory`, `:feature-paper`, and `:feature-settings`. Configure Google/Maven Central repositories and `FAIL_ON_PROJECT_REPOS`.

Run from `android-app/`:

```powershell
gradle wrapper --gradle-version 9.4.1
./gradlew --version
```

Expected: wrapper reports Gradle 9.4.1. Android modules configure API 36.1 with `compileSdk { version = release(36) { minorApiLevel = 1 } }`, while `minSdk` and `targetSdk` remain 36.

- [ ] **Step 3: Write the failing domain-contract test**

```kotlin
class ModelContractTest {
    @Test fun `normalized point rejects coordinates outside the page`() {
        assertFailsWith<IllegalArgumentException> { NormalizedPoint(-0.01f, 0.5f, 0.1f) }
        assertFailsWith<IllegalArgumentException> { NormalizedPoint(0.5f, 1.01f, 0.1f) }
    }

    @Test fun `provider profile rejects cleartext endpoint`() {
        assertFailsWith<IllegalArgumentException> {
            ProviderConfiguration(
                id = "local",
                type = ProviderType.OPENAI_COMPATIBLE,
                displayName = "Unsafe",
                baseUrl = "http://example.test/v1",
                credentialAlias = "provider-local",
                defaultModelId = "model",
                enabled = true,
                capabilities = ModelCapabilities(streaming = true),
            )
        }
    }
}
```

- [ ] **Step 4: Run the test and verify RED**

```powershell
./gradlew :core-model:test
```

Expected: compilation fails because `NormalizedPoint` and Provider types do not exist.

- [ ] **Step 5: Implement minimal provider-neutral models**

Implement immutable data/sealed types. `NormalizedPoint` validates x/y in `0f..1f`, radius in `0f..1f`, and finite values. `ProviderConfiguration` parses its URL with `java.net.URI`, requires `https`, a non-empty host, and no user-info.

```kotlin
interface ModelProvider {
    val descriptor: ProviderDescriptor
    fun stream(request: ModelRequest): Flow<ModelEvent>
    suspend fun discoverModels(): ModelDiscoveryResult
    suspend fun validate(configuration: ProviderConfiguration): ValidationResult
}
```

```kotlin
sealed interface ModelEvent {
    data class TextDelta(val text: String) : ModelEvent
    data class ReasoningDelta(val text: String) : ModelEvent
    data class UsageUpdated(val usage: TokenUsage) : ModelEvent
    data class Completed(val reason: FinishReason) : ModelEvent
    data class Failed(val error: ModelError) : ModelEvent
}
```

- [ ] **Step 6: Verify GREEN**

```powershell
./gradlew :core-model:test
```

Expected: all `core-model` tests pass.

- [ ] **Step 7: Document local setup and commit**

Document SDK 36 installation, emulator requirements, wrapper commands, and that no credentials belong in `local.properties` beyond SDK location.

```powershell
git add android-app
git commit -m "build(android): scaffold API 36.1 project and domain contracts"
```

## Task 2: Normalized transforms and configurable settings gesture

**Files:**
- Create: `android-app/paper-engine/build.gradle.kts`
- Create: `android-app/paper-engine/src/test/kotlin/dev/riddle/magicpaper/paper/PageTransformTest.kt`
- Create: `android-app/paper-engine/src/test/kotlin/dev/riddle/magicpaper/paper/ThreeFingerLongPressPolicyTest.kt`
- Create: `android-app/paper-engine/src/main/kotlin/dev/riddle/magicpaper/paper/PageTransform.kt`
- Create: `android-app/paper-engine/src/main/kotlin/dev/riddle/magicpaper/paper/SettingsEntryPolicy.kt`

**Interfaces:**
- Consumes: `NormalizedPoint` from Task 1.
- Produces: `PageTransform`, `TouchFrame`, `SettingsEntryPolicy`, `ThreeFingerLongPressPolicy`, `SettingsEntryEvent.OpenSettings`.

- [ ] **Step 1: Write failing transform tests**

```kotlin
class PageTransformTest {
    @Test fun `round trip preserves normalized geometry in portrait and landscape`() {
        val point = NormalizedPoint(0.25f, 0.75f, 0.02f)
        listOf(SizeF(1080f, 2400f), SizeF(2400f, 1080f), SizeF(2560f, 1600f)).forEach { size ->
            val transform = PageTransform(RectF(20f, 40f, size.width - 20f, size.height - 40f))
            val restored = transform.toNormalized(transform.toPixels(point))
            assertEquals(point.x, restored.x, 0.0001f)
            assertEquals(point.y, restored.y, 0.0001f)
        }
    }
}
```

- [ ] **Step 2: Write failing gesture tests**

Use a fake monotonic clock value passed to `onTouchFrame`.

```kotlin
@Test fun `three stable fingers open settings after two seconds`() {
    val policy = ThreeFingerLongPressPolicy(holdMillis = 2_000, slopNormalized = 0.02f)
    assertNull(policy.onTouchFrame(frame(3), 1_000))
    assertNull(policy.onTouchFrame(frame(3), 2_999))
    assertEquals(SettingsEntryEvent.OpenSettings, policy.onTouchFrame(frame(3), 3_000))
}

@Test fun `movement or stylus input cancels hidden gesture`() {
    val policy = ThreeFingerLongPressPolicy()
    policy.onTouchFrame(frame(3), 0)
    assertNull(policy.onTouchFrame(frame(3, movement = 0.1f), 2_000))
    assertNull(policy.onTouchFrame(frame(3, tool = PointerTool.STYLUS), 4_000))
}
```

- [ ] **Step 3: Run tests and verify RED**

```powershell
./gradlew :paper-engine:test
```

Expected: missing transform and policy types.

- [ ] **Step 4: Implement transform and policy**

`PageTransform` uses the current safe drawing rectangle. Radius maps against `min(width, height)`. The gesture policy requires exactly three finger contacts, movement at or below slop, no stylus, and emits once until all contacts release.

- [ ] **Step 5: Verify GREEN and commit**

```powershell
./gradlew :paper-engine:test
git add android-app/paper-engine
git commit -m "feat(android): add responsive page transforms and settings gesture"
```

## Task 3: Low-latency MagicPaperView input and rendering

**Files:**
- Create: `android-app/paper-engine/src/test/kotlin/dev/riddle/magicpaper/paper/StrokeReducerTest.kt`
- Create: `android-app/paper-engine/src/test/kotlin/dev/riddle/magicpaper/paper/DissolvePatternTest.kt`
- Create: `android-app/paper-engine/src/main/kotlin/dev/riddle/magicpaper/paper/StrokeReducer.kt`
- Create: `android-app/paper-engine/src/main/kotlin/dev/riddle/magicpaper/paper/DissolvePattern.kt`
- Create: `android-app/paper-engine/src/main/kotlin/dev/riddle/magicpaper/paper/MagicPaperView.kt`
- Create: `android-app/paper-engine/src/main/kotlin/dev/riddle/magicpaper/paper/PaperRenderModel.kt`
- Create: `android-app/paper-engine/src/main/res/values/colors.xml`

**Interfaces:**
- Consumes: normalized models, transform, gesture policy.
- Produces: `PaperIntent.StrokeStarted/PointAdded/StrokeEnded/Erase/OpenSettings`, `PaperRenderModel`, `MagicPaperView.submitRenderModel`.

- [ ] **Step 1: Write failing reducer tests**

```kotlin
@Test fun `stylus pressure maps into bounded normalized radius`() {
    val reducer = StrokeReducer(minRadius = 0.0015f, maxRadius = 0.006f)
    assertEquals(0.0015f, reducer.radius(PointerTool.STYLUS, 0f), 0.00001f)
    assertEquals(0.006f, reducer.radius(PointerTool.STYLUS, 1f), 0.00001f)
}

@Test fun `finger is ignored while stylus is active`() {
    val reducer = StrokeReducer()
    reducer.onStylusProximity(true)
    assertFalse(reducer.accept(PointerTool.FINGER))
    assertTrue(reducer.accept(PointerTool.STYLUS))
}
```

- [ ] **Step 2: Write failing deterministic dissolve test**

```kotlin
@Test fun `all ink is selected by final dissolve stage`() {
    val pattern = DissolvePattern(stages = 14)
    val pixels = (0 until 100).flatMap { x -> (0 until 100).map { y -> x to y } }
    assertTrue(pixels.all { (x, y) -> pattern.shouldErase(x, y, 13) })
    assertEquals(pattern.shouldErase(7, 11, 5), pattern.shouldErase(7, 11, 5))
}
```

- [ ] **Step 3: Verify RED**

```powershell
./gradlew :paper-engine:test
```

- [ ] **Step 4: Implement reducer and dissolve algorithm**

Port the deterministic coordinate hash from `src/ink.rs`. Keep the algorithm pure Kotlin. The reducer consumes historical MotionEvent samples, normalizes positions, maps pressure, and emits immutable intents.

- [ ] **Step 5: Implement MagicPaperView**

Use `View.onTouchEvent`, `MotionEvent.getHistoricalX/Y/Pressure`, `TOOL_TYPE_STYLUS`, `TOOL_TYPE_ERASER`, `ACTION_HOVER_ENTER/EXIT`, and `Paint` with anti-aliasing/round caps. Maintain render-only caches inside the View; authoritative strokes stay outside it. Use `postInvalidateOnAnimation()` and dirty bounds. Do not call Compose state setters for each point.

- [ ] **Step 6: Verify tests and Android compilation**

```powershell
./gradlew :paper-engine:test :paper-engine:lintDebug :paper-engine:assembleDebug
```

Expected: tests pass, lint has no errors, module assembles.

- [ ] **Step 7: Commit**

```powershell
git add android-app/paper-engine
git commit -m "feat(android): implement pressure-aware magic paper renderer"
```

## Task 4: Conversation state machine and fake Provider vertical slice

**Files:**
- Create: `android-app/conversation/build.gradle.kts`
- Create: `android-app/conversation/src/test/kotlin/dev/riddle/magicpaper/conversation/ConversationStateMachineTest.kt`
- Create: `android-app/conversation/src/main/kotlin/dev/riddle/magicpaper/conversation/ConversationState.kt`
- Create: `android-app/conversation/src/main/kotlin/dev/riddle/magicpaper/conversation/ConversationInput.kt`
- Create: `android-app/conversation/src/main/kotlin/dev/riddle/magicpaper/conversation/ConversationEffect.kt`
- Create: `android-app/conversation/src/main/kotlin/dev/riddle/magicpaper/conversation/ConversationStateMachine.kt`
- Create: `android-app/conversation/src/main/kotlin/dev/riddle/magicpaper/conversation/FakeModelProvider.kt`

**Interfaces:**
- Consumes: paper/domain models and `ModelProvider`.
- Produces: pure `transition(state, input): Transition`, effects for rasterize/OCR/request/render/persist, and fake streamed events.

- [ ] **Step 1: Write failing state-machine tests**

Cover `AC-001`, `AC-002`, `AC-003`, interruption, timeout, cancellation, stream append, linger clamp, and no persistence on failure.

```kotlin
@Test fun `visible ink commits exactly once after inactivity`() {
    val state = listeningWithInk(lastPointAt = 1_000)
    val before = machine.transition(state, Tick(3_799))
    assertTrue(before.effects.isEmpty())
    val atDeadline = machine.transition(state, Tick(3_800))
    assertEquals(listOf(Effect.BeginTurn(state.page.id)), atDeadline.effects)
    assertIs<ConversationState.Drinking>(atDeadline.state)
}

@Test fun `failed turn never requests completed memory persistence`() {
    val result = machine.transition(replyingState(), ProviderFailed(ModelError.Network))
    assertTrue(result.effects.none { it is Effect.PersistCompletedTurn })
    assertIs<ConversationState.FailureLingering>(result.state)
}
```

- [ ] **Step 2: Verify RED**

```powershell
./gradlew :conversation:test
```

- [ ] **Step 3: Implement minimum state machine**

The reducer is pure and does no I/O. Effects are executed by a separate orchestrator. Use a monotonic `Tick(nowMillis)` supplied by the caller; do not call system time inside transitions.

- [ ] **Step 4: Add fake Provider stream and orchestrator tests**

Use Turbine to prove the first `TextDelta` produces handwriting before `Completed`, later deltas append, and cancellation closes collection.

- [ ] **Step 5: Verify and commit**

```powershell
./gradlew :conversation:test
git add android-app/conversation
git commit -m "feat(android): add tested magic paper conversation state machine"
```

## Task 5: SSE transport and OpenAI/DeepSeek Provider adapters

**Files:**
- Create: `android-app/model-provider/build.gradle.kts`
- Create: `android-app/model-provider/src/test/resources/sse/*.txt`
- Create: `android-app/model-provider/src/test/kotlin/dev/riddle/magicpaper/provider/SseParserTest.kt`
- Create: `android-app/model-provider/src/test/kotlin/dev/riddle/magicpaper/provider/ProviderContractTest.kt`
- Create: `android-app/model-provider/src/main/kotlin/dev/riddle/magicpaper/provider/SseParser.kt`
- Create: `android-app/model-provider/src/main/kotlin/dev/riddle/magicpaper/provider/ModelTransport.kt`
- Create: `android-app/model-provider/src/main/kotlin/dev/riddle/magicpaper/provider/OpenAiCompatibleProvider.kt`
- Create: `android-app/model-provider/src/main/kotlin/dev/riddle/magicpaper/provider/DeepSeekProvider.kt`
- Create: `android-app/model-provider/src/main/kotlin/dev/riddle/magicpaper/provider/ProviderJson.kt`

**Interfaces:**
- Consumes: `ModelProvider`, `ModelRequest`, `ModelEvent`, credential lookup port.
- Produces: cancellable OkHttp transport, incremental SSE parser, two adapters.

- [ ] **Step 1: Write failing incremental SSE tests**

Feed bytes one to three at a time, including a Unicode code point split between chunks. Assert comments/blank lines are ignored, multiple `data:` lines join with newline, `[DONE]` completes, and malformed JSON becomes typed parse failure without crashing the process.

```kotlin
@Test fun `utf8 split across chunks reconstructs delta`() {
    val bytes = "data: {\"choices\":[{\"delta\":{\"content\":\"魔法\"}}]}\n\n".encodeToByteArray()
    val events = parser.feedInChunks(bytes, 1)
    assertEquals(listOf(SseEvent.Data("{\"choices\":[{\"delta\":{\"content\":\"魔法\"}}]}")), events)
}
```

- [ ] **Step 2: Verify RED, implement parser, verify GREEN**

```powershell
./gradlew :model-provider:test --tests '*SseParserTest*'
```

- [ ] **Step 3: Write shared failing Provider contract**

Create a test factory that runs the same fixture transport against both adapters and checks text deltas, finish reason, usage, `401`, `429` with retry-after, `5xx`, cancellation, truncated stream, and that tool calls are parsed but not executed.

- [ ] **Step 4: Implement DTO mapping and adapters**

Build requests from domain models only. Read secrets through `CredentialSource.get(alias)`. Never place the secret in DTO `toString`. DeepSeek overrides capabilities and any differing response fields inside its adapter.

- [ ] **Step 5: Verify and commit**

```powershell
./gradlew :model-provider:test :model-provider:lintDebug
git add android-app/model-provider
git commit -m "feat(android): add OpenAI and DeepSeek streaming providers"
```

## Task 6: Keystore credentials and Provider settings

**Files:**
- Create: `android-app/security/build.gradle.kts`
- Create: `android-app/security/src/test/kotlin/dev/riddle/magicpaper/security/RedactorTest.kt`
- Create: `android-app/security/src/main/kotlin/dev/riddle/magicpaper/security/CredentialStore.kt`
- Create: `android-app/security/src/main/kotlin/dev/riddle/magicpaper/security/AndroidKeystoreCredentialStore.kt`
- Create: `android-app/security/src/main/kotlin/dev/riddle/magicpaper/security/Redactor.kt`
- Create: `android-app/feature-settings/build.gradle.kts`
- Create: `android-app/feature-settings/src/test/kotlin/dev/riddle/magicpaper/settings/ProviderSettingsViewModelTest.kt`
- Create: `android-app/feature-settings/src/main/kotlin/dev/riddle/magicpaper/settings/ProviderSettingsViewModel.kt`
- Create: `android-app/feature-settings/src/main/kotlin/dev/riddle/magicpaper/settings/ProviderSettingsScreen.kt`

**Interfaces:**
- Consumes: Provider profiles/validation and Provider factory.
- Produces: credential alias store, redacted diagnostics, Provider add/edit/test/delete state.

- [ ] **Step 1: Write failing redaction and settings tests**

Prove raw keys and `Authorization` values are absent from output, validation sends no page image, deletion removes both profile and alias, and cleartext URL is rejected before transport.

- [ ] **Step 2: Verify RED**

```powershell
./gradlew :security:test :feature-settings:test
```

- [ ] **Step 3: Implement Keystore store**

Generate a non-exportable AES/GCM key under app alias `riddle-provider-secrets-v1`. Store ciphertext/IV in app-private preferences keyed by credential alias. Authenticate/decrypt only when needed; return typed unavailable/corrupt errors. Never expose secrets in observable UI state.

- [ ] **Step 4: Implement Provider settings UI**

Provide OpenAI and DeepSeek presets, custom HTTPS profile fields, actual host confirmation, validation status, enable/select/delete, and accessible localized labels.

- [ ] **Step 5: Verify and commit**

```powershell
./gradlew :security:test :feature-settings:test :feature-settings:lintDebug
git add android-app/security android-app/feature-settings
git commit -m "feat(android): secure provider credentials and settings"
```

## Task 7: Page rasterization and local handwriting-recognition routing

**Files:**
- Create: `android-app/conversation/src/test/kotlin/dev/riddle/magicpaper/conversation/InputRoutingTest.kt`
- Create: `android-app/paper-engine/src/test/kotlin/dev/riddle/magicpaper/paper/PageRasterizerTest.kt`
- Create: `android-app/paper-engine/src/main/kotlin/dev/riddle/magicpaper/paper/PageRasterizer.kt`
- Create: `android-app/conversation/src/main/kotlin/dev/riddle/magicpaper/conversation/HandwritingRecognizer.kt`
- Create: `android-app/conversation/src/main/kotlin/dev/riddle/magicpaper/conversation/MlKitHandwritingRecognizer.kt`
- Create: `android-app/conversation/src/main/kotlin/dev/riddle/magicpaper/conversation/TurnInputRouter.kt`

**Interfaces:**
- Consumes: selected model capabilities, normalized strokes.
- Produces: `TurnInput.PageImage` or `TurnInput.RecognizedText`; typed OCR failure.

- [ ] **Step 1: Write failing capability-routing tests**

```kotlin
@Test fun `vision model receives image without OCR`() = runTest {
    val result = router.route(visionCapabilities, page)
    assertIs<TurnInput.PageImage>(result.getOrThrow())
    assertEquals(0, recognizer.calls)
}

@Test fun `blank OCR prevents Provider request`() = runTest {
    recognizer.result = Result.success("   ")
    assertEquals(InputRoutingError.BlankRecognition, router.route(textCapabilities, page).exceptionOrNull())
}
```

- [ ] **Step 2: Write failing rasterizer tests**

Assert ink-bound crop plus padding, grayscale output, long side `<= 800`, no empty page, and cache file deletion after the consumer closes it.

- [ ] **Step 3: Verify RED and implement**

Map normalized strokes to a bounded bitmap off the main thread. Integrate ML Kit Digital Ink Recognition behind `HandwritingRecognizer`; language model selection uses user/app locale with an explicit fallback. Model download state becomes a typed prerequisite error rather than a hidden network action during commit.

- [ ] **Step 4: Verify and commit**

```powershell
./gradlew :paper-engine:test :conversation:test
git add android-app/paper-engine android-app/conversation
git commit -m "feat(android): route handwriting to vision or local recognition"
```

## Task 8: Room memory, pruning, draft recovery, and reconstruction

**Files:**
- Create: `android-app/memory/build.gradle.kts`
- Create: `android-app/memory/src/androidTest/kotlin/dev/riddle/magicpaper/memory/MemoryDaoTest.kt`
- Create: `android-app/memory/src/androidTest/kotlin/dev/riddle/magicpaper/memory/MigrationTest.kt`
- Create: `android-app/memory/src/main/kotlin/dev/riddle/magicpaper/memory/RiddleDatabase.kt`
- Create: `android-app/memory/src/main/kotlin/dev/riddle/magicpaper/memory/MemoryEntities.kt`
- Create: `android-app/memory/src/main/kotlin/dev/riddle/magicpaper/memory/MemoryDao.kt`
- Create: `android-app/memory/src/main/kotlin/dev/riddle/magicpaper/memory/RoomMemoryRepository.kt`
- Create: `android-app/conversation/src/test/kotlin/dev/riddle/magicpaper/conversation/ReconstructionPlannerTest.kt`
- Create: `android-app/conversation/src/main/kotlin/dev/riddle/magicpaper/conversation/ReconstructionPlanner.kt`

**Interfaces:**
- Consumes: completed turns and normalized strokes.
- Produces: transactional append/prune, recent dialogue, fresh catalog, draft recovery, faded reconstruction plan.

- [ ] **Step 1: Write failing DAO tests**

Test full round-trip, stable point ordering, memory-disabled behavior, clear-all transaction, and `AC-014` with 401 inserts leaving 400 and no orphan strokes.

- [ ] **Step 2: Verify RED**

```powershell
./gradlew :memory:connectedDebugAndroidTest
```

- [ ] **Step 3: Implement schema version 1 and repository**

Use foreign keys with cascade delete and indices on page timestamp/page ID. Completed page plus strokes inserts in one `withTransaction`; pruning runs in the same transaction. Draft revisions use replace semantics and never include API keys or temporary image bytes.

- [ ] **Step 4: Write and implement reconstruction tests**

Prove a current-catalog number maps to the correct stable page ID, stale/out-of-range numbers fail, and the plan includes date, original strokes, faded reply paths, 120-second timeout, and current-page restore data.

- [ ] **Step 5: Add migration-test baseline and verify**

Export schema 1 JSON and configure Room schema location. `MigrationTest` opens version 1 and validates schema so version 2 can add a real migration later.

- [ ] **Step 6: Commit**

```powershell
git add android-app/memory android-app/conversation
git commit -m "feat(android): persist and reconstruct magic paper memories"
```

## Task 9: App composition, immersive paper UI, settings, and orientation

**Files:**
- Create: `android-app/app/src/main/AndroidManifest.xml`
- Create: `android-app/app/src/main/kotlin/dev/riddle/magicpaper/RiddleApplication.kt`
- Create: `android-app/app/src/main/kotlin/dev/riddle/magicpaper/MainActivity.kt`
- Create: `android-app/app/src/main/kotlin/dev/riddle/magicpaper/AppContainer.kt`
- Create: `android-app/feature-paper/build.gradle.kts`
- Create: `android-app/feature-paper/src/test/kotlin/dev/riddle/magicpaper/paperui/PaperViewModelTest.kt`
- Create: `android-app/feature-paper/src/main/kotlin/dev/riddle/magicpaper/paperui/PaperViewModel.kt`
- Create: `android-app/feature-paper/src/main/kotlin/dev/riddle/magicpaper/paperui/MagicPaperScreen.kt`
- Create: `android-app/feature-settings/src/main/kotlin/dev/riddle/magicpaper/settings/AppSettingsScreen.kt`
- Create: `android-app/app/src/main/res/values/strings.xml`
- Create: `android-app/app/src/main/res/values-zh-rCN/strings.xml`
- Create: `android-app/app/src/androidTest/kotlin/dev/riddle/magicpaper/MagicPaperFlowTest.kt`

**Interfaces:**
- Consumes: every earlier module.
- Produces: runnable Android app with fake/real Provider selection, immersive paper, hidden settings, portrait lock, process recovery.

- [ ] **Step 1: Write failing ViewModel tests**

Test intent/effect wiring, inactivity timer cancellation when settings opens, draft preservation, interrupted stream recovery, fake Provider end-to-end flow, portrait preference state, and no per-point Compose state list replacement.

- [ ] **Step 2: Verify RED**

```powershell
./gradlew :feature-paper:test
```

- [ ] **Step 3: Implement composition root and ViewModel**

`AppContainer` constructs one approved HTTP client, database, credential store, Provider factory, repositories, clock/dispatchers, state machine, and ViewModels. No service locator is accessible from domain modules.

- [ ] **Step 4: Implement immersive Compose host**

Embed `MagicPaperView` with `AndroidView`. Hide persistent controls, keep edge-to-edge layout, provide semantic live-region state summaries, immersive help overlay, cancel behavior, and navigation to settings on `OpenSettings`.

- [ ] **Step 5: Implement portrait-lock behavior**

Observe preference in `MainActivity`; set `requestedOrientation` to `SCREEN_ORIENTATION_PORTRAIT` when enabled and `SCREEN_ORIENTATION_UNSPECIFIED` when disabled. Test the policy in ViewModel and activity instrumentation; keep orientation decisions out of paper engine.

- [ ] **Step 6: Write and run instrumentation flow**

With a non-vision fake Provider, draw a stroke, advance/test inactivity, observe rendered dissolve stages and progressive reply pixels, rotate, confirm geometry, open settings with the magical rune button, toggle portrait lock, return, and confirm the draft/page state.

```powershell
./gradlew :feature-paper:test :app:connectedDebugAndroidTest
```

- [ ] **Step 7: Verify UI lint and commit**

```powershell
./gradlew :app:lintDebug :app:assembleDebug
git add android-app/app android-app/feature-paper android-app/feature-settings
git commit -m "feat(android): assemble immersive Android magic paper app"
```

## Task 9R: Reopen magic-paper UX, Provider setup, and text-model pipeline

Execute every red-green-refactor slice in `doc/plans/magic-paper-ux-provider-text-repair.md`, beginning with its configuration-only R0 API 33 prerequisite. Task 9 remains incomplete until that plan's independent specification and quality review passes. Do not claim Android 13 compatibility merely because `minSdk` changed, phase labels/visible Compose reply text exist, or stale device-test XML passes.

## Task 10: Android 13 compatibility audit and closure

**Files:**
- Modify: `android-app/README.md`
- Modify: `doc/specs/android-magic-paper-app.md`
- Modify: `doc/specs/android-13-compatibility.md`
- Add only if a failing API 33 gate requires it: narrowly scoped Android compatibility code and its regression test

**Interfaces:**
- Consumes: the complete Task 1 through Task 9 application and test suite.
- Produces: one unchanged-feature APK supporting API 33 through API 36, plus API 33/API 36.1 parity evidence.

- [ ] **Step 1: Reverify the R0 configuration baseline**

Run the deterministic configuration assertion added in repair-plan R0. Inspect every merged manifest and verify `minSdk = 33`, compile API 36.1, and target 36.

```powershell
./gradlew verifyAndroidCompatibility
```

Expected GREEN: all modules and merged manifests meet the baseline. The historical RED evidence remains recorded in the R0 report.

- [ ] **Step 2: Audit real API 33 failures without speculative changes**

Review all R0–R6 lint/device evidence. Add a compatibility guard only when an observed API 33 failure has a failing regression test. Keep the API 36.1 compile DSL, `targetSdk = 36`, application ID, Provider contracts, Room schema, and full feature behavior unchanged.

- [ ] **Step 3: Verify compilation and API usage**

```powershell
./gradlew verifyAndroidCompatibility test lint assembleDebug
```

Expected GREEN: all gates pass and lint reports no unguarded API newer than 33. If a gate fails on a real post-33 API use, first add a failing regression test, then implement the smallest local AndroidX compatibility path or `SDK_INT` guard. Do not refactor unrelated code.

- [ ] **Step 4: Reverify API 33 tooling and the boundary AVD**

Confirm repair-plan R0 installed `platforms;android-33`, `system-images;android-33;google_apis;x86_64`, and `Riddle_API_33`. Repair missing tooling only if the recorded R0 environment was removed. Preserve `Riddle_API_36_1`.

```powershell
sdkmanager "platforms;android-33" "system-images;android-33;google_apis;x86_64"
avdmanager create avd -n Riddle_API_33 -k "system-images;android-33;google_apis;x86_64"
emulator -list-avds
```

- [ ] **Step 5: Run the identical device suite on both boundary versions**

Run the same `connectedDebugAndroidTest` cases without API-level assumptions, ignored tests, feature flags, or reduced-mode branches on `Riddle_API_33` and `Riddle_API_36_1`. Verify install/launch, writing, inactivity commit, dissolve, streaming reply, cancellation, magical-rune settings entry, orientation and portrait lock, Provider settings, Keystore, Room, and draft/process recreation behavior.

```powershell
./gradlew :app:connectedDebugAndroidTest
```

Expected: identical test inventory and zero failures/skips on both AVDs. Record any unavailable physical-stylus check separately; it must not be reported as passed.

- [ ] **Step 6: Verify release metadata and scope**

Build the release APK with temporary local signing material outside the repository. Inspect its manifest and signature: package `dev.riddle.magicpaper`, `minSdk = 33`, `targetSdk = 36`, one signer. Review the compatibility production diff and reject any change not directly justified by configuration or a failing API 33 gate.

- [ ] **Step 7: Document and commit the compatibility slice**

Update setup/device-matrix documentation and set `doc/specs/android-13-compatibility.md` to `implemented` only after all mandatory API 33 and API 36.1 gates pass.

```powershell
git add AGENTS.md android-app doc/specs/android-magic-paper-app.md doc/specs/android-13-compatibility.md doc/plans/android-magic-paper-implementation.md
git commit -m "feat(android): support Android 13 with full feature parity"
```

## Task 11: Full verification, documentation, and specification closure

**Files:**
- Modify: `android-app/README.md`
- Create: `doc/android-detailed-design.md`
- Modify: `doc/specs/android-magic-paper-app.md`
- Modify: `.gitignore`

**Interfaces:**
- Consumes: all completed implementation slices.
- Produces: verified build/test evidence, developer documentation, spec status `implemented`.

- [ ] **Step 1: Audit requirement traceability**

For every `FR`, `NFR`, and `AC` in the specification, list the exact test class/method or manual device check in `doc/android-detailed-design.md`. Any uncovered requirement blocks completion.

- [ ] **Step 2: Run full unit, lint, and build gates**

```powershell
./gradlew test
./gradlew lint
./gradlew assembleDebug
```

Expected: exit code 0, zero failed tests, zero lint errors, debug APK created.

- [ ] **Step 3: Run Android 13 and Android 16 boundary device matrix**

Run the identical `connectedCheck` suite on API 33 and API 36.1 emulator profiles, with profiles representing a compact phone, tall phone, landscape/wide foldable, and tablet. On stylus-capable hardware, manually verify pressure, eraser, and palm rejection.

```powershell
./gradlew connectedCheck
```

Expected: exit code 0 on each available profile. Record unavailable physical-stylus verification explicitly; do not claim it passed without hardware evidence.

- [ ] **Step 4: Perform privacy checks**

Create a test profile with a sentinel key, exercise validation and a fake conversation, then scan app logs, exported test database, saved-state diagnostics, and test artifacts for the sentinel. Expected: zero occurrences outside the encrypted credential fixture.

- [ ] **Step 5: Update documentation**

Document architecture, module responsibilities, state transitions, Provider profile setup, fake Provider mode, ML Kit model preparation, emulator/device matrix, privacy behavior, known limitations, and exact verification commands. Add Android build/cache/local/secret outputs to `.gitignore` without changing existing Rust ignore behavior.

- [ ] **Step 6: Mark specification implemented only after evidence exists**

Change `Status: approved` to `Status: implemented` only after Steps 1–4 pass or explicitly documented hardware-only checks remain. Do not use `implemented` when build or unit gates fail.

- [ ] **Step 7: Final commit**

```powershell
git add android-app/README.md doc/android-detailed-design.md doc/specs/android-magic-paper-app.md .gitignore
git commit -m "docs(android): record architecture and verification evidence"
```

## Plan self-review results

- Specification coverage: every FR/NFR group maps to Tasks 1 through 11 and the traceability audit.
- TDD order: each behavior task writes and verifies a failing test before production implementation.
- Type consistency: domain contracts originate in Task 1; every later task consumes those names through module dependencies.
- Scope: tool-agent execution remains excluded and tracked in `doc/TODO.md`.
- Verification honesty: toolchain absence, emulator absence, and stylus-hardware absence are explicit blockers to corresponding success claims.
