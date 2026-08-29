---
title: '4-3-real-time-hardware-telemetry-device-health-monitor'
type: 'feature'
created: '2026-08-28'
status: 'done'
baseline_commit: 'a6fb0cd28a4331c1a94aae31371cd7e18ec272a2'
review_loop_iteration: 0
context:
  - _bmad-output/planning-artifacts/architecture/architecture-hermes-android-server-2026-08-24/ARCHITECTURE-SPINE.md
  - _bmad-output/planning-artifacts/prds/prd-hermes-android-server-2026-08-24/prd.md
  - _bmad-output/implementation-artifacts/epic-4-context.md
---

<frozen-after-approval reason="human-owned intent — do not modify unless human renegotiates">

## Intent

**Problem:** Users running autonomous AI agent workloads on dedicated Android hardware lack real-time visibility into physical device vitals (CPU load, memory pressure, battery drain, and thermal throttling), risking silent process termination or hardware overheating.

**Approach:** Implement a non-blocking `TelemetryMonitor` and `SystemTelemetryCollector` in `com.hermes.node.engine` gathering CPU %, RAM consumption (used/total MB), battery level %, charging state, and battery temperature (°C) on a 2-second polling loop, streaming to Dashboard metric cards with adaptive thermal/load warning colors and pausing polling in the background to conserve power.

## Boundaries & Constraints

**Always:**
- Sample system hardware telemetry asynchronously on `Dispatchers.Default` / `Dispatchers.IO` without blocking the main or UI threads.
- Pause telemetry polling loops when the app is backgrounded or inactive to conserve battery and CPU resources.
- Safely handle Android security sandbox restrictions on `/proc/stat` and battery broadcasts with resilient fallbacks and zero crash tolerance.
- Display telemetry cards adhering to the cyber-terminal theme (`JetBrains Mono`, `DarkSurface`, `HermesCyan`, `StatusStarting`, `StatusError`).
- Maintain UDF (Unidirectional Data Flow) by exposing telemetry updates via `ServerUiState` immutable StateFlow.

**Ask First:**
- Adding historical telemetry time-series logging to persistent disk storage or SQLite databases.
- Integrating OEM-specific proprietary thermal or fan control APIs beyond Android standard `BatteryManager` / `ActivityManager`.

**Never:**
- Never perform file I/O or broadcast receiver registration on the main/UI thread.
- Never poll telemetry at intervals faster than 1 second to avoid CPU churn.
- Never crash or throw unhandled exceptions if `/proc` files or battery intents are unavailable/null.

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| Live Telemetry Polling | App in foreground, timer triggers (every 2s) | Emits updated `DeviceTelemetry` (CPU %, RAM MB, Battery %, Temp °C) | If sample fails, retain previous valid reading |
| Battery Status Broadcast | `ACTION_BATTERY_CHANGED` sticky intent with temp=345, level=85, charging | `batteryPercent = 85`, `isCharging = true`, `batteryTemperatureCelsius = 34.5f` | Default to 100%, false, 0.0f if intent is null |
| High Thermal Threshold | Battery temp > 38.0 °C (warning) or > 45.0 °C (critical) | Dashboard temperature card tints warning (`StatusStarting`) or critical (`StatusError`) | Graceful clamp and color transition |
| Restricted `/proc/stat` | Android sandbox denies `/proc/stat` read | CPU calculation falls back to process elapsed CPU delta or safe estimate | Return valid non-negative float without throwing |
| App Backgrounded | Android lifecycle changes to `ON_PAUSE` / `ON_STOP` | Polling coroutine pauses; no background wakeups occur for telemetry | Resume immediately on `ON_RESUME` |
| Server Stopped vs Running | Server daemon stopped, UI active | Hardware telemetry continues live updates while `uptimeSeconds` pauses | Uptime only increments when server is `RUNNING` |

</frozen-after-approval>

## Code Map

- `app/src/main/java/com/hermes/node/engine/TelemetryMonitor.kt` -- Core telemetry model `DeviceTelemetry`, `TelemetryCollector` interface, `SystemTelemetryCollector` Android hardware reader, and `TelemetryMonitor` coroutine polling engine.
- `app/src/main/java/com/hermes/node/viewmodel/ServerUiState.kt` -- Telemetry fields in `ServerUiState`: `totalMemoryMb`, `batteryPercent`, `isCharging`, `batteryTemperatureCelsius`.
- `app/src/main/java/com/hermes/node/viewmodel/ServerViewModel.kt` -- Integration of `TelemetryCollector`/`TelemetryMonitor`, lifecycle start/stop/pause/resume hooks, 2s polling orchestration, and UI state mapping.
- `app/src/main/java/com/hermes/node/ui/components/MetricCard.kt` -- Enhanced `MetricCard` with dynamic threshold color-coding and battery status formatting helpers.
- `app/src/main/java/com/hermes/node/ui/screens/DashboardScreen.kt` -- Full 5-card telemetry grid (CPU, RAM, Battery, Temperature, Uptime) and lifecycle-aware observer pausing polling in background.
- `app/src/main/java/com/hermes/node/MainActivity.kt` -- Inject telemetry components into `ServerViewModel` factory and forward activity lifecycle events if required.
- `app/src/main/java/com/hermes/node/ui/navigation/HermesNavGraph.kt` -- Update ViewModel factory with `SystemTelemetryCollector`.
- `app/src/test/java/com/hermes/node/engine/TelemetryMonitorTest.kt` -- Unit tests for telemetry collector parsing, CPU calculation formulas, temperature conversion, and error resiliency.
- `app/src/test/java/com/hermes/node/viewmodel/ServerViewModelTest.kt` -- Unit tests verifying telemetry lifecycle, background pause/resume, and UI state updates.

