# Android Magic Paper App Specification

- Status: `approved`
- Product: Riddle Android
- Target platform: Android 13 through Android 16 / API 33 through API 36; compile platform API 36.1
- Design approval: 2026-07-12
- Reference implementation: Rust application under `src/`
- Reference design: `doc/detailed-design.md`

## 1. Problem statement and user value

Riddle currently provides a Harry-Potter-like enchanted diary experience on reMarkable Paper Pro. The Android product must preserve that immersive behavior on Android 13 through Android 16 phones, foldables, and tablets while adding secure configuration for multiple OpenAI-compatible model services.

The user writes directly on a full-screen magical paper surface. After the user rests the pen or finger, the paper consumes the ink, consults the configured model, and writes a response back stroke by stroke. Conventional chat chrome must not replace this interaction.

## 2. Scope

The first usable Android release includes:

- full-screen handwriting with finger and stylus input;
- pressure-sensitive stylus width and palm rejection;
- automatic submission after 2.8 seconds of inactivity;
- deterministic user-ink dissolve animation;
- streamed model responses written back as animated handwriting;
- responsive layout for different screen sizes, aspect ratios, foldables, tablets, and rotation;
- an optional setting that locks the application to portrait orientation;
- local handwriting recognition before sending to models without vision capability;
- direct page-image input for models with vision capability;
- OpenAI and DeepSeek presets;
- arbitrary HTTPS OpenAI-compatible base URL, model name, and API key configuration;
- local conversation memory and animated reconstruction of historical pages;
- immersive help and settings entry gestures;
- process recreation of completed pages and drafts;
- accessibility, localization, security, and privacy controls described below.

## 3. Non-goals

The first release does not include model-triggered Android tools, automatic cross-provider fallback, Android versions earlier than API 33, cloud synchronization, reMarkable memory migration, or background conversations after cancellation. Tool-agent work is tracked in `doc/TODO.md` and requires a separate approved specification.

## 4. User stories

- As a user, I can write anywhere on a full-screen paper surface without opening a keyboard or chat composer.
- As a user, I see my ink respond to finger or stylus pressure and do not create marks with my palm while using a stylus.
- As a user, resting for 2.8 seconds submits my page automatically.
- As a user, I see my writing dissolve while the model is working and see the reply write itself back.
- As a user, I can rotate the device without losing or shifting handwriting.
- As a user, I can lock the app to portrait orientation in settings.
- As a user, I can configure OpenAI, DeepSeek, or another compatible HTTPS endpoint.
- As a user, I can use a text-only model because the app can locally recognize handwriting first.
- As a user, I can revisit a remembered page through a handwritten natural-language request.
- As a user, I can open settings with a hidden gesture while the default paper view stays immersive.
- As a user, I can later change the settings-entry mechanism without changing the paper engine.

## 5. Functional requirements

### 5.1 Paper surface and input

- `FR-001`: The default screen shall be a full-screen magical paper surface without persistent chat controls.
- `FR-002`: The paper surface shall accept finger and Android stylus input.
- `FR-003`: Stylus strokes shall use pressure to vary width within configured bounds.
- `FR-004`: While a stylus is active, touch contacts classified as palm input shall not create strokes.
- `FR-005`: Eraser-tool input shall remove visible ink and corresponding vector points.
- `FR-006`: Stroke points shall use normalized page coordinates independent of pixels and orientation.
- `FR-007`: Writing shall interrupt a lingering response and return the paper to input mode.
- `FR-008`: A large handwritten question mark shall open immersive help instead of sending a request.

### 5.2 Commit and animation state machine

- `FR-010`: A non-empty page shall commit after 2.8 seconds without active pointer contact.
- `FR-011`: A completely erased page shall not commit.
- `FR-012`: Model processing and ink dissolve shall start concurrently.
- `FR-013`: User ink shall dissolve over 14 deterministic stages.
- `FR-014`: Waiting for the first model event shall show a subtle thinking animation.
- `FR-015`: The first displayable delta shall begin animated handwriting immediately.
- `FR-016`: Later deltas shall append without restarting completed strokes.
- `FR-017`: Replies shall linger for 4 to 20 seconds based on length.
- `FR-018`: Replies shall dissolve over 10 stages before listening resumes.
- `FR-019`: Long replies shall paginate; text shall not be silently discarded.
- `FR-020`: Transitions shall be implemented by a testable state machine outside UI and adapters.

