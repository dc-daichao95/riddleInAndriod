# Riddle Development Guide

## 1. Scope and product direction

This repository currently contains the Rust implementation of Riddle for the reMarkable Paper Pro. Preserve it as the behavioral reference implementation unless a task explicitly changes the Rust product.

The next product is an Android application that:

- runs on Android 16 / API 36;
- provides the Riddle conversational and agent experience on Android;
- supports multiple remote model providers, including OpenAI/ChatGPT-compatible and DeepSeek-compatible services;
- can add providers without changing UI, conversation, or agent orchestration code;
- follows specification-driven development (SDD) and test-driven development (TDD);
- treats credentials, user conversations, tool calls, and device actions as sensitive data.

Place the Android project under `android-app/`. Do not convert the repository root into an Android-only project and do not break the existing Rust build.

The current implementation and design reference are:

- `src/` for existing behavior;
- `README.md` for product behavior and deployment notes;
- `doc/detailed-design.md` for the current detailed design.

## 2. Instruction priority

Apply instructions in this order:

1. direct user request;
2. this `AGENTS.md`;
3. an approved specification under `doc/specs/`;
4. repository design documents;
5. established code conventions in the affected module.

When an approved specification conflicts with current code, do not silently choose one. Record the conflict in the specification and resolve it before implementation.

## 3. Required development lifecycle

Every behavior-changing task must use the following lifecycle:

```text
Explore → Specify → Review → Plan → Red → Green → Refactor → Verify → Document
```

Do not start production implementation before the specification and acceptance criteria exist. Small mechanical changes may use a short specification, but may not skip expected behavior and verification.

### 3.1 Explore

Before editing:

- read this file and any nested `AGENTS.md` files;
- inspect the affected source and tests;
- read the relevant documents under `doc/`;
- identify whether behavior belongs to the existing Rust app, the Android app, or shared protocol documentation;
- identify existing uncommitted user changes and preserve them;
- list assumptions that materially affect architecture, privacy, provider behavior, or UX.

### 3.2 Specify (SDD)

Create or update a specification before implementation:

```text
doc/specs/<feature-id>-<short-name>.md
```

Use lowercase kebab-case filenames. Each specification must contain:

1. status: `draft`, `approved`, `implemented`, or `superseded`;
2. problem statement and user value;
3. scope and non-goals;
4. user stories or use cases;
5. functional requirements using stable IDs such as `FR-001`;
6. non-functional requirements using IDs such as `NFR-001`;
7. acceptance criteria in Given/When/Then form;
8. architecture and affected modules;
9. data model and persistence changes;
10. API, provider, streaming, and tool-call contracts;
11. error, retry, cancellation, and offline behavior;
12. security and privacy analysis;
13. accessibility and localization impact;
14. observability requirements with redaction rules;
15. migration and compatibility impact;
16. test strategy and requirement-to-test traceability;
17. rollout and rollback plan;
18. unresolved questions and decisions.

Requirements must be testable. Avoid terms such as “fast”, “secure”, or “user-friendly” without measurable criteria.

### 3.3 Review

Before implementation, verify that:

- every in-scope behavior has an acceptance criterion;
- provider-independent behavior is not expressed in vendor-specific terms;
- failure and cancellation paths are defined;
- sensitive data boundaries are explicit;
- the design works with at least one OpenAI-compatible provider and one DeepSeek-compatible provider;
- Android process death and configuration changes are considered;
- each requirement maps to one or more planned tests.

If the specification requires a product choice with materially different outcomes, stop and ask the user instead of guessing.

### 3.4 Plan

Break implementation into small vertical slices. A normal slice includes:

1. contract or domain change;
2. failing unit or contract test;
3. minimum implementation;
4. integration wiring;
5. UI state when applicable;
6. verification and documentation.

Prefer slices that remain reviewable and independently testable. Do not group unrelated cleanup with feature work.

## 4. TDD policy

Production behavior must be developed with red-green-refactor.

### 4.1 Red

