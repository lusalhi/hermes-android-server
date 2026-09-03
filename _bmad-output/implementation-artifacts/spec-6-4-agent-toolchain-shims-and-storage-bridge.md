---
title: '6-4-agent-toolchain-shims-and-storage-bridge'
type: 'feature'
created: '2026-09-03'
status: 'done'
baseline_commit: '8fb10424420b62bd83779a60e1fd596c48bcf2a4'
review_loop_iteration: 0
context: ['_bmad-output/implementation-artifacts/epic-6-context.md']
---

<frozen-after-approval reason="human-owned intent — do not modify unless human renegotiates">

## Intent

**Problem:** When Hermes Agent runs autonomous bash skills on Android, standard commands like `sudo`, `apt`, and `apt-get` immediately fail with "command not found", and the sub-process has no bind-mounted access to Android shared storage (e.g. `/sdcard/Download`), breaking workflows that expect a Linux VPS environment.

**Approach:** Deploy executable `/usr/bin/sudo`, `/usr/bin/apt`, and `/usr/bin/apt-get` POSIX shims into the PRoot userland environment during bootstrap initialization and repair, configure PRoot bind mounts for Android shared storage (`files/shared` and `/sdcard/Download` when accessible), and provide a user setting toggle under Skills in the Settings UI.

## Boundaries & Constraints

**Always:**
- Deploy `/usr/bin/sudo`, `/usr/bin/apt`, and `/usr/bin/apt-get` into `${usrDir}/bin` with executable POSIX permissions (`0755` / `canExecute`) during bootstrap extraction and repair.
- Auto-heal missing or corrupted shims during `BootstrapExtractor.checkHealth()` and before process startup so the sub-process environment is self-recovering.
- The `sudo` shim must transparently execute all passed arguments (`exec "$@"`) without prompting for password, reflecting PRoot's simulated root (UID 0) mode.
- The `apt` and `apt-get` shims must handle `update` by returning exit code 0 with standard package update output, and handle `install` by invoking `pkg` or `apk` if installed or returning exit code 0 with intercepted package information.
- Configure PRoot arguments in `ProcessController.createHermesDaemonConfig` to bind-mount `${filesDir}/shared:/shared` and `/sdcard/Download` (when existing and readable) whenever `sharedStorageEnabled` is true in `SkillsConfig`.
- Persist `sharedStorageEnabled` in `SkillsConfig` and `hermes.json` under `skills` with strict POSIX `0600` permissions.
- Expose a toggle switch for "Device Shared Storage" under the Skills section in `SettingsScreen`.
- Maintain full non-root compatibility on Android 9.0+ (API 28+).

**Ask First:**
- Requesting runtime `MANAGE_EXTERNAL_STORAGE` or Scoped Storage System File Picker dialogs beyond standard `/sdcard/Download` and app sandbox directories.

**Never:**
- Never require root access (`su`) or modify system partitions.
- Never download large external toolchains (>20MB) synchronously during daemon boot.

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| `sudo` execution | `sudo <command> [args]` | Sub-process executes `<command> [args]` directly without password prompt | Exits with `<command>` return code |
| `apt update` execution | `apt update` or `apt-get update` | Outputs simulated repository index update lines, exit code 0 | Returns 0 |
| `apt install` execution | `apt install -y jq ffmpeg` | Invokes native `pkg`/`apk` if present, or logs intercepted packages notice, exit code 0 | Returns 0 or package manager exit code |
| Shims missing on startup | `usr/bin/apt` or `usr/bin/sudo` deleted | `ensureToolchainShims()` auto-generates missing shims and sets executable bit | Logs recovery, allows start |
| Shared storage enabled | `sharedStorageEnabled = true` and `/sdcard/Download` exists | PRoot launched with `-b files/shared:/shared` and `-b /sdcard/Download:/sdcard/Download` | Gracefully skips unreadable directories |
| Shared storage disabled | `sharedStorageEnabled = false` | PRoot arguments omit external shared storage bind-mounts | Standard sandbox only |