Baseline states:

```text
Listening → Drinking → Thinking → Replying → Lingering → Fading → Listening
```

Additional states cover help, history reconstruction, settings, failure, cancellation, and interrupted recovery.

### 5.3 Responsive layout and orientation

- `FR-030`: The paper shall adapt to phones, foldables, and tablets without fixed pixel assumptions.
- `FR-031`: Insets, cutouts, rounded corners, and system bars shall not clip interactive content.
- `FR-032`: Window-size changes shall remap normalized strokes without changing relative geometry.
- `FR-033`: Drafts, state, and animation position shall survive configuration changes.
- `FR-034`: Settings shall provide a portrait-lock switch, disabled by default.
- `FR-035`: Portrait lock shall force portrait; disabling it shall restore sensor orientation.
- `FR-036`: Rotation during animation shall pause, relayout, and resume at the current point.

### 5.4 Settings entry and settings UI

- `FR-040`: The default settings entry shall be a three-finger long press held for 2 seconds.
- `FR-041`: Entry shall be selected through a `SettingsEntryPolicy` independent of the paper engine.
- `FR-042`: Future policies may use another gesture, edge entry, or visible control without changing conversation or rendering code.
- `FR-043`: Entering settings shall pause the inactivity timer.
- `FR-044`: Returning shall preserve and resume draft strokes.
- `FR-045`: Settings and large-question-mark help gestures shall not trigger each other.

### 5.5 Model providers

- `FR-050`: The application shall expose a provider-neutral `ModelProvider` contract.
- `FR-051`: The first release shall include an OpenAI-compatible Chat Completions adapter.
- `FR-052`: The first release shall include a DeepSeek-compatible adapter with isolated capability/mapping differences.
- `FR-053`: Deterministic fake Providers shall support tests, previews, and offline demos.
- `FR-054`: Provider profiles shall include ID, type, display name, HTTPS base URL, credential alias, model ID, enabled state, and capabilities.
- `FR-055`: Settings shall include OpenAI and DeepSeek presets.
- `FR-056`: Settings shall allow custom HTTPS OpenAI-compatible endpoints and model IDs.
- `FR-057`: Users shall add, edit, validate, enable, select, and delete profiles.
- `FR-058`: Validation shall use a minimal request and shall not upload the current page.
- `FR-059`: Shared code shall select behavior from capabilities rather than provider-name conditionals.
- `FR-060`: Adapters shall normalize text, reasoning, usage, completion, and typed failure events. Tool calls may be parsed but shall not execute.
- `FR-061`: The app shall never automatically send data to a different Provider after failure.

### 5.6 Vision and local handwriting recognition

- `FR-070`: Vision-capable models shall receive a cropped, downscaled page image and allowed context.
- `FR-071`: Text-only models shall receive locally recognized handwriting text.
- `FR-072`: Recognition diagnostics shall not introduce conventional chat chrome.
- `FR-073`: Blank/failed recognition shall retain ink, show an in-character error, and send no request.
- `FR-074`: The image pipeline shall crop to ink bounds, add padding, produce grayscale, and bound the long side to 800 pixels.
- `FR-075`: Temporary images shall be deleted after Provider consumption unless diagnostics explicitly retain them.

### 5.7 Streaming, cancellation, retry, and errors

