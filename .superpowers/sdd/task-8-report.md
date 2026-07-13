# Task 8 Report: Room memory, pruning, draft recovery, and reconstruction

## Scope

Implemented Room schema version 1 and a provider-neutral local memory repository in `android-app/memory`, plus a pure reconstruction planner in `android-app/conversation`. The slice persists completed pages and normalized strokes, enforces the 400-memory retention limit, stores replaceable draft revisions, produces recent dialogue and 40-page catalogs, validates historical selections by catalog revision and stable page ID, and carries the data needed for faded reconstruction and current-page restoration. UI integration remains Task 9. The Rust reference implementation was not changed.

## TDD evidence

### RED

- The first `:memory:connectedDebugAndroidTest` run reached `:memory:compileDebugAndroidTestKotlin` and failed on missing `RiddleDatabase`, `RoomMemoryRepository`, `DraftPage`, and related Task 8 contracts.
- The reconstruction-only `:conversation:testDebugUnitTest --tests '*ReconstructionPlannerTest*'` run failed on missing planner, catalog, historical-page, restore, and result types.
- `MigrationTest` first failed on the API 36 emulator because `dev.riddle.magicpaper.memory.RiddleDatabase/1.json` was not packaged in Android-test assets.
- Self-review added a focused freshness regression. `changingMemoryAvailabilityInvalidatesPreviouslyNumberedCatalog` failed because disabling memory changed catalog visibility without advancing its revision.

### GREEN and refactor

- Room version 1 stores completed-page metadata, ordered normalized stroke points, singleton non-secret preferences, and replaceable draft metadata/points.
- Point rows use explicit stroke and point order. Reconstruction preserves insertion order rather than sorting by stroke ID.
- Stroke and draft-point rows have indexed owner foreign keys with `ON DELETE CASCADE`; completed pages have an indexed completion timestamp and stable primary page ID.
- Completed-page metadata, stroke insertion, oldest-page pruning, and catalog revision updates occur in one `withTransaction` block. 401 inserts leave the newest 400 pages and no orphan points.
- Disabled memory rejects completed persistence and returns no recent context or catalog entries. Changing memory availability advances the catalog revision so prior numbered selections become stale.
- Clearing completed pages, cascading points, and the current draft is transactional while preserving the user's memory setting.
- Draft replacement deletes the prior draft and inserts the new revision and normalized points in one transaction. The schema and draft API contain no credential, secret, image, or byte payload field.
- Recent dialogue defaults to six turns and is returned chronologically; catalogs are newest-first and limited to 40 stable page IDs.
- Reconstruction uses a 1-based current-catalog selection, checks the current catalog revision, resolves the exact stable page ID, and returns typed stale, out-of-range, and missing-page failures.
- A ready reconstruction plan contains the historical timestamp, original strokes, faded reply paths at alpha `0.35`, a 120-second timeout, and a snapshot of current-page restoration data.
- The committed Room schema `1.json` is packaged for `MigrationTestHelper`; the baseline test creates and validates version 1 and scans persisted columns for forbidden secret/image-byte fields.
- AGP 9.2 built-in Kotlin uses the matching `com.android.legacy-kapt` compiler bridge for the plan-pinned Room 2.7.2 processor.

## Verification

The Android 16 emulator was launched with explicit `ANDROID_AVD_HOME=C:\Users\Chao_\.android\avd`. Before the final run, ADB reported `emulator-5554` as `device` and `sys.boot_completed=1`.

Fresh final command:

```powershell
$env:JAVA_HOME='C:\Program Files\Android\Android Studio\jbr'
$env:ANDROID_HOME='C:\Users\Chao_\AppData\Local\Android\Sdk'
$env:ANDROID_SDK_ROOT=$env:ANDROID_HOME
$env:ANDROID_AVD_HOME='C:\Users\Chao_\.android\avd'
.\gradlew.bat :memory:clean :conversation:clean :conversation:testDebugUnitTest :conversation:lintDebug :memory:lintDebug :memory:connectedDebugAndroidTest --no-daemon
```

Result: `BUILD SUCCESSFUL` in 32 seconds; 191 tasks (162 executed, 29 up-to-date).

- `conversation`: 29 unit tests, 0 failures, 0 errors, 0 skipped; the reconstruction class contributes 4 tests.
- `memory`: 7 API 36 emulator tests, 0 failures, 0 errors, 0 skipped; 6 DAO/repository tests and 1 schema migration baseline test.
- `:conversation:lintDebug` and `:memory:lintDebug`: passed.
- `git diff --check`: passed; only the existing worktree line-ending notice for the unrelated modified Task 3 report was emitted.
- A production/schema scan found no API-key, Authorization/Bearer, cleartext HTTP, secret, temporary-image, or byte-array field. The only secret/image matches are assertions in `MigrationTest` that prohibit such columns.

## Review

Self-review traced FR-090 through FR-100, AC-014 through AC-017, the Task 8 brief, cascade/index definitions in exported schema 1, transaction boundaries, memory-disabled data flow, stable ordering, catalog freshness, selection failure paths, and the security boundary. It found the catalog-revision invalidation issue described above; the focused RED/GREEN fix is included. No unaddressed Critical or Important issue remains.

## Remaining limitation

Task 8 produces persistence and reconstruction plans only. Rendering the date/strokes/faded reply, touch/timeout restoration, process recreation wiring, settings confirmation UI, and composition-root database construction belong to Task 9. The device suite ran on the available phone-shaped `Riddle_API_36_1` AVD; no wide/foldable or tablet emulator was required for this non-UI slice.
