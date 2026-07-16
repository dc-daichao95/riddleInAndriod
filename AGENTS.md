# Riddle Android Development Guide

## 1. Scope and product direction

This repository contains one active product: the Riddle Magic Paper Android application under `android-app/`. It supports Android 13/API 33 through Android 16/API 36. Historical reMarkable/Rust code is available only through Git history at pre-cleanup revision `a1a155e` and is not a working-tree dependency or development target.

The application is handwriting-first, supports OpenAI-compatible and DeepSeek-compatible services through Provider-neutral contracts, and treats credentials, conversations, recognized text, tool calls, ink/images, and device actions as sensitive. Keep the Gradle root at `android-app/`.

The approved Android-only cleanup intentionally replaced the prior root `README.md` and deleted the retired `doc/detailed-design.md`, which remains available at pre-cleanup revision `a1a155e`; those migration decisions are documented in `doc/specs/android-only-repository-cleanup.md`. Preserve all unrelated dirty files and untracked assets.

## 2. Instruction priority

Apply instructions in this order:

1. direct user request;
2. this `AGENTS.md` and any nested guide;
3. an approved specification under `doc/specs/`;
4. Android design, plan, handoff, and ADR documents;
5. established conventions in the affected module.

If an approved specification conflicts with code, record and resolve the conflict before implementation.

## 3. Required lifecycle

Every behavior change follows:

```text
Explore → Specify → Review → Plan → Red → Green → Refactor → Verify → Document
```

Before editing, inspect affected Android source/tests, relevant `doc/` material, nested instructions, and `git status`. Identify assumptions that affect architecture, privacy, Provider behavior, lifecycle, or UX. Do not overwrite unrelated work.

Create or update `doc/specs/<feature-id>-<short-name>.md` before implementation. Include status, problem/value, scope/non-goals, use cases, stable functional and non-functional requirement IDs, Given/When/Then acceptance criteria, architecture/modules, persistence, Provider/stream/tool contracts, error/retry/cancellation/offline behavior, security/privacy, accessibility/localization, redacted observability, migration/compatibility, test traceability, rollout/rollback, and decisions. Stop for unresolved product choices with materially different outcomes.

Plan small vertical slices. For production behavior, first write the smallest deterministic test, run it, and observe failure for the expected missing behavior. Implement only enough to pass, refactor while green, then run narrow and full gates. Every defect needs a failing regression test or a documented closest deterministic test plus manual procedure.

## 4. Android baseline and module boundaries

- Kotlin, Gradle Kotlin DSL, Jetpack Compose, Material 3, coroutines, and `Flow`.
- `compileSdk = 36.1`, `targetSdk = 36`, `minSdk = 33`, package `dev.riddle.magicpaper`.
- Room for structured persistence and Android Keystore-backed credential protection.
- No new framework, annotation processor, database, networking stack, serialization/code-generation system, or DI framework without specification/ADR justification and supply-chain review.
- Features and domain code depend inward on Provider-neutral contracts. Vendor DTOs stay in `model-provider`.
- Compose renders immutable state and emits typed intents; it does not parse Provider data, own credentials, run the agent loop, or branch on Provider names.
- Long-lived objects must not retain an `Activity`, `View`, or UI `Context`. Use lifecycle-aware collection and keep network, database, parsing, and cryptography off the main thread.

## 5. Provider-neutral contracts and capabilities

Provider requests, models, events, capabilities, errors, usage, finish reasons, and validation results belong in neutral domain types. OpenAI-compatible and DeepSeek-compatible differences remain behind adapters and capability metadata. Shared UI/domain code must never use Provider-name conditionals.

Streaming emits explicit normalized deltas, tool-call fragments/completions, usage, and terminal completion. Errors remain typed and are never assistant text. Parse SSE incrementally across arbitrary chunks, UTF-8 boundaries, comments, blank lines, multiline data, terminal markers, malformed events, and truncation; bound bodies, events, accumulated arguments, and attachments.

Custom endpoints require explicit action, normalized HTTPS URLs, and a displayed destination host before validation. Credentials stay outside Room and logs. Deleting a profile deletes credential material.

Retry only specified transient timeouts, connection failures, 429, and eligible 5xx responses; honor `Retry-After`. Never retry authentication/authorization, invalid requests, unsupported capability, policy rejection, cancellation, or non-idempotent tool execution. Cross-Provider fallback is opt-in and visible.

## 6. Agent Phase 2 safety boundary