</frozen-after-approval>

## Code Map

- `app/src/main/java/com/hermes/node/engine/BootstrapExtractor.kt` -- Add `TOOLCHAIN_SHIMS`, `ensureToolchainShims()`, shim auto-healing in `checkHealth()`, and generation in `extractFromStream()` and `repair()`.
- `app/src/main/java/com/hermes/node/data/model/HermesConfig.kt` -- Add `sharedStorageEnabled` (default `true`) to `SkillsConfig`, update `NON_SKILL_KEYS` and helper methods.
- `app/src/main/java/com/hermes/node/data/ConfigSerializer.kt` -- Add `shared_storage_enabled` JSON serialization and deserialization in `hermes.json`.
- `app/src/main/java/com/hermes/node/engine/ProcessController.kt` -- Update `createHermesDaemonConfig` to append PRoot `-b` bind mounts when `sharedStorageEnabled` is active.
- `app/src/main/java/com/hermes/node/ui/screens/SettingsScreen.kt` -- Add "Device Shared Storage" card/switch item under Skills section.
- `app/src/main/java/com/hermes/node/viewmodel/ServerViewModel.kt` -- Add `setSharedStorageEnabled(enabled: Boolean)` event handler.
- `app/src/test/java/com/hermes/node/engine/BootstrapExtractorTest.kt` -- Unit tests for shim generation, permissions, and health check validation.
- `app/src/test/java/com/hermes/node/engine/ProcessControllerTest.kt` -- Unit tests verifying PRoot arguments include bind mounts when shared storage is enabled/disabled.
- `app/src/test/java/com/hermes/node/data/ConfigSerializerTest.kt` -- Unit tests verifying `shared_storage_enabled` round-trip serialization.

## Tasks & Acceptance

**Execution:**
- [x] `app/src/main/java/com/hermes/node/data/model/HermesConfig.kt` -- Add `sharedStorageEnabled` property and helper methods in `SkillsConfig`.
- [x] `app/src/main/java/com/hermes/node/data/ConfigSerializer.kt` -- Serialize and deserialize `shared_storage_enabled` in `hermes.json`.
- [x] `app/src/main/java/com/hermes/node/engine/BootstrapExtractor.kt` -- Implement `ensureToolchainShims()` creating `sudo`, `apt`, and `apt-get` shims with executable permissions and integration in `checkHealth()`, `extractFromStream()`, and `repair()`.
- [x] `app/src/main/java/com/hermes/node/engine/ProcessController.kt` -- Bind mount `files/shared` and `/sdcard/Download` (if accessible) in `createHermesDaemonConfig` when `sharedStorageEnabled` is true.
- [x] `app/src/main/java/com/hermes/node/viewmodel/ServerViewModel.kt` -- Expose `setSharedStorageEnabled(Boolean)` to persist state updates.
- [x] `app/src/main/java/com/hermes/node/ui/screens/SettingsScreen.kt` -- Render "Device Shared Storage" toggle in Skills settings section.
- [x] `app/src/test/java/com/hermes/node/engine/BootstrapExtractorTest.kt` -- Add test verifying `ensureToolchainShims()` generates functional, executable shims.
- [x] `app/src/test/java/com/hermes/node/engine/ProcessControllerTest.kt` -- Add test verifying PRoot bind mount arguments with shared storage toggled.
- [x] `app/src/test/java/com/hermes/node/data/ConfigSerializerTest.kt` -- Add test verifying `shared_storage_enabled` serialization.

**Acceptance Criteria:**
- Given an extracted PRoot userland, when `ensureToolchainShims()` executes, then `/usr/bin/sudo`, `/usr/bin/apt`, and `/usr/bin/apt-get` exist and are executable (`canExecute == true`).
- Given a running PRoot environment with `sharedStorageEnabled = true`, when `createHermesDaemonConfig` builds process arguments, then `-b` arguments for shared directories are passed to PRoot.
- Given `sharedStorageEnabled` toggled in Settings, when configuration is saved, then `hermes.json` is updated with `shared_storage_enabled` under `skills` with POSIX `0600` permissions.

