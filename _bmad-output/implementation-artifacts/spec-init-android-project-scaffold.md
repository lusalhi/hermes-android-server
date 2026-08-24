---
title: 'Initialize Android Project Scaffold & Jetpack Compose UI'
type: 'feature'
created: '2026-08-24'
status: 'done'
baseline_commit: 'a4a5c2ec8a7d36ebd05418296837fbd378bb4548'
review_loop_iteration: 0
context:
  - _bmad-output/planning-artifacts/architecture/architecture-hermes-android-server-2026-08-24/ARCHITECTURE-SPINE.md
  - _bmad-output/planning-artifacts/prds/prd-hermes-android-server-2026-08-24/prd.md
---

<frozen-after-approval reason="human-owned intent — do not modify unless human renegotiates">

## Intent

**Problem:** Hermes Android Server requires a clean, production-grade Android Gradle project foundation and Jetpack Compose UI shell before daemon services, native processes, and runtime sandboxes can be wired.

**Approach:** Scaffold a multi-layer Android project (Gradle wrapper, Kotlin 2.0 / Compose BOM, Android 14 / SDK 34) with Material 3 theming, Navigation bar (Dashboard, Logs, Settings), and Unidirectional Data Flow (`ServerUiState`, `ServerViewModel`).

## Boundaries & Constraints

**Always:**
- Use pure Kotlin with Jetpack Compose and Material 3 components (AD-1).
- Set `minSdk = 28` (Android 9 Pie) and `targetSdk = 34` / `compileSdk = 34` with Java 17 compatibility.
- Ensure `./gradlew assembleDebug` and `./gradlew test` compile and pass cleanly against local SDK `/opt/android-sdk`.
- Follow Unidirectional Data Flow (UDF) where `ServerViewModel` exposes immutable `StateFlow<ServerUiState>` to Compose UI.

**Ask First:**
- Introducing external non-AndroidX third-party UI libraries or heavy DI frameworks (e.g. Hilt) if standard lightweight constructor injection / AndroidX ViewModel suffices for the scaffold.

**Never:**
- Do not introduce Flutter, React Native, or cross-platform web bridges.
- Do not stub core build files with broken dependencies or unresolved SDK references.

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| Launch App (Initial State) | App opens with no active service | Dashboard shows `STOPPED` status badge, Start button enabled, empty/sample log stream | Gracefully handle missing persisted config with defaults |
| Tab Navigation | User taps Dashboard, Logs, Settings tabs | `NavHost` transitions smoothly between screens without re-instantiating ViewModel | Preserve UI state across tab switches |
| Server State Transition Simulation | ViewModel receives toggle event | State transitions to `STARTING` -> `RUNNING` or `STOPPING` -> `STOPPED` in state flow | Display error state in UI if state transition emits failure |
| Log Viewer Stream | Logs emitted to UI state | LogsScreen displays timestamped entries with terminal-like monospaced styling | Handle empty log buffer gracefully with placeholder |

</frozen-after-approval>

## Code Map

- `settings.gradle.kts` -- Defines root project name and repository dependency resolution management.
- `build.gradle.kts` -- Root build configuration applying Android Application and Kotlin Android/Compose plugins.
- `gradle.properties` -- JVM heap allocation, AndroidX flags, and parallel build settings.
- `local.properties` -- Configures `sdk.dir=/opt/android-sdk`.
- `gradle/wrapper/gradle-wrapper.properties` -- Configures Gradle 8.9 wrapper distribution.
- `app/build.gradle.kts` -- Module-level Gradle configuration specifying `com.hermes.node`, SDK 34, Compose compiler, AndroidX dependencies, Coroutines, Material 3, and test libraries.
- `app/src/main/AndroidManifest.xml` -- App manifest with application entry point (`MainActivity`), foreground service permissions, wake lock permissions, and theme settings.
- `app/src/main/java/com/hermes/node/MainActivity.kt` -- Main Activity hosting `HermesApp` composable inside `HermesTheme`.
- `app/src/main/java/com/hermes/node/ui/theme/Theme.kt` -- Material 3 dark/light dynamic color theme definitions for Hermes Node.
- `app/src/main/java/com/hermes/node/ui/theme/Color.kt` -- Theme color palette constants (deep background, primary accents, terminal greens, status colors).
- `app/src/main/java/com/hermes/node/ui/theme/Type.kt` -- Typography configuration including monospaced terminal fonts for logs.
- `app/src/main/java/com/hermes/node/ui/navigation/Screen.kt` -- Sealed class / enum defining navigation routes (`Dashboard`, `Logs`, `Settings`).
- `app/src/main/java/com/hermes/node/ui/navigation/HermesNavGraph.kt` -- Top-level `Scaffold` with `NavigationBar` bottom bar and `NavHost`.
- `app/src/main/java/com/hermes/node/ui/screens/DashboardScreen.kt` -- Dashboard UI displaying server status badge, start/stop toggle, CPU/RAM/Uptime metrics, and agent summary.
- `app/src/main/java/com/hermes/node/ui/screens/LogsScreen.kt` -- Real-time log console screen with autoscroll toggle, clear, and terminal styling.
- `app/src/main/java/com/hermes/node/ui/screens/SettingsScreen.kt` -- Settings screen with provider selection (Nous Portal, OpenRouter, OpenAI, Custom), API keys, bot tokens, and autostart toggle.
- `app/src/main/java/com/hermes/node/viewmodel/ServerUiState.kt` -- Data contracts for server status enum (`STOPPED`, `STARTING`, `RUNNING`, `ERROR`), resource metrics, and configuration state.
- `app/src/main/java/com/hermes/node/viewmodel/ServerViewModel.kt` -- State management ViewModel exposing `StateFlow<ServerUiState>` and event handlers (`onStartServer`, `onStopServer`, `onClearLogs`).
- `app/src/test/java/com/hermes/node/viewmodel/ServerViewModelTest.kt` -- Unit tests validating ViewModel initial state, state transitions, and event processing.

