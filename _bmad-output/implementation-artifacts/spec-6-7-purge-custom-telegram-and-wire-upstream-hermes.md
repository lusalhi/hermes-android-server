---
title: '6-7-purge-custom-telegram-and-wire-upstream-hermes'
type: 'feature'
created: '2026-09-04'
status: 'done'
baseline_commit: '917c14c4ba4a2572a617bb8575fc21c5d144cbd0'
review_loop_iteration: 0
context:
  - _bmad-output/implementation-artifacts/epic-5-context.md
  - _bmad-output/implementation-artifacts/epic-6-context.md
---

<frozen-after-approval reason="human-owned intent — do not modify unless human renegotiates">

## Intent

**Problem:** The native Kotlin `TelegramGatewayManager` is a custom re-implementation that lacks upstream Hermes Agent's full ReAct loop, tool execution (bash, files, web search), multi-user contexts, and voice transcription. Maintaining a duplicate custom gateway creates confusion and feature disparities compared to the original Nous Research Hermes Agent.

**Approach:**
1. Completely delete `TelegramGatewayManager.kt` and its unit tests. Remove all `telegramGatewayManager` bindings, lifecycles, and references from `HermesServerService.kt` and `HermesServerServiceTest.kt`.
2. Let upstream Hermes Agent (`hermes gateway run`) handle the Telegram gateway natively as-is inside PRoot.
3. Update `ProcessController.syncHermesConfig` and `createHermesDaemonConfig` to inject upstream Telegram environment variables (`TELEGRAM_BOT_TOKEN`, `TELEGRAM_ALLOWED_USERS`, `TELEGRAM_ADMIN_IDS`) and synchronize upstream-compliant `telegram:` gateway blocks into `${filesDir}/.hermes/config.yaml`, `${filesDir}/.hermes/.env`, and mirrored `usr/root/.hermes/` directories.
4. Update `usr/bin/hermes` launcher script in `BootstrapExtractor` to execute `python3 -m hermes "$@"` so `hermes gateway run` launches the genuine upstream Python package.

## Boundaries & Constraints

**Always:**
- Completely remove `TelegramGatewayManager.kt` and `TelegramGatewayManagerTest.kt` from the codebase; do not leave deprecated stubs.
- Keep Settings UI and `HermesConfig` gateway models intact so users can configure Telegram Bot Token and Admin IDs seamlessly.
- Sync Telegram configuration to `TELEGRAM_BOT_TOKEN`, `TELEGRAM_ALLOWED_USERS`, and `.hermes/config.yaml` with POSIX `0600` permissions.
- Ensure `HermesServerService.kt` compiles cleanly without `TelegramGatewayManager` references and continues managing `ProcessController` and `TunnelManager`.
- Ensure all remaining tests pass with `./gradlew test` and `./gradlew assembleDebug`.

**Ask First:**
- Removing Discord, Slack, WhatsApp, or REST API gateway configuration fields from `SettingsScreen`.

**Never:**
- Never run concurrent polling on the Telegram API from Kotlin when `hermes gateway run` is running.
- Never write plain text token files without POSIX 0600 permissions.

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| Start Server with Telegram configured | `gateway.telegram.enabled = true`, `botToken = "123:ABC"` | `ProcessController` injects `TELEGRAM_BOT_TOKEN="123:ABC"`, syncs `.hermes/config.yaml`, and spawns `hermes gateway run`; upstream Hermes runs Telegram gateway | Fails gracefully if token invalid |
| Start Server without Telegram | `gateway.telegram.enabled = false` | Omits `TELEGRAM_BOT_TOKEN`; upstream Hermes starts without Telegram adapter | Normal daemon startup |
| Stop Server | User taps Stop Server | `HermesServerService` stops `ProcessController` via SIGTERM; upstream Python process shuts down cleanly | Process cleanup within timeout |
| Clean codebase | Verify deleted files | `TelegramGatewayManager.kt` and `TelegramGatewayManagerTest.kt` deleted; zero build errors | Build successful |

</frozen-after-approval>

## Code Map

