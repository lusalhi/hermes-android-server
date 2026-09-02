---
title: '6-2-episodic-memory-inspector-database-backup-factory-reset'
type: 'feature'
created: '2026-09-01'
status: 'done'
baseline_commit: 'f72e4a6fbc5510249eec0fed85314c2d26552c70'
review_loop_iteration: 0
context:
  - _bmad-output/planning-artifacts/architecture/architecture-hermes-android-server-2026-08-24/ARCHITECTURE-SPINE.md
  - _bmad-output/planning-artifacts/prds/prd-hermes-android-server-2026-08-24/prd.md
  - _bmad-output/implementation-artifacts/epic-6-context.md
---

<frozen-after-approval reason="human-owned intent — do not modify unless human renegotiates">

## Intent

**Problem:** Users hosting an autonomous Hermes agent on Android have no visibility into episodic memory consumption and lack native controls to back up SQLite conversation checkpoints or perform a factory reset, leaving sensitive data vulnerable to data loss and unrecoverable storage growth.

**Approach:** Build an episodic memory manager and Settings UI that inspects storage consumption across agent SQLite checkpoints, exports timestamped ZIP backups to Android Downloads and the System Share Sheet via FileProvider, and provides a safe factory memory wipe with an explicit confirmation dialog while preserving all configuration keys and credentials.

## Boundaries & Constraints

**Always:**
- Inspect SQLite database files, WAL/SHM auxiliary files, and checkpoint directories (`agent_data/`, `data/`, `checkpoints/`, and top-level `*.db`/`*.sqlite`) in the internal app storage.
- Strictly preserve `hermes.json`, `EncryptedSharedPreferences`, `.bootstrap_completed`, and `usr/` during memory reset.
- Require explicit user confirmation via an AlertDialog before executing memory factory reset.
- Support Android API 28 through 35 for export operations, using MediaStore for public Downloads on API 29+ and FileProvider for Share Sheet intents.
- Follow cyber Material 3 theme styling (`DarkSurface`, `DarkBorder`, `HermesCyan`, `StatusStarting`, `StatusRunning`, `StatusError`).

**Ask First:**
- Automatically stopping an actively running server daemon prior to factory memory wipe instead of executing live.
- Changing the default backup export naming pattern or directory structure in Downloads.

**Never:**
- Delete LLM provider API keys, gateway bot tokens, or skill toggles during episodic memory reset.
- Delete the extracted Linux userland environment (`usr/`) during memory reset.
- Expose plain unencrypted database checkpoints to world-readable directories without explicit user-initiated export.
- Block the main Compose UI thread during backup ZIP compression or storage calculation.

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| Storage Size (Empty) | Clean install with no memory files | `0 B` displayed in UI; "Clear Memory" disabled | Treat non-existent directories gracefully as 0 bytes |
| Storage Size (Populated) | Active agent with SQLite DBs and checkpoints | Sums byte sizes of all SQLite DB, WAL, SHM, and checkpoint files; displays formatted size (e.g. `4.2 MB`) | Handle unreadable files by logging warning and continuing |
| Export to Downloads | User taps "Export Backup" with existing data | Compresses memory files into `hermes-backup-<timestamp>.zip` in `Downloads/HermesNode/`; displays success message and logs event | On IOException/disk full, surfaces error message in UI and logs error |
| Export (Empty Data) | User taps "Export Backup" when size is `0 B` | Creates valid empty/manifest ZIP in Downloads with notice | Do not throw empty archive error |
| Share via Share Sheet | User taps "Share Backup" | Prepares ZIP in internal cache, resolves `content://` URI via FileProvider with read grant, launches Android share chooser | Catches ActivityNotFoundException / SecurityException |
| Clear Memory Dialog | User taps "Clear Memory" | Opens confirmation dialog with warning that memory will be deleted but settings preserved | Dialog can be dismissed with no changes |
| Confirm Clear Memory | User confirms in dialog | Deletes episodic memory directories and DB files; preserves `hermes.json`, `usr/`, and Keystore secrets; updates UI size to `0 B` | Surface any deletion errors in UI message |
| Clear While Running | User clears memory while server is `RUNNING` | Wipes files on disk, logs warning that running daemon will restart memory state cleanly | Daemon process not abruptly killed unless configured |

</frozen-after-approval>

## Code Map

