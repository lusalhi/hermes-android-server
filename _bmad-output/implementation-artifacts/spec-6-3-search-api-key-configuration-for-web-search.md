---
title: '6-3-search-api-key-configuration-for-web-search'
type: 'feature'
created: '2026-09-02'
status: 'done'
baseline_commit: '7a80aa040f9e5032776f35a88f36087a7a9ab572'
review_loop_iteration: 0
context: ['_bmad-output/implementation-artifacts/epic-6-context.md']
---

<frozen-after-approval reason="human-owned intent — do not modify unless human renegotiates">

## Intent

**Problem:** Users running Hermes Agent on Android with the Web Search skill enabled cannot perform live web searches because there is no mechanism in the Settings UI to select a search provider or enter an API key, causing the agent to silently disable web search and report to users that it lacks real-time internet access.

**Approach:** Add zero-install search provider selection (`brave`, `tavily`, `firecrawl`, `exa`) and an encrypted Search API Key input field to the Settings UI under Web Search, persist the credentials securely in `EncryptedSharedPreferences` and POSIX `0600` `hermes.json`, and inject the corresponding environment variables and `.hermes` configuration so the Hermes Agent daemon can execute web queries out-of-the-box.

## Boundaries & Constraints

**Always:**
- Persist the search API key securely at rest using Android Keystore / `EncryptedSharedPreferences` via `ConfigRepository`.
- Serialize `search_provider` and `search_api_key` into `/data/data/com.hermes.node/files/hermes.json` under `skills` with strict POSIX `0600` (`-rw-------`) permissions.
- Support zero-install HTTP REST providers: `brave` (default), `tavily`, `firecrawl`, and `exa`.
- In `ProcessController`, inject provider-specific environment variables (`BRAVE_SEARCH_API_KEY`, `BRAVE_API_KEY`, `TAVILY_API_KEY`, `FIRECRAWL_API_KEY`, `EXA_API_KEY`) and `HERMES_SEARCH_PROVIDER` into the sub-process environment when `searchApiKey` is configured.
- Sync provider configuration to `${filesDir}/.hermes/config.yaml` and `${filesDir}/.hermes/.env` to ensure upstream Hermes Agent recognizes the active backend.
- Mask the Search API Key input in the Settings UI with a password toggle (show/hide visibility).
- Backward compatibility: default missing `search_provider` to `"brave"` and missing `search_api_key` to `""` without exceptions.

**Ask First:**
- Adding search providers that require running `pip install` or compiling native C extensions inside the PRoot userland (e.g. `ddgs`).

**Never:**
- Log unmasked search API keys to stdout, stderr, logcat, terminal ring buffers, or exported backups.
- Expose search API keys in non-encrypted plain storage or world-readable files.

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| Configure Brave Key | User selects Brave and enters key `BSA...` | `searchProvider="brave"`, `searchApiKey="BSA..."` saved; `hermes.json` has `search_provider` & `search_api_key`; daemon receives `BRAVE_SEARCH_API_KEY` | Atomic write via staging file |
| Configure Tavily Key | User selects Tavily and enters key `tvly-...` | `searchProvider="tavily"`, `searchApiKey="tvly-..."` saved; daemon receives `TAVILY_API_KEY` | Validate non-null; strip leading/trailing whitespace |
| Empty API Key | User toggles Web Search ON but leaves key blank | Saves empty string; UI shows helper info warning that an API key is needed for web search | Does not crash; agent runs without web search tool |
| Legacy Config Migration | Existing `hermes.json` without search fields loaded | Defaults to `searchProvider="brave"` and `searchApiKey=""` | Fallback gracefully to default values |
| Hot Toggle / Update | User updates search key while server is running | Updated credentials written to disk with POSIX `0600`; daemon environment ready on next reload/start | UI updates immediately |

</frozen-after-approval>

## Code Map