## Tasks & Acceptance

**Execution:**
- [x] `settings.gradle.kts` -- Create root project settings with Google and Maven Central repos -- Establish root project name `hermes-android-server`.
- [x] `build.gradle.kts` -- Create root Gradle build script with plugin management (AGP 8.4.2, Kotlin 2.0.0) -- Centralize build toolchain versions.
- [x] `gradle.properties` -- Configure AndroidX and JVM arguments -- Enable fast build and AndroidX namespaces.
- [x] `local.properties` -- Configure SDK directory pointing to `/opt/android-sdk` -- Enable CLI compilation in environment.
- [x] `gradle/wrapper/gradle-wrapper.properties` -- Configure Gradle 8.9 wrapper properties -- Ensure reproducible gradle wrapper.
- [x] `app/build.gradle.kts` -- Configure app module dependencies (Compose, Material 3, Navigation, ViewModel, Coroutines, JUnit) -- Define build dependencies and Android 34 compilation targets.
- [x] `app/src/main/AndroidManifest.xml` -- Declare `MainActivity`, permissions (`FOREGROUND_SERVICE`, `WAKE_LOCK`, `INTERNET`, `POST_NOTIFICATIONS`) -- Define Android manifest foundation.
- [x] `app/src/main/java/com/hermes/node/ui/theme/Color.kt` -- Define color palette for Dark and Light Material 3 themes with status indicators -- Establish visual design tokens.
- [x] `app/src/main/java/com/hermes/node/ui/theme/Type.kt` -- Define Typography styles including code/monospaced styles -- Support terminal log styling.
- [x] `app/src/main/java/com/hermes/node/ui/theme/Theme.kt` -- Create `HermesTheme` composable with dark/light mode support -- Provide theme wrapper.
- [x] `app/src/main/java/com/hermes/node/viewmodel/ServerUiState.kt` -- Define immutable data models for server status, log entries, system stats, and config -- Formalize UDF state contracts.
- [x] `app/src/main/java/com/hermes/node/viewmodel/ServerViewModel.kt` -- Implement `ServerViewModel` with `MutableStateFlow<ServerUiState>` -- Provide central UI state coordinator.
- [x] `app/src/main/java/com/hermes/node/ui/navigation/Screen.kt` -- Define navigation destinations (`Dashboard`, `Logs`, `Settings`) with icons and labels -- Define navigation items.
- [x] `app/src/main/java/com/hermes/node/ui/screens/DashboardScreen.kt` -- Implement Dashboard composable with status card, server controls, and stats cards -- Deliver main server control UI.
- [x] `app/src/main/java/com/hermes/node/ui/screens/LogsScreen.kt` -- Implement Logs composable with LazyColumn log list, search bar, and controls -- Deliver terminal log viewer UI.
- [x] `app/src/main/java/com/hermes/node/ui/screens/SettingsScreen.kt` -- Implement Settings composable with LLM provider configuration and Bot token inputs -- Deliver configuration UI.
- [x] `app/src/main/java/com/hermes/node/ui/navigation/HermesNavGraph.kt` -- Implement top-level `HermesApp` scaffold with Bottom Navigation and NavHost -- Assemble navigation shell.
- [x] `app/src/main/java/com/hermes/node/MainActivity.kt` -- Set Compose content with `HermesTheme` and `HermesApp` -- Connect activity lifecycle to UI.
- [x] `app/src/test/java/com/hermes/node/viewmodel/ServerViewModelTest.kt` -- Implement unit tests for `ServerViewModel` state changes and log appending -- Ensure state logic test coverage.