- `FR-080`: Model streams shall be cancellable by the state machine.
- `FR-081`: SSE parsing shall support arbitrary chunks, UTF-8 splits, comments, blank lines, multi-line data, terminal markers, malformed events, and truncation.
- `FR-082`: Transport shall define connect, read, write, and total timeouts.
- `FR-083`: Authentication, authorization, request, capability, policy, rate-limit, server, timeout, network, cancellation, parsing, and OCR failures shall be typed.
- `FR-084`: Only eligible transient connection, timeout, `429`, and selected `5xx` failures may retry, respecting `Retry-After`.
- `FR-085`: Authentication, invalid request, unsupported capability, policy rejection, and cancellation shall not retry.
- `FR-086`: Text displayed before a stream failure shall remain visible through the failure transition.
- `FR-087`: Failed or cancelled turns shall not become completed memories.
- `FR-088`: Diagnostics shall be sanitized; the paper shall show concise in-character errors.

### 5.8 Memory and historical reconstruction

- `FR-090`: Room shall persist successful turns with timestamp, normalized strokes, transcription, complete reply, Provider/model IDs, and schema version.
- `FR-091`: Memory shall be enabled by default and configurable.
- `FR-092`: Disabled memory shall prevent persistence and stored-context transmission.
- `FR-093`: At most 400 memories shall remain; oldest records shall prune transactionally.
- `FR-094`: Settings shall clear memories after explicit confirmation.
- `FR-095`: Recent context count shall be configurable with default 6.
- `FR-096`: A newest-first catalog of up to 40 pages shall support historical selection.
- `FR-097`: A valid current-catalog selection shall route to reconstruction before reply text.
- `FR-098`: Reconstruction shall show date, original user strokes, and old reply in faded ink.
- `FR-099`: Touch or a 120-second timeout shall restore the current page.

### 5.9 Lifecycle and recovery

- `FR-100`: Completed pages and draft strokes shall survive process recreation.
- `FR-101`: Interrupted streams shall restore as interrupted and shall not silently replay.
- `FR-102`: Provider secrets shall not enter saved instance state.
- `FR-103`: Essential transitions shall persist transactionally before visible completion.

## 6. Non-functional requirements

- `NFR-001`: Use Kotlin, Gradle Kotlin DSL, Android Gradle Plugin 9.2.1, Gradle 9.4.1, API 36.1 compilation, `targetSdk = 36`, and `minSdk = 33`.
- `NFR-002`: Compose/Material 3 shall implement app UI; custom Android `View/Canvas` shall implement high-frequency paper input and animation.
- `NFR-003`: Input and animated points shall not cause full Compose recomposition per point.
- `NFR-004`: Database, network, OCR, and credential work shall not block the main thread.
- `NFR-005`: No fixed resolution, density, or aspect ratio assumptions.
- `NFR-006`: API keys shall use Keystore-backed protection and be absent from Room, logs, crash reports, and saved state.
- `NFR-007`: Cleartext HTTP shall be rejected and not globally enabled.
- `NFR-008`: Normal unit/CI tests shall not call paid services or need real credentials.
- `NFR-009`: TalkBack, touch targets, scalable text, contrast, and non-color-only state cues are required.
- `NFR-010`: User-visible strings shall use localized Android resources.
- `NFR-011`: Prompts, responses, images, credentials, and memories shall be redacted from default diagnostics.
- `NFR-012`: State, parsing, persistence, and transforms shall be deterministic under controlled tests.
- `NFR-013`: Existing Rust code and build files shall remain unchanged unless separately requested.

## 7. Architecture

### 7.1 Modules

```text
android-app/
├── app/                 # Activity, navigation, composition root
├── core-model/          # Provider-neutral and paper-neutral models
├── paper-engine/        # Custom View, input, rendering, transforms
├── model-provider/      # SPI, HTTP/SSE, OpenAI and DeepSeek adapters
├── conversation/        # State machine, orchestration, OCR routing
├── memory/              # Room entities, DAO, migrations
├── security/            # Keystore credentials and redaction
├── feature-paper/       # Immersive paper Compose host
└── feature-settings/    # Providers, memory, orientation, entry policy
```

Dependencies point inward toward `core-model`. Feature modules do not consume Provider DTOs. The paper engine does not know Provider names or secrets.

### 7.2 UI and rendering boundary

Compose owns navigation, settings, panels, lifecycle state, and accessibility. `MagicPaperView` owns MotionEvent sampling, stylus/palm classification, Canvas rendering, dissolve animation, handwriting playback, and dirty invalidation.