- `app/src/main/java/com/hermes/node/engine/MemoryManager.kt` -- Interface `MemoryManagerInterface` and implementation `MemoryManager` providing `calculateStorageUsage()`, `formatStorageSize()`, `exportMemoryBackup()`, `exportToDownloads()`, `getShareIntent()`, and `clearEpisodicMemory()`.
- `app/src/main/java/com/hermes/node/viewmodel/ServerUiState.kt` -- Add `storageSizeBytes`, `storageSizeFormatted`, `isExportingMemory`, `isResettingMemory`, `memoryActionMessage`, `isMemoryActionSuccess`, and `showClearMemoryDialog`.
- `app/src/main/java/com/hermes/node/viewmodel/ServerViewModel.kt` -- Inject `MemoryManagerInterface`, refresh storage size on init, implement `refreshStorageUsage()`, `onExportMemoryToDownloads()`, `onShareMemoryBackup()`, `onShowClearMemoryDialog()`, `onDismissClearMemoryDialog()`, `onConfirmClearMemory()`, and `onDismissMemoryActionMessage()`.
- `app/src/main/java/com/hermes/node/ui/screens/SettingsScreen.kt` -- Add "Episodic Memory & Storage" card with storage size pill, Export Backup button, Share button, Clear Memory button, confirmation AlertDialog, and feedback banner.
- `app/src/main/java/com/hermes/node/ui/navigation/HermesNavGraph.kt` -- Instantiate `MemoryManager` and wire memory callbacks to `SettingsScreen`.
- `app/src/main/AndroidManifest.xml` -- Declare `androidx.core.content.FileProvider` with file paths meta-data.
- `app/src/main/res/xml/file_paths.xml` -- Configure secure file sharing paths for FileProvider.
- `app/src/test/java/com/hermes/node/engine/MemoryManagerTest.kt` -- Unit test suite for storage calculation, ZIP backup creation, file filtering, and memory wiping.
- `app/src/test/java/com/hermes/node/viewmodel/ServerViewModelTest.kt` -- Unit tests for memory lifecycle, export state transitions, confirmation dialog, and memory reset.

## Tasks & Acceptance

**Execution:**
- [x] `app/src/main/res/xml/file_paths.xml` -- Define FileProvider path mappings for cache and files directories -- Required for secure Intent.ACTION_SEND sharing.
- [x] `app/src/main/AndroidManifest.xml` -- Register androidx.core.content.FileProvider -- Grants read permissions for memory backup archives.
- [x] `app/src/main/java/com/hermes/node/engine/MemoryManager.kt` -- Implement MemoryManager and MemoryManagerInterface -- Handles storage inspection, ZIP archiving, MediaStore export, Share Sheet intents, and safe factory reset.
- [x] `app/src/main/java/com/hermes/node/viewmodel/ServerUiState.kt` -- Add memory management state fields to ServerUiState -- Exposes reactive memory telemetry and dialog state to Compose UI.
- [x] `app/src/main/java/com/hermes/node/viewmodel/ServerViewModel.kt` -- Implement memory management actions and lifecycle methods -- Dispatches storage calculation, export, and reset on IO dispatcher.
- [x] `app/src/main/java/com/hermes/node/ui/screens/SettingsScreen.kt` -- Add "Episodic Memory & Storage" section and confirmation dialog -- Renders memory card, action buttons, and wipe confirmation.
- [x] `app/src/main/java/com/hermes/node/ui/navigation/HermesNavGraph.kt` -- Wire MemoryManager and ViewModel actions to SettingsScreen -- Connects composable UI events to ViewModel handlers.
- [x] `app/src/test/java/com/hermes/node/engine/MemoryManagerTest.kt` -- Unit tests for MemoryManager logic -- Verifies byte calculation, ZIP generation, exclusion of settings, and factory reset.
- [x] `app/src/test/java/com/hermes/node/viewmodel/ServerViewModelTest.kt` -- Unit tests for memory ViewModel operations -- Verifies state transitions, dialog handling, and log emissions.

**Acceptance Criteria:**
- Given the Settings screen, when viewing "Episodic Memory & Storage", then total episodic memory size is displayed with a formatted byte string (e.g. `0 B` or `14.2 MB`).
- Given episodic memory files in internal storage, when the user taps "Export Backup", then a timestamped ZIP file (`hermes-backup-*.zip`) is saved to Android Downloads or shared via the System Share Sheet, and a confirmation message is displayed.
- Given the Memory Management section, when the user taps "Clear Memory", then an explicit confirmation dialog appears warning that memories will be wiped while settings are preserved.
- Given the confirmation dialog, when the user confirms "Wipe Memory", then episodic SQLite database files and checkpoints are deleted, `hermes.json`, Keystore credentials, and `usr/` binaries remain intact, and storage size resets to `0 B`.

### Review Findings

_Patch:_

- [x] [Review][Patch] Add running-daemon backup consistency warning — when `status == RUNNING`, show a notice that the backup may be less consistent while the server runs (resolved decision: warn-only) [SettingsScreen.kt, ServerViewModel.kt] — severity: medium
- [x] [Review][Patch] API 28 export broken: `WRITE_EXTERNAL_STORAGE` declared but never requested at runtime — public Downloads write fails on min SDK [AndroidManifest.xml:11, MemoryManager.kt:277-291] — severity: high
- [x] [Review][Patch] MediaStore `IS_PENDING` finalization result ignored — stuck pending row while reporting success [MemoryManager.kt:267-269] — severity: medium
- [x] [Review][Patch] Shared backup archives never purged from `cacheDir/backups` — sensitive conversation data accumulates indefinitely [MemoryManager.kt:307-335] — severity: medium
- [x] [Review][Patch] No API 29+ MediaStore or FileProvider share-contract test coverage — only filesystem fallback and fake-manager paths tested [MemoryManagerTest.kt:243-268] — severity: medium
- [x] [Review][Patch] No executable UI verification for memory controls and clear-dialog acceptance criterion [SettingsScreen.kt:579-771, 1653-1716] — severity: medium
- [x] [Review][Patch] Fallback export can write app-internal `Downloads` dir while reporting public Downloads success [MemoryManager.kt:280-291] — severity: low
- [x] [Review][Patch] Failed share-intent path leaks staged ZIP in cache [MemoryManager.kt:309-317] — severity: low
- [x] [Review][Patch] Backup ZIP written non-atomically to final path [MemoryManager.kt:169-184] — severity: low
- [x] [Review][Patch] Memory ViewModel operations lack catch-all — a thrown implementation exception crashes the app with no error UI [ServerViewModel.kt:1283-1484] — severity: low
- [x] [Review][Patch] Cancelled memory job's `finally` clears the newer operation's progress flags [ServerViewModel.kt:1311-1367] — severity: low
- [x] [Review][Patch] ViewModel tests lack exception/overlap/cancellation coverage for memory ops [ServerViewModelTest.kt:2204-2518] — severity: low

