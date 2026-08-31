---
title: '6-1-skill-manifest-viewer-tool-enablement-toggles'
type: 'feature'
created: '2026-08-31'
status: 'done'
baseline_commit: '595240ddd9e1f9e33eb506c96192bbdbef345156'
review_loop_iteration: 0
context:
  - _bmad-output/planning-artifacts/architecture/architecture-hermes-android-server-2026-08-24/ARCHITECTURE-SPINE.md
  - _bmad-output/planning-artifacts/prds/prd-hermes-android-server-2026-08-24/prd.md
  - _bmad-output/implementation-artifacts/epic-6-context.md
---

<frozen-after-approval reason="human-owned intent — do not modify unless human renegotiates">

## Intent

**Problem:** Users hosting an autonomous Hermes agent on Android have no visibility or granular control over which capabilities and system tools (web search, file management, bash execution, cron scheduling) the agent is permitted to execute, posing security and unexpected action risks.

**Approach:** Build a Skill Manifest model and interactive settings UI allowing users to view all installed Hermes capabilities with descriptive metadata, toggle individual skills on or off in real-time, persist the tool whitelist encrypted at rest and in POSIX `0600` `hermes.json`, and expose reactive state flow updates to the agent runtime without requiring application reinstallation.

## Boundaries & Constraints

**Always:**
- Default all core skills (`web_search`, `file_manager`, `bash_runner`, `cron_scheduler`) to enabled (`true`) on clean installation.
- Persist skill toggles securely using `EncryptedSharedPreferences` / `ConfigRepository` and serialize the skill whitelist into `/data/data/com.hermes.node/files/hermes.json` with strict POSIX `0600` permissions (`-rw-------`).
- Maintain backward compatibility with existing `HermesConfig` JSON payloads missing the `skills` object by parsing missing keys as `true`.
- Follow cyber Material 3 design tokens (`HermesCyan`, `DarkSurface`, `DarkBorder`, `StatusRunning`) with distinct iconography for each skill manifest entry.

**Ask First:**
- Adding dynamic runtime package installation (e.g. `pip install` or downloading unverified 3rd-party community skills into PRoot userland).
- Removing or renaming any of the four standard core Hermes skill identifiers.

**Never:**
- Allow disabling skills to corrupt or overwrite provider API keys, gateway tokens, or episodic SQLite databases.
- Require root permissions, shell commands, or device reboots to toggle skills.

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| Toggle Core Skill Off | User toggles `bash_runner` off in Settings | `SkillsConfig.bashRunner` set to `false`, `hermes.json` serialized with `"bash_runner": false`, UI switch updates immediately | If disk write fails, surface error message in `ServerUiState.configSaveMessage` and keep previous state |
| Toggle Core Skill On | User toggles disabled `web_search` to on | `SkillsConfig.webSearch` set to `true`, `hermes.json` updated with `"web_search": true` | Atomic config update via staging file |
| Clean Install / Migration | Existing config without `skills` block loaded | Default all core skills to `true` (`web_search`, `file_manager`, `bash_runner`, `cron_scheduler`) | Fallback gracefully to default `SkillsConfig()` |
| Toggle During Active Server | Server is `RUNNING` and user toggles a skill | Config file `hermes.json` updated on disk with `0600` permissions; runtime tool registry reloaded | Server daemon continues running uninterrupted |
| Custom Skill Toggle | Future custom skill id passed to toggle handler | `SkillsConfig.customSkills` updated with key-value pair and serialized to JSON | Retain custom skill entries in JSON without schema crash |

</frozen-after-approval>

## Code Map

- `app/src/main/java/com/hermes/node/data/model/HermesConfig.kt` -- Add `SkillInfo` and `SkillsConfig` data classes with default skill metadata and helper toggle methods; include `skills: SkillsConfig` in `HermesConfig`.
- `app/src/main/java/com/hermes/node/data/ConfigSerializer.kt` -- Serialize and deserialize `"skills"` JSON object with `web_search`, `file_manager`, `bash_runner`, `cron_scheduler` flags and POSIX `0600` enforcement.
- `app/src/main/java/com/hermes/node/data/ConfigRepository.kt` -- Add `getSkillsConfig()`, `saveSkillsConfig()`, and `saveSkillEnabled()` to `ConfigRepository` and `EncryptedConfigRepository`.
- `app/src/main/java/com/hermes/node/viewmodel/ServerUiState.kt` -- Add `skillsConfig: SkillsConfig` and `installedSkills: List<SkillInfo>` properties to `ServerUiState`.
- `app/src/main/java/com/hermes/node/viewmodel/ServerViewModel.kt` -- Add `onToggleSkill(skillId: String, enabled: Boolean)` event handler; load skills on init and persist on save.
- `app/src/main/java/com/hermes/node/ui/screens/SettingsScreen.kt` -- Add "Agent Skills & Capabilities" card section displaying manifest items with icons, descriptions, and Material 3 switches.
- `app/src/main/java/com/hermes/node/ui/navigation/HermesNavGraph.kt` -- Wire `onToggleSkill` callback from `ServerViewModel` to `SettingsScreen`.
- `app/src/test/java/com/hermes/node/data/ConfigSerializerTest.kt` -- Add tests verifying JSON serialization and deserialization of skill flags.
- `app/src/test/java/com/hermes/node/data/ConfigRepositoryTest.kt` -- Add tests verifying encrypted persistence and retrieval of skill settings.
- `app/src/test/java/com/hermes/node/viewmodel/ServerViewModelTest.kt` -- Add unit tests verifying `onToggleSkill` updates state and triggers config persistence.

