---
title: '5-1-multi-platform-messaging-gateway-adapters-telegram-discord-s'
type: 'feature'
created: '2026-08-29'
status: 'done'
baseline_commit: '9e3c3481d9128056c5f17f1b4ef8fae286b07996'
review_loop_iteration: 0
context:
  - _bmad-output/planning-artifacts/architecture/architecture-hermes-android-server-2026-08-24/ARCHITECTURE-SPINE.md
  - _bmad-output/planning-artifacts/prds/prd-hermes-android-server-2026-08-24/prd.md
  - _bmad-output/implementation-artifacts/epic-5-context.md
---

<frozen-after-approval reason="human-owned intent — do not modify unless human renegotiates">

## Intent

**Problem:** Users hosting an autonomous Hermes agent on Android can currently only configure basic Telegram token properties, lacking support for multi-platform messaging gateways (Telegram with Admin IDs, Discord, Slack, WhatsApp, and REST API/Local Web Server port configuration) and their encrypted persistence.

**Approach:** Implement structured data models for Telegram, Discord, Slack, WhatsApp, and REST API gateways, extend `ConfigRepository` Keystore-backed encryption and `ConfigSerializer` atomic POSIX 0600 JSON generator, expand `ServerUiState` and `ServerViewModel` with gateway state management, and build interactive gateway configuration cards with toggle controls, password masking, and input validation in `SettingsScreen`.

## Boundaries & Constraints

**Always:**
- Securely persist all gateway tokens, API keys, and webhook secrets using Android Keystore / `EncryptedSharedPreferences` via `ConfigRepository`.
- Atomically write `hermes.json` with strict POSIX `0600` file permissions (read/write only by the application UID) upon saving settings.
- Maintain Unidirectional Data Flow (UDF) by exposing immutable `ServerUiState` via `StateFlow` in `ServerViewModel`.
- Ensure all token and secret input fields in `SettingsScreen` support toggleable visibility (show/hide password masking).
- Trim all string inputs (tokens, IDs, URLs) before serialization and persistence.
- Preserve backward-compatibility for existing Telegram token accessors in `HermesConfig` and `ConfigRepository`.

**Ask First:**
- Adding external network ping or live token validation requests to 3rd-party gateway APIs (Telegram, Discord, Slack) from the Android UI layer prior to daemon start.
- Modifying POSIX process launch flags or PRoot sandbox environment variables outside of `hermes.json` configuration.

**Never:**
- Never log raw bot tokens, API keys, app tokens, or webhook secrets to console logs, logcat, or ring buffers.
- Never write configuration files in plain text without POSIX 0600 permissions.
- Never crash the application on invalid port numbers, malformed token strings, or null preference values.

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| Configure Telegram with Admin IDs | `botToken = "123:ABC"`, `adminIds = "111,222"`, `enabled = true` | `hermes.json` contains `gateways.telegram` with `enabled: true`, `bot_token: "123:ABC"`, `admin_user_ids: "111,222"` | Store trimmed string, tolerate empty admin IDs |
| Configure Discord Gateway | `botToken = "OTg3..."`, `channelIds = "ch-1,ch-2"`, `enabled = true` | `hermes.json` contains `gateways.discord` with `enabled: true`, `bot_token: "OTg3..."`, `channel_ids: "ch-1,ch-2"` | Mask token in UI, trim whitespace |
| Configure Slack Gateway | `appToken = "xapp-123"`, `botToken = "xoxb-456"`, `enabled = true` | `hermes.json` contains `gateways.slack` with `enabled: true`, `app_token: "xapp-123"`, `bot_token: "xoxb-456"` | Mask both tokens with independent reveal toggles |
| Configure WhatsApp Gateway | `sessionLink = "https://wa.me/..."`, `webhookToken = "tok_wa"`, `enabled = true` | `hermes.json` contains `gateways.whatsapp` with `enabled: true`, `session_link: "https://wa.me/..."`, `webhook_token: "tok_wa"` | Validate non-null, store securely |
| Custom REST API Port | `port = "9000"`, `enabled = true` | `hermes.json` contains `gateways.rest_api` with `enabled: true`, `port: 9000` | Parse int safely; fallback to 8000 if invalid/empty |
| Blank/Disabled Gateway | Gateway enabled toggle set to `false`, tokens blank | `hermes.json` contains `enabled: false` and empty strings for tokens | Server daemon omits initializing that gateway |
| Backward Compatibility | Legacy call to `gateway.telegramToken` or `gateway.isTelegramEnabled` | Returns `telegram.botToken` and `telegram.enabled` correctly | Seamlessly maps to new `TelegramGatewayConfig` |

</frozen-after-approval>

