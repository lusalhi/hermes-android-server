---
title: 'Keystore-Backed Encrypted Secrets Repository & POSIX 0600 Config Serialization'
type: 'feature'
created: '2026-08-25'
status: 'done'
baseline_commit: 'c3d4823c8eb5f94169c04addcdba9b582c3d0ca7'
review_loop_iteration: 0
context:
  - _bmad-output/planning-artifacts/architecture/architecture-hermes-android-server-2026-08-24/ARCHITECTURE-SPINE.md
  - _bmad-output/planning-artifacts/prds/prd-hermes-android-server-2026-08-24/prd.md
  - _bmad-output/implementation-artifacts/epic-2-context.md
---

<frozen-after-approval reason="human-owned intent — do not modify unless human renegotiates">

## Intent

**Problem:** Sensitive user credentials (LLM API keys, Telegram bot tokens, custom endpoints) must never be stored in plain text on disk or exposed through device backups, and the runtime configuration (`hermes.json`) consumed by the Python sidecar daemon requires strict POSIX `0600` file permissions to prevent unauthorized access across device apps.

**Approach:** Implement `ConfigRepository` backed by AndroidX Security Crypto (`EncryptedSharedPreferences` / AES-256 GCM) for persistent credential encryption at rest, and implement `ConfigSerializer` to atomically serialize structured settings to `context.filesDir/hermes.json` with strict POSIX `0600` permissions (`setReadable(true, true)`, `setWritable(true, true)`), integrated into `ServerViewModel` and `SettingsScreen`.

## Boundaries & Constraints

**Always:**
- Encrypt API keys and bot tokens at rest using Android Keystore-backed `EncryptedSharedPreferences` (AES-256 GCM).
- Write `hermes.json` atomically (write to staging temp file and replace destination) to prevent partial/corrupted reads by child processes.
- Enforce POSIX `0600` permissions on `hermes.json` (readable and writable exclusively by app UID, non-executable, not readable by group/others).
- Load stored credentials and provider configurations from `ConfigRepository` into `ServerUiState` upon ViewModel initialization.
- Perform config persistence and JSON serialization asynchronously on `Dispatchers.IO`.

**Ask First:**
- Clearing or resetting encrypted secret keys during normal settings save operations.

**Never:**
- Store API keys or bot tokens in plain unencrypted `SharedPreferences` or unmasked log outputs.
- Write world-readable (`0644` or `0777`) configuration files.
- Crash or fail silently if Keystore operations encounter transient hardware failures (provide fallback / structured error reporting).

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| Save Settings (Valid Input) | User enters provider, API key, Telegram token, and taps "Save Settings" | Credentials stored in `EncryptedSharedPreferences`; atomic `hermes.json` generated with `0600` permissions; `isSettingsSaved` confirmed | Logs success; surfaces feedback |
| Initial App Launch | `EncryptedSharedPreferences` contains saved keys from previous session | ViewModel loads saved configuration into `ServerUiState` automatically | If preferences unreadable, loads defaults safely |
| Missing or Blank Secrets | API key or token left empty | Serializes config with null/empty values for omitted fields without crashing; `hermes.json` reflects configured subset | Handles empty strings safely |
| Atomic File Write Failure | Disk full or I/O failure during temp file write | Temp file discarded; original `hermes.json` left uncorrupted | Returns error result; surfaces warning |
| POSIX Permission Verification | `hermes.json` written to disk | `file.canRead() == true`, `file.canWrite() == true`, `file.canExecute() == false`, `ownerOnly` flags set | Logs warning if permission call fails |

</frozen-after-approval>

## Code Map

- `app/build.gradle.kts` -- Add `androidx.security:security-crypto:1.1.0-alpha06` for Android Keystore `EncryptedSharedPreferences` support.
- `app/src/main/java/com/hermes/node/data/model/HermesConfig.kt` -- Data structures for provider settings, gateway credentials, and serialization schema.
- `app/src/main/java/com/hermes/node/data/ConfigRepository.kt` -- Interface and implementation for Keystore-backed secret storage (`EncryptedSharedPreferences`), with support for standard `SharedPreferences` in unit test environments.
- `app/src/main/java/com/hermes/node/data/ConfigSerializer.kt` -- Atomic JSON generator writing `hermes.json` with strict POSIX `0600` file permissions.
- `app/src/main/java/com/hermes/node/viewmodel/ServerUiState.kt` -- Add `isSettingsSaved: Boolean` and `configSaveMessage: String?`.
- `app/src/main/java/com/hermes/node/viewmodel/ServerViewModel.kt` -- Inject `ConfigRepository` and `ConfigSerializer`, load persisted credentials on `init`, and implement `onSaveSettings()`.
- `app/src/main/java/com/hermes/node/ui/screens/SettingsScreen.kt` & `HermesNavGraph.kt` -- Wire "Save Settings" button to `viewModel.onSaveSettings()` and show reactive confirmation toast/banner.
- `app/src/test/java/com/hermes/node/data/ConfigRepositoryTest.kt` -- Unit tests for credential persistence, encryption retrieval, and default values.
- `app/src/test/java/com/hermes/node/data/ConfigSerializerTest.kt` -- Unit tests for JSON schema formatting, atomic file writing, and POSIX `0600` permissions.
- `app/src/test/java/com/hermes/node/viewmodel/ServerViewModelTest.kt` -- Unit tests for initial settings loading, save settings trigger, and error handling.