- `app/src/main/java/com/hermes/node/data/model/HermesConfig.kt` -- Add `searchProvider: String = SEARCH_PROVIDER_BRAVE` and `searchApiKey: String = ""` to `SkillsConfig`; define constants for `SEARCH_PROVIDER_BRAVE`, `SEARCH_PROVIDER_TAVILY`, `SEARCH_PROVIDER_FIRECRAWL`, `SEARCH_PROVIDER_EXA`.
- `app/src/main/java/com/hermes/node/data/ConfigSerializer.kt` -- Serialize and deserialize `search_provider` and `search_api_key` in the `"skills"` JSON block.
- `app/src/main/java/com/hermes/node/data/ConfigRepository.kt` -- Add preference keys `KEY_SEARCH_PROVIDER` and `KEY_SEARCH_API_KEY` in `EncryptedConfigRepository`; load and save search provider and key.
- `app/src/main/java/com/hermes/node/engine/ProcessController.kt` -- In `createHermesDaemonConfig`, read search config and inject provider environment variables (`BRAVE_SEARCH_API_KEY`, `TAVILY_API_KEY`, etc.) and ensure `.hermes/config.yaml` / `.env` are synchronized.
- `app/src/main/java/com/hermes/node/viewmodel/ServerUiState.kt` -- Add `searchApiKeyVisible: Boolean = false` or bind `skillsConfig` updates.
- `app/src/main/java/com/hermes/node/viewmodel/ServerViewModel.kt` -- Add `onUpdateSearchProvider(provider: String)` and `onUpdateSearchApiKey(apiKey: String)` event handlers, wire into config save.
- `app/src/main/java/com/hermes/node/ui/screens/SettingsScreen.kt` -- Add search provider dropdown/radio and password-masked Search API Key input field with helper text within the Web Search card when Web Search is enabled.
- `app/src/main/java/com/hermes/node/ui/navigation/HermesNavGraph.kt` -- Wire new search config callbacks to `SettingsScreen`.
- `app/src/test/java/com/hermes/node/data/ConfigSerializerTest.kt` -- Unit tests for serialization and parsing of `search_provider` and `search_api_key`.
- `app/src/test/java/com/hermes/node/data/ConfigRepositoryTest.kt` -- Unit tests for encrypted persistence of search provider and API key.
- `app/src/test/java/com/hermes/node/viewmodel/ServerViewModelTest.kt` -- Unit tests for `onUpdateSearchProvider` and `onUpdateSearchApiKey` ViewModel state flows and persistence.

## Tasks & Acceptance

**Execution:**
- [x] `app/src/main/java/com/hermes/node/data/model/HermesConfig.kt` -- Add search provider constants, `searchProvider`, and `searchApiKey` properties to `SkillsConfig` -- Establishes typed configuration for web search credentials.
- [x] `app/src/main/java/com/hermes/node/data/ConfigSerializer.kt` -- Update JSON generator and parser to read/write `search_provider` and `search_api_key` inside `skills` -- Persists search config in POSIX 0600 `hermes.json`.
- [x] `app/src/main/java/com/hermes/node/data/ConfigRepository.kt` -- Add `saveSearchConfig`, `getSearchProvider`, `getSearchApiKey` to `ConfigRepository` and `EncryptedConfigRepository` -- Protects search API secrets in Keystore.
- [x] `app/src/main/java/com/hermes/node/engine/ProcessController.kt` -- Read search credentials and export `BRAVE_SEARCH_API_KEY` / `TAVILY_API_KEY` / `FIRECRAWL_API_KEY` / `EXA_API_KEY` and sync `.hermes` config -- Bridges Android config to Python agent runtime.
- [x] `app/src/main/java/com/hermes/node/viewmodel/ServerViewModel.kt` -- Add `onUpdateSearchProvider` and `onUpdateSearchApiKey` handlers and state wiring -- Connects user UI input to configuration repository.
- [x] `app/src/main/java/com/hermes/node/ui/screens/SettingsScreen.kt` -- Render search provider selector and password-masked Search API Key input field under Web Search -- Gives user mobile UI controls to configure search API keys.
- [x] `app/src/main/java/com/hermes/node/ui/navigation/HermesNavGraph.kt` -- Pass search config callbacks from ViewModel to SettingsScreen -- Completes navigation graph wiring.
- [x] `app/src/test/java/com/hermes/node/data/ConfigSerializerTest.kt` -- Add unit tests for `search_provider` and `search_api_key` serialization and deserialization -- Verifies JSON contract.
- [x] `app/src/test/java/com/hermes/node/data/ConfigRepositoryTest.kt` -- Add unit tests for encrypted storage of search credentials -- Verifies Keystore persistence.
- [x] `app/src/test/java/com/hermes/node/viewmodel/ServerViewModelTest.kt` -- Add unit tests for search provider and API key ViewModel event handling -- Verifies UI state transitions.

