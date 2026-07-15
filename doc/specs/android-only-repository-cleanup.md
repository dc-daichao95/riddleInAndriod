# Android-only Repository Cleanup Specification

- Status: `approved`
- Product: Riddle Android Magic Paper
- Approval: user selected complete Android-only conversion and approved the cleanup design
- Runtime: Android 13/API 33 through Android 16/API 36
- Baseline: `codex/android-magic-paper` at or after `a1a155e`

## 1. Problem statement and user value

The repository root still contains the retired Rust/reMarkable implementation, device deployment scripts, legacy configuration, an old product README, and instructions that tell future developers to preserve or build the retired product. Android also consumes reply fonts from a root-level directory inherited from the previous layout. These files make the active product boundary ambiguous and can cause later agents to edit, test, document, or package the wrong application.

The repository shall become Android-only while preserving the Android application, its approved specifications, licenses, development evidence, Git history, and verified behavior.

## 2. Scope and non-goals

In scope:

- remove tracked Rust/reMarkable source, build, deployment, configuration, icon, and detailed-design artifacts;
- move Android-required reply font assets and their license/checksum files under the Android project;
- update Gradle and tests to use the new asset location;
- fully rewrite root `README.md`, root `AGENTS.md`, `.gitignore`, and `android-app/README.md` for an Android-only repository;
- update active specifications, plans, handoff documents, ADR references, and continuation prompts that point to deleted paths or describe Rust as the active reference;
- retain approved Android product behavior and Git history;
- verify no active documentation or build path depends on deleted files.

Non-goals:

- moving the Gradle project from `android-app/` to the repository root;
- changing Android runtime behavior, UI, provider contracts, persistence, min/target/compile SDK, dependencies, or application ID;
- implementing Tasks 3–6, tool-capable Agent behavior, or release signing;
- deleting Android development evidence solely because it is historical;
- erasing retired code from Git history;
- changing third-party font or Unicode licenses.

## 3. User stories and use cases

- A developer cloning the branch sees one active product: Riddle Android.
- A development agent can read root `README.md` and `AGENTS.md` without being instructed to inspect or preserve deleted Rust paths.
- Android builds and tests resolve all fonts and checksums from inside `android-app/`.
- A maintainer can recover the retired reMarkable implementation from Git history without it affecting current builds.
- A new environment can follow one migration document and one authoritative implementation plan without stale file references.

## 4. Functional requirements

- `CLEAN-FR-001`: The following tracked retired artifacts shall be removed: `.cargo/`, `src/`, `scripts/`, `Cargo.toml`, `Cargo.lock`, `build.rs`, `build-takeover.sh`, `external.manifest.json`, `oracle.env.example`, `settings.schema.json`, and root `icon.png`.
- `CLEAN-FR-002`: `doc/detailed-design.md`, which describes only the retired Rust/reMarkable implementation, shall be removed despite its pre-existing user modification; this direct approved cleanup supersedes the earlier preservation rule for that file only.
- `CLEAN-FR-003`: Root `fonts/` content required by Android shall move to an Android-owned asset directory under `android-app/paper-engine/src/main/assets/fonts/` with filenames, bytes, licenses, and SHA-256 values unchanged.
- `CLEAN-FR-004`: `paper-engine` Gradle asset registration and checksum verification shall resolve fonts from the Android-owned path and shall not traverse outside `android-app/`.
- `CLEAN-FR-005`: Font tests shall resolve the Android-owned path and continue verifying Dancing Script, LXGW WenKai, license text, manifest content, and pinned hashes.
- `CLEAN-FR-006`: Root `README.md` shall be replaced with an Android product document covering product behavior, supported APIs, current completion status, repository layout, setup, build/test commands, provider configuration, security/privacy, documentation entry points, and known remaining tasks.
- `CLEAN-FR-007`: Root `AGENTS.md` shall become Android-only and retain the SDD/TDD lifecycle, provider-neutral architecture, security/privacy, testing, accessibility, localization, compatibility, documentation, change-discipline, and definition-of-done rules that remain applicable.
- `CLEAN-FR-008`: `.gitignore` shall remove retired Rust/reMarkable patterns and cover Android/Gradle build output, IDE state, local SDK paths, credentials, signing material, emulator artifacts, generated reports, and agent worktrees without ignoring source, schemas, specifications, licenses, or wrapper files.
- `CLEAN-FR-009`: `android-app/README.md` shall no longer call Android a separate product from Rust and shall contain canonical current build, lint, unit, instrumentation, emulator, Provider, and secret-handling instructions.
- `CLEAN-FR-010`: Active Android specifications, plans, handoff documents, prompts, and ADRs shall not require deleted paths. Historical behavior provenance may refer to a pre-cleanup Git commit, but not instruct developers to read a missing working-tree file.
- `CLEAN-FR-011`: Root `LICENSE`, Android sources/tests, Room schemas, approved specifications, current plans, handoff materials, ADRs, `.superpowers` Android evidence, and original Android branding assets shall be retained.
- `CLEAN-FR-012`: The cleanup commit shall contain no API keys, signing secrets, user conversations, build output, emulator data, or unrelated user changes from `.superpowers/sdd/task-3-report.md` and `doc/TODO.md`.

