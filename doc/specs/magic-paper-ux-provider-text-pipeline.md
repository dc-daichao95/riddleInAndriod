# Magic Paper UX, Provider Setup, and Text-Model Pipeline Specification

- Status: `approved`
- Product: Riddle Android
- Approval: user approved the recommended design on 2026-07-14
- Runtime: Android 13 / API 33 through Android 16 / API 36
- Parent specification: `doc/specs/android-magic-paper-app.md`

## 1. Problem statement and user value

Real-device testing found that the three-finger settings gesture conflicts with Android system gestures, Provider setup requires an unfriendly save-before-test workflow, and text-only models such as DeepSeek cannot complete the magic-paper experience. The current implementation also reports dissolve and reply states without rendering the required staged ink dissolve or handwritten reply playback.

The repair must make setup discoverable and safe while preserving immersion, and must provide the same complete handwriting-to-reply experience for vision and non-vision models.

## 2. Scope and non-goals

In scope:

- a safe-inset-aware magical rune button as the default settings entry;
- a configurable settings-entry mode with the legacy three-finger policy retained but disabled by default;
- validation-first Provider setup with model discovery and a model selector;
- secure temporary credential handling during validation;
- local handwriting-model provisioning for text-only Providers;
- real staged user-ink dissolve and handwritten reply playback;
- reply linger, dissolve, cancellation, interruption, error recovery, and process/configuration behavior;
- current-window raster geometry, safe insets, and monotonic timing;
- identical behavior and tests on API 33 and API 36.

Non-goals:

- agent tool execution;
- silent cross-provider fallback;
- cloud handwriting recognition;
- automatic Provider capability inference from vendor or model names;
- a multi-screen settings navigation framework;
- Android versions earlier than API 33.

## 3. User stories and use cases

- A user taps a subtle magical rune without invoking an Android system gesture and enters settings without losing the draft.
- A user selects a Provider preset, enters an API key, confirms the destination host, validates it, receives available models, selects one, and saves it.
- A user writes to a text-only model; the app prepares local recognition, dissolves the submitted ink, streams the answer, and writes it back stroke by stroke.
- A user cancels or begins writing during a response; stale Provider or animation events cannot alter the new page.
- A user with TalkBack, large text, reduced motion, a display cutout, or a gesture-navigation device can complete both flows.

## 4. Functional requirements

