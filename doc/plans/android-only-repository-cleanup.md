# Android-only Repository Cleanup Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Convert the current branch into an unambiguous Android-only repository without changing Android runtime behavior or losing required fonts, licenses, specifications, or development evidence.

**Architecture:** Keep `android-app/` as the only Gradle/product root. Move pinned reply fonts into `paper-engine/src/main/assets/fonts`, remove the retired Rust/reMarkable tree, and make root guidance plus active Android documents reference only paths that remain in the checkout. The migration lands as one atomic implementation commit because font relocation, legacy deletion, and documentation correction must not leave an intermediate broken repository.

**Tech Stack:** Git, Gradle Kotlin DSL, Kotlin/JVM tests, Android lint/resource packaging, PowerShell, Android 16 instrumentation.

## Global Constraints

- Follow `doc/specs/android-only-repository-cleanup.md` exactly.
- Keep the Android Gradle root at `android-app/`.
- Preserve package `dev.riddle.magicpaper`, `minSdk = 33`, `targetSdk = 36`, and `compileSdk = 36.1`.
- Do not add a runtime or build dependency.
- Preserve font and license bytes and their pinned SHA-256 values.
- Do not change Android runtime behavior, Provider contracts, persistence, UI, or Tasks 3–6.
- Do not stage `.superpowers/sdd/task-3-report.md`, `doc/TODO.md`, or untracked branding assets.
- The approved cleanup intentionally replaces the current user-modified `README.md` and deletes the current user-modified `doc/detailed-design.md`.
- Never print or commit API keys, credentials, signing material, user conversations, ink, emulator data, or build outputs.

---

### Task 1: Atomic Android-only repository migration

**Files:**

- Move: `fonts/*` → `android-app/paper-engine/src/main/assets/fonts/*`
- Modify: `android-app/paper-engine/build.gradle.kts`
- Modify: `android-app/paper-engine/src/test/kotlin/dev/riddle/magicpaper/paper/PinnedReplyAssetsTest.kt`
- Modify: `android-app/paper-engine/src/test/kotlin/dev/riddle/magicpaper/paper/BundledTypefaceGlyphSourceTest.kt`
- Delete: `.cargo/`, `src/`, `scripts/`
- Delete: `Cargo.toml`, `Cargo.lock`, `build.rs`, `build-takeover.sh`
- Delete: `external.manifest.json`, `oracle.env.example`, `settings.schema.json`, `icon.png`
- Delete: `doc/detailed-design.md`
- Replace: `README.md`, `AGENTS.md`, `.gitignore`, `android-app/README.md`
- Modify: active specifications, plans, handoff, prompt, and ADR documents returned by the retired-path scan
- Test: focused font tests, pinned-asset verification, all JVM tests, lint, debug assembly, Android 16 critical instrumentation

**Interfaces:**

- Consumes: exact font/license bytes and hashes currently under root `fonts/`; approved Android-only cleanup specification; current Android Gradle module graph.
- Produces: `android-app/paper-engine/src/main/assets/fonts/` as the only font source; an Android-only root guidance contract; no working-tree dependency on retired files.

- [ ] **Step 1: Record the pre-move asset and scope baseline**

Run from the repository root:

```powershell
Get-FileHash fonts\DancingScript.ttf,fonts\LXGWWenKai-Regular.ttf,fonts\OFL.txt,fonts\LXGWWenKai-OFL.txt,fonts\MANIFEST.sha256 -Algorithm SHA256
git status --short
git ls-files .cargo src scripts Cargo.toml Cargo.lock build.rs build-takeover.sh external.manifest.json oracle.env.example settings.schema.json icon.png fonts doc/detailed-design.md
```

Expected:

- hashes match the checked-in manifest and pinned Gradle values;
- the only pre-existing dirty protected paths are `.superpowers/sdd/task-3-report.md`, `README.md`, `doc/TODO.md`, and `doc/detailed-design.md`;
- branding assets remain untracked and outside the cleanup stage set.

- [ ] **Step 2: Write the font-location RED tests**

In `PinnedReplyAssetsTest.kt`, change only the font directory:

```kotlin
val fonts = repository.resolve("android-app/paper-engine/src/main/assets/fonts")
```

In `BundledTypefaceGlyphSourceTest.kt`, introduce the Android-owned font directory and use it for both tests:

```kotlin
private val fonts = repository.resolve("android-app/paper-engine/src/main/assets/fonts")

latin = Typeface.createFromFile(fonts.resolve("DancingScript.ttf"))
cjk = Typeface.createFromFile(fonts.resolve("LXGWWenKai-Regular.ttf"))

val names = readSfntNames(fonts.resolve("LXGWWenKai-Regular.ttf"))
```

- [ ] **Step 3: Run the focused test to verify RED**

Run from `android-app/`:

```powershell
.\gradlew.bat :paper-engine:testDebugUnitTest --tests "dev.riddle.magicpaper.paper.PinnedReplyAssetsTest" --tests "dev.riddle.magicpaper.paper.BundledTypefaceGlyphSourceTest" --rerun-tasks
```

Expected: FAIL because `android-app/paper-engine/src/main/assets/fonts` does not exist or its font files are missing. A compilation, network, or unrelated failure is not valid RED.

- [ ] **Step 4: Move fonts without changing bytes**

Create `android-app/paper-engine/src/main/assets/fonts/` and move exactly:

```text
DancingScript.ttf
LXGWWenKai-Regular.ttf
OFL.txt
LXGWWenKai-OFL.txt
MANIFEST.sha256
```

After the move, root `fonts/` must be absent. Compare post-move SHA-256 output with Step 1 before editing Gradle.

- [ ] **Step 5: Make Gradle consume only Android-owned font assets**

Replace root-relative entries in `android-app/paper-engine/build.gradle.kts` with:

```kotlin
val replyFonts = layout.projectDirectory.dir("src/main/assets/fonts")

val pinnedReplyAssets = mapOf(
    replyFonts.file("DancingScript.ttf").asFile to "21808625578fe8d8cd10cb684be546dca077b27cd03a53a2f1ec11dc743c924c",
    replyFonts.file("OFL.txt").asFile to "5a296979e49df4e947349fd6afdc3c0b305282d563f7ef2fad5412d2a235d1e8",
    replyFonts.file("LXGWWenKai-Regular.ttf").asFile to "39ad71264b588165b469e35e6afb162a378dacd1f95348160240ba9038ac3009",
    replyFonts.file("LXGWWenKai-OFL.txt").asFile to "c38b1994a5e48ac30ac7d1da7d0409fd8fd8127dfe28a13d6e787d5b1ef34a5e",
    file("unicode-data/15.0.0/GraphemeBreakProperty.txt") to "5a0f8748575432f8ff95e1dd5bfaa27bda1a844809e17d6939ee912bba6568a1",
    file("unicode-data/15.0.0/emoji-data.txt") to "29071dba22c72c27783a73016afb8ffaeb025866740791f9c2d0b55cc45a3470",
    file("unicode-data/15.0.0/LICENSE.txt") to "e7a93b009565cfce55919a381437ac4db883e9da2126fa28b91d12732bc53d96",
)
```

Update manifest inputs to:

```kotlin
assets.from(replyFonts.file("MANIFEST.sha256"), file("unicode-data/15.0.0/MANIFEST.sha256"))
manifests.put(replyFonts.file("MANIFEST.sha256").asFile.absolutePath, replyFonts.asFile.absolutePath)
```

Delete the `androidComponents { ... addStaticSourceDirectory("../../fonts") ... }` block because `src/main/assets` is a standard Android asset source.

- [ ] **Step 6: Run font GREEN and packaged-asset verification**

Run from `android-app/`:

```powershell
.\gradlew.bat :paper-engine:verifyPinnedReplyAssets :paper-engine:testDebugUnitTest --tests "dev.riddle.magicpaper.paper.PinnedReplyAssetsTest" --tests "dev.riddle.magicpaper.paper.BundledTypefaceGlyphSourceTest" :app:mergeDebugAssets --rerun-tasks
```

Expected: BUILD SUCCESSFUL; all focused tests pass; merged app assets include the five `fonts/` files exactly once.

- [ ] **Step 7: Remove retired tracked files**

Delete exactly the `CLEAN-FR-001` and `CLEAN-FR-002` paths:

```text
.cargo/
src/
scripts/
Cargo.toml
Cargo.lock
build.rs
build-takeover.sh
external.manifest.json
oracle.env.example
settings.schema.json
icon.png
doc/detailed-design.md
```

Before recursive deletion, resolve each absolute path and verify it is inside the current worktree. Do not delete `.superpowers/`, `android-app/`, `doc/TODO.md`, `LICENSE`, or branding assets.

- [ ] **Step 8: Replace the root Android product README**

Write `README.md` with these exact top-level sections and facts:

