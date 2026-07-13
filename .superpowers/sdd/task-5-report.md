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
