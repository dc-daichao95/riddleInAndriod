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

## Review-fix cycle

The first review identified missing explicit provider-pipeline effects, incomplete listening ink handling, unsafe post-cancel recommit behavior, an under-specified fake Provider contract, and overflow-sensitive tick arithmetic.

### Review RED

Added tests before production changes for:

- explicit preparation and Provider-request effect availability (the final staged dependency is documented below);
- blank listening page through `InkChanged`, pre-deadline tick, exact deadline, and exactly-once transition;
- cancellation clearing committed ink and preventing a later tick from recommitting;
- backward and near-`Long.MAX_VALUE` ticks;
- fake descriptor/model/validation behavior;
- rejection of disabled, provider-type-mismatched, and non-streaming profiles;
- request-aware cold stream creation, independent collections, and recorded request trace.

Command: `./gradlew :conversation:test --no-daemon`

Observed expected compilation failure for missing `RecognizeText`, `RequestProvider`, request-aware fake factory, and request trace.

### Review GREEN and clean verification

Focused command: `./gradlew :conversation:test --no-daemon` — exit 0.

Full command: `./gradlew :conversation:clean :conversation:test --no-daemon` — `BUILD SUCCESSFUL`; 16 tests, 0 failures, 0 errors, 0 skipped.

The reducer still performs no I/O and reads no clock. Deadline comparison rejects backward ticks and avoids subtract/add overflow. OCR and provider routing remain abstract effects; no Task 7 implementation was introduced.

## Staged-pipeline race fix

Follow-up review found that emitting preparation and `RequestProvider` together could start collection before the reducer entered its delta-accepting state. The pipeline is now explicitly staged:

1. The inactivity boundary enters `Drinking` and emits `BeginTurn`, `Rasterize`, and `RenderInkDissolve`. No Provider request exists yet.
2. A later executor supplies `TurnInputPrepared(ModelRequest)` after Task 7's abstract preparation/routing boundary completes.
3. That reducer transition atomically enters `Thinking` and emits exactly one `RequestProvider` carrying the complete provider-neutral request.
4. A `TextDelta` delivered immediately afterward begins handwriting and is retained.

No OCR implementation or routing policy is present in Task 4.

### Race RED/GREEN evidence

The failing test was added first and the focused command failed on the missing `TurnInputPrepared` input and the old page-ID-only `RequestProvider` effect.

After the minimum staged implementation:

- `./gradlew :conversation:test --no-daemon` — `BUILD SUCCESSFUL`;
- `./gradlew :conversation:clean :conversation:test --no-daemon` — `BUILD SUCCESSFUL`;
- XML results: 17 tests, 0 failures, 0 errors, 0 skipped.