## 5. Non-functional requirements

- `CLEAN-NFR-001`: Runtime Android behavior and packaged font bytes shall remain unchanged.
- `CLEAN-NFR-002`: The Android project shall remain rooted at `android-app/` to minimize churn and preserve existing commands.
- `CLEAN-NFR-003`: No new runtime or build dependency shall be introduced.
- `CLEAN-NFR-004`: The resulting active documentation shall have one unambiguous product direction and no broken local path references.
- `CLEAN-NFR-005`: Deletion shall be performed through a reviewable Git commit and remain recoverable from history.
- `CLEAN-NFR-006`: API 33/API 36 behavior, package name `dev.riddle.magicpaper`, `minSdk = 33`, `targetSdk = 36`, and `compileSdk = 36.1` shall not change.

## 6. Acceptance criteria

- `CLEAN-AC-001`: Given the cleanup commit, when tracked files are listed, then every `CLEAN-FR-001` artifact and `doc/detailed-design.md` is absent.
- `CLEAN-AC-002`: Given the Android project, when repository-reference scanning runs, then production/build/test paths contain no dependency on root `fonts/`, Rust `src/`, Cargo files, reMarkable scripts, Oracle configuration, or deleted manifests.
- `CLEAN-AC-003`: Given moved fonts, when SHA-256 values are compared with the pre-cleanup manifest, then every font/license byte and expected hash is unchanged.
- `CLEAN-AC-004`: Given a clean Gradle invocation from `android-app/`, when pinned assets, unit tests, lint, and debug assembly run, then all succeed without relying on a path outside `android-app/` except the local SDK/JDK.
- `CLEAN-AC-005`: Given root `README.md`, when a new Android developer follows it, then they can locate requirements, configure the SDK, run canonical gates, configure a Provider safely, and identify Tasks 3–6 without seeing retired-device instructions.
- `CLEAN-AC-006`: Given root `AGENTS.md`, when an agent reads it, then every required source, test, document, and command path exists after cleanup and the only product scope is Android.
- `CLEAN-AC-007`: Given active Markdown files, when local path references are scanned, then no required reference targets a deleted file; intentionally historical references name a Git revision explicitly.
- `CLEAN-AC-008`: Given `git status` and the staged diff, when scope is audited, then protected unrelated changes and untracked branding assets are not accidentally deleted or staged.
- `CLEAN-AC-009`: Given the Android 16 emulator, when the relevant critical instrumentation test is run after cleanup, then its test inventory and result are unchanged from the pre-cleanup baseline.

## 7. Architecture and affected modules

