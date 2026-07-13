# Task 7 Report: Page rasterization and local handwriting routing

## Scope

Implemented bounded page-image rasterization in `paper-engine` and provider-capability routing in `conversation`. Vision-capable models receive an owned temporary PNG without invoking recognition; text-only models receive local ML Kit Digital Ink recognition results. Provider calls, credentials, tool execution, cross-provider fallback, and app effect composition remain out of scope. The Rust reference implementation was not changed.

## TDD evidence

### RED

- Added `PageRasterizerTest`, `InputRoutingTest`, and `MlKitHandwritingRecognizerTest` before production code.
- The first valid `:paper-engine:test :conversation:test` run failed at compilation because `PageRasterizer`, `TurnInput`, `TurnInputRouter`, `HandwritingRecognizer`, typed recognition errors, and the paper-engine module dependency did not exist.
- After the implementation plan's pinned ML Kit version was clarified, compilation against `digital-ink-recognition:18.1.0` failed because 18.1.0 exposes `com.google.mlkit.vision.digitalink.*`, not the newer documentation's `com.google.mlkit.vision.digitalink.recognition.*`. The cached 18.1.0 AAR was inspected and imports were adapted without changing the pinned dependency.
- The first routing execution then failed because Android bitmap rendering was invoked by a plain JVM test. The routing test was moved under Robolectric so it exercises the real rasterizer rather than a mocked image path.

### GREEN and refactor

- Crops normalized pen strokes to their radius-aware ink bounds plus configurable padding.
- Requires caller-supplied page dimensions, avoiding a fixed device resolution or aspect ratio.
- Downscales without upscaling and guarantees the output long side is at most 800 pixels.
- Renders opaque black/white antialiased grayscale PNG output off the main thread.
- Rejects pages without pen ink before creating a cache file.
- Returns a closeable image owner whose `close()` deletes the temporary cache file.
- Routes vision capability directly to a page image and never invokes recognition on that path.
- Routes text-only capability through `HandwritingRecognizer`; blank output and recognition prerequisites are typed failures, so no staged provider input is produced.
- Preserves coroutine cancellation instead of converting it into a normal routing/recognition failure.
- Implements ML Kit Digital Ink Recognition behind an injected backend, selects the requested locale when supported, and uses explicit `en-US` fallback otherwise.
- Checks `RemoteModelManager.isModelDownloaded` and returns `ModelNotDownloaded`; commit routing contains no download call or hidden network action.
- Filters eraser strokes before constructing ML Kit `Ink` and closes the recognizer after every recognition attempt.

## Verification

Fresh clean command:

```powershell
$env:JAVA_HOME='C:\Program Files\Android\Android Studio\jbr'
$env:ANDROID_HOME="$env:LOCALAPPDATA\Android\Sdk"
.\gradlew.bat :paper-engine:clean :conversation:clean :paper-engine:test :conversation:test :paper-engine:lintDebug :conversation:lintDebug --no-daemon
```

Result: `BUILD SUCCESSFUL` (133 tasks; 96 executed, 37 up-to-date).

- `paper-engine`: 28 tests, including 4 rasterizer tests; 0 failures, 0 errors, 0 skipped.
- `conversation`: 23 tests, including 4 routing and 2 ML Kit abstraction tests; 0 failures, 0 errors, 0 skipped.
- `:paper-engine:lintDebug` and `:conversation:lintDebug`: passed.
- `git diff --check`: passed (line-ending conversion warnings only).

## Review

Self-review checked FR-070 through FR-075, AC-009 through AC-011, cancellation propagation, cache ownership/deletion, provider isolation, model-download behavior, locale fallback, Android main-thread boundaries, and the pinned dependency. No unaddressed Critical or Important issue was found.

## Remaining limitation

Normal tests use deterministic fakes and make no live provider or model-download calls. Actual ML Kit model availability and handwriting accuracy require a separately provisioned API 36 device/emulator with a downloaded language model; commit routing intentionally reports the missing model as a prerequisite rather than downloading it.