## Tasks & Acceptance

**Execution:**
- [x] `app/build.gradle.kts` -- Add `androidx.security:security-crypto` dependency -- Enables Android Keystore encryption.
- [x] `app/src/main/java/com/hermes/node/data/model/HermesConfig.kt` & `ConfigRepository.kt` -- Implement data models and `ConfigRepository` for secure credential persistence -- Provides at-rest Keystore encryption for FR-3 & FR-5.
- [x] `app/src/main/java/com/hermes/node/data/ConfigSerializer.kt` -- Implement atomic `hermes.json` serialization with POSIX `0600` permissions -- Enforces AD-5 filesystem security.
- [x] `app/src/main/java/com/hermes/node/viewmodel/ServerViewModel.kt` & `ServerUiState.kt` -- Integrate `ConfigRepository` / `ConfigSerializer` into ViewModel lifecycle -- Handles startup loading and settings save actions.
- [x] `app/src/main/java/com/hermes/node/ui/screens/SettingsScreen.kt` & `HermesNavGraph.kt` -- Wire "Save Settings" UI button to ViewModel and display feedback -- Delivers end-to-end settings persistence UX.
- [x] `app/src/test/java/com/hermes/node/data/ConfigRepositoryTest.kt`, `ConfigSerializerTest.kt` & `ServerViewModelTest.kt` -- Add comprehensive unit tests for repository, serializer, permissions, and ViewModel interactions -- Verifies acceptance criteria.

**Acceptance Criteria:**
- Given API keys and gateway tokens entered in Settings, when tapping "Save Settings", then credentials are saved securely in `ConfigRepository` and `hermes.json` is generated with `0600` POSIX permissions.
- Given existing saved settings in `ConfigRepository`, when the application launches and `ServerViewModel` initializes, then `ServerUiState` is populated with the persisted provider and credential values.
- Given an atomic write to `hermes.json`, when serialization completes, then the file contains valid JSON matching the active settings without exposing secrets in logs.

## Spec Change Log

## Design Notes

`ConfigRepository` uses `MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()` to instantiate `EncryptedSharedPreferences.create()`.
To allow robust unit testing on standard JVM test runners, `ConfigRepository` supports injecting a standard `SharedPreferences` or mock delegate.
`ConfigSerializer` creates `hermes.json.tmp`, writes formatted JSON content, sets `setReadable(true, true)` / `setWritable(true, true)` / `setExecutable(false, false)`, and renames it atomically to `hermes.json`.

## Verification

**Commands:**
- `./gradlew test` -- expected: `BUILD SUCCESSFUL` with all unit tests passing.
- `./gradlew assembleDebug` -- expected: `BUILD SUCCESSFUL` producing valid `app-debug.apk`.

## Suggested Review Order

**Encrypted Secrets Persistence & Repository Layer**

- Keystore-backed EncryptedSharedPreferences repository with isolated fallback handling
  [`ConfigRepository.kt:35`](../../app/src/main/java/com/hermes/node/data/ConfigRepository.kt#L35)

- Clean domain models representing providers, gateways, and daemon configuration
  [`HermesConfig.kt:15`](../../app/src/main/java/com/hermes/node/data/model/HermesConfig.kt#L15)

**Atomic POSIX 0600 Config Serialization**

- Atomic temp-file JSON generator enforcing POSIX 0600 permissions
  [`ConfigSerializer.kt:35`](../../app/src/main/java/com/hermes/node/data/ConfigSerializer.kt#L35)

**State Management & ViewModel Lifecycle**

- Startup loading of encrypted credentials and asynchronous settings save
  [`ServerViewModel.kt:45`](../../app/src/main/java/com/hermes/node/viewmodel/ServerViewModel.kt#L45)

- Reactive saving state and feedback banner messages
  [`ServerUiState.kt:45`](../../app/src/main/java/com/hermes/node/viewmodel/ServerUiState.kt#L45)

**UI Presentation & Navigation Binding**

- Save button with loading indicator and dismissable feedback banner
  [`SettingsScreen.kt:430`](../../app/src/main/java/com/hermes/node/ui/screens/SettingsScreen.kt#L430)

- Dependency injection of encrypted repository into ViewModel factory
  [`HermesNavGraph.kt:45`](../../app/src/main/java/com/hermes/node/ui/navigation/HermesNavGraph.kt#L45)

**Unit Testing & Security Assertions**

- Tests for atomic serialization, JSON escaping, and POSIX 0600 permissions
  [`ConfigSerializerTest.kt:30`](../../app/src/test/java/com/hermes/node/data/ConfigSerializerTest.kt#L30)

- Tests for encrypted credential persistence and defaults
  [`ConfigRepositoryTest.kt:25`](../../app/src/test/java/com/hermes/node/data/ConfigRepositoryTest.kt#L25)

- Tests for ViewModel startup loading and save settings flow
  [`ServerViewModelTest.kt:600`](../../app/src/test/java/com/hermes/node/viewmodel/ServerViewModelTest.kt#L600)