**Acceptance Criteria:**
- Given a clean environment with Java 17 and Android SDK 34, when `./gradlew assembleDebug` is executed, then the APK builds successfully without errors.
- Given the unit test suite, when `./gradlew test` is executed, then all `ServerViewModel` unit tests pass.
- Given the application UI, when navigating between Dashboard, Logs, and Settings tabs, then the corresponding screen composables render with proper Material 3 styling and state retention.

## Spec Change Log

## Design Notes

```kotlin
// ServerUiState Golden Model
data class ServerUiState(
    val status: ServerStatus = ServerStatus.STOPPED,
    val uptimeSeconds: Long = 0L,
    val cpuUsagePercent: Float = 0f,
    val memoryUsageMb: Long = 0L,
    val logs: List<LogEntry> = emptyList(),
    val isAutoStartEnabled: Boolean = false,
    val selectedProvider: String = "nous_portal",
    val apiKey: String = "",
    val telegramToken: String = ""
)

enum class ServerStatus {
    STOPPED, STARTING, RUNNING, STOPPING, ERROR
}
```

## Verification

**Commands:**
- `./gradlew test` -- expected: `BUILD SUCCESSFUL` with all unit tests passing.
- `./gradlew assembleDebug` -- expected: `BUILD SUCCESSFUL` producing `app/build/outputs/apk/debug/app-debug.apk`.

## Suggested Review Order

**UI Shell & Navigation**

- Top-level Scaffold with Material 3 bottom navigation and state-retaining NavHost tabs.
  [`HermesNavGraph.kt:40`](../../app/src/main/java/com/hermes/node/ui/navigation/HermesNavGraph.kt#L40)

- Dashboard screen displaying server status pill, start/stop toggle, and telemetry metrics grid.
  [`DashboardScreen.kt:76`](../../app/src/main/java/com/hermes/node/ui/screens/DashboardScreen.kt#L76)

- Monospaced terminal console viewer with level filters, search, auto-scroll, and log copying.
  [`LogsScreen.kt:75`](../../app/src/main/java/com/hermes/node/ui/screens/LogsScreen.kt#L75)

- LLM provider selection, masked credential fields, and daemon remote access settings.
  [`SettingsScreen.kt:65`](../../app/src/main/java/com/hermes/node/ui/screens/SettingsScreen.kt#L65)

**State Management & Lifecycle**

- Unidirectional state contracts defining server statuses, log levels, and configuration state.
  [`ServerUiState.kt:24`](../../app/src/main/java/com/hermes/node/viewmodel/ServerUiState.kt#L24)

- Central ViewModel managing daemon start/stop lifecycle transitions, metrics loop, and ring buffer.
  [`ServerViewModel.kt:32`](../../app/src/main/java/com/hermes/node/viewmodel/ServerViewModel.kt#L32)

**Design System & Activity Entry Point**

- Android edge-to-edge Activity entry point hosting HermesApp inside the custom theme.
  [`MainActivity.kt:10`](../../app/src/main/java/com/hermes/node/MainActivity.kt#L10)

- Material 3 theme configuration supporting dynamic color and cyber dark/light palettes.
  [`Theme.kt:47`](../../app/src/main/java/com/hermes/node/ui/theme/Theme.kt#L47)

- Cyber brand accents, status indicator colors, and terminal log color tokens.
  [`Color.kt:6`](../../app/src/main/java/com/hermes/node/ui/theme/Color.kt#L6)

**Build Toolchain & Manifest Foundation**

- App module build script configuring SDK 34, Compose BOM, dependencies, and test runners.
  [`app/build.gradle.kts:1`](../../app/build.gradle.kts#L1)

- Android manifest declaring required foreground service, wake lock, and system boot permissions.
  [`AndroidManifest.xml:4`](../../app/src/main/AndroidManifest.xml#L4)

**Unit Test Suite**

- Comprehensive unit tests covering state transitions, metrics updates, log buffering, and error handling.
  [`ServerViewModelTest.kt:37`](../../app/src/test/java/com/hermes/node/viewmodel/ServerViewModelTest.kt#L37)