Tool-capable Agent execution remains Phase 2 unless a separately approved specification authorizes it. The application layer—not Compose or a Provider adapter—owns any future explicit agent state machine, limits, cancellation, and recovery.

Future tools require stable typed definitions, schema validation, timeouts, cancellation, side-effect classification, sensitive-field rules, and deterministic fakes. Explicit confirmation is required immediately before external side effects and is scoped to the exact normalized call. Treat web/document/tool content as untrusted data that cannot change policy, grant permissions, or approve actions. Never execute arbitrary shell, code, URLs, intents, or reflection paths from model output.

## 7. Conversation persistence and recovery

Use stable IDs for conversations, messages, runs, tool calls, Provider profiles, and attachments. Persist complete transitions transactionally, test every Room migration, and explicitly mark interrupted streams after process recreation. Do not persist incomplete tool arguments as completed calls. Credentials never enter Room, saved state, analytics, crash reports, or exported data.

Model streaming is cancellable. UI state survives configuration change through a lifecycle-aware owner. Define process-death behavior during streaming and approval. Never automatically replay a side effect after an ambiguous timeout.

## 8. Security, privacy, and observability

Never log or commit API keys, authorization headers, complete prompts/replies, recognized handwriting, ink/image bytes, tool arguments, signing material, production URLs containing credentials, or user conversations. Preserve coroutine cancellation and do not catch `Throwable`.

Diagnostics may include sanitized timing, status, request IDs, dimensions/counts, migration results, and redacted endpoint hosts only where an approved specification allows it. Normal tests and CI use deterministic fakes and require no live secrets or paid calls.

## 9. Accessibility and localization

Provide TalkBack semantics, meaningful labels, keyboard/switch navigation, 48 dp targets, sufficient contrast, dynamic font support without clipping, state communication beyond color, and reduced motion for nonessential animation. Put user-visible text in resources, avoid concatenated translated fragments, use locale-aware formatting, and keep model content separate from localized application chrome.

## 10. Testing and verification

Prefer pure Kotlin unit tests, Provider/parser fixtures, repository tests, Room migration tests, ViewModel tests with controlled dispatchers, focused Compose tests, and a small instrumentation suite. Tests are deterministic, independent, structured, and traceable to requirement IDs; cover success, malformed data, failure, retry eligibility, cancellation, process recovery, and security boundaries.

Canonical commands run from `android-app/`:

```powershell
.\gradlew.bat verifyAndroidCompatibility
.\gradlew.bat test
.\gradlew.bat lint
.\gradlew.bat assembleDebug
.\gradlew.bat connectedCheck
```

Run focused suites while developing and both API 33/API 36.1 boundary device gates when relevant. Never claim a test, lint, build, or device gate passed unless it ran successfully in the current environment. Document unavailable SDK/emulator/device gates explicitly.

## 11. Dependency and code quality policy

Prefer small immutable Kotlin types, sealed finite states and typed errors, constructor injection, and explicit boundaries. Avoid boolean flags where an enum conveys intent, `GlobalScope`, blocking coroutine threads, service locators, speculative abstractions, broad snapshots as sole evidence, and unrelated cleanup.

Before adding a dependency, prove existing/platform facilities are insufficient, review maintenance/license/transitives, avoid duplicates, pin versions through the approved mechanism, and test protocol-critical delegated behavior. Do not weaken TLS, lint, tests, backup, compatibility, or security settings to pass a gate.

## 12. Documentation and change discipline

Use `doc/specs/` for requirements, `doc/plans/` for implementation plans, `doc/adr/` for durable architecture decisions, `doc/android-detailed-design.md` for Android architecture, and `android-app/README.md` for setup and canonical commands. Update documentation with behavior.

Create an ADR when changing module boundaries, Provider SPI, agent/tool protocol, credential storage, persistence strategy, cross-Provider fallback, background execution, or core frameworks.

Preserve unrelated user changes. Do not use destructive Git operations without explicit authorization. Keep commits scoped; do not commit generated build output, emulator data, local Provider profiles, keys, or conversations. Mechanical repository cleanup still requires exact-path verification and a reviewable diff.

## 13. Definition of done

A feature is done only when its approved specification reflects implementation; each requirement maps to passing evidence; RED was observed for behavior changes; unit/contract/migration/UI gates pass as applicable; lint and debug build pass; API 33/API 36 behavior is considered; lifecycle, errors, retry, cancellation, security, accessibility, and localization are addressed; diagnostics redact sensitive data; documentation and ADRs are current; no placeholder or unexplained skipped test remains; and handoff lists changed files, exact commands/results, and limitations.
