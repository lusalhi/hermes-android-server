---
title: 'Story 3.3: Battery Optimization Exemption & Boot Completed Auto-Start'
type: 'feature'
created: '2026-08-26'
status: 'done'
baseline_commit: 'e56128d6d3857ad5e1c8c262a0741f4096ed0ddc'
review_loop_iteration: 1
context: []
---

<frozen-after-approval reason="human-owned intent — do not modify unless human renegotiates">

## Intent

**Problem:** Android OS aggressively terminates background services during Doze mode and kills daemons upon device reboots or memory pressure, preventing the Hermes Node from acting as an always-on 24/7 dedicated AI agent server.

**Approach:** Implement a `BootReceiver` listening for `RECEIVE_BOOT_COMPLETED` to automatically launch `HermesServerService` on system reboot when enabled in settings, and provide a `BatteryOptimizationHelper` to detect Doze whitelist status and prompt users for `ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` with OEM guidance.

## Boundaries & Constraints

**Always:**
- Guard `BootReceiver` execution by checking `isAutoStartEnabled()` from `ConfigRepository` before invoking `HermesServerService.start(context)`.
- Use `ContextCompat.startForegroundService` or `HermesServerService.start(context)` so Android 8.0+ background execution limits are respected.
- Handle missing Keystore gracefully in `BootReceiver` by using the resilient `EncryptedConfigRepository.create(context)` fallback.
- Declare `BootReceiver` in `AndroidManifest.xml` with `android:exported="true"` and filters for `ACTION_BOOT_COMPLETED`, `ACTION_MY_PACKAGE_REPLACED`, and quickboot actions.
- Provide direct intent navigation to `ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` (URI `package:<package-name>`) with fallback to `ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS`.

**Ask First:**
- Modifying default auto-start preference (default must remain `false` for user consent).
- Adding additional third-party dependencies outside AndroidX / Jetpack libraries.

**Never:**
- Never start `HermesServerService` on boot if `autoStartOnBoot` is disabled in `ConfigRepository`.
- Never block the `BroadcastReceiver.onReceive` thread with heavy I/O or synchronous operations; delegate immediate service start.
- Never crash if `PowerManager` or `isIgnoringBatteryOptimizations` is unavailable on older/modified Android OS versions.

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| Boot completed with auto-start enabled | `ACTION_BOOT_COMPLETED`, `autoStartOnBoot == true` | `HermesServerService.start(context)` invoked; service starts in foreground | Catch any startup exceptions and log error |
| Boot completed with auto-start disabled | `ACTION_BOOT_COMPLETED`, `autoStartOnBoot == false` | Receiver exits immediately without starting service | N/A |
| App updated (Package replaced) | `ACTION_MY_PACKAGE_REPLACED`, `autoStartOnBoot == true` | `HermesServerService.start(context)` invoked; restores daemon | Catch any startup exceptions and log error |
| Battery optimization enabled (not whitelisted) | `powerManager.isIgnoringBatteryOptimizations == false` | UI displays warning banner / prompt and provides "Request Exemption" action button | Fallback to settings intent if direct package intent fails |
| Battery optimization disabled (whitelisted) | `powerManager.isIgnoringBatteryOptimizations == true` | UI displays "Unrestricted" badge; no warning banner shown | N/A |
| User enables auto-start in settings | User flips "Auto-start on Boot" toggle to `true` | Setting updated in `ServerUiState` & `ConfigRepository`; if battery optimized, shows exemption hint | N/A |

</frozen-after-approval>

## Code Map

- `app/src/main/AndroidManifest.xml` -- Register `BootReceiver` with `RECEIVE_BOOT_COMPLETED` and `ACTION_MY_PACKAGE_REPLACED` intent filters.
- `app/src/main/java/com/hermes/node/service/BootReceiver.kt` -- Broadcast receiver handling system boot and package replacement events to trigger daemon startup based on configuration.
- `app/src/main/java/com/hermes/node/service/BatteryOptimizationHelper.kt` -- Utility & helper interface for detecting Doze exemption state, creating request intents, and providing OEM-specific guidance URLs.
- `app/src/main/java/com/hermes/node/viewmodel/ServerUiState.kt` -- Add fields for battery optimization status (`isBatteryOptimizationIgnored`, `showBatteryOptimizationPrompt`).
- `app/src/main/java/com/hermes/node/viewmodel/ServerViewModel.kt` -- Add methods to check/refresh battery optimization state, handle exemption requests, and observe auto-start preferences.
- `app/src/main/java/com/hermes/node/ui/screens/SettingsScreen.kt` -- Integrate Battery Optimization Exemption card, status pill, request button, and OEM guidance link.
- `app/src/main/java/com/hermes/node/ui/screens/DashboardScreen.kt` -- Add optional dismissible banner if battery optimization is active while daemon is running or auto-start is enabled.
- `app/src/test/java/com/hermes/node/service/BootReceiverTest.kt` -- Unit tests for `BootReceiver` intent filtering and auto-start condition logic.
- `app/src/test/java/com/hermes/node/service/BatteryOptimizationHelperTest.kt` -- Unit tests for battery optimization checks and intent generation.
- `app/src/test/java/com/hermes/node/viewmodel/ServerViewModelTest.kt` -- Unit tests for battery optimization state updates in ViewModel.

