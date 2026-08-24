---
title: 'Automated ARM64 Linux Rootfs Bootstrap & Permission Setup'
type: 'feature'
created: '2026-08-24'
status: 'done'
baseline_commit: 'd0fd026b97d8b811f3d218e03f336d11f5e9d6f6'
review_loop_iteration: 0
context:
  - _bmad-output/planning-artifacts/architecture/architecture-hermes-android-server-2026-08-24/ARCHITECTURE-SPINE.md
  - _bmad-output/planning-artifacts/prds/prd-hermes-android-server-2026-08-24/prd.md
  - _bmad-output/implementation-artifacts/epic-2-context.md
---

<frozen-after-approval reason="human-owned intent — do not modify unless human renegotiates">

## Intent

**Problem:** On first app launch or after runtime updates, the embedded Python 3.11 and ARM64 PRoot userland must be unpacked into the internal app sandbox (`/data/data/com.hermes.node/files/usr`) with verified executable POSIX permissions (`chmod 755`), without requiring user terminal commands or external Termux installation.

**Approach:** Implement `BootstrapExtractor` using Apache Commons Compress and XZ decompression to extract the asset archive `bootstrap-arm64.tar.xz` into `context.filesDir/usr`, verify permissions and critical binary paths (`proot`, `python3`, `hermes`), and integrate extraction lifecycle and progress reporting into `ServerViewModel` and `ServerUiState`.

## Boundaries & Constraints

**Always:**
- Unpack files into internal app storage sandbox (`context.filesDir.resolve("usr")`), never external or world-readable storage.
- Enforce executable permissions (`chmod 755` / `File.setExecutable(true, false)`) on all extracted binaries and directories.
- Preserve directory hierarchy and handle symbolic link entries gracefully without corrupting standard file extractions.
- Check an installation marker file (`.bootstrap_complete` / version code) before extracting to prevent redundant extractions on subsequent app launches.
- Run archive decompression asynchronously on `Dispatchers.IO` to ensure UI remains completely responsive.

**Ask First:**
- Deleting existing user data directories (e.g. `agent_data`, database checkpoints) during bootstrap recovery.

**Never:**
- Require root privileges or external helper APKs (e.g., Termux).
- Block the main Android UI thread during archive decompression.
- Leave partially extracted corrupt state marked as valid.

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|---|---|---|---|
| Fresh App Launch (No userland) | `usr` missing or `.bootstrap_complete` absent | Unpacks `bootstrap-arm64.tar.xz` to `filesDir/usr`, sets permissions `755`, writes `.bootstrap_complete`, marks state complete | Returns `ExtractionResult.Error` with message, updates `ServerUiState` |
| Subsequent Launch (Already installed) | Valid `usr` directory and matching `.bootstrap_complete` | Skips extraction instantly, sets `isBootstrapComplete = true` | N/A |
| Corrupted/Partial Extraction | Extraction interrupted or critical binary (`python3`/`proot`) missing | `isBootstrapInstalled` evaluates to false; triggers re-extraction | Logs error, cleans destination staging, retries extraction |
| Archive Missing from Assets | `bootstrap-arm64.tar.xz` not in assets | Extraction fails cleanly reporting asset not found | Emits error state, logs to `ServerUiState.logs` |

</frozen-after-approval>

## Code Map

- `app/build.gradle.kts` -- Adds `org.apache.commons:commons-compress:1.26.2` and `org.tukaani:xz:1.9` for archive streaming decompression.
- `app/src/main/assets/bootstrap-arm64.tar.xz` -- Embedded archive asset containing the base userland directory tree (`usr/bin/python3`, `usr/bin/proot`, `usr/bin/hermes`, `usr/lib`, etc.).
- `app/src/main/java/com/hermes/node/engine/BootstrapExtractor.kt` -- Core decompression engine, permission setter, binary verifier, and progress reporter.
- `app/src/main/java/com/hermes/node/viewmodel/ServerUiState.kt` -- Augmented with `isBootstrapping`, `isBootstrapComplete`, `bootstrapProgress`, `bootstrapMessage`.
- `app/src/main/java/com/hermes/node/viewmodel/ServerViewModel.kt` -- Wires `BootstrapExtractor` checks and extraction routine upon startup or user trigger.
- `app/src/main/java/com/hermes/node/ui/screens/DashboardScreen.kt` -- Displays bootstrap progress indicator / status pill when bootstrap is in progress.
- `app/src/test/java/com/hermes/node/engine/BootstrapExtractorTest.kt` -- Unit tests verifying archive extraction, executable permissions, marker handling, and error states.

