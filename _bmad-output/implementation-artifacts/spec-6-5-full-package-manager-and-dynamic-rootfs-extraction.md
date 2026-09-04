---
title: '6-5-full-package-manager-and-dynamic-rootfs-extraction'
type: 'feature'
created: '2026-09-04'
status: 'done'
baseline_commit: 'c6e3ff87afe40e7f53b4cb79ee0fd57d36c9efea'
review_loop_iteration: 0
context: ['_bmad-output/implementation-artifacts/epic-6-context.md']
---

<frozen-after-approval reason="human-owned intent — do not modify unless human renegotiates">

## Intent

**Problem:** The embedded PRoot sandbox currently uses minimal toolchain shims where native package managers like `apk` or `pkg` are absent, preventing Hermes Agent from installing real compiled ARM64 Linux packages (e.g. `ffmpeg`, `nodejs`, `clang`, `jq`) from online distribution repositories.

**Approach:** Implement a `PackageManagerInstaller` engine component to dynamically download, extract, and configure an Alpine Linux package manager rootfs (`apk`) into `${usrDir}`, setup DNS and repository configuration files (`/etc/resolv.conf`, `/etc/apk/repositories`), connect real execution to the existing `apt`/`apt-get` shim, and expose package manager health status and installation actions in `SettingsScreen` and `ServerViewModel`.

## Boundaries & Constraints

**Always:**
- Extract package manager archive streams (supporting both `.tar.xz` and `.tar.gz` / `.tgz`) into `${usrDir}` without wiping user databases, checkpoints, or configuration in `${filesDir}`.
- Configure DNS resolution (`${usrDir}/etc/resolv.conf` with `nameserver 8.8.8.8\nnameserver 1.1.1.1\n`) and Alpine repository mirrors (`${usrDir}/etc/apk/repositories` pointing to Alpine v3.20 `main` and `community`).
- Enforce executable POSIX permissions (`0755` / `canExecute == true`) on `bin/apk` and its rootfs mirror `usr/bin/apk`.
- Ensure the `apt` and `apt-get` shim scripts automatically detect `apk` on `PATH` and delegate `install` to `apk add` (with `-y` flags stripped) and `update` to `apk update`.
- Expose `packageManagerStatus` (`NOT_INSTALLED`, `DOWNLOADING`, `EXTRACTING`, `READY`, `ERROR`) and download/extract progress in `ServerUiState` and `ServerViewModel`.
- Add an "Extended Linux Toolchain & Package Manager" card in `SettingsScreen` under Skills displaying the active package manager state, installed tools summary, and an "Install Package Manager" button.
- Perform all network downloads and filesystem extraction on `Dispatchers.IO` with cancelable coroutine jobs.

**Ask First:**
- Switching the default Linux distribution base from Alpine Linux (`apk`) to Termux (`pkg`/`apt`).

**Never:**
- Never wipe `${filesDir}` or purge user memory databases during package manager installation.
- Never block the Android main thread during download or archive decompression.
- Never crash the application on network timeout, 404 response, or malformed archive streams.

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| Install from valid archive stream | `installFromStream(inputStream, onProgress)` | Extracts `apk`, configures `/etc/resolv.conf` & repos, `isPackageManagerInstalled()` returns true | Returns `Result.success(installedFilesCount)` |
| Network error during download | No internet connection or connection timeout | Catches `IOException`, reverts UI state to `ERROR` with user message | Returns `Result.failure(e)`, leaves existing files intact |
| Corrupted / incomplete archive | Truncated input stream | Halts extraction, logs error, returns `Result.failure` | Does not corrupt existing critical binaries |
| `apt install` delegation | `apt install -y jq` executed in PRoot | Shim invokes `apk add jq`, packages installed from online repository | Returns `apk` exit code |
| Health check detection | `BootstrapExtractor.checkHealth()` | Detects whether `bin/apk` is present and executable as part of extended userland | Continues reporting healthy, updates UI status |
| Reinstallation / Update | User taps "Reinstall / Update" | Overwrites system binaries cleanly while preserving user data in `${filesDir}` | Atomically replaces binaries |

</frozen-after-approval>

## Code Map

- `app/src/main/java/com/hermes/node/engine/PackageManagerInstaller.kt` -- New engine class handling download, stream decompression (`tar.gz` and `tar.xz`), DNS/repo setup, and verification.
- `app/src/main/java/com/hermes/node/engine/BootstrapExtractor.kt` -- Integration for package manager detection, DNS network config (`ensureNetworkConfig`), and shim delegation.
- `app/src/main/java/com/hermes/node/viewmodel/ServerUiState.kt` -- Add `packageManagerStatus`, `packageManagerProgress`, and `packageManagerMessage`.
- `app/src/main/java/com/hermes/node/viewmodel/ServerViewModel.kt` -- Add `installPackageManager()` and `refreshPackageManagerStatus()` methods.
- `app/src/main/java/com/hermes/node/ui/screens/SettingsScreen.kt` -- Add "Extended Linux Toolchain & Package Manager" card under Skills.
- `app/src/main/java/com/hermes/node/ui/navigation/HermesNavGraph.kt` -- Wire package manager actions from ViewModel to SettingsScreen.
- `app/src/test/java/com/hermes/node/engine/PackageManagerInstallerTest.kt` -- Unit tests for stream extraction, repository creation, DNS config, and error handling.
- `app/src/test/java/com/hermes/node/viewmodel/ServerViewModelTest.kt` -- Unit tests for package manager state transitions and install actions.

