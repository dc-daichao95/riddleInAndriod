# Magic Paper Controls, Language, and Provider Repair Specification

- Status: `approved`
- Product: Riddle Android
- Approval: user confirmed the proposed design on 2026-07-15
- Runtime: Android 13 / API 33 through Android 16 / API 36
- Parent: `doc/specs/magic-paper-ux-provider-text-pipeline.md`

Implementation progress (2026-07-16): `MCL-FR-001..010` are implemented and independently approved in `147120f`, `ac344d3`, `09fda56`, and `2cf0a88`. The latest gate passed 185/185 JVM tests, Android 16 `MagicRuneSettingsTest` 8/8, instrumentation compilation, and `lintDebug`. `MCL-FR-011..018` remain pending Tasks 3–4, so the overall specification remains `approved`, not `implemented`.

## 1. Problem statement and user value

Installed builds can hide model selection when model discovery is unsupported or empty, render JSON null as visible reply text, silently use an English fake provider when no profile is selected, send Chinese handwriting to an English recognizer, and display model replies as ordinary Compose text. The paper also lacks explicit immersive send and tool controls, and its input dissolve lacks the intended magical character.

The repair makes setup explicit, preserves language intent, prevents invalid visible text, and completes an understandable paper-first interaction without relying on system-conflicting gestures.

## 2. Scope and non-goals

In scope:

- deterministic provider discovery success and manual fallback UX;
- null-safe streaming/provider-profile JSON parsing;
- explicit handwriting language preference: automatic, Simplified Chinese, Traditional Chinese, English;
- provider-neutral same-language response instruction;
- no silent production fallback when no provider is selected;
- visible reply stroke playback through `MagicPaperView`;
- send rune, writing lock, pen/eraser chooser, cancel affordance, magical dissolve polish;
- TalkBack, localization, reduced motion, lifecycle and cancellation tests.

Non-goals:

- agent tools;
- cloud OCR;
- arbitrary language detection from ink alone;
- vendor-specific prompt branching;
- changing the approved provider SPI or font licenses;
- Android versions below API 33.

## 3. User stories and use cases

- A new user validates a compatible endpoint and either selects a discovered model or sees an explicit prefilled manual fallback.
- A user never sees literal `null` tokens caused by provider protocol metadata.
- A Chinese writer explicitly selects Simplified or Traditional Chinese and the matching local recognizer receives the page.
- A user writes Chinese and receives a Chinese answer unless the user explicitly asks for another language.
- A user taps a magical send rune, cannot accidentally add strokes during the active turn, can cancel, and sees the answer written back as ink.
- A user selects pen or eraser from a compact magical tool chooser.

## 4. Functional requirements

- `MCL-FR-001`: A non-empty successful model discovery shall show a sorted, deduplicated model selector.
- `MCL-FR-002`: Unsupported or empty discovery shall remain a typed fallback state, preserve and prefill the preset model ID, explain the fallback visibly, and require minimum completion validation before save.
- `MCL-FR-003`: Authentication, rate-limit, network, malformed-data and cancellation errors shall remain distinguishable and shall not create a selectable profile.
- `MCL-FR-004`: JSON null for content, reasoning, tool fields or nullable persisted model IDs shall be represented as absence, never the string `"null"`.
- `MCL-FR-005`: Production model selection without a selected valid profile shall return a localized configuration-required state and shall not invoke fake, recognition, raster or network work. Fake providers remain test/preview-only dependencies.
- `MCL-FR-006`: `HandwritingLanguage` shall contain `AUTOMATIC`, `SIMPLIFIED_CHINESE`, `TRADITIONAL_CHINESE`, and `ENGLISH` and be persistently configurable.
- `MCL-FR-007`: Automatic language canonicalization shall map `zh-CN` and `zh-SG` to `zh-Hans`, `zh-TW`, `zh-HK`, and `zh-MO` to `zh-Hant`, English locales to `en-US`, and otherwise use the existing documented fallback.
- `MCL-FR-008`: Explicit Simplified, Traditional or English selection shall override system locale when provisioning and invoking the handwriting recognizer.
- `MCL-FR-009`: Recognized text shall enter `ModelRequest` byte-for-byte as user content. A provider-neutral system instruction shall request the same response language unless the user explicitly requests another language.
- `MCL-FR-010`: Vision prompt construction shall use the same response-language policy without provider/model name conditions.
- `MCL-FR-011`: Provider text deltas shall extend `ReplyStrokePlanner.Stream`, update `ReplyPlayback`, and render visible reply paths in `MagicPaperView` before stream completion without replaying written prefixes.
- `MCL-FR-012`: Ordinary visible Compose reply text shall be removed after reply-pixel tests pass. Exactly one accessibility text node per current reply page shall retain the exact source text.
- `MCL-FR-013`: A safe-inset-aware magical send rune with at least a 48 dp touch target shall submit a non-empty draft immediately and pause inactivity submission.
- `MCL-FR-014`: From accepted send until completed, cancelled or failed terminal recovery, new stroke/erase input shall be rejected. A localized cancel affordance shall remain available.
- `MCL-FR-015`: The magical tool rune shall expose pen and eraser choices, communicate the selected tool without color alone, and not depend on multi-finger/system gestures.
- `MCL-FR-016`: Normal input dissolve shall retain deterministic stages 0 through 13 while adding bounded star-dust edge particles derived from stage, pixel position and generation. It shall not rebuild the full source raster per particle.
- `MCL-FR-017`: Reduced motion shall disable particle motion/shimmer, keep zero-delay dissolve stage progression, complete each reply page immediately, and retain the four-second readable linger.
- `MCL-FR-018`: Generation ownership shall reject late provider, recognition, send, tool and animation events after cancellation, navigation, teardown or process recovery.