## Tasks & Acceptance

**Execution:**
- [x] `app/src/main/java/com/hermes/node/service/BatteryOptimizationHelper.kt` -- Create battery optimization helper interface and implementation for checking whitelist status and creating exemption intents.
- [x] `app/src/main/java/com/hermes/node/service/BootReceiver.kt` -- Create `BootReceiver` to handle `ACTION_BOOT_COMPLETED` and `ACTION_MY_PACKAGE_REPLACED` with `ConfigRepository` auto-start checks.
- [x] `app/src/main/AndroidManifest.xml` -- Declare `BootReceiver` with `android:exported="true"` and appropriate boot intent filters.
- [x] `app/src/main/java/com/hermes/node/viewmodel/ServerUiState.kt` -- Add `isBatteryOptimizationIgnored: Boolean` and `showBatteryOptimizationPrompt: Boolean`.
- [x] `app/src/main/java/com/hermes/node/viewmodel/ServerViewModel.kt` -- Add battery optimization detection, auto-start toggle hooks, and intent launcher helpers.
- [x] `app/src/main/java/com/hermes/node/ui/screens/SettingsScreen.kt` -- Add Battery Optimization status card, "Request Exemption" action button, and OEM guidance.
- [x] `app/src/main/java/com/hermes/node/ui/screens/DashboardScreen.kt` -- Add battery optimization warning card when daemon runs without whitelist.
- [x] `app/src/test/java/com/hermes/node/service/BootReceiverTest.kt` -- Test `BootReceiver` auto-start behavior on boot intents.
- [x] `app/src/test/java/com/hermes/node/service/BatteryOptimizationHelperTest.kt` -- Test `BatteryOptimizationHelper` intent generation and fallback.
- [x] `app/src/test/java/com/hermes/node/viewmodel/ServerViewModelTest.kt` -- Test battery optimization UI state and auto-start logic.

### Review Findings
- [x] [Review][Patch] Fix Flawed Substring Matching in BatteryOptimizationHelper.getDontKillMyAppUrl [app/src/main/java/com/hermes/node/service/BatteryOptimizationHelper.kt:135]
- [x] [Review][Patch] Eliminate Leaking Coroutine State Collectors in HermesServerService.setupProcessExitListener [app/src/main/java/com/hermes/node/service/HermesServerService.kt:76]
- [x] [Review][Patch] Fix User Dismissal Overwrite on Activity Resume in ServerViewModel [app/src/main/java/com/hermes/node/viewmodel/ServerViewModel.kt:612]
- [x] [Review][Patch] Reduce onDestroy Process Stop Grace Timeout to Prevent Service ANR [app/src/main/java/com/hermes/node/service/HermesServerService.kt:132]
- [x] [Review][Patch] Invoke processController.close() on Service Teardown [app/src/main/java/com/hermes/node/service/HermesServerService.kt:134]
- [x] [Review][Patch] Remove Undeclared LOCKED_BOOT_COMPLETED from BootReceiver Supported Actions [app/src/main/java/com/hermes/node/service/BootReceiver.kt:23]
- [x] [Review][Patch] Add Explicit Intent Action, URI, Flag, and Dispatch Assertions in BatteryOptimizationHelperTest [app/src/test/java/com/hermes/node/service/BatteryOptimizationHelperTest.kt:36]
- [x] [Review][Patch] Add HermesServerService.processState Flow Assertions and Clean Reset in Service Tests [app/src/test/java/com/hermes/node/service/HermesServerServiceTest.kt:34]
- [x] [Review][Patch] Ensure Dashboard Battery Warning Dismiss Button Has Adequate Touch Target [app/src/main/java/com/hermes/node/ui/screens/DashboardScreen.kt:167]

**Acceptance Criteria:**
- Given "Auto-start on boot" is enabled in settings, when device broadcasts `ACTION_BOOT_COMPLETED` or `ACTION_MY_PACKAGE_REPLACED`, then `BootReceiver` initiates `HermesServerService` in foreground.
- Given "Auto-start on boot" is disabled in settings, when device broadcasts `ACTION_BOOT_COMPLETED`, then `BootReceiver` does not start `HermesServerService`.
- Given the app is not on the battery optimization whitelist, when opening Settings or starting the server, then the UI prompts the user with an option to request battery optimization exemption (`ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`).
- Given the user requests exemption, when launched, then Android OS battery optimization dialog or settings is displayed with OEM guidance link.