## Spec Change Log

_None._

## Design Notes

The `MemoryManager` inspects the following internal storage locations:
- `filesDir/agent_data`
- `filesDir/data`
- `filesDir/checkpoints`
- Any `*.db`, `*.sqlite`, `*.sqlite3`, `*.db-wal`, `*.db-shm` files in `filesDir`

During factory reset, the following are strictly preserved:
- `filesDir/hermes.json` (Configuration)
- `filesDir/usr/` (PRoot and Python binaries)
- `filesDir/.bootstrap_completed` (Bootstrap marker)
- Android Keystore / EncryptedSharedPreferences (Secrets)

## Verification

**Commands:**
- `./gradlew test` -- expected: `BUILD SUCCESSFUL` with all memory manager and ViewModel unit tests passing.
- `./gradlew assembleDebug` -- expected: `BUILD SUCCESSFUL` producing valid debug APK.

**Manual checks (if no CLI):**
- Verify Settings screen displays the Episodic Memory & Storage card with current storage size, Export Backup button, Share Backup button, and Clear Memory button.
- Verify tapping Clear Memory displays the warning confirmation dialog with destructive styling.

## Suggested Review Order

**Episodic Memory Engine & Security Boundaries**

- Core interface definition and episodic roots resolution
  [`MemoryManager.kt:28`](../../app/src/main/java/com/hermes/node/engine/MemoryManager.kt#L28)

- Safe recursive storage calculation excluding configuration and userland
  [`MemoryManager.kt:130`](../../app/src/main/java/com/hermes/node/engine/MemoryManager.kt#L130)

- Atomic ZIP compression with relative path preservation and manifest fallback
  [`MemoryManager.kt:166`](../../app/src/main/java/com/hermes/node/engine/MemoryManager.kt#L166)

- Scoped MediaStore Downloads export with pending cleanup and API 28 fallback
  [`MemoryManager.kt:238`](../../app/src/main/java/com/hermes/node/engine/MemoryManager.kt#L238)

- Secure FileProvider share intent with ClipData URI permission propagation
  [`MemoryManager.kt:307`](../../app/src/main/java/com/hermes/node/engine/MemoryManager.kt#L307)

- Safe factory memory wipe strictly preserving credentials and Linux runtime
  [`MemoryManager.kt:339`](../../app/src/main/java/com/hermes/node/engine/MemoryManager.kt#L339)

**State Management & Lifecycle**

- Reactive UI state contracts for storage telemetry, progress flags, and dialogs
  [`ServerUiState.kt:446`](../../app/src/main/java/com/hermes/node/viewmodel/ServerUiState.kt#L446)

- Asynchronous storage calculation, export, and wipe orchestration with fail-safe error recovery
  [`ServerViewModel.kt:1279`](../../app/src/main/java/com/hermes/node/viewmodel/ServerViewModel.kt#L1279)

**User Interface & Security Integration**

- Settings screen Episodic Memory card with storage pill, action triggers, and dynamic running daemon warning
  [`SettingsScreen.kt:582`](../../app/src/main/java/com/hermes/node/ui/screens/SettingsScreen.kt#L582)

- Navigation graph dependency injection connecting MemoryManager to ViewModel and UI
  [`HermesNavGraph.kt:59`](../../app/src/main/java/com/hermes/node/ui/navigation/HermesNavGraph.kt#L59)

- Least-privilege FileProvider path definitions and API 28 storage permission
  [`file_paths.xml:1`](../../app/src/main/res/xml/file_paths.xml#L1)
  [`AndroidManifest.xml:51`](../../app/src/main/AndroidManifest.xml#L51)

**Automated Verification Suite**

- Unit test suite verifying byte sizing, ZIP structure, credential preservation, and cleanup
  [`MemoryManagerTest.kt:31`](../../app/src/test/java/com/hermes/node/engine/MemoryManagerTest.kt#L31)

- ViewModel unit tests for memory lifecycle, export state transitions, and dialog flows
  [`ServerViewModelTest.kt:2205`](../../app/src/test/java/com/hermes/node/viewmodel/ServerViewModelTest.kt#L2205)