Repository layout after cleanup:

```text
repository root
├── AGENTS.md
├── README.md
├── LICENSE
├── android-app/                  # only active product/build
│   └── paper-engine/src/main/assets/fonts/
└── doc/                          # Android specs, plans, ADRs, handoff, prompts
```

Affected Android module: `paper-engine` build configuration and asset-path tests only. No production Kotlin contract or runtime state machine changes are approved.

## 8. Data model and persistence changes

None. Room schemas, migrations, preferences, credentials, drafts, conversations, and recovery state remain unchanged.

## 9. API, provider, streaming, and tool-call contracts

None. OpenAI-compatible and DeepSeek-compatible Provider contracts, normalized streaming, retry/cancellation behavior, and the Phase 2 tool boundary remain unchanged.

## 10. Error, retry, cancellation, and offline behavior

No runtime behavior changes. Build verification shall fail clearly if any moved asset is missing, modified, unlicensed, or has a checksum mismatch. Cleanup must not weaken checksum tasks to make a build pass.

## 11. Security and privacy analysis

Legacy plaintext Oracle configuration examples shall be removed. Updated documentation shall direct users to the Android Keystore-backed Provider flow and forbid secrets in repository files, `local.properties`, logs, screenshots, build properties, or signing configuration. `.gitignore` is defense in depth and not a substitute for secret handling.

## 12. Accessibility and localization impact

No runtime impact. Documentation shall continue to state TalkBack, keyboard/switch navigation, 48 dp targets, dynamic font, English/Simplified Chinese resources, and reduced-motion requirements.

## 13. Observability requirements with redaction rules

No new telemetry. Verification output may contain paths, task names, counts, hashes, and SDK versions. It must not contain credentials, authorization headers, prompts, replies, handwriting, user data, or signing passwords.

## 14. Migration and compatibility impact

- Clone/build entry remains `android-app/`.
- Font asset paths change inside source control but packaged filenames and bytes remain stable.
- Retired files remain recoverable from Git commit `a1a155e` or earlier.
- External users of the removed Rust/reMarkable build are intentionally unsupported on this Android-only branch.
- API 33 and API 36 behavior is unchanged.

## 15. Test strategy and requirement-to-test traceability

| Requirements | Evidence |
| --- | --- |
| CLEAN-FR-001..002 | tracked-file absence and staged deletion audit |
| CLEAN-FR-003..005 | SHA-256 comparison, `verifyPinnedReplyAssets`, font unit tests, asset merge inspection |
| CLEAN-FR-006..010 | Markdown/path/reference scan and manual documentation review |
| CLEAN-FR-011..012 | explicit retained-file inventory, `git status`, staged name audit, secret scan |
| CLEAN-NFR-001..003 | full JVM tests, lint, `assembleDebug`, dependency diff audit |
| CLEAN-NFR-004..006 | active-doc scan, Android compatibility task, manifest inspection |
| CLEAN-AC-009 | focused Android 16 instrumentation class |

Deletion/configuration work shall use pre/post verification rather than inventing runtime behavior tests. Font-path production/build changes require a failing path/checksum test or task before the move and a passing result afterward.

## 16. Rollout and rollback plan

Land the cleanup as reviewable commits: first the approved specification/plan, then one implementation commit containing deletions, font relocation, and documentation updates. Push only after independent review and all available gates pass. Rollback uses `git revert` of the implementation commit; do not reintroduce individual legacy files without a new approved specification.

## 17. Decisions

- Convert this branch completely to Android-only.
- Keep `android-app/` as the Gradle root.
- Move, do not delete, Android-required fonts and licenses.
- Delete the Rust-only detailed design and retired root product files.
- Preserve Android historical evidence and Git history.
- Rewrite root guidance instead of leaving compatibility notices for the retired product.

## 18. Unresolved questions and decisions

None. Public release, production signing, and Tasks 3–6 remain separately gated work.
