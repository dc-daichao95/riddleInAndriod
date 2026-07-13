# Task 4 Report: Conversation state machine and fake Provider

## Status

Implemented and verified the pure conversation reducer, effect model, stream orchestrator, and deterministic `FakeModelProvider` vertical slice.

## Scope delivered

- Caller-supplied monotonic `Tick` drives the 2.8-second inactivity boundary; the reducer reads no clock and performs no I/O.
- Visible ink commits once by transitioning out of `Listening`; erased pages do not commit.
- Turn start emits rasterization and ink-dissolve effects together.
- First text delta begins handwriting and later deltas append.
- Completion emits completed-turn persistence once and clamps reply linger to 4–20 seconds.
- Writing interrupts a lingering response; cancellation closes Provider collection.
- Timeout and Provider failure retain partial reply in failure linger and never request completed-turn persistence.
- Fake Provider uses only deterministic in-memory flows; no live endpoint or credential is involved.

## TDD evidence

### RED 1

Command: `./gradlew :conversation:test --no-daemon`

Observed failure: conversation tests compiled far enough to report unresolved production contracts (`ConversationStateMachine`, states, effects, orchestrator, and fake Provider). This was the expected missing-feature failure.

### GREEN 1

Command: `./gradlew :conversation:test --no-daemon`

Observed result: exit 0 with the initial 10 tests passing.

### RED 2 (self-review regression)

Added `each collection treats its first delta as new handwriting` after identifying that append state could leak between cold-flow collections.

Observed result: 11 tests executed, 1 expected failure in `ConversationOrchestratorTest`.

### GREEN and clean verification

Command: `./gradlew :conversation:clean :conversation:test --no-daemon`

Observed result: exit 0. XML results report 11 tests, 0 failures, 0 errors, 0 skipped (8 reducer tests and 3 orchestrator tests).

`git diff --check` also completed successfully for Task 4 changes.

## Review notes and limitations

- The linger length function is deterministic and bounded; its current 80 ms per character policy is intentionally local to the reducer and can be revised by a later approved behavior specification.
- Rasterization, OCR, persistence, transport adapters, UI, tools, paid calls, Room, and credentials are outside this slice. Effects mark boundaries; later tasks provide their production executors.
- Gradle wrapper/dependency acquisition required approved network execution outside the filesystem sandbox. No version was changed to work around it.
- Existing Task 3 report edits and generated build directories were preserved and excluded from this commit.