## Spec Change Log

- **2026-08-26 (Code Review Loop 1)**: Resolved all 9 code review patch findings. Fixed flawed OEM substring matching in `BatteryOptimizationHelper`, eliminated leaking coroutine state collectors in `HermesServerService.setupProcessExitListener`, preserved user prompt dismissal on activity resume in `ServerViewModel`, reduced service `onDestroy` stop timeout to 1500ms and added `processController.close()` teardown, removed undeclared `LOCKED_BOOT_COMPLETED` from `BootReceiver`, added `processState` assertions and test resets in `HermesServerServiceTest`, and increased dismiss button touch target size in `DashboardScreen`. Verified with 100% pass on `./gradlew testDebugUnitTest` and `./gradlew assembleDebug`.

## Design Notes

- **BootReceiver Architecture**: To enable reliable unit testing without full Android framework runtime, `BootReceiver` accepts an optional lambda or interface for starting the service and retrieving `ConfigRepository`.
- **Battery Optimization Helper**: Uses `PowerManager.isIgnoringBatteryOptimizations(packageName)`. If false, creates an intent with `Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` pointing to `package:${context.packageName}`. Also provides URL mapping for vendor-specific aggressiveness (DontKillMyApp).
- **Settings Screen Integration**: Under "Daemon & Remote Access", includes a clear indicator of battery optimization status ("Unrestricted" vs "Restricted/Optimized") with a direct one-tap button to open system dialog.

## Verification

**Commands:**
- `./gradlew testDebugUnitTest` -- expected: All unit tests in `com.hermes.node.service` and `com.hermes.node.viewmodel` pass.
- `./gradlew assembleDebug` -- expected: APK builds cleanly with `BootReceiver` registered in manifest.

**Manual checks (if no CLI):**
- Verify `BootReceiver` class is declared in `AndroidManifest.xml` with `android:exported="true"`.
- Verify `SettingsScreen` renders battery optimization status card with toggle and action button.

## Suggested Review Order

**Battery Optimization Exemption**

- PowerManager whitelist detection and settings exemption intent launcher
  [`BatteryOptimizationHelper.kt:20`](../../app/src/main/java/com/hermes/node/service/BatteryOptimizationHelper.kt#L20)

- Vendor-specific URL mapping for DontKillMyApp guidance
  [`BatteryOptimizationHelper.kt:100`](../../app/src/main/java/com/hermes/node/service/BatteryOptimizationHelper.kt#L100)

**Boot Auto-Start**

- System broadcast receiver starting daemon on boot when enabled in config
  [`BootReceiver.kt:18`](../../app/src/main/java/com/hermes/node/service/BootReceiver.kt#L18)

- Receiver registration with boot completed and package replaced intent filters
  [`AndroidManifest.xml:40`](../../app/src/main/AndroidManifest.xml#L40)

**UI & Lifecycle Integration**

- Battery optimization state and prompt flags in UI model
  [`ServerUiState.kt:49`](../../app/src/main/java/com/hermes/node/viewmodel/ServerUiState.kt#L49)

- Exemption request handling, state checks, and auto-start prompt logic
  [`ServerViewModel.kt:605`](../../app/src/main/java/com/hermes/node/viewmodel/ServerViewModel.kt#L605)

- Dynamic battery optimization status refresh on Activity resume
  [`MainActivity.kt:35`](../../app/src/main/java/com/hermes/node/MainActivity.kt#L35)

- Settings screen battery exemption status card, request button, and OEM link
  [`SettingsScreen.kt:456`](../../app/src/main/java/com/hermes/node/ui/screens/SettingsScreen.kt#L456)

- Dashboard screen dismissible battery optimization warning banner
  [`DashboardScreen.kt:123`](../../app/src/main/java/com/hermes/node/ui/screens/DashboardScreen.kt#L123)

**Automated Test Suite**

- Unit tests for Doze detection, intent launchers, and OEM resolution
  [`BatteryOptimizationHelperTest.kt:20`](../../app/src/test/java/com/hermes/node/service/BatteryOptimizationHelperTest.kt#L20)

- Unit tests for boot receiver gating and exception safety
  [`BootReceiverTest.kt:20`](../../app/src/test/java/com/hermes/node/service/BootReceiverTest.kt#L20)

- Unit tests for ViewModel battery optimization state and prompts
  [`ServerViewModelTest.kt:1008`](../../app/src/test/java/com/hermes/node/viewmodel/ServerViewModelTest.kt#L1008)

