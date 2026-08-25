---
title: '3-1-android-foreground-service-with-partial-wakelock'
type: 'feature'
created: '2026-08-25'
status: 'done'
baseline_commit: 'ddd7024ec526043ea52bfbf430519d03a9eb9879'
review_loop_iteration: 0
context: []
---

<frozen-after-approval reason="human-owned intent — do not modify unless human renegotiates">

## Intent

**Problem:** The Hermes AI agent daemon must run 24/7 in the background without being throttled, suspended, or killed by Android Doze mode, screen-off power saving, or OS memory management.

**Approach:** Implement `HermesServerService` as an Android `ForegroundService` acquiring a `PowerManager.PARTIAL_WAKE_LOCK`, posting an ongoing non-dismissible notification via `NotificationHelper` with live server status and a quick "Stop" action, and synchronizing state with `ServerViewModel`.

## Boundaries & Constraints

**Always:**
- Acquire `PowerManager.PARTIAL_WAKE_LOCK` when the foreground service starts and release it cleanly when the service stops or is destroyed.
- Set ongoing notification flag (`setOngoing(true)`) so the notification cannot be swiped away while the daemon is actively running.
- Safely check `wakeLock.isHeld` before releasing to prevent `WakeLock under-locked` runtime exceptions.
- Provide a non-blocking notification channel (`hermes_server_channel`) with `IMPORTANCE_LOW` to avoid intrusive alert chimes.
- Support Android 14+ (API 34+) foreground service requirements (`foregroundServiceType="specialUse"` with `PROPERTY_SPECIAL_USE_FGS_SUBTYPE` property).
- Ensure all service and WakeLock operations are non-blocking on the main UI thread.

**Ask First:**
- Modifying Android permissions beyond existing `FOREGROUND_SERVICE`, `WAKE_LOCK`, and `POST_NOTIFICATIONS`.

**Never:**
- Never keep the `WakeLock` acquired when the server is in `STOPPED` or `ERROR` state.
- Never leave orphan foreground notifications active after the service has terminated.
- Never block UI thread with heavy service operations or synchronous IPC.

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| Start Server (Happy Path) | User taps START SERVER or sends `ACTION_START` | `HermesServerService` starts in foreground, acquires WakeLock (`isHeld == true`), posts ongoing notification with "Stop" action, UI updates to `RUNNING` | Logs error if service fails to start |
| Stop Server (Happy Path) | User taps STOP SERVER or sends `ACTION_STOP` | WakeLock is released (`isHeld == false`), ongoing notification is dismissed, service stops (`stopSelf()`), UI updates to `STOPPED` | Safely handles stop when already stopped |
| Notification Stop Action | User taps "Stop" action on status bar notification | `PendingIntent` fires `ACTION_STOP` to `HermesServerService`, releases WakeLock, stops service, and updates `ServerViewModel` state to `STOPPED` | N/A |
| Tap Notification Body | User taps notification body | `PendingIntent` brings `MainActivity` to the foreground via `FLAG_ACTIVITY_SINGLE_TOP` | N/A |
| Service Destroyed Unexpectedly | OS invokes `onDestroy()` while running | Defensively checks `wakeLock.isHeld` and releases WakeLock, cleans up notification | Catches exceptions and logs warning |
| Missing Notification Permission (Android 13+) | `POST_NOTIFICATIONS` not granted | Service starts in foreground safely without crashing; notification post failure handled gracefully | Fallback without crashing service |

</frozen-after-approval>

## Code Map

- `app/src/main/AndroidManifest.xml` -- Declare `HermesServerService` with `foregroundServiceType="specialUse"` and `PROPERTY_SPECIAL_USE_FGS_SUBTYPE` metadata.
- `app/src/main/java/com/hermes/node/service/NotificationHelper.kt` -- Create notification channel, build ongoing notification with status text, `MainActivity` PendingIntent, and "Stop" action button.
- `app/src/main/java/com/hermes/node/service/HermesServerService.kt` -- ForegroundService implementation managing WakeLock lifecycle, notification dispatch, `ACTION_START`/`ACTION_STOP` intent commands, and service status StateFlow / Binder.
- `app/src/main/java/com/hermes/node/service/WakeLockManager.kt` -- Encapsulate `PowerManager.PARTIAL_WAKE_LOCK` acquisition and safe release with testable abstraction.
- `app/src/main/java/com/hermes/node/viewmodel/ServerViewModel.kt` -- Wire start/stop server controls to trigger `HermesServerService` and reflect running/stopped status in `ServerUiState`.
- `app/src/main/java/com/hermes/node/ui/navigation/HermesNavGraph.kt` -- Pass Application context or service callbacks into `ServerViewModel` factory.
- `app/src/test/java/com/hermes/node/service/NotificationHelperTest.kt` -- Test notification channel creation, notification attributes, ongoing flag, and action intents.
- `app/src/test/java/com/hermes/node/service/WakeLockManagerTest.kt` -- Test WakeLock acquisition, release, idempotency, and under-lock prevention.
- `app/src/test/java/com/hermes/node/service/HermesServerServiceTest.kt` -- Test service lifecycle, start/stop intent handling, and foreground notification posting.
- `app/src/test/java/com/hermes/node/viewmodel/ServerViewModelTest.kt` -- Test ViewModel interaction with service start/stop and state synchronization.

## Tasks & Acceptance

