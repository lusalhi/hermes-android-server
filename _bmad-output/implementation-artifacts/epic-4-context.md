# Epic 4 Context: Real-Time Console Log Streamer, Memory RingBuffer & System Telemetry

<!-- Compiled from planning artifacts. Edit freely. Regenerate with compile-epic-context if planning docs change. -->

## Goal

Provide live operational visibility into the Hermes agent daemon through non-blocking asynchronous log streaming (`stdout`/`stderr`) into a thread-safe 2,000-line memory `RingBuffer`, a monospaced terminal UI with real-time filtering, search, and export, and hardware telemetry monitoring (CPU, RAM, Battery, Temperature).

## Stories

- Story 4.1: Asynchronous Coroutine Process I/O Streamer with Circular RingBuffer
- Story 4.2: Terminal Console UI with Real-Time Filtering, Search & Export
- Story 4.3: Real-Time Hardware Telemetry & Device Health Monitor

## Requirements & Constraints

- **Process I/O Streaming:** `stdout` and `stderr` streams emitted by the Hermes daemon sub-process must be captured asynchronously on `Dispatchers.IO`.
- **Log Buffer Capacity & Safety:** In-memory circular `RingBuffer` must strictly cap storage at 2,000 lines, automatically pruning oldest lines to prevent memory leaks or GC thrashing.
- **Log Parsing & Severity:** Streamed lines must be classified into `LogEntry` structures with timestamp, raw text, and severity levels (`INFO`, `WARN`, `ERROR`, `DEBUG`).
- **Terminal UI Performance:** UI rendering in Jetpack Compose must consume log updates via throttled/buffered `StateFlow` to ensure smooth 60fps rendering without freezing the main thread.
- **Log Inspection Tools:** Log screen must support text search, severity filter chips, auto-scroll locking, and full/filtered clipboard export.
- **Hardware Telemetry Metrics:** Telemetry collector must gather CPU utilization %, RAM usage (MB/GB), battery level %, charging status (⚡), and battery temperature (°C).
- **Power Optimization:** Telemetry polling (default 2s interval) must automatically pause when the UI is backgrounded or hidden.

## Technical Decisions

- **Architecture Spine AD-4 (Coroutine IO RingBuffer):** Process `stdout`/`stderr` streams MUST be read asynchronously using Kotlin Coroutines on `Dispatchers.IO` into a thread-safe circular `RingBuffer` capped at 2,000 lines. The UI MUST consume log updates via throttled `StateFlow`.
- **Package Location:** Core log streamer and buffer in `com.hermes.node.engine` (or `com.hermes.node.engine.log`), UI components in `com.hermes.node.ui.screens` (LogsScreen) and `com.hermes.node.ui.components`.
- **State Management:** Unidirectional Data Flow with `LogsUiState` exposed through `ServerViewModel` or dedicated `LogsViewModel`.

## UX & Interaction Patterns

- **Monospaced Cyber-Terminal:** Jetpack Compose terminal log viewer adhering to the cyber-terminal theme (`JetBrains Mono`, color-coded severity tags, dark background).
- **Interactive Controls:** Search bar with clear action, severity chips (`ALL`, `INFO`, `WARN`, `ERROR`, `DEBUG`), "Auto-scroll" toggle, "Copy Logs" button.
- **Telemetry Cards:** Dashboard telemetry cards for CPU, RAM, and Battery with color thresholds (e.g., warning colors when temperature or CPU load is high).

## Cross-Story Dependencies

- **Epic 3 Pre-requisite:** Builds directly upon the `ProcessController` and `HermesServerService` from Epic 3, attaching stdout/stderr streams when the process launches.
- **Within Epic 4:** Story 4.1 (`LogStreamer` & `RingBuffer`) provides the data engine consumed by Story 4.2 (`LogsScreen` UI) and alongside Story 4.3 (Telemetry monitor).