## Tasks & Acceptance

**Execution:**
- [x] `app/src/main/java/com/hermes/node/engine/TelemetryMonitor.kt` -- Implement `DeviceTelemetry` data class, `TelemetryCollector` interface, `SystemTelemetryCollector` (MemoryInfo, BatteryManager, CPU delta), and `TelemetryMonitor` polling flow.
- [x] `app/src/main/java/com/hermes/node/viewmodel/ServerUiState.kt` -- Add `totalMemoryMb`, `batteryPercent`, `isCharging`, `batteryTemperatureCelsius` to `ServerUiState`.
- [x] `app/src/main/java/com/hermes/node/ui/components/MetricCard.kt` -- Enhance `MetricCard` with threshold color helpers (`getThermalColor`, `getCpuColor`) and formatting utilities.
- [x] `app/src/main/java/com/hermes/node/viewmodel/ServerViewModel.kt` -- Integrate `TelemetryCollector`, implement 2-second background-safe polling loop, and expose lifecycle `pauseTelemetry()`/`resumeTelemetry()`.
- [x] `app/src/main/java/com/hermes/node/ui/screens/DashboardScreen.kt` -- Update telemetry section with CPU, RAM, Battery, Temperature, and Uptime cards with lifecycle-aware pause/resume binding.
- [x] `app/src/main/java/com/hermes/node/MainActivity.kt` & `app/src/main/java/com/hermes/node/ui/navigation/HermesNavGraph.kt` -- Wire `SystemTelemetryCollector` in ViewModel factories.
- [x] `app/src/test/java/com/hermes/node/engine/TelemetryMonitorTest.kt` -- Unit test telemetry collection logic, battery calculation, and CPU delta formulas.
- [x] `app/src/test/java/com/hermes/node/viewmodel/ServerViewModelTest.kt` -- Update ViewModel tests for telemetry flow and lifecycle controls.

**Acceptance Criteria:**
- Given the Dashboard screen in foreground, when viewing telemetry cards, then CPU %, RAM used (MB), Uptime (HH:MM:SS), battery level % (with charging indicator ⚡), and battery temperature (°C) update dynamically every 2 seconds.
- Given the application transitions to the background (`ON_PAUSE`/`ON_STOP`), then telemetry polling coroutines are paused to prevent battery drain.
- Given high CPU load (>75%) or elevated battery temperature (>38°C), then corresponding metric card icons/values adaptively change to warning/critical status colors.
- Given unit tests in `TelemetryMonitorTest` and `ServerViewModelTest`, 100% of telemetry calculations and state transitions pass cleanly.

### Review Findings

- [x] [Review][Patch] Fix stale unit test assertions expecting removed fake metrics [app/src/test/java/com/hermes/node/viewmodel/ServerViewModelTest.kt:82-83, 101-102]
- [x] [Review][Patch] Remove unnecessary reset of cpuUsagePercent and memoryUsageMb in onRepairRuntime [app/src/main/java/com/hermes/node/viewmodel/ServerViewModel.kt:246-247]
- [x] [Review][Patch] Prevent 32-bit integer overflow when calculating battery percentage with large level values [app/src/main/java/com/hermes/node/engine/TelemetryMonitor.kt:461-463]
- [x] [Review][Patch] Enforce minimum 1000ms polling interval floor in TelemetryMonitor to prevent CPU churn [app/src/main/java/com/hermes/node/engine/TelemetryMonitor.kt:539, 573]
- [x] [Review][Patch] Add synchronized guards to TelemetryMonitor start, stop, pause, and resume [app/src/main/java/com/hermes/node/engine/TelemetryMonitor.kt:557-600]
- [x] [Review][Patch] Reset previous CPU baseline timestamp when transitioning to process CPU fallback in SystemTelemetryCollector [app/src/main/java/com/hermes/node/engine/TelemetryMonitor.kt:415-438]
- [x] [Review][Patch] Clean up unused private isTelemetryPaused variable in ServerViewModel [app/src/main/java/com/hermes/node/viewmodel/ServerViewModel.kt:1020]
- [x] [Review][Patch] Add unit test coverage for edge cases, collector fallbacks, and boundary temperatures [app/src/test/java/com/hermes/node/engine/TelemetryMonitorTest.kt]
- [x] [Review][Defer] Child Node subprocess CPU tracking when running under restricted /proc/stat Android sandbox [app/src/main/java/com/hermes/node/engine/TelemetryMonitor.kt:415] — deferred, pre-existing
- [x] [Review][Defer] Adaptive status warning icon/color when battery level is critically low [app/src/main/java/com/hermes/node/ui/screens/DashboardScreen.kt:434] — deferred, pre-existing