- `MPX-FR-001`: The default settings entry shall be a magical rune button inside safe drawing insets, with a visual size of 28–32 dp and a touch target of at least 48 dp.
- `MPX-FR-002`: A single rune tap shall pause inactivity submission, preserve the current draft, emit the existing settings-navigation effect, and provide reduced-motion-compatible feedback.
- `MPX-FR-003`: Settings entry shall use a configurable `SettingsEntryMode`; `MAGIC_RUNE_BUTTON` is the default and the existing three-finger policy remains available but disabled by default.
- `MPX-FR-004`: Provider setup shall use one responsive three-stage screen: preset/endpoint, credential validation and model discovery, then model selection and save.
- `MPX-FR-005`: Validation shall show the normalized HTTPS destination host before network access and require explicit confirmation for custom endpoints.
- `MPX-FR-006`: Successful validation shall call the Provider-neutral model-discovery contract, deduplicate models by stable ID, sort them deterministically, and expose a selectable model list.
- `MPX-FR-007`: If a compatible endpoint does not support model discovery, the UI shall offer an explicit manual model-ID fallback followed by a minimum completion validation; it shall not silently assume success.
- `MPX-FR-008`: A new Provider profile shall not become selectable or durable until validation succeeds and a model is selected. Editing an existing profile with an empty key shall retain its credential.
- `MPX-FR-009`: Validation failure, cancellation, or editor abandonment shall restore the prior credential or delete temporary credential material.
- `MPX-FR-010`: Text-only model submission shall ensure the locale-appropriate ML Kit handwriting model is locally available, downloading it when required with cancellable, deduplicated work.
- `MPX-FR-011`: Recognition-model preparation, recognition, and Provider errors shall be visible, localized typed states; the original ink shall remain recoverable and no Provider request shall occur after a recognition failure.
- `MPX-FR-012`: Submitted user ink shall remain in the render model while dissolve stages 0 through 13 are rendered in order, and shall be removed only after the final stage.
- `MPX-FR-013`: A Provider text delta shall be converted into provider-neutral normalized reply strokes and progressively rendered through `MagicPaperView`; ordinary visible Compose text shall not substitute for handwriting.
- `MPX-FR-014`: Later deltas shall append to the reply plan without resetting or replaying completed reply strokes.
- `MPX-FR-015`: The final reply shall linger for a clamped 4–20 seconds, dissolve through stages 0 through 9, and return to `Listening` automatically.
- `MPX-FR-016`: Cancel, new writing, navigation, and lifecycle teardown shall cancel recognition, Provider collection, dissolve, and reply playback. Generation IDs shall reject late events.
- `MPX-FR-017`: Rasterization shall use the page/window geometry current at commit time rather than dimensions captured when the application container was constructed.
- `MPX-FR-018`: Paper and settings controls shall respect display cutout, status/navigation, IME, and gesture-navigation safe insets.
- `MPX-FR-019`: Inactivity and animation deadlines shall use a monotonic clock.
- `MPX-FR-020`: Vision and text-only Providers shall converge on the same state-machine phases and rendering lifecycle after input routing.
- `MPX-FR-021`: Reply layout shall paginate at grapheme boundaries using current safe page bounds; all accumulated model text shall appear on exactly one reply page or as an explicit unsupported-glyph placeholder, and no text may be silently discarded.
- `MPX-FR-022`: Reply rendering shall use bundled, version-pinned fonts: the existing OFL Dancing Script for supported Latin glyphs and unmodified LXGW WenKai Regular v1.522 for supported CJK glyphs. Both OFL license texts shall ship with the application/repository.
- `MPX-FR-023`: Text shall be segmented by an internal Unicode-15.0 grapheme implementation. A cluster missing from both bundled fonts, including unsupported emoji or script shaping, shall render one deterministic boxed rune containing its Unicode code-point label while the exact original cluster remains in accessibility and persisted reply text.
- `MPX-FR-024`: Each reply page shall write, linger, and dissolve before the next queued page begins. Stream deltas may extend the current final page or append pages, but may not reorder, replay, or drop previously planned graphemes.
- `MPX-FR-025`: Configuration recreation shall retain the Activity-owned ViewModel, active job, generation, animation cursor, and monotonic deadline. Final ViewModel teardown shall cancel them. OS process death shall mark the run interrupted, restore the submitted draft, and render any persisted partial reply as non-animated interrupted strokes without resending the request.
- `MPX-FR-026`: Reduced-motion mode shall suppress rune shimmer, emit dissolve stages without inter-stage delay, jump reply playback to each complete page, retain the normal 4-second minimum readable linger, and keep all state/accessibility announcements.

## 5. Non-functional requirements

- `MPX-NFR-001`: The repair shall remain Provider-neutral; shared UI/domain code shall not branch on DeepSeek, OpenAI, or model-name strings.
- `MPX-NFR-002`: Normal input, dissolve, and reply playback shall avoid per-point Compose state replacement and full bitmap-cache rebuilds.
- `MPX-NFR-003`: API keys shall not enter `StateFlow`, SavedState, Room, logs, analytics, crash reports, screenshots, or source control.
- `MPX-NFR-004`: The same production feature set and test inventory shall run on API 33 and API 36 without version-specific exclusions.
- `MPX-NFR-005`: All new animations shall honor reduced-motion settings while retaining understandable state transitions.
- `MPX-NFR-006`: Production changes shall be vertical and minimal; unrelated framework, dependency, persistence, or Provider refactoring is prohibited.
- `MPX-NFR-007`: Font rendering, grapheme segmentation, pagination, and unsupported-glyph placeholders shall be deterministic across API 33 and API 36.1 for the same text and page geometry.
- `MPX-NFR-008`: The CJK font shall be downloaded only from the official `lxgw/LxgwWenKai` v1.522 release, verified by a recorded SHA-256 checksum, kept unmodified to preserve its reserved font name, and redistributed with OFL 1.1.
- `MPX-NFR-009`: The internal grapheme segmenter shall pass every case in Unicode 15.0.0 `GraphemeBreakTest.txt`, pinned from the official Unicode data distribution with SHA-256 and the Unicode data license recorded in the repository.