## Code Map

- `app/src/main/java/com/hermes/node/data/model/HermesConfig.kt` -- Data classes: `TelegramGatewayConfig`, `DiscordGatewayConfig`, `SlackGatewayConfig`, `WhatsAppGatewayConfig`, `RestApiGatewayConfig`, updated `GatewayConfig`, and root `HermesConfig`.
- `app/src/main/java/com/hermes/node/data/ConfigRepository.kt` -- `ConfigRepository` interface and `EncryptedConfigRepository` implementation with encrypted SharedPreferences keys and accessors for all gateways.
- `app/src/main/java/com/hermes/node/data/ConfigSerializer.kt` -- JSON serialization of `gateways` object (`telegram`, `discord`, `slack`, `whatsapp`, `rest_api`) into `hermes.json` with POSIX 0600 permissions.
- `app/src/main/java/com/hermes/node/viewmodel/ServerUiState.kt` -- State properties for Telegram, Discord, Slack, WhatsApp, and REST API toggles and inputs.
- `app/src/main/java/com/hermes/node/viewmodel/ServerViewModel.kt` -- Loading, state update handlers, input trimming, and persistence orchestration for all gateway configs.
- `app/src/main/java/com/hermes/node/ui/screens/SettingsScreen.kt` -- Cyber-terminal UI cards for Telegram, Discord, Slack, WhatsApp, and REST API gateways with toggle switches, visibility reveal buttons, and input validation.
- `app/src/test/java/com/hermes/node/data/ConfigRepositoryTest.kt` -- Unit tests verifying default values, full gateway persistence, individual accessors, and clearing.
- `app/src/test/java/com/hermes/node/data/ConfigSerializerTest.kt` -- Unit tests verifying `generateJson` and `serialize` for all gateways, special character escaping, and POSIX 0600 permissions.
- `app/src/test/java/com/hermes/node/viewmodel/ServerViewModelTest.kt` -- Unit tests for ViewModel gateway state mutations, persistence, and input trimming.

## Tasks & Acceptance

**Execution:**
- [x] `app/src/main/java/com/hermes/node/data/model/HermesConfig.kt` -- Expand `GatewayConfig` with dedicated models for Telegram, Discord, Slack, WhatsApp, and REST API with backward compatibility.
- [x] `app/src/main/java/com/hermes/node/data/ConfigRepository.kt` -- Add preference keys, getters, setters, and `saveConfig`/`getConfig` mapping for all gateway configurations.
- [x] `app/src/main/java/com/hermes/node/data/ConfigSerializer.kt` -- Update `generateJson` to serialize `telegram`, `discord`, `slack`, `whatsapp`, and `rest_api` gateway structures into `hermes.json`.
- [x] `app/src/main/java/com/hermes/node/viewmodel/ServerUiState.kt` -- Add state fields for enabled status, tokens, admin/channel IDs, and port configurations across all gateways.
- [x] `app/src/main/java/com/hermes/node/viewmodel/ServerViewModel.kt` -- Implement update event methods (`onUpdateTelegramEnabled`, `onUpdateTelegramAdminUserIds`, `onUpdateDiscordEnabled`, `onUpdateDiscordToken`, `onUpdateDiscordChannelIds`, `onUpdateSlackEnabled`, `onUpdateSlackAppToken`, `onUpdateSlackBotToken`, `onUpdateWhatsAppEnabled`, `onUpdateWhatsAppSessionLink`, `onUpdateWhatsAppWebhookToken`, `onUpdateRestApiEnabled`, `onUpdateRestApiPort`), input trimming, and persistence.
- [x] `app/src/main/java/com/hermes/node/ui/screens/SettingsScreen.kt` -- Build expandable/cyber-themed UI sections for all five messaging gateways with password masking toggles and port input validation.
- [x] `app/src/test/java/com/hermes/node/data/ConfigRepositoryTest.kt` -- Add unit tests for all gateway preference serialization and retrieval.
- [x] `app/src/test/java/com/hermes/node/data/ConfigSerializerTest.kt` -- Add unit tests for full gateway JSON generation and atomic write.
- [x] `app/src/test/java/com/hermes/node/viewmodel/ServerViewModelTest.kt` -- Add unit tests for ViewModel gateway state transitions and save operations.

**Acceptance Criteria:**
- Given configured credentials for Telegram, Discord, Slack, WhatsApp, or REST API in Settings, when saving settings, then `ConfigRepository` encrypts all secrets in `EncryptedSharedPreferences` and `ConfigSerializer` atomically writes `hermes.json` with POSIX 0600 permissions.
- Given the Settings screen, when toggling a gateway on or off or editing token/port fields, then `ServerUiState` updates reactively and supports revealing/hiding sensitive tokens.
- Given invalid or empty port inputs, when updating REST API settings, the system gracefully defaults to port 8000 without crashing.
- Given unit tests in `ConfigRepositoryTest`, `ConfigSerializerTest`, and `ServerViewModelTest`, 100% of gateway persistence, JSON serialization, and ViewModel tests pass.