## Review follow-up

All Important review findings were addressed with focused tests first.

- The initial focused RED failed because deterministic ML Kit conversion and managed cache-cleanup contracts did not exist. After those contracts were implemented, `InputRoutingTest.recognized text is trimmed before staging` produced the expected behavioral RED (`"  hello  "` was still staged instead of `"hello"`).
- Successful OCR is now trimmed before blank checking and staging; whitespace-only OCR remains `InputRoutingError.BlankRecognition`.
- ML Kit `Ink` conversion is an internal testable seam. Because the normalized Task 1 domain has no timestamps, it deterministically derives synthetic milliseconds `0..N` from preserved stroke/point order. Pen-point timestamps are strictly increasing across stroke boundaries, eraser strokes remain excluded, and no wall clock participates.
- `deleteOnExit` was removed. `RasterizedPage.close()` deletes synchronously or throws typed `CacheCleanupFailed` without exposing the cache path.
- Partial encoding failure prioritizes typed cleanup failure when deletion also fails. Cancellation is rethrown and performs cleanup; a typed cleanup failure is attached if cancellation cleanup itself fails.
- Before rasterization, a direct-directory sweep removes only files with the owned `riddle-page-` prefix older than one hour, skips active page handles, and processes at most 64 files. Unrelated cache files and paths outside the owned cache directory are never deletion candidates.
- Tests cover trimmed and blank OCR, actual ML Kit stroke/point ordering and timestamps, successful close, close deletion failure, encoding plus deletion failure, bounded owned stale cleanup, cancellation during encoding, and cancellation after an encoded image cannot be handed to the caller.

Final clean review gate:

```powershell
.\gradlew.bat :paper-engine:clean :conversation:clean :paper-engine:test :conversation:test :paper-engine:lintDebug :conversation:lintDebug --no-daemon
```

Result: `BUILD SUCCESSFUL` (133 tasks; 86 executed, 47 up-to-date).

- `paper-engine`: 33 tests, 0 failures, 0 errors, 0 skipped.
- `conversation`: 25 tests, 0 failures, 0 errors, 0 skipped.
- Both module lint gates passed.

## Cross-instance ownership follow-up

The remaining Important review finding was reproduced first: with per-rasterizer active sets, a second `PageRasterizer` targeting the same cache directory swept the first rasterizer's still-open page when its timestamp met the stale threshold.

The cache now has a process-wide, canonical-directory-scoped ownership registry:

- directory state uses active-file refcounts and a per-directory lock;
- cache creation plus registration, stale candidate selection/deletion, and close plus unregistration are serialized through that directory state;
- equivalent `File` aliases converge on the canonical cache directory;
- `close()` is atomic and idempotent, unregisters exactly once in `finally`, and still surfaces typed immediate deletion failure;
- after failed close, the unregistered leftover is eligible for a later bounded managed sweep;
- registry entries are removed when they have no users and no active files, avoiding empty static directory-state retention;
- prefix, canonical parent, age, and per-sweep count restrictions remain in force.

Focused RED/GREEN coverage proves an open page survives a stale sweep from a second rasterizer and becomes sweepable only after close/unregister. A deterministic latch-controlled concurrency test also proves a sweep cannot race the interval between cache-file creation and registration.

Final clean gate after this fix:

```powershell
.\gradlew.bat :paper-engine:clean :conversation:clean :paper-engine:test :conversation:test :paper-engine:lintDebug :conversation:lintDebug --no-daemon
```

Result: `BUILD SUCCESSFUL` (133 tasks; 86 executed, 47 up-to-date).

- `paper-engine`: 35 tests, 0 failures, 0 errors, 0 skipped.
- `conversation`: 25 tests, 0 failures, 0 errors, 0 skipped.
- Both module lint gates passed.