## 6. Acceptance criteria

- `MPX-AC-001`: Given a draft on the paper, when the safe-area rune is tapped, then settings opens, the draft remains unchanged, and the inactivity timer is paused.
- `MPX-AC-002`: Given the default entry configuration, when a three-finger hold occurs, then it does not open settings; when the legacy mode is explicitly selected, then the existing gesture works.
- `MPX-AC-003`: Given a valid key and confirmed host, when “Validate and get models” is selected, then a sorted, deduplicated model selector appears without first saving a selectable profile.
- `MPX-AC-004`: Given authentication, network, cancellation, or unsupported-model-list failure, when validation ends, then the error is distinguishable and no orphan credential or new selectable profile remains.
- `MPX-AC-005`: Given a clean install and a non-vision model, when ink auto-submits, then the local recognition model is downloaded at most once, recognized text is sent to the selected Provider, and the original ink remains until dissolve completes.
- `MPX-AC-006`: Given visible submitted ink, when dissolve runs, then stages 0 through 13 are observed in order and bitmap alpha/ink coverage decreases monotonically before the source strokes are cleared.
- `MPX-AC-007`: Given the first text delta before stream completion, when reply playback advances, then new handwritten pixels are visible in `MagicPaperView`; a later delta appends without resetting prior progress.
- `MPX-AC-008`: Given a completed reply, when linger and stages 0 through 9 finish, then the paper returns to `Listening` with no stale reply pixels.
- `MPX-AC-009`: Given an active stream or animation, when the user writes or cancels, then the operation stops, late events are ignored, and the new stroke remains authoritative.
- `MPX-AC-010`: Given rotation, split-window, or recreation, when a new page is submitted, then raster geometry matches the current rendered page and controls remain inside safe insets.
- `MPX-AC-011`: Given API 33 and API 36.1 AVDs, when the identical non-vision end-to-end suite runs, then all cases pass with zero failures, errors, or skips.
- `MPX-AC-012`: Given a reply longer than one page and deltas split inside grapheme sequences, when playback completes, then concatenating page source ranges exactly reproduces the original reply and no range overlaps or has a gap.
- `MPX-AC-013`: Given Latin, simplified Chinese, mixed punctuation, combining marks, ZWJ emoji, RTL text, and an unsupported code point, when pages are planned on API 33 and API 36.1, then normalized stroke/page metadata is identical; unsupported clusters produce one labeled placeholder and remain exact in accessibility text.
- `MPX-AC-014`: Given recreation during input dissolve, reply playback, or linger, when the Activity is recreated, then the same generation resumes from the retained cursor/deadline. Given OS process death, then no Provider request is replayed and the run is restored as interrupted with draft and partial reply data preserved.
- `MPX-AC-015`: Given reduced motion, when the complete flow runs, then rune shimmer is absent, dissolve stages have zero delay, each reply page appears complete, and its readable linger is at least 4 seconds.
- `MPX-AC-016`: Given a populated Room v1 database, when migration to v2 runs, then all pages/strokes/profiles remain byte-equivalent and the new interrupted-run table is valid and empty. Given a v2 interrupted run, process recovery replans its partial reply without a network request.
- `MPX-AC-017`: Given process death at every candidate-credential journal boundary, when startup recovery runs repeatedly, then exactly the durable profile-referenced alias survives, prior referenced credentials are never deleted, selection matches `selectionRequested`, and the journal is eventually cleared without exposing a secret.

## 7. Architecture and affected modules

- `feature-paper`: rune entry, immutable UI state, pipeline coordination, accessible status.
- `feature-settings`: staged editor, validation/discovery state, model selector, responsive/inset-aware UI.
- `conversation`: recognition-model provisioning port and typed failures.
- `paper-engine`: staged dissolve, reply-stroke planning/playback, current page geometry.
- `model-provider`: Provider-neutral model discovery and explicit unsupported fallback mapping.
- `security`: temporary credential transaction and cleanup.
- `app`: composition, monotonic clock, current geometry source, fake non-vision device test path.