### Review Findings

- [x] [Review][Patch] Dynamic port and multi-gateway connection logging during daemon startup and state observation [app/src/main/java/com/hermes/node/viewmodel/ServerViewModel.kt:412-429, 906-911]
- [x] [Review][Patch] Complete credential persistence log check across all messaging platforms in loadPersistedConfig [app/src/main/java/com/hermes/node/viewmodel/ServerViewModel.kt:519-528]
- [x] [Review][Patch] Use rememberSaveable for token visibility and dropdown state persistence across activity recreations [app/src/main/java/com/hermes/node/ui/screens/SettingsScreen.kt:119-126]
- [x] [Review][Patch] Real-time REST API port validation with error highlight and supporting text in Settings UI [app/src/main/java/com/hermes/node/ui/screens/SettingsScreen.kt:932-965]
- [x] [Review][Patch] Expand repository clear unit tests covering all gateway keys and enabled toggles [app/src/test/java/com/hermes/node/data/ConfigRepositoryTest.kt:267-302]
- [x] [Review][Patch] Add multi-platform gateway special characters and URL serialization unit tests [app/src/test/java/com/hermes/node/data/ConfigSerializerTest.kt:168-185]
- [x] [Review][Patch] Provide default implementations for gateway methods in ConfigRepository interface for mock compatibility [app/src/main/java/com/hermes/node/data/ConfigRepository.kt:28-57]
- [x] [Review][Defer] Active messaging gateway indicator chips on Dashboard screen [app/src/main/java/com/hermes/node/ui/screens/DashboardScreen.kt] — deferred to Epic 5.2

## Spec Change Log

_None._

## Design Notes

### Gateway Model Hierarchy
```kotlin
data class TelegramGatewayConfig(
    val enabled: Boolean = false,
    val botToken: String = "",
    val adminUserIds: String = ""
)

data class DiscordGatewayConfig(
    val enabled: Boolean = false,
    val botToken: String = "",
    val channelIds: String = ""
)

data class SlackGatewayConfig(
    val enabled: Boolean = false,
    val appToken: String = "",
    val botToken: String = ""
)

data class WhatsAppGatewayConfig(
    val enabled: Boolean = false,
    val sessionLink: String = "",
    val webhookToken: String = ""
)

data class RestApiGatewayConfig(
    val enabled: Boolean = true,
    val port: Int = 8000
)
```

## Verification

**Commands:**
- `./gradlew test` -- expected: All unit tests in `ConfigRepositoryTest`, `ConfigSerializerTest`, and `ServerViewModelTest` pass.

## Suggested Review Order

**Gateway Models & Architecture**

- Dedicated data classes for Telegram, Discord, Slack, WhatsApp, and REST API with legacy backward compatibility
  [`HermesConfig.kt:10`](../../app/src/main/java/com/hermes/node/data/model/HermesConfig.kt#L10)

**Persistence & Serialization**

- Keystore-backed preference encryption and accessors across all messaging platforms
  [`ConfigRepository.kt:80`](../../app/src/main/java/com/hermes/node/data/ConfigRepository.kt#L80)

- Atomic POSIX 0600 JSON serialization of multi-gateway configurations into hermes.json
  [`ConfigSerializer.kt:52`](../../app/src/main/java/com/hermes/node/data/ConfigSerializer.kt#L52)

**State Management & ViewModel**

- Reactive UI state fields and update handlers for all gateway tokens and toggles
  [`ServerUiState.kt:40`](../../app/src/main/java/com/hermes/node/viewmodel/ServerUiState.kt#L40)

- Startup daemon logging, input trimming, and persistence orchestration in ServerViewModel
  [`ServerViewModel.kt:412`](../../app/src/main/java/com/hermes/node/viewmodel/ServerViewModel.kt#L412)

**User Interface**

- Cyber-terminal settings cards with visibility reveal buttons and port validation
  [`SettingsScreen.kt:450`](../../app/src/main/java/com/hermes/node/ui/screens/SettingsScreen.kt#L450)

**Unit Tests & Verification**

- Comprehensive tests for multi-gateway persistence, JSON generation, and ViewModel state transitions
  [`ConfigRepositoryTest.kt:80`](../../app/src/test/java/com/hermes/node/data/ConfigRepositoryTest.kt#L80)