## Tasks & Acceptance

**Execution:**
- [x] `app/build.gradle.kts` -- Add Commons Compress and XZ dependencies -- Required for streaming tar.xz decompression.
- [x] `app/src/main/assets/bootstrap-arm64.tar.xz` -- Create asset package containing standard ARM64 Linux rootfs structure -- Required for unpack payload.
- [x] `app/src/main/java/com/hermes/node/engine/BootstrapExtractor.kt` -- Implement extraction, POSIX permission setting, binary integrity checks, and version markers -- Required for FR-1 & AD-2.
- [x] `app/src/main/java/com/hermes/node/viewmodel/ServerUiState.kt` & `ServerViewModel.kt` -- Wire extraction state, lifecycle events, and logs -- Required for UI reactive feedback.
- [x] `app/src/main/java/com/hermes/node/ui/screens/DashboardScreen.kt` -- Add bootstrap progress UI overlay/banner during extraction -- Required for UX visibility.
- [x] `app/src/test/java/com/hermes/node/engine/BootstrapExtractorTest.kt` & `ServerViewModelTest.kt` -- Unit test extraction, permissions, edge cases, and viewmodel state transitions -- Required for verification.

**Acceptance Criteria:**
- Given a device with no previous installation, when `BootstrapExtractor.extract()` is invoked, then `bootstrap-arm64.tar.xz` is unpacked into `filesDir/usr` with executable permissions on binaries and `.bootstrap_complete` written.
- Given an already-extracted and intact userland, when `BootstrapExtractor.isBootstrapInstalled()` is called, then it returns `true` and avoids re-extraction.
- Given an invalid or missing archive stream, when `extract()` is called, then `ExtractionResult.Error` is returned without crashing the app.

## Spec Change Log

## Design Notes

Using `TarArchiveInputStream` wrapped around `XZCompressorInputStream` over `InputStream` allows streaming directly to disk without loading multi-megabyte archives entirely into JVM heap memory.
Permissions are applied recursively using `File.setExecutable(true, false)` and `File.setReadable(true, false)` for all files in `bin/` and `libexec/`, and on all directories.

## Verification

**Commands:**
- `./gradlew test` -- expected: `BUILD SUCCESSFUL` with all unit tests passing.
- `./gradlew assembleDebug` -- expected: `BUILD SUCCESSFUL` producing valid `app-debug.apk`.

## Suggested Review Order

**Runtime Bootstrap & Decompression Engine**

- Streaming extraction, Zip Slip security guard, POSIX permission enforcement, and integrity verification
  [`BootstrapExtractor.kt:23`](../../app/src/main/java/com/hermes/node/engine/BootstrapExtractor.kt#L23)

- Userland installation check against critical binaries and marker file
  [`BootstrapExtractor.kt:60`](../../app/src/main/java/com/hermes/node/engine/BootstrapExtractor.kt#L60)

**State Management & UI Integration**

- Reactive bootstrap state fields (`isBootstrapping`, `isBootstrapComplete`, `bootstrapProgress`)
  [`ServerUiState.kt:25`](../../app/src/main/java/com/hermes/node/viewmodel/ServerUiState.kt#L25)

- Automatic bootstrap initialization, retry triggers, and startup safety guards
  [`ServerViewModel.kt:34`](../../app/src/main/java/com/hermes/node/viewmodel/ServerViewModel.kt#L34)

- Linear progress indicator and bootstrap error action banner
  [`DashboardScreen.kt:140`](../../app/src/main/java/com/hermes/node/ui/screens/DashboardScreen.kt#L140)

- Production extractor instantiation wired into composable graph
  [`HermesNavGraph.kt:45`](../../app/src/main/java/com/hermes/node/ui/navigation/HermesNavGraph.kt#L45)

**Verification & Peripherals**

- Unit tests for archive decompression, permissions, symlinks, and security traversal guards
  [`BootstrapExtractorTest.kt:24`](../../app/src/test/java/com/hermes/node/engine/BootstrapExtractorTest.kt#L24)

- Unit tests for ViewModel bootstrap lifecycle, error handling, and server execution gating
  [`ServerViewModelTest.kt:255`](../../app/src/test/java/com/hermes/node/viewmodel/ServerViewModelTest.kt#L255)

- Streaming tar.xz decompression dependencies (`commons-compress` and `xz`)
  [`build.gradle.kts:75`](../../app/build.gradle.kts#L75)