## 5. Non-functional requirements

- `MCL-NFR-001`: Shared UI/domain behavior shall remain provider-neutral.
- `MCL-NFR-002`: Keys, prompts, recognized text and replies shall not be logged or placed in analytics/crash metadata.
- `MCL-NFR-003`: The implementation shall use existing Compose, View, Room, coroutines, Flow and networking stacks; no new runtime dependency is approved.
- `MCL-NFR-004`: API 33 and API 36 shall use the same production behavior and test inventory.
- `MCL-NFR-005`: Canvas rendering shall batch immutable reply snapshots and avoid per-point Compose state.
- `MCL-NFR-006`: All user-visible strings shall be English and Simplified Chinese resources.
- `MCL-NFR-007`: Controls shall meet 48 dp touch targets, TalkBack semantics, keyboard focus and dynamic-font constraints.

## 6. Acceptance criteria

- `MCL-AC-001`: Given fresh setup and a non-empty catalog, when validation succeeds, then `provider_model_selector` is visible and selectable before save.
- `MCL-AC-002`: Given unsupported or empty discovery, when validation completes, then a visible fallback explanation and the preset model ID appear and minimum completion validation is required.
- `MCL-AC-003`: Given stream chunks containing JSON null content/reasoning/tool fields, when parsed, then no event contains literal `"null"`.
- `MCL-AC-004`: Given a nullable persisted model, when encoded and decoded, then it remains null.
- `MCL-AC-005`: Given no selected provider, when send is requested, then the paper shows configuration-required state and no fake/provider/recognizer invocation occurs.
- `MCL-AC-006`: Given system locale `en-US` and explicit Simplified Chinese, when Chinese ink is submitted, then `zh-Hans` is provisioned/recognized and recognized `你好` is sent unchanged.
- `MCL-AC-007`: Given `zh-TW` automatic mode, when recognition begins, then `zh-Hant` is selected.
- `MCL-AC-008`: Given recognized Chinese input, when the request is built, then its provider-neutral instruction requests Chinese response and contains no vendor branch.
- `MCL-AC-009`: Given first provider delta before completion, when playback advances, then reply pixels increase in `MagicPaperView`; a later delta preserves existing pixel prefix/cursor.
- `MCL-AC-010`: Given reply playback, when the Compose tree is inspected, then visible ordinary reply text is absent and one exact accessibility page node exists.
- `MCL-AC-011`: Given a non-empty draft, when send rune is tapped, then one generation starts immediately and inactivity cannot submit a duplicate.
- `MCL-AC-012`: Given an active send-to-terminal interval, when drawing/erasing is attempted, then the draft/render state is unchanged; cancel restores an authoritative writable state.
- `MCL-AC-013`: Given pen/eraser selection, when a stroke or erase gesture is performed, then the selected tool behavior and accessible selected state match.
- `MCL-AC-014`: Given normal motion, when dissolve stages run, then source coverage decreases monotonically and bounded deterministic star-dust pixels appear/disappear without leaving stale pixels.
- `MCL-AC-015`: Given reduced motion, when the same flow runs, then no moving/shimmer particles occur and functional state announcements remain.

## 7. Architecture and affected modules