The View emits immutable paper intents. A lifecycle-aware ViewModel emits rendering state/commands without exposing repositories or Provider DTOs.

### 7.3 Provider-neutral interfaces

```kotlin
interface ModelProvider {
    val descriptor: ProviderDescriptor
    fun stream(request: ModelRequest): Flow<ModelEvent>
    suspend fun listModels(): Result<List<ModelDescriptor>>
    suspend fun validate(configuration: ProviderConfiguration): ValidationResult
}
```

```kotlin
interface SettingsEntryPolicy {
    fun onTouchFrame(frame: TouchFrame, nowMillis: Long): SettingsEntryEvent?
}
```

`ThreeFingerLongPressPolicy` emits `OpenSettings` after exactly three valid contacts remain within movement tolerance for 2,000 milliseconds.

### 7.4 Data flow

```text
MotionEvent → MagicPaperView → normalized PaperIntent
→ ConversationStateMachine → rasterizer or local recognizer
→ selected ModelProvider → normalized ModelEvent stream
→ handwriting layout/paths → MagicPaperView commands
→ completed-turn MemoryRepository transaction
```

## 8. Data model and persistence

Room uses stable UUIDs and explicit schema versions. Core records are:

- `MemoryPage`: ID, timestamp, transcript, reply, Provider ID, model ID, status, schema version;
- `StrokeEntity`: owner ID, stroke/point order, normalized x/y/radius, tool type;
- `ProviderProfile`: non-secret metadata and credential alias;
- `AppPreference`: memory state/count, portrait lock, settings-entry policy ID;
- `DraftPage`: normalized strokes and persisted revision;
- `InterruptedTurn`: non-secret request metadata without temporary image.

Provider credentials remain outside Room. Every schema change requires migration tests. Pruning and completed-turn persistence are transactional.

## 9. Security and privacy

- API keys use a non-exportable Keystore key to encrypt credential material.
- Custom base URLs are normalized, require HTTPS, and show the host before validation.
- Authorization headers and request/response bodies are never logged.
- Temporary images use app-private cache and are deleted after consumption.
- Memories remain app-private and are not automatically synchronized or backed up in the first release.
- Deleting a Provider deletes its credential material.
- Clearing memory requires confirmation and transactionally removes associated data.
- Tool execution, arbitrary URL launching, shell execution, and model-directed Android actions are absent.

## 10. Accessibility, localization, and observability

- TalkBack announces paper mode without reading every stroke point.
- Help, settings, validation, memory clear, cancellation, and portrait lock expose semantics.
- Essential state is represented by text/semantics, not only color or motion.
- Reduced-motion preference shortens nonessential animation while preserving transitions.
- Strings use resources and locale-aware date/number formatting.
- Sanitized metrics may include state durations, hashed profile IDs, endpoint host, status codes, request IDs, image dimensions/size, OCR result status/duration, time-to-first-delta, frame overruns, migrations, and pruning.
- Diagnostics exclude prompts, responses, transcripts, strokes, images, API keys, authorization values, and tool arguments.

## 11. Acceptance criteria

