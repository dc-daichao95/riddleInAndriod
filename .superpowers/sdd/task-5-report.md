# Task 5 report: SSE transport and provider adapters

## Status

Implemented incremental SSE framing, a cancellable OkHttp transport boundary, and provider-neutral OpenAI-compatible and DeepSeek-compatible streaming adapters.

## TDD evidence

- Parser RED: `:model-provider:testDebugUnitTest --tests '*SseParserTest*'` failed because `SseParser` and `SseEvent` did not exist.
- Parser GREEN: the same focused command passed after the minimal incremental parser implementation.
- Adapter RED: `:model-provider:testDebugUnitTest --tests '*ProviderContractTest*'` failed because the transport and provider adapter contracts did not exist.
- Adapter GREEN: the shared contract passed for both adapters after implementation. One observed ordering failure (completion before usage) was corrected to emit usage before terminal completion.

## Coverage

- Arbitrary 1–3 byte chunks and split UTF-8 code points.
- SSE comments, CRLF/LF blank lines, multiline `data`, `[DONE]`, malformed JSON, and truncated events.
- Equivalent OpenAI/DeepSeek text, usage, finish, authentication, rate limit with `Retry-After`, server, network timeout, cancellation, and parse failure mapping.
- Tool-call fields are parsed as untrusted protocol data and have no execution path.
- Credentials are retrieved only through `CredentialSource.get(alias)`; request diagnostics redact bodies and header values.

## Verification

Fresh command:

```powershell
./gradlew :model-provider:test :model-provider:lintDebug
```

Result: `BUILD SUCCESSFUL` (75 tasks; 29 executed, 46 up-to-date).

## Limitations

- No live-provider calls were made; fixtures and fake transports are deterministic.
- Credential persistence is intentionally deferred to the security/settings task; this module consumes only the credential lookup port.

## Review corrections

The review follow-up moved raw credential resolution entirely into `OkHttpModelTransport`; provider requests now carry only a credential alias. Concrete MockWebServer tests cover authorization injection, bounded/redacted error bodies, and `Call.cancel()` on collector cancellation.

Additional RED/GREEN coverage now verifies:

- DeepSeek-only reasoning capability and `reasoning_content` mapping;
- meaningful `GET /models` and content-free minimal validation against the candidate profile host;
- immediate `[DONE]` upstream termination and exactly one completion;
- normalized tool-call identity, name, argument fragments, and completion without execution;
- finite connect/read/write/total timeouts;
- delta-seconds and RFC HTTP-date `Retry-After` parsing with an injected clock and typed retry eligibility;
- SSE `id` and persistent `retry` field semantics;
- malformed/bounded provider errors with credential redaction;
- committed SSE fixtures consumed by contract tests.

Fresh clean review gate:

```powershell
./gradlew :model-provider:clean :model-provider:test :model-provider:lintDebug
```

Result: `BUILD SUCCESSFUL` (76 tasks; 37 executed, 39 up-to-date).

## Final review wave

Focused RED/GREEN regressions additionally established that:

- validation requires an actual terminal completion; empty, truncated, nonterminal, and provider-error responses are typed invalid results;
- invalid SSE `retry` fields are ignored without clearing the last valid reconnection delay;
- provider adapters receive an injected `RetryPolicy`, with deterministic adapter-level RFC HTTP-date testing;
- fragmented function names accumulate into one stable `ToolCallStarted`, while argument fragments and completion remain ordered and intact;
- general transport requests reject case-insensitive credential-bearing headers, preventing caller overrides of transport-owned authorization.

The final clean command remained:

```powershell
./gradlew :model-provider:clean :model-provider:test :model-provider:lintDebug
```

Result: `BUILD SUCCESSFUL` (76 tasks; 37 executed, 39 up-to-date).