- `model-provider`: null-safe protocol mapping and shared contract fixtures.
- `feature-settings`: discovery fallback state, preset preservation, handwriting-language editor and resources.
- `conversation`: language policy/canonicalization and request instruction contract.
- `feature-paper`: send/tool intents, locked input policy, planner/playback orchestration and accessibility state.
- `paper-engine`: reply snapshot Canvas renderer and deterministic particle layer.
- `memory`/`app`: language preference persistence, Room v2 migration registration and active-run mapping.

UI remains an immutable state renderer. Provider adapters emit normalized events; the ViewModel owns generation/deadlines; `MagicPaperView` owns Canvas drawing only.

## 8. Data model and persistence changes

- Add a persisted enum value for handwriting language using stable ASCII names.
- Unknown/corrupt preference values migrate to `AUTOMATIC`.
- Register `RiddleDatabase.MIGRATION_1_2` in app composition.
- Reuse the v2 active reply run for partial source recovery; do not persist paths, cursors or particles.
- Nullable provider model values remain JSON null/absent, never string `"null"`.

## 9. API, provider, streaming and tool-call contracts

- Provider-neutral completion requests gain a language instruction through ordinary system-message content/policy, not vendor DTO fields.
- Model discovery maintains typed success, unsupported, empty, error and cancelled outcomes.
- Streaming null fields produce no delta event.
- No Agent tool-call behavior changes.

## 10. Error, retry, cancellation and offline behavior

- Provider setup preserves prior profile/credential on failure.
- Missing provider is a typed local error with no automatic retry/fallback.
- OCR model download remains cancellable/deduplicated and reports typed preparation failure.
- Send, playback, dissolve and provider work share generation cancellation. A late event cannot unlock or mutate a newer generation.
- Offline model preparation/network errors retain recoverable ink and selected language.

## 11. Security and privacy analysis

API keys remain Keystore-backed. Language preference is non-sensitive. Recognized text and replies remain sensitive content and are excluded from logs, SavedState bundles beyond approved partial-recovery storage, screenshots/analytics, and diagnostics. The no-provider error must not expose configuration internals.

## 12. Accessibility and localization impact

Send/tool/cancel runes require labels, roles, selected/disabled state and 48 dp targets. Locked paper announces why writing is disabled. Reply source is exposed once per page. All new copy is in `values` and `values-zh-rCN`. Particle animation is nonessential and removed in reduced-motion mode.

## 13. Observability and redaction

Allowed: phase, selected language enum, discovery outcome class, model count, animation stage and request ID. Forbidden: key, authorization, recognized text, prompt/reply body, model-list body and image bytes.

## 14. Migration and compatibility impact

Existing profiles/drafts remain readable. Missing language preference becomes `AUTOMATIC`. Existing v1 databases migrate through the approved v1-to-v2 migration. API 33 and API 36 share one APK and implementation.

## 15. Test strategy and traceability

| Requirements | Evidence |
| --- | --- |
| MCL-FR-001..003 | ViewModel and Compose provider setup tests with success/unsupported/empty/error fixtures |
| MCL-FR-004 | OpenAI/DeepSeek stream fixtures and profile JSON round-trip tests |
| MCL-FR-005 | no-selected-provider ViewModel/composition test with zero dependency calls |
| MCL-FR-006..010 | canonicalization, preference, OCR routing and request-capture tests |
| MCL-FR-011..012 | reply Canvas pixel growth, append non-replay and Compose semantics tests |
| MCL-FR-013..015 | send lock, duplicate deadline, pen/eraser and accessibility tests |
| MCL-FR-016..018 | dissolve coverage/particle bounds/reduced-motion/generation tests |
| Migration | populated database and preference compatibility tests |

Normal tests use fake clocks/providers/recognizers and no live keys.

## 16. Rollout and rollback plan

Land as independent reviewed commits: protocol/setup, language policy, controls/dissolve, reply integration. Do not remove visible Compose reply text until Canvas pixel tests pass. Rollback preserves selected profiles, language preference, drafts and active-run recovery.

## 17. Decisions

- Explicit language selector with automatic default.
- No production fake-provider fallback.
- Same-language response instruction is provider-neutral.
- One magical send rune and expandable pen/eraser chooser.
- Writing locks during the active request-to-terminal interval; cancel remains available.
- Deterministic bounded star-dust dissolve with reduced-motion suppression.

## 18. Unresolved questions

None. The user approved the design on 2026-07-15.