## Tasks & Acceptance

**Execution:**
- [x] `app/src/main/java/com/hermes/node/engine/PackageManagerInstaller.kt` -- Create `PackageManagerInstaller` with stream extraction, network config setup (`resolv.conf`, `repositories`), and status check.
- [x] `app/src/main/java/com/hermes/node/engine/BootstrapExtractor.kt` -- Add `ensureNetworkConfig(usrDir)` and support for extended package manager verification.
- [x] `app/src/main/java/com/hermes/node/viewmodel/ServerUiState.kt` -- Add package manager state model (`PackageManagerStatus`, progress, message).
- [x] `app/src/main/java/com/hermes/node/viewmodel/ServerViewModel.kt` -- Implement `installPackageManager()` and status observation.
- [x] `app/src/main/java/com/hermes/node/ui/screens/SettingsScreen.kt` -- Add Extended Toolchain & Package Manager card with status badge and install button.
- [x] `app/src/main/java/com/hermes/node/ui/navigation/HermesNavGraph.kt` -- Connect `onInstallPackageManager` to `SettingsScreen`.
- [x] `app/src/test/java/com/hermes/node/engine/PackageManagerInstallerTest.kt` -- Test package manager extraction, config generation, and corruption handling.
- [x] `app/src/test/java/com/hermes/node/viewmodel/ServerViewModelTest.kt` -- Test ViewModel package manager state flow and installation error handling.

**Acceptance Criteria:**
- Given a valid rootfs archive stream with `bin/apk`, when `installFromStream` executes, then `bin/apk` is executable and `${usrDir}/etc/resolv.conf` and `${usrDir}/etc/apk/repositories` are created.
- Given `apk` installed in `${usrDir}/bin`, when `apt install -y curl` is called, the shim executes `apk add curl` with the `-y` flag stripped.
- Given package manager installation triggered from Settings UI, when download and extraction succeed, then `packageManagerStatus` transitions to `READY`.

## Spec Change Log

_None._

## Design Notes

Alpine package manager configuration files:
- `/etc/resolv.conf`:
  ```
  nameserver 8.8.8.8
  nameserver 1.1.1.1
  ```
- `/etc/apk/repositories`:
  ```
  https://dl-cdn.alpinelinux.org/alpine/v3.20/main
  https://dl-cdn.alpinelinux.org/alpine/v3.20/community
  ```
The installer supports both tarball streams (.tar.gz using `GzipCompressorInputStream` and .tar.xz using `XZCompressorInputStream`) via Commons Compress.

## Verification

**Commands:**
- `./gradlew test` -- expected: `BUILD SUCCESSFUL` with all unit tests passing.
- `./gradlew assembleDebug` -- expected: `BUILD SUCCESSFUL` generating debug APK.

## Suggested Review Order

**Package Manager Engine & Extraction Pipeline**

- Streaming archive extraction, staged commit, atomic replacement, and APK binary resolution
  [`PackageManagerInstaller.kt:60`](../../app/src/main/java/com/hermes/node/engine/PackageManagerInstaller.kt#L60)

- Package manager presence detection, network config preservation, and health-check auditing
  [`BootstrapExtractor.kt:280`](../../app/src/main/java/com/hermes/node/engine/BootstrapExtractor.kt#L280)

**UI & State Management**

- Package manager status models, progress tracking, and installed tools summary
  [`ServerUiState.kt:65`](../../app/src/main/java/com/hermes/node/viewmodel/ServerUiState.kt#L65)

- Async download and installation handlers with progress scaling and cancelation
  [`ServerViewModel.kt:1070`](../../app/src/main/java/com/hermes/node/viewmodel/ServerViewModel.kt#L1070)

- Extended Toolchain card displaying status badges, progress bar, and install buttons
  [`SettingsScreen.kt:850`](../../app/src/main/java/com/hermes/node/ui/screens/SettingsScreen.kt#L850)

- Wiring install action from ViewModel to SettingsScreen composable
  [`HermesNavGraph.kt:180`](../../app/src/main/java/com/hermes/node/ui/navigation/HermesNavGraph.kt#L180)

**Peripherals & Verification**

- Unit tests for stream extraction, directory symlinks, HTTP download, and security defenses
  [`PackageManagerInstallerTest.kt:35`](../../app/src/test/java/com/hermes/node/engine/PackageManagerInstallerTest.kt#L35)

- Tests verifying package manager status flow, install progress, and error preservation
  [`ServerViewModelTest.kt:2800`](../../app/src/test/java/com/hermes/node/viewmodel/ServerViewModelTest.kt#L2800)