The existing state machine and orchestrator remain authoritative. Render effects shall be executed instead of inferred from phase labels.

## 8. Data model and persistence changes

Add transient editor/model-discovery state without secret values. Persist validated Provider configuration, selected model ID, and entry-mode enum. Room schema v2 adds an `interrupted_runs` write-ahead record containing stable run/page ID, draft reference, accumulated partial reply source text, recovery status, and update time. Create/update this record transactionally before the corresponding submitted state or newly received reply prefix becomes visible to UI/rendering. On startup, atomically convert any write-ahead `ACTIVE` record to terminal `INTERRUPTED`, then restore; never resend the request. Delete or terminally resolve the record only after clean completion/cancellation state is durably persisted. Configuration recreation retains active generation/cursor/deadline only in the retained ViewModel. OS process recovery creates no active generation and replans page ranges/strokes deterministically from persisted partial text. The v1→v2 migration preserves every page/stroke/profile and creates the empty run table transactionally; migration tests validate populated v1 data. Rollback disables new behavior while retaining schema v2 rather than installing a schema-v1 binary.

Credential commit protocol:

1. The password field owns a non-saveable, non-observable ephemeral buffer. Before touching Keystore, synchronously persist a secret-free journal containing profile ID, unique `candidate-<profile-id>-<nonce>` alias, prior alias, `selectionRequested`, and phase `INTENT`.
2. Write the candidate secret, immediately clear the field, then durably advance the journal to `CANDIDATE_WRITTEN` before network access.
3. Discovery/validation uses a temporary configuration referencing the candidate alias. Failure, cancellation, editor abandonment, or invalid host deletes the candidate then clears the journal; the prior profile/alias remains authoritative.
4. On save, persist the validated profile referencing the candidate alias. This profile upsert is the commit point. Then durably mark `PROFILE_COMMITTED`, apply requested selection, delete the prior alias only after no durable profile references it, and clear the journal.
5. Startup recovery is idempotent and inspects both journal phase and durable profile references: `INTENT` with no candidate clears the journal; `INTENT` or `CANDIDATE_WRITTEN` with an unreferenced candidate deletes it and clears the journal; any phase whose candidate is already referenced treats the profile as committed, applies `selectionRequested`, deletes only an unreferenced prior alias, and clears the journal; `PROFILE_COMMITTED` performs the same completion. Candidate existence/read failure produces a typed inconsistent-storage state and never deletes the prior alias.
6. An edit with an empty secret creates no journal/candidate and retains the prior alias. Managed aliases are never deleted while referenced by any durable profile. Every crash boundary above has a table-driven restart test.

## 9. API, Provider, streaming, and tool-call contracts

The Provider SPI shall replace ambiguous `Result<List<ModelDescriptor>>` discovery with:

```kotlin
sealed interface ModelDiscoveryResult {
    data class Success(val models: List<ModelDescriptor>) : ModelDiscoveryResult
    data object Unsupported : ModelDiscoveryResult
    data class Failed(val error: ModelError) : ModelDiscoveryResult
}
```

Both OpenAI-compatible and DeepSeek-compatible adapters shall implement the same contract suite. Blank IDs are rejected; IDs are trimmed, compared case-sensitively, first response metadata wins duplicates, and results sort by Kotlin/Unicode code-point string order independent of locale.

Discovery limits are identical for both adapters: maximum HTTP body 2 MiB; maximum 2,000 raw catalog entries; model ID maximum 256 UTF-8 bytes; display name maximum 512 UTF-8 bytes; sanitized error body maximum 64 KiB. A body, catalog, ID, or display name over its limit terminates discovery with typed `ModelError.ResponseTooLarge` or `ModelError.InvalidResponse`; entries are never silently truncated and partial catalogs are never exposed.

