---
title: 'Binary Health Check & One-Tap Environment Repair'
type: 'feature'
created: '2026-08-25'
status: 'done'
baseline_commit: 'e3bf68a6671a5b46e08ead3bfacd197850d747c3'
review_loop_iteration: 0
context:
  - _bmad-output/planning-artifacts/architecture/architecture-hermes-android-server-2026-08-24/ARCHITECTURE-SPINE.md
  - _bmad-output/planning-artifacts/prds/prd-hermes-android-server-2026-08-24/prd.md
  - _bmad-output/implementation-artifacts/epic-2-context.md
---

<frozen-after-approval reason="human-owned intent — do not modify unless human renegotiates">

## Intent

**Problem:** If core ARM64 binaries (`python3`, `proot`, `hermes`) are deleted, corrupted, or stripped of POSIX executable permissions after bootstrap, the Hermes agent daemon fails silently or crashes on startup without a clear diagnostic or recovery path.

**Approach:** Implement a startup `checkHealth()` integrity verification in `BootstrapExtractor` to detect missing or non-executable core binaries and version mismatches, expose integrity state in `ServerUiState` and `ServerViewModel`, and provide a prominent one-tap "Repair Runtime" action in `DashboardScreen` that cleanly re-installs userland binaries while strictly preserving user episodic database checkpoints and data files.

## Boundaries & Constraints

**Always:**
- Verify presence, file type, and executable permissions (`canExecute()`) for all critical binaries (`bin/python3`, `bin/proot`, `bin/hermes`) and check `.bootstrap_complete` version alignment during health checks.
- Confine repair cleanup strictly to `context.filesDir/usr` and the marker file, preserving all user databases, episodic checkpoints, and persistent configurations in `context.filesDir`.
- Run health checks and repair routines asynchronously on `Dispatchers.IO` to ensure zero UI jank.
- Prevent starting the server daemon when runtime health check fails or when repair is active.

**Ask First:**
- Deleting or modifying any user files outside `context.filesDir/usr` (e.g., episodic databases or secret configs).

**Never:**
- Wipe the entire `context.filesDir` during environment repair.
- Require root privileges or terminal interaction to repair corrupted binaries.
- Allow server daemon launch while in an integrity error / corrupted state.

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| Healthy Userland | `usr/` directory intact, marker version 1, all binaries executable | `checkHealth()` returns `Healthy`; UI shows ready status, no warning banner | N/A |
| Missing Core Binary | `usr/bin/python3` or `usr/bin/hermes` deleted | `checkHealth()` returns `Corrupted`; UI displays warning banner with affected binary name and "Repair Runtime" button | Blocks server start; logs warning |
| Missing Executable Flag | Binary exists but lacks `canExecute()` and permission repair fails | `checkHealth()` detects non-executable binary; UI displays integrity warning | Displays repair banner; logs permission issue |
| One-Tap Repair Triggered | User taps "Repair Runtime" on warning banner | Stops daemon if running, purges `usr/`, re-extracts `bootstrap-arm64.tar.xz`, writes marker, clears corrupted status; preserves user data files | If re-extraction fails, surfaces error banner with retry option |
| Not Installed (Fresh Install) | Marker and `usr/` absent | `checkHealth()` returns `NotInstalled`; triggers automatic initial bootstrap | Handled by initial bootstrap flow |

</frozen-after-approval>

## Code Map

- `app/src/main/java/com/hermes/node/engine/BootstrapExtractor.kt` -- Add `HealthCheckResult` sealed class (`Healthy`, `NotInstalled`, `Corrupted(issues, details)`), `checkHealth(): HealthCheckResult` method, and `repair(onProgress)` method that safely deletes only `usr/` and marker before re-extracting.
- `app/src/main/java/com/hermes/node/viewmodel/ServerUiState.kt` -- Add `isRuntimeCorrupted: Boolean`, `integrityWarning: String?`, and `isRepairing: Boolean`.
- `app/src/main/java/com/hermes/node/viewmodel/ServerViewModel.kt` -- Integrate `performHealthCheck()`, update `checkAndInitializeBootstrap()` to handle health statuses, wire `onRepairRuntime()` action, and guard `onStartServer()` against corrupted runtime state.
- `app/src/main/java/com/hermes/node/ui/screens/DashboardScreen.kt` -- Add `RuntimeIntegrityCard` warning banner when `state.isRuntimeCorrupted` is true, with detailed issue description and a "Repair Runtime" button wired to `onRepairRuntime`.
- `app/src/main/java/com/hermes/node/ui/navigation/HermesNavGraph.kt` -- Wire `onRepairRuntime = viewModel::onRepairRuntime` into `DashboardScreen`.
- `app/src/test/java/com/hermes/node/engine/BootstrapExtractorTest.kt` -- Unit tests for `checkHealth()` (healthy, missing binary, non-executable binary, version mismatch), `repair()` (restores binaries, preserves user data files).
- `app/src/test/java/com/hermes/node/viewmodel/ServerViewModelTest.kt` -- Unit tests for ViewModel health check detection, corrupted state transitions, one-tap repair flow, and server start prevention when corrupted.

## Tasks & Acceptance