## Spec Change Log

_None._

## Design Notes

- **Battery Data Extraction:**
  ```kotlin
  val batteryIntent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
  val rawTemp = batteryIntent?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) ?: 0
  val tempCelsius = rawTemp / 10.0f
  val level = batteryIntent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
  val scale = batteryIntent?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
  val batteryPercent = if (level >= 0 && scale > 0) (level * 100 / scale) else 100
  val status = batteryIntent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
  val isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
  ```
- **RAM Calculation:**
  ```kotlin
  val memInfo = ActivityManager.MemoryInfo()
  activityManager.getMemoryInfo(memInfo)
  val totalMb = memInfo.totalMem / (1024 * 1024)
  val availMb = memInfo.availMem / (1024 * 1024)
  val usedMb = (totalMb - availMb).coerceAtLeast(0L)
  ```
- **Thermal & CPU Color Thresholds:**
  - Temperature: `< 38°C` -> `HermesCyan` / `StatusRunning`, `38°C–45°C` -> `StatusStarting` (Amber), `> 45°C` -> `StatusError` (Red)
  - CPU: `< 75%` -> `HermesCyan`, `75%–90%` -> `StatusStarting`, `> 90%` -> `StatusError`

## Verification

**Commands:**
- `./gradlew test --tests "com.hermes.node.engine.TelemetryMonitorTest"` -- expected: 100% tests pass
- `./gradlew test --tests "com.hermes.node.viewmodel.ServerViewModelTest"` -- expected: 100% tests pass
- `./gradlew test` -- expected: All unit tests pass
- `./gradlew assembleDebug` -- expected: Build succeeds producing `app-debug.apk`

**Manual checks (if no CLI):**
- Verify Dashboard renders CPU, RAM, Battery (with ⚡ when charging), Temperature (°C), and Uptime cards.

## Suggested Review Order

**Core Telemetry Engine & Hardware Collection**

- Non-blocking hardware collector gathering RAM, battery broadcasts, and CPU delta ticks
  [`TelemetryMonitor.kt:60`](../../app/src/main/java/com/hermes/node/engine/TelemetryMonitor.kt#L60)

- Core telemetry data model and collector contract
  [`TelemetryMonitor.kt:29`](../../app/src/main/java/com/hermes/node/engine/TelemetryMonitor.kt#L29)

- Coroutine polling engine with lifecycle pause and resume controls
  [`TelemetryMonitor.kt:293`](../../app/src/main/java/com/hermes/node/engine/TelemetryMonitor.kt#L293)

**State Flow & ViewModel Management**

- Telemetry fields added to immutable UI state
  [`ServerUiState.kt:27`](../../app/src/main/java/com/hermes/node/viewmodel/ServerUiState.kt#L27)

- Background-safe 2s polling orchestration and lifecycle hooks
  [`ServerViewModel.kt:795`](../../app/src/main/java/com/hermes/node/viewmodel/ServerViewModel.kt#L795)

**Cyber-Terminal UI & Dynamic Threshold Colors**

- Metric card threshold color mappers for CPU load and thermal vitals
  [`MetricCard.kt:28`](../../app/src/main/java/com/hermes/node/ui/components/MetricCard.kt#L28)

- 5-card telemetry grid with lifecycle-aware background pause/resume
  [`DashboardScreen.kt:378`](../../app/src/main/java/com/hermes/node/ui/screens/DashboardScreen.kt#L378)

- Dependency wiring in MainActivity and Navigation Graph
  [`MainActivity.kt:37`](../../app/src/main/java/com/hermes/node/MainActivity.kt#L37)
  [`HermesNavGraph.kt:62`](../../app/src/main/java/com/hermes/node/ui/navigation/HermesNavGraph.kt#L62)

**Test Suite Verification**

- Unit tests for hardware reading, CPU delta formulas, and temperature conversion
  [`TelemetryMonitorTest.kt:26`](../../app/src/test/java/com/hermes/node/engine/TelemetryMonitorTest.kt#L26)

- Unit tests for ViewModel polling lifecycle and state transitions
  [`ServerViewModelTest.kt:1344`](../../app/src/test/java/com/hermes/node/viewmodel/ServerViewModelTest.kt#L1344)

