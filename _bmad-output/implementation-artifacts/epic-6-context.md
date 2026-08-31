# Epic 6 Context: Agent Skills Management, Episodic Memory Backups & Checkpoint Reset

<!-- Compiled from planning artifacts. Edit freely. Regenerate with compile-epic-context if planning docs change. -->

## Goal

Provide a mobile-native management interface for Hermes agent capabilities and episodic memory, allowing users to inspect and toggle installed skills (web search, file manager, bash runner, cron scheduler) dynamically in `hermes.json`, inspect local storage usage, export SQLite conversation checkpoints and databases to Android Downloads / System Share Sheet, and safely wipe episodic state with explicit confirmation.

## Stories

- Story 6.1: Skill Manifest Viewer & Tool Enablement Toggles
- Story 6.2: Episodic Memory Inspector, Database Backup & Factory Reset

## Requirements & Constraints

- **FR-12 (Skill Manifest Viewer & Toggles)**: Inspect installed Hermes agent skills (`web_search`, `file_manager`, `bash_runner`, `cron_scheduler`) with individual on/off switches. Toggling a skill persists the updated whitelist in `hermes.json` with POSIX `0600` permissions.
- **FR-13 (Memory & State Management)**: Inspect local SQLite database file sizes and conversation checkpoint storage under internal app storage (`/data/data/com.hermes.node/files/agent_data` or sandbox data dir). Provide an export action to write timestamped backups into Android `Downloads/HermesNode/` or trigger Android system `Intent.ACTION_SEND` (Share Sheet). Provide a factory reset action to delete episodic SQLite memory only after explicit user confirmation dialog.
- **NFR-3 (Zero-CLI Friction)**: 100% of skill toggles and database backup/reset operations performed via Android GUI.
- **NFR-6 (Security at Rest)**: Configuration changes to skills must preserve Keystore encryption for secrets and atomic `0600` POSIX file writing. Backup exports must safely handle Android Scoped Storage / MediaStore without breaking on API 28–35.

## Technical Decisions

- **Skills Configuration Model**: Extend `HermesConfig` / `ConfigRepository` to include a `skills: SkillsConfig` model with boolean flags for standard Hermes tools (`webSearchEnabled`, `fileManagerEnabled`, `bashRunnerEnabled`, `cronSchedulerEnabled`) or list of enabled skill identifiers serialized into `hermes.json`.
- **Memory Inspector & Storage Manager**: A dedicated `MemoryManager` / `DatabaseBackupHelper` utility to calculate directory and file sizes for Hermes agent SQLite checkpoints (located in `/data/data/com.hermes.node/files/data` or `agent_data`), handle copying SQLite files (with WAL/SHM handling or backup commands), and invoke `MediaStore` / `FileProvider` / `Intent.ACTION_SEND` safely on Android 9.0+ (API 28–35).
- **Factory Reset Safety**: Resetting memory deletes the agent's SQLite DB / checkpoint files while strictly preserving user configuration (`hermes.json`, encrypted provider keys, gateway tokens). Requires a confirmation alert dialog before execution.
- **ViewModel & StateFlow Integration**: Expose `skillsConfig`, `storageSizeFormatted`, `isExporting`, and `isResetting` in `ServerUiState` with corresponding UI events in `ServerViewModel` and `SettingsScreen`.

## UX & Interaction Patterns

- **Settings / Skills Section**: Card-based section in `SettingsScreen` or dedicated tab displaying installed skill items with iconography, title, brief capability description, and a Material 3 switch toggle.
- **Memory Management Section**: Section in `SettingsScreen` showing total agent episodic storage used (e.g. `14.2 MB`), with "Export Backup" button and "Clear Memory" (destructive red/warning) button.
- **Confirmation Dialog**: Destructive action warning dialog for "Clear Memory" requiring explicit confirmation before deleting episodic SQLite checkpoints.

## Cross-Story Dependencies

- **Dependency on Epic 2 (ConfigRepository & 0600 Serialization)**: Story 6.1 builds upon `ConfigRepository` and `ConfigSerializer` to persist skill toggles atomically to `hermes.json`.
- **Dependency on Story 6.1 for Story 6.2**: Story 6.2 uses the same Settings screen and ViewModel architecture established in prior epics and Story 6.1.