- `app/src/main/java/com/hermes/node/engine/TelegramGatewayManager.kt` -- DELETE file.
- `app/src/test/java/com/hermes/node/engine/TelegramGatewayManagerTest.kt` -- DELETE file.
- `app/src/main/java/com/hermes/node/service/HermesServerService.kt` -- Remove `telegramGatewayManager` property, `ensureTelegramGatewayManager()`, and startup/shutdown lifecycle calls.
- `app/src/test/java/com/hermes/node/service/HermesServerServiceTest.kt` -- Clean up any tests referencing `telegramGatewayManager`.
- `app/src/main/java/com/hermes/node/engine/ProcessController.kt` -- In `createHermesDaemonConfig` and `syncHermesConfig`, inject `TELEGRAM_BOT_TOKEN`, `TELEGRAM_ALLOWED_USERS`, and sync `telegram:` gateway block into `.hermes/config.yaml` and `.env`.
- `app/src/main/java/com/hermes/node/engine/BootstrapExtractor.kt` -- Update `/usr/bin/hermes` launcher script to delegate directly to Python `exec python3 -m hermes "$@"`.
- `app/src/test/java/com/hermes/node/engine/ProcessControllerTest.kt` -- Add unit tests verifying `TELEGRAM_BOT_TOKEN` environment injection and `config.yaml` Telegram block synchronization.

## Tasks & Acceptance

**Execution:**
- [x] Delete `app/src/main/java/com/hermes/node/engine/TelegramGatewayManager.kt` and `app/src/test/java/com/hermes/node/engine/TelegramGatewayManagerTest.kt`.
- [x] `app/src/main/java/com/hermes/node/service/HermesServerService.kt` -- Remove `telegramGatewayManager` field, initialization, and stop calls.
- [x] `app/src/main/java/com/hermes/node/engine/ProcessController.kt` -- Sync `TELEGRAM_BOT_TOKEN` and `telegram:` section into `.hermes/config.yaml`, `.env`, and PRoot environment.
- [x] `app/src/main/java/com/hermes/node/engine/BootstrapExtractor.kt` -- Update `/usr/bin/hermes` to execute `exec python3 -m hermes "$@"`.
- [x] `app/src/test/java/com/hermes/node/engine/ProcessControllerTest.kt` -- Test Telegram token environment injection and YAML config synchronization.
- [x] Run `./gradlew test` and `./gradlew assembleDebug` to verify complete clean build.

**Acceptance Criteria:**
- Given `TelegramGatewayManager.kt` is deleted, then `app` compiles with zero unresolved references.
- Given Telegram is configured in Settings, when the daemon config is created, then `TELEGRAM_BOT_TOKEN` and `config.yaml` contain the configured token and admin IDs for upstream Hermes.
- Given the server is started, then `HermesServerService` launches upstream `hermes gateway run` as the sole Telegram handler.

## Spec Change Log

_None._

## Design Notes

By removing the custom Kotlin gateway and feeding credentials directly into upstream Hermes Agent (`TELEGRAM_BOT_TOKEN` and `.hermes/config.yaml`), users receive 100% genuine Nous Research features (ReAct loop, bash execution, SQLite memory, voice notes) with zero duplicate code in Android.

## Verification

**Commands:**
- `./gradlew test` -- expected: `BUILD SUCCESSFUL` with all unit tests passing.
- `./gradlew assembleDebug` -- expected: `BUILD SUCCESSFUL` generating debug APK.

## Suggested Review Order

**Service Simplification & Process Lifecycle**

- Remove duplicate gateway, log upstream daemon startup.
  [`HermesServerService.kt:280`](../../app/src/main/java/com/hermes/node/service/HermesServerService.kt#L280)

- Replace mock sleep launcher with genuine Python Hermes module invocation.
  [`BootstrapExtractor.kt:142`](../../app/src/main/java/com/hermes/node/engine/BootstrapExtractor.kt#L142)

- Self-heal POSIX 0755 executable permissions on hermes launchers.
  [`BootstrapExtractor.kt:158`](../../app/src/main/java/com/hermes/node/engine/BootstrapExtractor.kt#L158)

**Upstream Config Synchronization & Environment Injection**

- Inject Telegram bot token and admin IDs into daemon PRoot environment.
  [`ProcessController.kt:127`](../../app/src/main/java/com/hermes/node/engine/ProcessController.kt#L127)

- Synchronize upstream-compliant Telegram YAML sequences and atomic config replacements.
  [`ProcessController.kt:220`](../../app/src/main/java/com/hermes/node/engine/ProcessController.kt#L220)

**Peripherals & Verification Suite**

- Verify process environment injection and YAML config synchronization under tests.
  [`ProcessControllerTest.kt:641`](../../app/src/test/java/com/hermes/node/engine/ProcessControllerTest.kt#L641)

- Verify server service starts daemon with Telegram configuration enabled.
  [`HermesServerServiceTest.kt:97`](../../app/src/test/java/com/hermes/node/service/HermesServerServiceTest.kt#L97)

- Verify bootstrap extraction creates executable Python module launcher.
  [`BootstrapExtractorTest.kt:248`](../../app/src/test/java/com/hermes/node/engine/BootstrapExtractorTest.kt#L248)