**Execution:**
- [x] `app/src/main/java/com/hermes/node/engine/BootstrapExtractor.kt` -- Implement `HealthCheckResult`, `checkHealth()`, and `repair()` methods -- Provides diagnostic verification and non-destructive userland repair.
- [x] `app/src/main/java/com/hermes/node/viewmodel/ServerUiState.kt` -- Add runtime health and repair state fields (`isRuntimeCorrupted`, `integrityWarning`, `isRepairing`) -- Enables UI reactive state binding for integrity status.
- [x] `app/src/main/java/com/hermes/node/viewmodel/ServerViewModel.kt` -- Wire `performHealthCheck()`, `onRepairRuntime()`, and start guards -- Manages health check lifecycle and repair coroutine execution.
- [x] `app/src/main/java/com/hermes/node/ui/screens/DashboardScreen.kt` & `HermesNavGraph.kt` -- Render integrity warning banner with "Repair Runtime" action and connect callback -- Delivers one-tap repair UX.
- [x] `app/src/test/java/com/hermes/node/engine/BootstrapExtractorTest.kt` & `ServerViewModelTest.kt` -- Implement unit tests for health checks, permission handling, user data preservation, and ViewModel state transitions -- Verifies edge cases and acceptance criteria.

**Acceptance Criteria:**
- Given a device with a missing or non-executable core binary (`python3`, `proot`, or `hermes`), when the application checks health or starts, then `isRuntimeCorrupted` is set to true and an integrity warning banner with the specific issue is displayed.
- Given an integrity warning banner displayed on the Dashboard, when the user taps "Repair Runtime", then the runtime userland is cleanly re-extracted, the health check passes, and existing user data files outside `usr/` remain intact.
- Given a corrupted runtime state, when the user attempts to start the server daemon, then the start request is blocked and an error message is surfaced in the UI.

## Spec Change Log

## Design Notes

`checkHealth()` returns a structured `HealthCheckResult` that differentiates between an uninstalled state (fresh launch), a corrupted state (specific missing binaries or permission failures), and a healthy state.
During `repair()`, only `usr/` directory and `.bootstrap_complete` are cleaned. Any user database (e.g. `data/`, `checkpoints/`, `hermes.json`) located in `filesDir` remains untouched.

## Verification

**Commands:**
- `./gradlew test` -- expected: `BUILD SUCCESSFUL` with all unit tests passing.
- `./gradlew assembleDebug` -- expected: `BUILD SUCCESSFUL` producing valid `app-debug.apk`.

## Suggested Review Order

**Runtime Integrity & Health Check Engine**

- Diagnostic health check classifying healthy, uninstalled, and corrupted userland environments
  [`BootstrapExtractor.kt:70`](../../app/src/main/java/com/hermes/node/engine/BootstrapExtractor.kt#L70)

- Structured integrity status taxonomy with detailed issue breakdown
  [`BootstrapExtractor.kt:25`](../../app/src/main/java/com/hermes/node/engine/BootstrapExtractor.kt#L25)

- Safe userland cleanup ensuring write permissions before purging usr directory
  [`BootstrapExtractor.kt:373`](../../app/src/main/java/com/hermes/node/engine/BootstrapExtractor.kt#L373)

- One-tap repair workflow validating assets and preserving external user data
  [`BootstrapExtractor.kt:166`](../../app/src/main/java/com/hermes/node/engine/BootstrapExtractor.kt#L166)

**State Management & Lifecycle Coordination**

- Integrity warning, repair progress, and corrupted runtime reactive state fields
  [`ServerUiState.kt:42`](../../app/src/main/java/com/hermes/node/viewmodel/ServerUiState.kt#L42)

- Health verification dispatcher updating UI state and logging integrity warnings
  [`ServerViewModel.kt:39`](../../app/src/main/java/com/hermes/node/viewmodel/ServerViewModel.kt#L39)

- Asynchronous one-tap repair lifecycle handling daemon shutdown and verification
  [`ServerViewModel.kt:168`](../../app/src/main/java/com/hermes/node/viewmodel/ServerViewModel.kt#L168)

- Server startup guard blocking execution on corrupted runtime environments
  [`ServerViewModel.kt:255`](../../app/src/main/java/com/hermes/node/viewmodel/ServerViewModel.kt#L255)

**UI & Navigation Presentation**

- Dedicated runtime integrity warning banner card with one-tap repair action
  [`DashboardScreen.kt:294`](../../app/src/main/java/com/hermes/node/ui/screens/DashboardScreen.kt#L294)

- Cyber status pill showing CORRUPTED and REPAIRING states
  [`DashboardScreen.kt:378`](../../app/src/main/java/com/hermes/node/ui/screens/DashboardScreen.kt#L378)

- Server control hero card disabling daemon toggle during corrupted state
  [`DashboardScreen.kt:590`](../../app/src/main/java/com/hermes/node/ui/screens/DashboardScreen.kt#L590)

- Navigation graph wiring repair callbacks to viewmodel
  [`HermesNavGraph.kt:103`](../../app/src/main/java/com/hermes/node/ui/navigation/HermesNavGraph.kt#L103)

**Test Coverage & Edge Cases**

- Unit tests for integrity health checks, self-healing permissions, and read-only cleanup
  [`BootstrapExtractorTest.kt:350`](../../app/src/test/java/com/hermes/node/engine/BootstrapExtractorTest.kt#L350)

- Unit tests for ViewModel corruption detection, start guards, and repair flow
  [`ServerViewModelTest.kt:465`](../../app/src/test/java/com/hermes/node/viewmodel/ServerViewModelTest.kt#L465)