**Execution:**
- [x] `app/src/main/java/com/hermes/node/service/WakeLockManager.kt` -- Implement `WakeLockManager` wrapping `PowerManager.WakeLock` with safe acquire/release and testable interface.
- [x] `app/src/main/java/com/hermes/node/service/NotificationHelper.kt` -- Implement `NotificationHelper` managing `hermes_server_channel` channel creation and ongoing notification builder.
- [x] `app/src/main/java/com/hermes/node/service/HermesServerService.kt` -- Implement `HermesServerService` with foreground service startup, WakeLock acquisition, ongoing notification, and `ACTION_START`/`ACTION_STOP` commands.
- [x] `app/src/main/AndroidManifest.xml` -- Register `HermesServerService` with `foregroundServiceType="specialUse"` and required subtype property.
- [x] `app/src/main/java/com/hermes/node/viewmodel/ServerViewModel.kt` -- Integrate service start/stop invocations into `onStartServer` / `onStopServer` and observe service running state.
- [x] `app/src/main/java/com/hermes/node/ui/navigation/HermesNavGraph.kt` -- Supply application context to ViewModel to enable service intent launching.
- [x] `app/src/test/java/com/hermes/node/service/WakeLockManagerTest.kt` -- Unit test WakeLock acquisition, release, idempotency, and error handling.
- [x] `app/src/test/java/com/hermes/node/service/NotificationHelperTest.kt` -- Unit test notification channel configuration, content title/text, ongoing flag, and actions.
- [x] `app/src/test/java/com/hermes/node/service/HermesServerServiceTest.kt` -- Unit test `HermesServerService` lifecycle and command handling.
- [x] `app/src/test/java/com/hermes/node/viewmodel/ServerViewModelTest.kt` -- Update ViewModel tests for service integration and state flow assertions.

**Acceptance Criteria:**
- Given the user taps START SERVER on the Dashboard, when `HermesServerService` starts as a Foreground Service, then `PowerManager.PARTIAL_WAKE_LOCK` is acquired and an ongoing notification displays live status.
- Given the daemon is actively running, when inspecting the notification in the Android status bar, then the notification has `ongoing = true` and cannot be swiped away.
- Given the running server with active WakeLock, when the user taps "Stop" on the notification or STOP SERVER on the Dashboard, then the WakeLock is safely released, the notification is dismissed, and the service terminates.

## Design Notes

- **Android 14+ Foreground Service Requirements**: In API 34+, Android mandates that every foreground service declare its type. We use `specialUse` with `<property android:name="android.app.PROPERTY_SPECIAL_USE_FGS_SUBTYPE" android:value="Hermes Node AI Agent Daemon" />` in `AndroidManifest.xml` and pass `ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE` to `startForeground`.
- **WakeLock Safe Wrapper**: `WakeLockManager` wraps `PowerManager.WakeLock` with `setReferenceCounted(false)`. Calling `release()` checks `isHeld` first to prevent `RuntimeException` from mismatched releases.
- **Service-ViewModel Communication**: `HermesServerService` maintains a static/companion or binder `StateFlow<Boolean>` (`isRunning`) and helper functions `start(context)` / `stop(context)` so UI and ViewModel can trigger commands and observe real service state reliably.

## Verification

**Commands:**
- `./gradlew test` -- expected: `BUILD SUCCESSFUL` with 100% unit tests passing across all test suites.
- `./gradlew assembleDebug` -- expected: `BUILD SUCCESSFUL` generating `app-debug.apk`.

## Suggested Review Order

**Foreground Service & System Integration**

- Master entry point for Android Foreground Service and API 34+ type handling
  [`HermesServerService.kt:16`](../../app/src/main/java/com/hermes/node/service/HermesServerService.kt#L16)

- AndroidManifest service declaration with `specialUse` type and metadata
  [`AndroidManifest.xml:27`](../../app/src/main/AndroidManifest.xml#L27)

**WakeLock Management & Safety**

- Safe WakeLock wrapper preventing under-locked runtime exceptions and leaking
  [`WakeLockManager.kt:48`](../../app/src/main/java/com/hermes/node/service/WakeLockManager.kt#L48)

**Ongoing Notification**

- Notification channel setup, non-dismissible ongoing flag, and Stop action
  [`NotificationHelper.kt:15`](../../app/src/main/java/com/hermes/node/service/NotificationHelper.kt#L15)

**ViewModel & UI State Synchronization**

- Bi-directional service state synchronization and start/stop intent triggers
  [`ServerViewModel.kt:359`](../../app/src/main/java/com/hermes/node/viewmodel/ServerViewModel.kt#L359)

- Dependency wiring in Jetpack Compose navigation graph
  [`HermesNavGraph.kt:50`](../../app/src/main/java/com/hermes/node/ui/navigation/HermesNavGraph.kt#L50)

**Unit Verification Test Suites**

- Comprehensive service lifecycle, WakeLock, and command routing tests
  [`HermesServerServiceTest.kt:37`](../../app/src/test/java/com/hermes/node/service/HermesServerServiceTest.kt#L37)

- WakeLock acquisition, idempotency, and concurrency unit tests
  [`WakeLockManagerTest.kt:30`](../../app/src/test/java/com/hermes/node/service/WakeLockManagerTest.kt#L30)

- Notification construction and ongoing attribute validation
  [`NotificationHelperTest.kt:14`](../../app/src/test/java/com/hermes/node/service/NotificationHelperTest.kt#L14)

- ViewModel reactive service coordination tests
  [`ServerViewModelTest.kt:813`](../../app/src/test/java/com/hermes/node/viewmodel/ServerViewModelTest.kt#L813)