- `AC-001`: Given visible ink and no pointer down, when 2.8 seconds elapse, exactly one turn and Provider request begin.
- `AC-002`: Given all ink was erased, when 2.8 seconds elapse, no rasterization or request occurs.
- `AC-003`: Given a turn begins, ink dissolves concurrently and the first delta writes before stream completion.
- `AC-004`: Given an active stylus, palm contacts create no stroke while pressure changes width.
- `AC-005`: Given draft and partial animation, rotation preserves geometry and resumes at the prior point.
- `AC-006`: Given portrait lock, orientation remains portrait; disabling restores sensor rotation.
- `AC-007`: Given listening paper, three stable fingers for 2 seconds open settings and pause commit without changing the draft.
- `AC-008`: Given another `SettingsEntryPolicy`, its trigger opens settings without changing paper or conversation code.
- `AC-009`: Given a vision model, commit sends a bounded grayscale image without requiring local recognition.
- `AC-010`: Given a text-only model, commit runs local recognition and sends recognized text plus allowed context.
- `AC-011`: Given recognition failure/blank, ink remains, an error appears, and no request occurs.
- `AC-012`: Given equivalent fixtures, OpenAI and DeepSeek adapters emit equivalent neutral text, usage, finish, error, and cancellation events.
- `AC-013`: Given selected Provider failure, no other Provider receives page, prompt, or memory data.
- `AC-014`: Given 401 completed memories, committing one leaves the newest 400 and removes oldest strokes transactionally.
- `AC-015`: Given valid catalog selection, faded date/strokes/reply animate and current page restores on touch or 120 seconds.
- `AC-016`: Given draft and stream, process recreation restores draft, marks interrupted, and does not replay.
- `AC-017`: Given a stored profile, raw API key is absent from Room, saved state, logs, and diagnostics.
- `AC-018`: Given a multi-page reply, overflow goes to another page and no text is discarded.

## 12. Test strategy and traceability

| Requirement group | Primary verification |
| --- | --- |
| FR-001–008 | paper-engine unit/Robolectric tests and Android stylus tests |
| FR-010–020 | pure Kotlin state-machine tests with fake clock/Provider |
| FR-030–036 | coordinate property tests, window-size tests, API 33 and API 36.1 emulators |
| FR-040–045 | gesture-policy unit and integration touch tests |
| FR-050–061 | shared Provider contract fixtures and fake transport tests |
| FR-070–075 | rasterizer, OCR routing, and cache deletion tests |
| FR-080–088 | SSE fixtures, cancellation, timeout, and retry tests |
| FR-090–099 | Room DAO, transaction, migration, pruning, reconstruction tests |
| FR-100–103 | saved-state/process-recreation and interruption tests |
| NFR-006–011 | credential scan, logging, network security, accessibility tests |

Normal tests use fake Providers and no live secrets. Optional live smoke tests are separately tagged.

Required gates after scaffolding:

```sh
./gradlew test
./gradlew lint
./gradlew assembleDebug
./gradlew connectedCheck
```

`connectedCheck` requires API 33 and API 36.1 emulator profiles. Phone, wide/foldable, and tablet layouts must use the same feature suite without API-level exclusions.

## 13. Migration and compatibility

- No Rust-memory migration is included.
- The Rust application remains untouched.
- Android Room starts at schema version 1 with migration-test infrastructure before version 2.
- Provider adapters remain behind stable domain contracts.
- Unknown persisted settings-entry policy IDs fall back to `three_finger_long_press`.

## 14. Rollout and rollback

Implementation proceeds in independently testable slices:

1. Android API 36.1 scaffold and domain contracts;
2. paper input/rendering and coordinate transforms;
3. state machine and fake Provider experience;
4. OpenAI/DeepSeek streaming and secure profiles;
5. local recognition routing;
6. Room memory and reconstruction;
7. settings, portrait lock, recovery, accessibility, and device verification.

Each slice keeps the fake Provider experience runnable. Rollback disables/removes the newest slice without changing Rust or corrupting prior Android data.

## 15. Decisions

- Primary UX is full-screen magical paper.
- Vision models receive images; text-only models use local recognition.
- Tool-agent capabilities are deferred.
- Settings defaults to three-finger long press for 2 seconds behind a replaceable policy.
- Android 13/API 33 through Android 16/API 36 are supported by one full-feature APK; the project compiles against API 36.1 and targets API 36.
- Finger and pressure stylus input are supported.
- Rotation is supported with optional portrait lock.
- OpenAI/DeepSeek presets and custom HTTPS OpenAI-compatible profiles are supported.
- Compose hosts app UI; custom View/Canvas implements paper rendering.

## 16. Unresolved questions

There are no blocking product questions for implementation planning. Exact dependency versions, package name, display name, signing setup, and local handwriting-recognition library shall be fixed in the implementation plan using available Android tooling and documented ADRs where required. Signing secrets shall never enter the repository.