## Tasks & Acceptance

**Execution:**
- [x] `app/src/main/java/com/hermes/node/data/model/HermesConfig.kt` -- Define `SkillInfo` and `SkillsConfig` models with default manifest list and toggle helpers.
- [x] `app/src/main/java/com/hermes/node/data/ConfigSerializer.kt` -- Update JSON generator and parser to handle the `"skills"` block while preserving POSIX `0600` permissions.
- [x] `app/src/main/java/com/hermes/node/data/ConfigRepository.kt` -- Implement skills preferences accessors in `ConfigRepository` and `EncryptedConfigRepository`.
- [x] `app/src/main/java/com/hermes/node/viewmodel/ServerUiState.kt` -- Add `skillsConfig` and `installedSkills` fields to `ServerUiState`.
- [x] `app/src/main/java/com/hermes/node/viewmodel/ServerViewModel.kt` -- Add `onToggleSkill` handler, update UI state, and wire into configuration lifecycle.
- [x] `app/src/main/java/com/hermes/node/ui/screens/SettingsScreen.kt` -- Implement "Skills & Capabilities" composable card with skill list, icons, and switch toggles.
- [x] `app/src/main/java/com/hermes/node/ui/navigation/HermesNavGraph.kt` -- Pass `onToggleSkill` lambda to `SettingsScreen`.
- [x] `app/src/test/java/com/hermes/node/data/ConfigSerializerTest.kt` -- Test JSON serialization/deserialization for core and custom skills.
- [x] `app/src/test/java/com/hermes/node/data/ConfigRepositoryTest.kt` -- Test `saveSkillsConfig` and `getSkillsConfig` in `EncryptedConfigRepository`.
- [x] `app/src/test/java/com/hermes/node/viewmodel/ServerViewModelTest.kt` -- Test `onToggleSkill` state transitions and config serialization invocation.

**Acceptance Criteria:**
- Given the Settings screen, when viewing "Agent Skills & Capabilities", then all 4 core skills (Web Search, File Manager, Bash Runner, Cron Scheduler) are listed with their title, description, icon, and current toggle state.
- Given a user toggling any skill switch (e.g. `bash_runner` off), when the switch is toggled, then the UI state updates immediately and the updated whitelist is written to `hermes.json` with strict POSIX `0600` permissions.
- Given an existing installation or configuration without a `skills` block, when loaded, then all skills default to enabled (`true`) without throwing JSON or null pointer exceptions.

## Spec Change Log

_None._

## Design Notes

The `SkillsConfig` model contains explicit typed properties for the four standard core Hermes tools while supporting an extensible `customSkills` map:
```kotlin
data class SkillsConfig(
    val webSearch: Boolean = true,
    val fileManager: Boolean = true,
    val bashRunner: Boolean = true,
    val cronScheduler: Boolean = true,
    val customSkills: Map<String, Boolean> = emptyMap()
)
```
JSON serialization outputs:
```json
{
  "skills": {
    "web_search": true,
    "file_manager": true,
    "bash_runner": true,
    "cron_scheduler": true
  }
}
```

## Verification

**Commands:**
- `./gradlew test` -- expected: `BUILD SUCCESSFUL` with all unit tests passing for serializer, repository, and viewmodel.
- `./gradlew assembleDebug` -- expected: `BUILD SUCCESSFUL` producing valid debug APK.

**Manual checks (if no CLI):**
- Verify Settings screen renders the "Agent Skills & Capabilities" card cleanly with distinct icons and theme-compliant switches.

## Suggested Review Order

**Core Models & Configuration Contract**

- Core skill metadata and toggle helper definitions
  [`HermesConfig.kt:62`](../../app/src/main/java/com/hermes/node/data/model/HermesConfig.kt#L62)

- JSON serialization with POSIX 0600 permissions and resilient parser defaults
  [`ConfigSerializer.kt:97`](../../app/src/main/java/com/hermes/node/data/ConfigSerializer.kt#L97)

- Encrypted preferences persistence and stale key removal
  [`ConfigRepository.kt:155`](../../app/src/main/java/com/hermes/node/data/ConfigRepository.kt#L155)

**State Management & UI Interaction**

- ViewModel skill toggling with coroutine mutex synchronization and fail-safe rollback
  [`ServerViewModel.kt:743`](../../app/src/main/java/com/hermes/node/viewmodel/ServerViewModel.kt#L743)

- Settings screen "Agent Skills & Capabilities" card with accessibility and empty state handling
  [`SettingsScreen.kt:415`](../../app/src/main/java/com/hermes/node/ui/screens/SettingsScreen.kt#L415)

- Navigation wiring connecting UI switch events to ViewModel
  [`HermesNavGraph.kt:442`](../../app/src/main/java/com/hermes/node/ui/navigation/HermesNavGraph.kt#L442)

**Automated Verification Suite**

- Unit tests for skills JSON serialization and non-boolean filtering
  [`ConfigSerializerTest.kt:380`](../../app/src/test/java/com/hermes/node/data/ConfigSerializerTest.kt#L380)

- Unit tests for encrypted preferences persistence and stale custom skill cleanup
  [`ConfigRepositoryTest.kt:317`](../../app/src/test/java/com/hermes/node/data/ConfigRepositoryTest.kt#L317)

- ViewModel unit tests for state updates, sequence synchronization, and persistence
  [`ServerViewModelTest.kt:2050`](../../app/src/test/java/com/hermes/node/viewmodel/ServerViewModelTest.kt#L2050)