**Acceptance Criteria:**
- Given the Settings screen with Web Search enabled, when the user expands Web Search or views its configuration, then a search provider selector (Brave, Tavily, Firecrawl, Exa) and a masked API Key input field with a visibility toggle are displayed.
- Given the user enters an API key for Brave Search (`BSA...`), when saved, then `hermes.json` contains `"search_provider": "brave"` and `"search_api_key": "BSA..."` with POSIX `0600` permissions, and the key is encrypted at rest.
- Given a configured search API key, when the Hermes server daemon starts, then the process environment contains `BRAVE_SEARCH_API_KEY` (or the respective provider key), enabling Hermes Agent to execute web search tool calls without extra Python packages.
- Given legacy configurations without search fields, when loaded, then `searchProvider` defaults to `"brave"` and `searchApiKey` defaults to `""` without throwing errors.

## Spec Change Log

_None._

## Design Notes

The `SkillsConfig` data class is extended with:
```kotlin
data class SkillsConfig(
    val webSearch: Boolean = true,
    val searchProvider: String = SEARCH_PROVIDER_BRAVE,
    val searchApiKey: String = "",
    val fileManager: Boolean = true,
    val bashRunner: Boolean = true,
    val cronScheduler: Boolean = true,
    val customSkills: Map<String, Boolean> = emptyMap()
)
```
JSON output in `hermes.json`:
```json
{
  "skills": {
    "web_search": true,
    "search_provider": "brave",
    "search_api_key": "BSA...",
    "file_manager": true,
    "bash_runner": true,
    "cron_scheduler": true
  }
}
```
Environment variables injected into the daemon process:
```kotlin
when (config.skills.searchProvider.lowercase()) {
    "brave" -> {
        env["BRAVE_SEARCH_API_KEY"] = config.skills.searchApiKey
        env["BRAVE_API_KEY"] = config.skills.searchApiKey
    }
    "tavily" -> env["TAVILY_API_KEY"] = config.skills.searchApiKey
    "firecrawl" -> env["FIRECRAWL_API_KEY"] = config.skills.searchApiKey
    "exa" -> env["EXA_API_KEY"] = config.skills.searchApiKey
}
env["HERMES_SEARCH_PROVIDER"] = config.skills.searchProvider.lowercase()
```

## Verification

**Commands:**
- `./gradlew test` -- expected: `BUILD SUCCESSFUL` with all unit tests passing.
- `./gradlew assembleDebug` -- expected: `BUILD SUCCESSFUL` producing valid debug APK.

**Manual checks (if no CLI):**
- Verify Settings screen renders provider selector and masked API key field when Web Search is enabled.
- Verify saving credentials updates `hermes.json` and encrypted preferences.

## Suggested Review Order

**UI & User Interaction**

- Web search configuration card, provider dropdown, and masked API key input field
  [`SettingsScreen.kt:560`](../../app/src/main/java/com/hermes/node/ui/screens/SettingsScreen.kt#L560)

- ViewModel state flows, provider sanitization, and setting persistence
  [`ServerViewModel.kt:835`](../../app/src/main/java/com/hermes/node/viewmodel/ServerViewModel.kt#L835)

**Daemon Environment & Process Execution**

- Environment injection gated by webSearch and synchronizing `.env`/`config.yaml`
  [`ProcessController.kt:103`](../../app/src/main/java/com/hermes/node/engine/ProcessController.kt#L103)

- Safe YAML/ENV merging with POSIX 0600 permissions
  [`ProcessController.kt:160`](../../app/src/main/java/com/hermes/node/engine/ProcessController.kt#L160)

**Security & Data Persistence**

- Keystore encrypted preferences accessors for search credentials
  [`ConfigRepository.kt:410`](../../app/src/main/java/com/hermes/node/data/ConfigRepository.kt#L410)

- Atomic JSON serialization into hermes.json with backwards compatibility
  [`ConfigSerializer.kt:106`](../../app/src/main/java/com/hermes/node/data/ConfigSerializer.kt#L106)

- Typed search model and non-skill key protection
  [`HermesConfig.kt:70`](../../app/src/main/java/com/hermes/node/data/model/HermesConfig.kt#L70)

**Verification & Tests**

- Unit tests covering search credentials in ViewModel
  [`ServerViewModelTest.kt:2210`](../../app/src/test/java/com/hermes/node/viewmodel/ServerViewModelTest.kt#L2210)

- Process environment injection and sync tests
  [`ProcessControllerTest.kt:235`](../../app/src/test/java/com/hermes/node/engine/ProcessControllerTest.kt#L235)

- JSON and Keystore persistence tests
  [`ConfigSerializerTest.kt:530`](../../app/src/test/java/com/hermes/node/data/ConfigSerializerTest.kt#L530)