Write the smallest test that demonstrates the missing behavior. Run it and confirm it fails for the expected reason.

A test that passes before implementation does not prove the new behavior. Correct the test or explain why the change is non-behavioral.

### 4.2 Green

Write the minimum production code required to pass the new test. Do not add speculative abstractions or unrelated features.

### 4.3 Refactor

Improve naming, boundaries, duplication, and structure only while the test suite remains green.

### 4.4 Regression rule

Every defect fix requires a failing regression test that reproduces the defect before the fix. If the defect cannot be reproduced automatically, document why and add the closest deterministic test plus a manual verification procedure.

### 4.5 Test quality

Tests must:

- describe behavior rather than implementation details;
- be deterministic and independent of test order;
- avoid real paid model calls in normal unit and CI suites;
- use fake clocks, fake IDs, and controlled dispatchers where time or concurrency matters;
- assert structured results instead of log text where possible;
- cover success, error, cancellation, malformed stream, and retry behavior;
- avoid broad snapshot tests as the sole behavior check;
- map back to specification requirement IDs when practical.

## 5. Android project baseline

Create the Android product in `android-app/` with:

- Kotlin as the primary language;
- Gradle Kotlin DSL;
- `compileSdk = 36` and `targetSdk = 36` for Android 16;
- a documented `minSdk` selected from product requirements, not guessed in feature code;
- Jetpack Compose and Material 3 for new UI;
- Kotlin coroutines and `Flow` for asynchronous and streaming state;
- dependency injection through a small explicit composition root or an approved DI framework;
- Room or another explicitly approved Android persistence layer for structured local data;
- Android Keystore-backed protection for provider credentials;
- WorkManager only for deferrable guaranteed background work;
- foreground services only when their user-visible use case satisfies Android platform policy.

Do not introduce a new framework, annotation processor, database, networking stack, or code-generation system without documenting the reason and trade-offs in the feature specification or an ADR.

### 5.1 Recommended module structure

Begin with a small modular structure and split further only when justified:

```text
android-app/
├── app/                    # Android entry point and composition root
├── core/
│   ├── model/              # Provider-neutral domain types
│   ├── network/            # HTTP, SSE, JSON and transport primitives
│   ├── database/           # Local persistence
│   ├── security/           # Credential and redaction services
│   ├── testing/            # Shared fakes and test fixtures
│   └── ui/                 # Shared Compose components and theme
├── data/
│   ├── provider/           # ModelProvider implementations
│   └── conversation/       # Repositories and persistence mapping
├── domain/
│   └── agent/              # Agent loop, policies and use cases
└── feature/
    ├── chat/
    ├── providers/
    └── settings/
```

Module dependencies must point inward toward provider-neutral contracts. Feature and domain modules must not depend on concrete vendor adapters.

### 5.2 Android lifecycle rules

- Model streaming must be cancellable when the owning operation ends.
- UI state must survive configuration changes through a `ViewModel` or equivalent lifecycle-aware owner.
- Persist essential conversation and pending-operation state required for process recreation.
- Do not store an `Activity`, `View`, or `Context` in long-lived domain objects.
- Use `applicationContext` only in Android infrastructure components that require it.
- Collect flows with lifecycle-aware APIs.
- Never perform network, database, JSON parsing, or cryptographic work on the main thread.
- Define behavior for process death during a stream or tool approval.

## 6. Architecture rules

Use dependency inversion and keep four conceptual layers:

```text
Compose UI
    ↓ intents / state
Application use cases and agent orchestration
    ↓ provider-neutral ports
Repositories and model-provider adapters
    ↓
HTTP, database, Keystore and Android platform APIs
```

### 6.1 UI layer

The UI:

- renders immutable screen state;
- emits user intents;
- does not construct HTTP requests;
- does not parse provider JSON or SSE;
- does not own API keys;
- does not implement the agent loop;
- does not contain provider-name conditionals beyond display metadata.

### 6.2 Domain and application layer

