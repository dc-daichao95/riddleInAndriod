# ADR 0001: Typed provider model discovery

- Status: accepted
- Date: 2026-07-15
- Specification: `doc/specs/magic-paper-ux-provider-text-pipeline.md` (`MPX-FR-006`, `MPX-AC-003`, `MPX-AC-004`, `MPX-NFR-001`)

## Context

`ModelProvider.listModels()` previously returned `Result<List<ModelDescriptor>>`. That shape could not distinguish an endpoint that does not implement model discovery from an empty but valid catalog, and callers had to inspect adapter exceptions to distinguish authentication, authorization, throttling, transport, timeout, and malformed-response failures. It also provided no shared resource limits or normalization contract for OpenAI-compatible and DeepSeek-compatible adapters.

Provider setup needs these outcomes before any profile becomes selectable or durable. The UI and domain must not branch on provider names or receive vendor DTOs.

## Decision

The provider-neutral SPI returns:

```kotlin
sealed interface ModelDiscoveryResult {
    data class Success(val models: List<ModelDescriptor>) : ModelDiscoveryResult
    data object Unsupported : ModelDiscoveryResult
    data class Failed(val error: ModelError) : ModelDiscoveryResult
}
```

Both initial adapters run the same contract implementation and tests. HTTP 404, 405, and 501 from the normalized `/models` endpoint map to `Unsupported`; an authenticated empty `data` array remains `Success(emptyList())`. Other outcomes retain typed `ModelError` values, including distinct authentication (401), authorization (403), rate-limit (429), server (5xx), network, timeout, invalid response, and response-too-large failures. External coroutine cancellation propagates and closes the response; it is not converted into ordinary success or retried.

One discovery request has exactly one authoritative `TransportResponse`. The adapter consumes the first response (including its complete success body), then terminates outer transport collection with an internal non-cancellation terminal signal. A malformed transport that emits again cannot overwrite the first result or start a second response body, and its outer `finally` still runs.

Catalog bodies are decoded as strict UTF-8 before JSON parsing. Invalid bytes, invalid continuation bytes split across chunks, and truncated multibyte sequences fail the complete discovery with `ModelError.InvalidResponse`; replacement characters are never silently synthesized from malformed transport bytes. Catalog normalization trims IDs, rejects blank IDs, compares IDs case-sensitively, keeps the first descriptor for an exact duplicate ID, and sorts by Kotlin `String` natural order. This is locale independent. Vendor JSON is parsed inside the adapter and only `ModelDescriptor` crosses the SPI.

Discovery uses identical hard limits for all adapters:

| Input | Accepted maximum | Over-limit result |
| --- | ---: | --- |
| Complete success body | 2 MiB | `ModelError.ResponseTooLarge` |
| Raw catalog entries | 2,000 | `ModelError.ResponseTooLarge` |
| Trimmed model ID | 256 UTF-8 bytes | `ModelError.InvalidResponse` |
| Display name | 512 UTF-8 bytes | `ModelError.InvalidResponse` |
| Sanitized HTTP error body | 64 KiB | `ModelError.ResponseTooLarge` |

Limits are inclusive. No entry, error, or partial catalog is silently truncated. The transport reads at most 64 KiB of an HTTP error body plus one detection byte, redacts credentials, and carries an explicit over-limit flag to the adapter. Success chunks are bounded while collected, so an oversized body stops upstream collection and releases its response.

All blocking Okio response reads run on the transport's injectable IO dispatcher. Discovery body assembly, strict decoding, JSON parsing, normalization, sorting, discovery HTTP-error mapping, and streaming HTTP-error parsing run on the adapter's injectable parsing dispatcher, which defaults to `Dispatchers.Default`; neither success nor error processing executes on the caller's main dispatcher. The OkHttp callback resumes its cancellable continuation with a resource handler that closes a response discarded by prompt cancellation before collector dispatch. After collector resumption, an independent cancellation watcher runs away from the blocking-read dispatcher. Cancellation immediately invokes both `Call.cancel()` and `Response.close()`, so neither the resume-before-dispatch window nor a server that sends headers and a partial body and then stalls can leak a response or prevent the collecting job from completing cancellation. The normal completion path also closes the body and connection.

## Compatibility and migration

This is an intentional source-breaking SPI change before a stable Android release. Implementations and test fakes must replace `Result.success(models)` with `ModelDiscoveryResult.Success(models)` and callers must exhaustively handle `Unsupported` and `Failed`. There is no persisted-data or Room migration, no wire-protocol change, and no credential migration. Provider profile and selection behavior remain unchanged until the staged settings slice consumes this contract.

Rollback requires reverting the SPI, both adapter implementation, all in-repository fakes, and the shared contract tests together. Persisted provider profiles remain readable because this decision changes neither their schema nor their secret aliases.

## Consequences

- Provider setup can offer manual model entry only for explicit unsupported or empty discovery, without exception inspection or provider-name branches.
- Every adapter must pass the same boundary, normalization, typed-error, cancellation, and cleanup suite.
- Adapters cannot expose partial catalogs when any entry or body is invalid.
- Adding a new provider requires mapping its discovery protocol to this sealed result; vendor-specific error details remain sanitized inside the adapter.
- Discovery performs no automatic retry and makes no completion request. The separately approved manual validation flow remains a later slice.
