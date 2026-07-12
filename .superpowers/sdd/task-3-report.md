# Task 3 report: Low-latency MagicPaperView

## Scope delivered

- Added pressure-aware `StrokeReducer`, including stylus/eraser pressure clamping and stylus-proximity palm rejection.
- Ported the wrapping coordinate hash from `src/ink.rs` into deterministic `DissolvePattern`; the final stage selects every coordinate.
- Added immutable `PaperRenderModel` and `PaperIntent` contracts.
- Added `MagicPaperView` with historical/current `MotionEvent` sampling, finger/stylus/eraser handling, hover proximity, settings-policy frames, render-only stroke caches, pressure-width round-cap Canvas drawing, dirty bounds, and animation-aligned invalidation.
- Kept authoritative strokes outside the View and exposed a callback rather than Compose per-point state updates.

## TDD evidence

### RED

The reducer and dissolve tests were created before production types. The requested wrapper invocation attempted to download Gradle and failed under sandbox network denial (`java.net.SocketException: Permission denied`). An escalation request was automatically rejected because the account usage limit had been reached. Thus the intended missing-symbol compile failures could not be observed through Gradle.

### GREEN / verification

An installed Gradle 9.4.1 executable was discovered, but use failed because sandbox policy denied access to its distribution JAR (`AccessDeniedException: ...gradle-core-9.4.1.jar`). Consequently the required `:paper-engine:test :paper-engine:lintDebug :paper-engine:assembleDebug` gate could not be completed in this environment.

Static verification: `git diff --check` passed. Rust sources and root Cargo files were not changed.

## Self-review

- Final dissolve stage also suppresses one-point strokes.
- Eraser-domain strokes are excluded from ink rendering.
- Input previews are render-only and are cleared when an authoritative snapshot is submitted.
- No credential, networking, persistence, conversation, or later UI work was introduced.

## Remaining concern

None identified in the Task 3 scope.

## Final warning fix and verification (2026-07-13)

### RED evidence

The controller's full gate reproduced one lint/compiler warning at `MagicPaperView.kt:209`: deprecated `invalidate(Rect)`. Investigation confirmed that the dirty rectangle was immediately followed by a full-view `postInvalidateOnAnimation()`, so the implementation both used the deprecated overload and discarded its dirty-bounds optimization.

### Fix

Replaced both calls with `postInvalidateOnAnimation(left, top, right, bottom)`. This preserves animation-aligned invalidation, retains the calculated dirty bounds, and removes the per-input `Rect` allocation.

### Final clean gate

Executed with explicit `JAVA_HOME`, `ANDROID_HOME`, and `ANDROID_SDK_ROOT`:

```text
.\gradlew.bat :paper-engine:test :paper-engine:lintDebug :paper-engine:assembleDebug --no-daemon --no-configuration-cache
BUILD SUCCESSFUL in 18s
76 actionable tasks: 17 executed, 59 up-to-date
```

No deprecation warning was emitted. Unit tests, Android lint, and debug assembly all completed successfully.

## Review fixes (2026-07-13)

### RED evidence

- Rust-u32 golden test failed at `DissolvePatternTest.kt:13`, proving signed Kotlin remainder selected a high-bit hash too early.
- `DissolveSelectionTest` initially failed compilation because no spatial segment-selection abstraction existed.
- Seven `PaperInputReducerTest` cases initially failed compilation because pointer-ID lifecycle, history routing, cancellation, eraser sequences, and preview state had no pure reducer seam.
- Resource-enabled Robolectric View tests then failed both behavioral assertions: the old View did not render a down-only preview point and an unrelated hover exit cleared stylus proximity.

### GREEN implementation

- Hash output is converted to unsigned u32 magnitude before the stage remainder; golden values include `0xF2150407`, `0x85F983EC`, and `0x8654E53C`.
- `DissolveSelection` rasterizes segments with Bresenham coordinates and returns contiguous surviving pixel runs. These runs are cached by `MagicPaperView` when authoritative render models are submitted or its size changes.
- `PaperInputReducer` owns the active pointer ID/tool/stroke, orders historical samples, ignores incidental pointers, distinguishes cancellation, handles eraser-only sequences, and exposes immutable point/segment preview snapshots.
- `MagicPaperView` resolves moves with `findPointerIndex(activePointerId)`, safely handles action indices, translates only the active pointer's history, renders down dots immediately, preserves bounded `postInvalidateOnAnimation`, and applies tool-aware hover exits.
- Robolectric resources are enabled only for module unit tests so View translation and hover behavior run without instrumentation.

### Focused GREEN

```text
:paper-engine:testDebugUnitTest --tests '*DissolvePatternTest' --tests '*DissolveSelectionTest' --tests '*PaperInputReducerTest'
BUILD SUCCESSFUL in 11s
```

The focused View integration tests subsequently passed as part of the complete module run.

### Final clean gate

```text
.\gradlew.bat :paper-engine:test :paper-engine:lintDebug :paper-engine:assembleDebug --no-daemon --no-configuration-cache
BUILD SUCCESSFUL in 17s
90 actionable tasks: 15 executed, 75 up-to-date
```

The output contains no warnings or lint errors.

After self-review adjusted zero-pointer cancellation handling and reused the cached dissolve pattern, the identical full gate was rerun fresh and exited successfully (`exit code 0`) in 24.1 seconds.