The domain layer owns:

- conversation and message semantics;
- agent run state;
- tool-call validation and approval policies;
- provider-neutral completion requests and events;
- retry eligibility and cancellation semantics;
- token/cost usage abstractions;
- use cases and state transitions.

It must be Kotlin/JVM testable without an Android device whenever possible.

### 6.3 Data and infrastructure layer

The data layer owns:

- provider adapters;
- HTTP and SSE/WebSocket transport;
- provider DTOs and mapping;
- Room entities and migrations;
- encrypted credential references;
- retry-after and rate-limit parsing;
- sanitized diagnostics.

Vendor DTOs must not cross into domain or UI modules.

## 7. Multi-model provider architecture

### 7.1 Provider-neutral contract

All model integrations must implement a provider-neutral interface. The exact types may evolve through specifications, but the contract should follow this shape:

```kotlin
interface ModelProvider {
    val descriptor: ProviderDescriptor

    fun stream(request: ModelRequest): Flow<ModelEvent>

    suspend fun listModels(): Result<List<ModelDescriptor>>

    suspend fun validate(configuration: ProviderConfiguration): ValidationResult
}
```

Provider-neutral request types should support:

```kotlin
data class ModelRequest(
    val modelId: String,
    val messages: List<Message>,
    val tools: List<ToolDefinition> = emptyList(),
    val responseFormat: ResponseFormat? = null,
    val temperature: Double? = null,
    val maxOutputTokens: Int? = null,
    val reasoning: ReasoningOptions? = null,
    val metadata: Map<String, String> = emptyMap(),
)
```

Do not expose a vendor request object through this contract.

### 7.2 Streaming events

Normalize all streaming formats into explicit events:

```kotlin
sealed interface ModelEvent {
    data class TextDelta(val text: String) : ModelEvent
    data class ReasoningDelta(val text: String) : ModelEvent
    data class ToolCallStarted(val id: String, val name: String) : ModelEvent
    data class ToolCallArgumentsDelta(val id: String, val json: String) : ModelEvent
    data class ToolCallCompleted(val call: ToolCall) : ModelEvent
    data class UsageUpdated(val usage: TokenUsage) : ModelEvent
    data class Completed(val reason: FinishReason) : ModelEvent
}
```

Transport failures are emitted as typed exceptions or a separate terminal result according to the approved specification. Do not encode errors as assistant text.

### 7.3 Provider capabilities

Providers and models expose capabilities rather than forcing the application to infer them from names:

```kotlin
data class ModelCapabilities(
    val streaming: Boolean,
    val vision: Boolean,
    val toolCalling: Boolean,
    val structuredOutput: Boolean,
    val reasoning: Boolean,
    val systemMessages: Boolean,
)
```

The UI and agent runtime must enable features from capabilities. Never use code such as `if (providerName == "deepseek")` in shared behavior.

### 7.4 Initial adapters

The first implementation should support:

1. an OpenAI-compatible Chat Completions adapter for compatible ChatGPT/OpenAI-style endpoints;
2. a DeepSeek-compatible adapter, reusing OpenAI-compatible transport where its contract truly matches and isolating differences in mapping/capabilities;
3. a deterministic `FakeModelProvider` for tests and previews.

Provider-specific features must be implemented behind adapter capability extensions. Do not put all vendors into one large conditional request builder.

### 7.5 Provider configuration

A provider profile should contain only validated configuration:

```kotlin
data class ProviderConfiguration(
    val id: String,
    val providerType: ProviderType,
    val displayName: String,
    val baseUrl: String,
    val credentialAlias: String,
    val defaultModelId: String?,
    val enabled: Boolean,
)
```

Rules:

- validate URL scheme and reject cleartext HTTP by default;
- do not concatenate untrusted path fragments without normalization;
- store secrets through Android Keystore-backed storage, not Room or plain preferences;
- never log authorization headers, API keys, complete prompts, tool arguments, or model responses;
- allow custom compatible endpoints only after an explicit user action;
- show the actual destination host before credential validation;
- support deletion of a profile and its credential material.