```markdown
# Riddle Magic Paper for Android

Riddle is an immersive handwriting-first LLM application for Android 13 through Android 16. Write across the full paper, send with the magic rune, and receive a streamed answer rendered back as handwriting.

## Current status
## Supported Android versions
## Features
## Repository layout
## Quick start
## Build and test
## Configure a model provider
## Security and privacy
## Accessibility and localization
## Specifications and development workflow
## Remaining work
## License and bundled fonts
```

Required statements:

- current supported range is API 33–36, compile 36.1, target 36;
- OpenAI-compatible and DeepSeek-compatible profiles are supported without Provider-name branches in shared UI/domain code;
- keys are Keystore-backed and normal tests use fakes;
- canonical commands run from `android-app/`;
- current continuation starts at master-plan Task 3;
- root `LICENSE` is MIT and bundled font licenses live beside the Android assets;
- no reMarkable installation, Cargo, Oracle environment, takeover mode, SSH, AppLoad, Quill, or Rust build instruction remains.

- [ ] **Step 9: Replace root development instructions with Android-only rules**

Rewrite `AGENTS.md` so its opening scope is:

```markdown
# Riddle Android Development Guide

## 1. Scope and product direction

This repository contains one active product: the Riddle Magic Paper Android application under `android-app/`. It supports Android 13/API 33 through Android 16/API 36. Historical reMarkable/Rust code is available only through Git history and is not a working-tree dependency or development target.
```

Retain and reconcile these existing rule groups without deleted paths:

```text
instruction priority
Explore → Specify → Review → Plan → Red → Green → Refactor → Verify → Document
Kotlin/Gradle/Compose/coroutines/Flow/Room/Keystore baseline
provider-neutral contracts and capabilities
stream parsing, retry and cancellation
agent Phase 2 safety boundary
conversation persistence and process recovery
accessibility and localization
test pyramid and no live secrets
verification gates
dependency/supply-chain policy
Android documentation paths
change discipline and definition of done
```

Remove every rule requiring `src/`, Cargo, Rust builds, `doc/detailed-design.md`, or preservation of the retired product. Keep protection for unrelated dirty files but record that the approved cleanup intentionally replaces `README.md` and deletes `doc/detailed-design.md`.

- [ ] **Step 10: Rewrite Android setup guidance and ignore rules**

In `android-app/README.md`:

- remove “separate Gradle build” and “does not replace the Rust product”;
- retain exact JDK 21, SDK 33/36.1, build-tools 36.1, AVD, `local.properties`, test, and process-recovery guidance;
- add Provider setup flow, Keystore warning, canonical `test`, `lint`, `assembleDebug`, and focused connected-test commands;
- link to root README, cleanup spec, continuation spec, and master plan.

Replace `.gitignore` with Android-only categories that include:

```gitignore
/.worktrees/
/.superpowers/
*.log

/android-app/.gradle/
/android-app/.kotlin/
/android-app/local.properties
/android-app/build/
/android-app/**/build/
/.idea/
*.iml

*.jks
*.keystore
keystore.properties
signing.properties
*.apk
*.aab

/.android/
/.avd/
```

Do not ignore Gradle wrapper files, Room schemas, source assets, font licenses, specs, or test fixtures.

- [ ] **Step 11: Repair active documentation references**

Run:

```powershell
rg -n "reMarkable|remarkable|Cargo|Cargo\.toml|src/|src\\|oracle\.env|build-takeover|external\.manifest|settings\.schema|root `fonts/`|root-level fonts" README.md AGENTS.md android-app/README.md doc
```

For current specs/plans/handoff/prompts/ADRs, replace missing working-tree dependencies with Android paths or historical wording such as:

```text
The retired reference implementation is available in Git history at a1a155e and is not required for current development.
```

Historical plans may retain old path names only if their first page labels them historical and explicitly says those paths exist only at pre-cleanup revision `a1a155e`. Update at minimum:

```text
doc/specs/android-magic-paper-app.md
doc/specs/magic-paper-ux-provider-text-pipeline.md
doc/specs/android-continuation-handoff-spec.md
doc/plans/android-magic-paper-implementation.md
doc/plans/magic-paper-ux-provider-text-repair.md
doc/plans/android-completion-master-plan.md
doc/handoff/android-development-baseline-2026-07-16.md
doc/prompts/android-continuation-agent-prompt.md
doc/adr/0001-typed-provider-model-discovery.md (only if scan matches)
```

The cleanup spec and cleanup plan may name deleted paths because they document the migration.

