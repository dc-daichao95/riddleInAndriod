# Task 6 Report: Keystore credentials and Provider settings

## Scope

Implemented the Android `security` and `feature-settings` modules for provider credentials, diagnostics redaction, profile add/edit/test/enable/select/delete state, and the localized Compose settings surface. The Rust reference implementation was not changed.

## TDD evidence

### RED

- Initial `:security:test :feature-settings:test` reached the expected missing-production-symbol failure (`Redactor`, `ProviderSettingsViewModel`, and related contracts were unresolved).
- `RedactorTest.redacts raw key and authorization header values` then failed because the first implementation consumed the following diagnostic field; the regex boundary was corrected.
- `ProviderSettingsViewModelTest.repository cancellation is preserved` failed because `refresh()` converted `CancellationException` to a storage error; cancellation is now rethrown.
- `ProviderSettingsViewModelTest.editing metadata without replacement secret preserves credential` failed because a blank edit replaced the stored secret; metadata-only edits now retain the alias credential.

Environment setup findings during RED: the wrapper required network access, and Gradle required the installed Android Studio JBR plus `ANDROID_HOME`. No machine-specific path was committed.

### GREEN and refactor

- Credentials use a non-exportable Android Keystore AES/GCM key with the exact alias `riddle-provider-secrets-v1`.
- IV and ciphertext are stored together in app-private preferences under the provider credential alias; missing/tampered record shapes map to typed unavailable/corrupt results.
- Redaction removes supplied raw secrets and case-insensitive Authorization values.
- Provider state never contains credential values. HTTPS/profile validation precedes credential writes or Provider construction.
- Validation confirms the actual URI host and only calls the provider-neutral validation contract; no page image enters settings validation.
- Profile/credential writes and deletes use compensation, persist selected-profile clearing, and report failed compensation as inconsistent storage.
- UI-triggered operations run in `viewModelScope`; coroutine cancellation is preserved.
- Non-secret editor state is ViewModel-owned across configuration changes; credential input remains ephemeral and excluded from observable state. Concurrent UI mutations are rejected while an operation is active.
- A testable AES-GCM/storage seam covers unique IVs, authenticated tamper rejection, plaintext exclusion, deletion, and unavailable key providers without requiring AndroidKeyStore on the JVM.
- OpenAI, DeepSeek, and custom HTTPS presets, editing, testing, enabling, selecting, and deleting are exposed with localized labels and profile-specific TalkBack descriptions.
- Plan-pinned Compose BOM `2025.06.01` and lifecycle `2.9.1` are declared in the version catalog.
- Android Gradle outputs are ignored so module staging cannot include generated files.

## Review

An independent review reported no Critical issues. Its Important findings covering cancellation and non-cancellable compensation, composition-owned operations, lifecycle-owned draft state, concurrent mutations, selected-profile rollback, compensation visibility, corrupt-record classification, crypto/storage testability, profile-specific semantics, and generated build artifacts were addressed before the final gate.

## Verification

Final clean command:

```powershell
$env:JAVA_HOME='C:\Program Files\Android\Android Studio\jbr'
$env:ANDROID_HOME="$env:LOCALAPPDATA\Android\Sdk"
.\gradlew.bat :security:clean :feature-settings:clean :security:test :feature-settings:test :feature-settings:lintDebug --no-daemon
```

Result: exit code 0.

- `RedactorTest`: 2 tests, 0 failures, 0 errors.
- `EncryptedCredentialStoreTest`: 4 tests, 0 failures, 0 errors.
- `ProviderSettingsViewModelTest`: 12 tests, 0 failures, 0 errors.
- `:feature-settings:lintDebug`: passed.
- `git diff --check`: passed (line-ending conversion warnings only).
- Production source scan found no API-key-shaped strings, Authorization/Bearer values, or cleartext `http://` endpoints.

## Remaining limitation

The required Task 6 gate is JVM unit tests plus lint. AES-GCM and record-storage semantics are directly tested, but the platform `AndroidKeyStore` provider and `KeyGenParameterSpec` instantiation still require an API 36 device or emulator instrumentation suite. The Android implementation compiles against API 36.1.