### 7.6 Retry and fallback

Retry only failures defined as transient, such as selected timeouts, connection failures, `429`, or eligible `5xx` responses. Respect `Retry-After` where supplied.

Do not automatically retry:

- authentication or authorization failures;
- invalid requests;
- unsupported capabilities;
- content-policy rejections;
- non-idempotent tool execution;
- cancelled requests.

Cross-provider fallback must be opt-in and visible. Never silently send a conversation or attachment to a different provider.

## 8. Agent runtime design

### 8.1 Agent state machine

Implement the agent loop as an explicit, testable state machine. A baseline model is:

```text
Idle
  → PreparingRequest
  → AwaitingModel
  → StreamingResponse
  → AwaitingToolApproval (when required)
  → ExecutingTool
  → SubmittingToolResult
  → AwaitingModel
  → Completed | Cancelled | Failed
```

The state machine must not live in a Composable or provider adapter.

### 8.2 Agent loop constraints

Each run must have configurable limits:

- maximum model turns;
- maximum tool calls;
- maximum consecutive failures;
- maximum wall-clock duration;
- maximum accumulated output/token budget when available;
- maximum tool-result size;
- cancellation signal.

Hitting a limit must produce a typed terminal state and a user-visible explanation. It must not be disguised as a normal model answer.

### 8.3 Tool contract

Tools use typed definitions and results:

```kotlin
interface AgentTool<I : Any, O : Any> {
    val descriptor: ToolDescriptor
    val inputSerializer: KSerializer<I>
    val outputSerializer: KSerializer<O>

    suspend fun execute(input: I, context: ToolExecutionContext): ToolResult<O>
}
```

Every tool must define:

- stable name and version;
- purpose and JSON input schema;
- validation rules;
- permission and approval policy;
- timeout and cancellation behavior;
- side-effect classification;
- sensitive input/output fields;
- deterministic fake for tests.

Do not execute arbitrary shell commands, code, URLs, intents, or model-generated reflection paths merely because the model requested them.

### 8.4 Tool approval policy

Classify tools at minimum as:

- `READ_ONLY`: reads user-authorized data without external mutation;
- `REVERSIBLE_WRITE`: changes data with a practical undo path;
- `EXTERNAL_SIDE_EFFECT`: sends messages, publishes, purchases, deletes, changes accounts, or affects other people/systems;
- `PROHIBITED`: behavior the application will never execute.

Require explicit user confirmation immediately before external side effects. The approval UI must show the concrete action, target, relevant data, provider, and whether it is reversible.

Approval is scoped to the exact normalized tool call. A changed argument set requires new approval.

### 8.5 Tool result handling

- Validate and size-limit tool output before returning it to the model.
- Treat tool output as untrusted data, not instructions.
- Separate display-safe summaries from raw model-facing payloads.
- Redact secrets from logs and diagnostics.
- Include stable tool-call IDs for replay protection.
- Never automatically repeat a side-effecting tool after an ambiguous timeout.

### 8.6 Prompt injection boundary

Content from web pages, documents, tool output, retrieved memory, and external messages is untrusted. It cannot change system policy, grant permissions, or approve tools.

The agent runtime must maintain separate channels for:

- application policy;
- developer/system instructions;
- user instructions;
- retrieved or tool-provided data.

Do not flatten untrusted content into privileged instructions.

## 9. Conversation and persistence

Use stable domain IDs for conversations, messages, runs, tool calls, provider profiles, and attachments.

At minimum model:

- `Conversation`;
- `Message` with role and ordered content parts;
- `AgentRun` with state and limits;
- `ToolCallRecord` with approval and execution status;
- `ProviderProfile` without secret values;
- `Attachment` metadata and lifecycle;
- token/usage records when providers supply them.

Persistence rules:

- write complete state transitions transactionally;
- define migration tests for every schema version change;
- do not persist partial JSON argument fragments as completed tool calls;
- mark interrupted streams/runs explicitly after process recreation;
- allow deletion/export according to product specifications;
- never place raw credentials in Room, logs, saved-state bundles, analytics, or crash reports.

## 10. Networking and parsing

- Use one approved HTTP stack behind a transport abstraction.
- Apply explicit connect, read, write, and total-operation timeouts.
- Support cancellation from UI through provider and transport layers.
- Parse SSE incrementally across arbitrary byte/chunk boundaries.
- Handle comments, blank lines, multi-line `data`, terminal markers, malformed events, and UTF-8 split boundaries.
- Bound response body, event, accumulated tool-argument, and attachment sizes.
- Map HTTP and provider errors into stable domain error types.
- Preserve provider request IDs for diagnostics without exposing secrets.
- Do not enable cleartext traffic globally to support a custom endpoint.

All parser behavior requires fixture-based contract tests.

## 11. UI and UX requirements

### 11.1 Compose state

Each feature should expose immutable UI state and accept typed intents. A typical shape is:

```kotlin
data class ChatUiState(
    val messages: List<MessageUiModel>,
    val composer: ComposerState,
    val activeRun: RunUiState?,
    val provider: ProviderUiModel?,
    val error: UserFacingError?,
)
```

One-off effects such as navigation or snackbars must not be represented as repeatedly consumable state without an explicit consumption design.

### 11.2 Streaming UX

- Display incremental text without rebuilding the entire conversation model for every token.
- Throttle or batch high-frequency deltas before Compose rendering while preserving final text.
- Provide explicit stop/cancel control.
- Distinguish waiting, streaming, tool approval, tool execution, completed, cancelled, and failed states.
- Preserve already received text when a stream fails.
- Never render internal reasoning by default; treat it according to provider policy and product specification.

### 11.3 Accessibility

All features must support:

- TalkBack semantics and meaningful control labels;
- keyboard and switch navigation where applicable;
- minimum touch target sizes;
- dynamic font scaling without clipping;
- sufficient color contrast;
- state communication that does not rely on color alone;
- reduced-motion behavior for nonessential animation.

### 11.4 Localization

- Put user-visible strings in resources.
- Do not concatenate translated sentence fragments.
- Format dates, numbers, token counts, and currency with locale-aware APIs.
- Keep model-generated content separate from application-localized UI strings.

## 12. Testing strategy

### 12.1 Test pyramid

Prefer this order:

1. pure Kotlin unit tests for domain logic and state machines;
2. provider contract and streaming parser tests with fixtures;
3. repository tests with fake transport/database;
4. Room migration tests;
5. ViewModel tests with controlled dispatchers;
6. Compose UI tests for critical user flows;
7. a small number of Android instrumentation and end-to-end tests;
8. optional manual smoke tests against real providers, never required for ordinary unit tests.

### 12.2 Required provider contract suite

Every provider adapter must pass a shared contract suite covering:

- configuration validation;
- authorization header construction without logging secrets;
- normal text streaming;
- UTF-8 split across transport chunks;
- multiple text deltas in one event;
- tool-call argument fragments;
- usage and finish reason mapping;
- provider error body mapping;
- `401`, `403`, `429`, eligible `5xx`, and timeout behavior;
- cancellation and resource cleanup;
- unsupported capability rejection;
- malformed and truncated streams.

### 12.3 Required agent tests

The agent state machine must test:

- text-only completion;
- one and multiple tool calls;
- approval accepted and rejected;
- tool validation failure;
- tool timeout and cancellation;
- ambiguous side-effect timeout without automatic replay;
- maximum turn/tool/time limits;
- provider failure between tool result and follow-up response;
- process interruption recovery state;
- prompt-injection content failing to elevate privileges;
- exact approval invalidation when arguments change.

### 12.4 No live secrets in CI

CI and normal local tests must not require real API keys or paid endpoints. Live-provider smoke tests must be opt-in, separately tagged, secret-redacted, and excluded from pull-request gates unless explicitly configured by the repository owner.