- [ ] **Step 12: Run structural and secret audits**

Run from the repository root:

```powershell
git ls-files .cargo src scripts Cargo.toml Cargo.lock build.rs build-takeover.sh external.manifest.json oracle.env.example settings.schema.json icon.png fonts doc/detailed-design.md
rg -n "RIDDLE_OPENAI_KEY|sk-[A-Za-z0-9]|BEGIN (RSA |EC |OPENSSH )?PRIVATE KEY" . -g '!**/build/**' -g '!doc/specs/android-only-repository-cleanup.md'
rg -n "\.\./\.\./fonts|repository\.resolve\(\"fonts|src/oracle\.rs|src/ink\.rs|src/script\.rs" android-app README.md AGENTS.md doc -g '!doc/specs/android-only-repository-cleanup.md' -g '!doc/plans/android-only-repository-cleanup.md'
```

Expected:

- the first command prints nothing;
- the secret scan finds no secret value;
- active code/build/current docs have no missing-path dependency;
- any historical-plan match is explicitly labeled as pre-cleanup history.

- [ ] **Step 13: Run full Android verification**

Run from `android-app/`:

```powershell
.\gradlew.bat verifyAndroidCompatibility :paper-engine:verifyPinnedReplyAssets test lint assembleDebug --rerun-tasks
.\gradlew.bat :app:connectedDebugAndroidTest "-Pandroid.testInstrumentationRunnerArguments.class=dev.riddle.magicpaper.MagicRuneSettingsTest" --rerun-tasks
```

Expected:

- both commands exit 0;
- all JVM tests have zero failures/errors/skips;
- lint has no errors;
- debug APK builds;
- Android 16 `MagicRuneSettingsTest` retains 8/8 passing tests;
- no live Provider is called.

- [ ] **Step 14: Audit exact implementation scope**

Run:

```powershell
git status --short
git diff --check
git diff --stat
git diff --name-status
```

Confirm:

- `.superpowers/sdd/task-3-report.md` and `doc/TODO.md` remain unstaged user changes;
- branding assets remain untracked for Task 5;
- `README.md` replacement and `doc/detailed-design.md` deletion are intentional approved cleanup changes;
- no Kotlin production behavior, Provider contract, Room schema, SDK value, dependency version, or application ID changed.

- [ ] **Step 15: Commit the atomic migration**

Stage only the explicit moved/deleted/modified cleanup paths and commit:

```powershell
git commit -m "chore(android): remove retired reMarkable project"
```

Expected: one atomic cleanup commit after the existing spec and plan commits. Do not push until independent spec-compliance and code-quality review is clean.

---

### Task 2: Independent review and GitHub handoff

**Files:** Read-only review of the Task 1 commit; update the progress ledger only if review is clean.

**Interfaces:**

- Consumes: cleanup specification, this plan, Task 1 report, and the full diff from the pre-cleanup base through the implementation commit.
- Produces: independent Spec Compliance and Code Quality verdicts plus a synchronized GitHub branch.

- [ ] **Step 1: Generate a review package**

Use the commit immediately before Task 1 implementation as BASE and the implementation commit as HEAD:

```text
review-package BASE HEAD .superpowers/sdd/review-android-only-cleanup.diff
```

- [ ] **Step 2: Run independent review**

The reviewer must verify deletion scope, font-byte preservation, Gradle asset isolation, README/AGENTS accuracy, active-document references, security, tests, and staged-file discipline. Critical and Important findings block completion; dispatch one fix wave and re-review.

- [ ] **Step 3: Verify and push**

After clean review:

```powershell
git status -sb
git rev-list --left-right --count '@{upstream}...HEAD'
git push origin HEAD:refs/heads/codex-android-magic-paper
```

Expected: local and `origin/codex-android-magic-paper` resolve to the same final commit. Report retained dirty/untracked user files explicitly.

## Plan self-review

- `CLEAN-FR-001..012`, `CLEAN-NFR-001..006`, and `CLEAN-AC-001..009` each map to an explicit step and verification command.
- Font relocation has an observable RED before production/build configuration changes and a checksum-backed GREEN afterward.
- The plan does not promote the Gradle project to root, add dependencies, or implement Tasks 3–6.
- The deletion and documentation rewrite land atomically, so no committed revision has missing font dependencies or contradictory active guidance.
- The only permitted pre-existing user-file overrides are the explicitly approved `README.md` replacement and `doc/detailed-design.md` deletion.