After HTTPS host confirmation, authenticated discovery runs first. `Success` with one or more models validates the credential without a completion call. Authentication, authorization, rate-limit, network, timeout, cancellation, and malformed responses remain typed failures. `Unsupported` or an explicitly empty catalog enables manual model entry only after the user accepts that one validation request may be billable. That request contains only the literal application string `ping`, `maxOutputTokens = 1`, no system message, page, conversation, attachment, tools, metadata, or automatic retry, with a 10-second total timeout. A provider-SPI ADR is required in the same slice.

Provider validation and discovery use the candidate credential alias, not a secret-bearing domain model. Image inputs shall use typed provider-neutral attachment content; OpenAI data-URL encoding belongs in the adapter. Tool calls remain parsed but never executed.

## 10. Error, retry, cancellation, and offline behavior

Model download, handwriting recognition, authentication, authorization, model-discovery unsupported, rate limit, transient network, malformed stream, and playback failures are distinct. No paid request is retried merely to populate settings. Recognition-model download may retry only by explicit user action after failure.

Recognition downloads are coordinated per `(languageTag, modelVersion)` at application scope. Concurrent waiters share one backend invocation. Cancelling one waiter does not cancel shared work while another waiter remains. When the final waiter cancels, cancellation is requested best-effort and late completion is ignored; because the ML Kit task may continue, the next process/session always checks installed state before requesting another download. “At most once” means one concurrent backend invocation per key, not one lifetime attempt.

Cancellation propagates through recognition waiter, Provider, raster, dissolve, and playback. Offline text submission retains ink and explains the missing local prerequisite or network state.

## 11. Security and privacy analysis

- Confirm destination host before using a credential.
- Follow the journaled candidate-alias commit/recovery protocol above; no cross-store atomic rename is assumed.
- Never expose raw keys through UI state or diagnostics.
- The secret exists only in a non-saveable local password-field buffer until it is synchronously accepted by the credential sink. It is cleared on acceptance, disposal, Activity backgrounding, and navigation. Copy/cut, autofill, text suggestions, and state restoration are disabled.
- Settings set `FLAG_SECURE` while the credential editor is visible and clear it when leaving settings; tests verify screenshots/recent-app previews are protected without leaving the paper window permanently secure.
- Delete temporary raster files after success, failure, or cancellation.
- Treat model lists and Provider error bodies as untrusted, size-bounded data.
- Preserve HTTPS-only and no-cleartext policies.

## 12. Accessibility and localization impact

The rune has a localized “Open magic settings” label, button role, 48 dp target, focusability, and non-color-only feedback. Settings use semantic headings, field errors, progress announcements, and a keyboard-accessible model selector. Visible and TalkBack state covers model preparation, recognizing, dissolving, thinking, writing, completed, cancelled, and failed. Strings are English/Chinese resources. Large font and narrow width must not clip controls. The exact reply source text remains available as one accessibility node per page, including clusters displayed as placeholder runes; it is not duplicated as visible Compose text.

## 13. Observability requirements and redaction

Tests/diagnostics may record phase, API level, model count, elapsed duration, typed error, animation stage, and Provider request ID. They shall not record keys, authorization headers, full prompts, recognized handwriting, replies, raw model-list bodies, or image bytes.

## 14. Migration and compatibility impact

Existing Provider profiles remain readable and are marked `NotTested` until revalidated when edited. Existing selected profiles remain usable unless invalid. The default settings-entry mode changes from three-finger to rune button. An explicitly persisted legacy three-finger value remains valid; missing, unknown, corrupt, or future values migrate to the rune. API 33–36 share one implementation and APK.

## 15. Test strategy and requirement traceability

