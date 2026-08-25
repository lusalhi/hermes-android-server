# Epic 3 Context: Always-On Background Daemon, WakeLock Management & Boot Auto-Start

<!-- Compiled from planning artifacts. Edit freely. Regenerate with compile-epic-context if planning docs change. -->

## Goal

Enable the Hermes Agent to run continuously 24/7 as an unkillable background server on Android devices by encapsulating the POSIX runtime in an Android Foreground Service with CPU WakeLock management, robust process lifecycle controls (graceful SIGTERM with 5s SIGKILL timeout), battery optimization whitelisting prompts, and device boot auto-start via RECEIVE_BOOT_COMPLETED.

## Stories

- Story 3.1: Android Foreground Service with Partial WakeLock
- Story 3.2: Native Child Process Controller with Graceful Signal Handling
- Story 3.3: Battery Optimization Exemption & Boot Completed Auto-Start

## Requirements & Constraints

- Host the master daemon inside an Android `ForegroundService` (`HermesServerService`) with an ongoing, non-dismissible notification displaying live server status, active gateways, and quick actions (Stop / Open Dashboard).
- Acquire and maintain `PowerManager.PARTIAL_WAKE_LOCK` while the daemon is running to prevent OS Doze mode / CPU throttling when the screen locks.
- Master Start/Stop/Restart daemon controls that manage the native sub-process with graceful `SIGTERM` signal and a strict 5-second fallback to `SIGKILL` before releasing WakeLock.
- Battery optimization whitelisting detection (`PowerManager.isIgnoringBatteryOptimizations`) with prompt launcher for `ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` and OEM-specific DontKillMyApp guidance.
- System boot auto-start receiver (`RECEIVE_BOOT_COMPLETED`) to trigger `HermesServerService` on device boot when enabled in user settings.
- Continuous background uptime > 72 hours without unexpected termination when plugged into AC power (NFR-2).
- Zero-terminal friction: 100% of daemon lifecycle management handled via UI and system service (NFR-3).

## Technical Decisions

- **Foreground Service & WakeLock (AD-3)**: `com.hermes.node.service.HermesServerService` orchestrates daemon lifecycle, binds with `ServerViewModel` via `IBinder` / Kotlin `StateFlow`, manages notification channel (`hermes_server_channel`), and holds `PowerManager.WakeLock`.
- **Sub-Process Controller**: Decouple process lifecycle into dedicated `com.hermes.node.engine.ProcessController` (or `ProcessManager`) handling `ProcessBuilder`, POSIX signal dispatch (`SIGTERM`/`SIGKILL`), PID tracking, and exit code capture rather than coupling process management directly to `ServerViewModel` (per Epic 2 retro item 1).
- **Notification Management**: `com.hermes.node.service.NotificationHelper` manages notification updates, action intents (Stop Daemon, Launch Activity), and foreground service type `dataSync` / `specialUse` (Android 14+ compatible).
- **Boot Receiver**: `com.hermes.node.service.BootReceiver` listens for `ACTION_BOOT_COMPLETED` and `ACTION_MY_PACKAGE_REPLACED`, checks auto-start preference in `ConfigRepository`, and invokes `ContextCompat.startForegroundService`.
- **Battery Optimization Helper**: Detects battery optimization state and provides intent helper for battery optimization exemption flow.

## UX & Interaction Patterns

- Dashboard Start/Stop master toggle with live state transition feedback (`STOPPED` -> `STARTING` -> `RUNNING` -> `STOPPING` -> `STOPPED`).
- Non-dismissible status bar notification showing server state with a quick action to "Stop" or tap to open `MainActivity`.
- In-app warning banner / settings prompt if battery optimizations are active, directing user to whitelist the app.

## Cross-Story Dependencies

- Builds on Epic 2 userland binaries (`/data/data/com.hermes.node/files/usr/bin/proot`, `python3`) and `hermes.json` configuration.
- Story 3.1 sets up `HermesServerService`, notification, and WakeLock lifecycle.
- Story 3.2 integrates `ProcessController` within `HermesServerService` to execute and terminate the child process safely.
- Story 3.3 wires `BootReceiver` and battery exemption checks into settings and service startup.
- Feeds into Epic 4 (which attaches coroutine log streaming and ring buffer to the running process).