## 13. Verification gates

Before declaring an Android change complete, run the narrowest relevant tests during development and the full module gates before handoff.

Once the Android project exists, maintain canonical commands in `android-app/README.md`. Expected gates include equivalents of:

```sh
./gradlew test
./gradlew lint
./gradlew assembleDebug
./gradlew connectedCheck     # when an emulator/device is available
```

Also run any configured formatting, static-analysis, dependency, and Compose stability checks.

Completion requires:

- approved specification updated to `implemented`;
- acceptance criteria traced to passing tests;
- new tests observed failing before implementation when behavior changed;
- unit and contract tests passing;
- lint/static checks passing;
- debug build succeeding;
- relevant UI/instrumentation tests passing or a documented environment limitation;
- no placeholder code, ignored failure, or unexplained skipped test;
- documentation and migration notes updated.

Never claim a test or build passed unless it was actually executed successfully in the current environment.

## 14. Code quality rules

- Prefer clear, small, immutable Kotlin types.
- Use sealed hierarchies for finite states and typed errors.
- Avoid boolean parameters when an enum communicates intent.
- Keep provider branching inside provider adapters or capability policy.
- Do not use exceptions for normal UI state transitions.
- Do not catch `Throwable`; preserve coroutine cancellation.
- Avoid `GlobalScope` and unmanaged background work.
- Do not block coroutine threads with synchronous network or database work.
- Keep serialization DTOs separate from domain models.
- Prefer constructor injection and explicit dependencies over service locators.
- Add comments for invariants and non-obvious protocol behavior, not restatements of code.
- Remove dead code and temporary debug logging before handoff.
- Do not weaken lint, tests, TLS, backup, or security settings merely to make a check pass.

## 15. Dependency and supply-chain policy

Before adding a dependency:

1. show why platform or existing dependencies are insufficient;
2. prefer actively maintained, well-scoped libraries;
3. review license and transitive dependency impact;
4. avoid duplicate libraries for the same responsibility;
5. pin versions through the project version catalog or approved mechanism;
6. add tests around behavior delegated to protocol-critical libraries;
7. never add a dependency only to avoid writing a small, well-tested adapter.

Do not update unrelated dependencies in a feature change.

## 16. Documentation and decision records

Update documentation in the same change as behavior.

Use:

- `doc/specs/` for feature specifications;
- `doc/adr/` for durable architectural decisions;
- `doc/detailed-design.md` for the existing Rust implementation;
- `doc/android-detailed-design.md` for the Android architecture once scaffolding begins;
- `android-app/README.md` for local build, test, emulator, and provider setup.

Create an ADR when changing:

- module boundaries;
- HTTP/serialization/database/DI frameworks;
- provider SPI or agent tool protocol;
- credential storage;
- persistence schema strategy;
- cross-provider fallback policy;
- background execution model.

## 17. Change discipline

- Preserve unrelated user changes in a dirty worktree.
- Do not perform destructive Git operations without explicit authorization.
- Keep changes scoped to the approved specification.
- Do not silently rewrite the Rust implementation while building Android.
- Avoid broad formatting or generated-file churn unrelated to the task.
- Do not commit secrets, local provider profiles, production URLs with credentials, APK signing keys, or user conversations.
- Generated build outputs must remain ignored.

## 18. Definition of done

A feature is done only when:

- the specification is approved and reflects the implemented behavior;
- required tests were written first and now pass;
- provider-neutral boundaries remain intact;
- error, cancellation, retry, lifecycle, and security cases are implemented;
- Android 16 behavior has been considered and relevant checks run;
- accessibility and localization impacts are addressed;
- logs and diagnostics are verified to redact sensitive values;
- documentation and ADRs are current;
- the final handoff lists changed files, verification commands, and any remaining limitations.

If any gate cannot run because the required SDK, emulator, network, or device is unavailable, report that limitation explicitly. Do not substitute an assumption for verification.