## Spec Change Log

_None._

## Design Notes

The shims are written as minimal POSIX `/bin/sh` scripts:
```sh
#!/bin/sh
# /usr/bin/sudo
exec "$@"
```
```sh
#!/bin/sh
# /usr/bin/apt and /usr/bin/apt-get
if [ "$1" = "update" ]; then
  echo "Reading package lists... Done"
  exit 0
elif [ "$1" = "install" ]; then
  shift
  echo "Hermes Shim: Package installation requested for: $@"
  if command -v pkg >/dev/null 2>&1; then
    exec pkg install -y "$@"
  elif command -v apk >/dev/null 2>&1; then
    exec apk add "$@"
  fi
  exit 0
else
  exit 0
fi
```

## Verification

**Commands:**
- `./gradlew test` -- expected: `BUILD SUCCESSFUL` with all unit tests passing.
- `./gradlew assembleDebug` -- expected: `BUILD SUCCESSFUL` generating debug APK.

## Suggested Review Order

**Toolchain Shims & Self-Healing**

- Defines POSIX `sudo`, `apt`, and `apt-get` scripts and self-healing deployment logic
  [`BootstrapExtractor.kt:61`](../../app/src/main/java/com/hermes/node/engine/BootstrapExtractor.kt#L61)

**Shared Storage & PRoot Bind Mounts**

- Auto-heals shims pre-launch and mounts shared directories into PRoot
  [`ProcessController.kt:78`](../../app/src/main/java/com/hermes/node/engine/ProcessController.kt#L78)

**Configuration Model & Persistence**

- Adds `sharedStorageEnabled` property to Skills data model
  [`HermesConfig.kt:77`](../../app/src/main/java/com/hermes/node/data/model/HermesConfig.kt#L77)

- Serializes and deserializes `shared_storage_enabled` to POSIX `0600` `hermes.json`
  [`ConfigSerializer.kt:84`](../../app/src/main/java/com/hermes/node/data/ConfigSerializer.kt#L84)

- Persists shared storage setting to Android Keystore-backed preferences
  [`ConfigRepository.kt:32`](../../app/src/main/java/com/hermes/node/data/ConfigRepository.kt#L32)

**UI & State Management**

- Adds "Device Shared Storage" card and toggle switch in Skills UI
  [`SettingsScreen.kt:734`](../../app/src/main/java/com/hermes/node/ui/screens/SettingsScreen.kt#L734)

- Handles setting updates with automatic error rollback and logging
  [`ServerViewModel.kt:513`](../../app/src/main/java/com/hermes/node/viewmodel/ServerViewModel.kt#L513)

- Wires setting toggle handler in Jetpack Compose navigation graph
  [`HermesNavGraph.kt:410`](../../app/src/main/java/com/hermes/node/ui/navigation/HermesNavGraph.kt#L410)

**Peripherals & Verification**

- Verifies shim generation, argument/flag parsing, and health-check self-healing
  [`BootstrapExtractorTest.kt:557`](../../app/src/test/java/com/hermes/node/engine/BootstrapExtractorTest.kt#L557)

- Verifies PRoot arguments include bind mounts when shared storage is toggled
  [`ProcessControllerTest.kt:518`](../../app/src/test/java/com/hermes/node/engine/ProcessControllerTest.kt#L518)

- Verifies round-trip serialization of shared storage setting
  [`ConfigSerializerTest.kt:577`](../../app/src/test/java/com/hermes/node/data/ConfigSerializerTest.kt#L577)

- Verifies repository persistence when disabled
  [`ConfigRepositoryTest.kt:512`](../../app/src/test/java/com/hermes/node/data/ConfigRepositoryTest.kt#L512)