| Requirements | Planned evidence |
| --- | --- |
| MPX-FR-001–003; MPX-AC-001–002 | Compose bounds/semantics, entry migration, draft/timer tests |
| MPX-FR-004–009; MPX-AC-003–004 | editor state, journal crash-boundary, host, cleanup, discovery/save Compose tests |
| MPX-FR-010–011; MPX-AC-005 | shared ML Kit backend tests: installed, concurrent download, per-waiter/final cancellation, process restart, failure |
| MPX-FR-012; MPX-AC-006 | dissolve stage sequence and bitmap coverage tests |
| MPX-FR-013–015; MPX-AC-007–008 | reply planner, append/playback, linger/dissolve pixel tests |
| MPX-FR-016; MPX-AC-009 | generation cancellation, late-event, write interruption tests |
| MPX-FR-017–020; MPX-AC-010–011 | geometry, inset bounds, monotonic clock, identical API 33/API 36.1 non-vision instrumentation |
| MPX-FR-021–024; MPX-AC-012–013 | pinned-font checksum/license, Unicode grapheme corpus, placeholder, bidi, pagination/no-loss and cross-API golden metadata tests |
| MPX-FR-025; MPX-AC-014 | recreation during every animation phase, final teardown cancellation, process-death interruption restoration tests |
| MPX-FR-026; MPX-AC-015 | reduced-motion zero-delay/final-page/4-second linger and announcement tests |
| MPX-AC-016 | Room v1→v2 populated migration and interrupted-run recovery/no-request tests |
| MPX-AC-017 | exhaustive candidate-journal crash-boundary and idempotent restart tests |
| MPX-NFR-001 | shared OpenAI/DeepSeek discovery and stream/render contract suite; no vendor-name shared-code scan |
| MPX-NFR-002 | render-cache counters and no per-point Compose mutation tests |
| MPX-NFR-003 | StateFlow/SavedState/database/log/artifact sentinel scans and FLAG_SECURE lifecycle tests |
| MPX-NFR-004, MPX-NFR-007 | identical sorted test inventory and deterministic metadata on API 33/36.1 |
| MPX-NFR-005 | reduced-motion tests mapped to MPX-AC-015 |
| MPX-NFR-006 | per-slice production diff review against failing-test justification |
| MPX-NFR-008 | official release URL, v1.522 pin, SHA-256, unmodified-font and OFL presence verification |
| MPX-NFR-009 | full pinned Unicode 15.0.0 GraphemeBreakTest conformance suite and license/checksum verification |

Normal tests use deterministic fake recognition, Provider events, clocks, and dispatchers. Optional real ML Kit/provider smoke tests are separately tagged and require no committed secrets.

## 16. Rollout and rollback plan

Implement as reviewable vertical commits defined in the repair plan. Keep the existing fallback UI only until the handwritten renderer passes pixel tests, then remove visible Compose reply text. Rollback must always retain the rune settings entry. Editor discovery and animation implementations may independently fall back to the last verified profile and static final reply strokes while preserving validated profiles and drafts; rollback must not restore the conflicting three-finger default or leak candidate credentials.

## 17. Decisions

- Use a subtle upper-right magical rune button as the default settings entry.
- Retain but default-disable the three-finger policy.
- Use a single responsive three-stage Provider screen.
- Validate and discover models before saving/selecting a new profile.
- Automatically provision local handwriting recognition for text-only models.
- Render real staged dissolve and reply strokes through `MagicPaperView`.
- Move the configuration-only `minSdk = 33` slice before repair implementation so API 33 evidence is possible; defer any behavior compatibility guard until a real API 33 failure proves it necessary.
- Bundle unmodified LXGW WenKai Regular v1.522 from the official repository under OFL 1.1 for CJK and keep the existing Dancing Script for Latin.
- Preserve long replies through deterministic grapheme-safe pagination with explicit missing-glyph placeholders.

Font source decision evidence:

- Official project/release source: `https://github.com/lxgw/LxgwWenKai`, release v1.522.
- Official license: SIL Open Font License 1.1; the upstream project explicitly permits embedding and redistribution with the OFL text.
- `android-app/paper-engine/src/main/assets/fonts/DancingScript.ttf` and its adjacent `OFL.txt` remain the Latin source. The LXGW asset has its own adjacent upstream OFL text and checksum manifest.
- Unicode conformance source: `https://www.unicode.org/Public/15.0.0/ucd/auxiliary/GraphemeBreakTest.txt`; redistribute it with the Unicode data license from `https://www.unicode.org/license.txt` and a recorded SHA-256 checksum.

## 18. Unresolved questions

None. The user approved the recommended design on 2026-07-14.
