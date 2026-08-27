---
title: '4-2-terminal-console-ui-with-real-time-filtering-search-export'
type: 'feature'
created: '2026-08-27'
status: 'done'
baseline_commit: 'f456666c5b942629ea1c0b8c606a11d459557ccf'
review_loop_iteration: 0
context:
  - _bmad-output/planning-artifacts/architecture/architecture-hermes-android-server-2026-08-24/ARCHITECTURE-SPINE.md
  - _bmad-output/planning-artifacts/prds/prd-hermes-android-server-2026-08-24/prd.md
  - _bmad-output/implementation-artifacts/epic-4-context.md
---

<frozen-after-approval reason="human-owned intent — do not modify unless human renegotiates">

## Intent

**Problem:** Users debugging autonomous agent execution lack granular inspection tools in the terminal log view, making it difficult to search through high-volume streaming logs, isolate critical errors or warnings, pause auto-scrolling during rapid emissions, or export filtered log segments to the clipboard.

**Approach:** Build a cyber-terminal monospaced console UI with real-time text search, severity filter chips (`ALL`, `INFO`, `WARN`, `ERROR`, `DEBUG`), an auto-scroll lock toggle, one-tap clipboard export (supporting full or filtered logs with timestamps), and modular UI components in `com.hermes.node.ui.components`.

## Boundaries & Constraints

**Always:**
- Maintain cyber-terminal styling using `MonospaceCodeStyle`, `TerminalBackground`, and high-contrast severity colors (`LogInfo`, `LogWarn`, `LogError`, `LogDebug`).
- Perform log search and level filtering reactively without freezing the Compose UI or blocking the main thread.
- Support toggling auto-scroll lock so users can inspect past logs without new incoming logs forcing the viewport to the bottom.
- Export formatted logs with timestamp (`HH:mm:ss.SSS`), log level tag (`[INFO ]`, `[WARN ]`, `[ERROR]`, `[DEBUG]`), and raw message to the Android clipboard.
- When search or severity filters are active, "Copy Logs" copies the currently filtered log view, or copies all logs when no filter is applied, with user feedback (Toast / Snack).
- Address Retro Action Item 2 by organizing reusable UI cards (`StatusPill`, `ServerControlCard`, `RuntimeIntegrityCard`, `MetricCard`) into `com.hermes.node.ui.components`.

**Ask First:**
- Adding persistent log disk storage or file export beyond Android clipboard export.

**Never:**
- Never block the UI thread during log searching, filtering, or clipboard formatting.
- Never drop or mutate the underlying ring buffer logs when filtering in the UI layer.
- Never crash the UI when rendering ANSI-stripped or empty log streams.

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| Search Query Filter | User types `"gateway"` into search field | LazyColumn displays only entries containing `"gateway"` (case-insensitive) | Show `"No matching logs found."` if 0 matches |
| Severity Level Filter | User taps `"ERROR"` filter chip | LazyColumn displays only entries with `LogLevel.ERROR` | Show `"No matching logs found."` if 0 matches |
| Combined Search & Severity | User selects `"WARN"` chip and searches `"memory"` | Displays only entries matching both `LogLevel.WARN` and containing `"memory"` | Displays empty state message if no matches |
| Auto-scroll Lock Enabled | `isAutoScroll = true`, new logs emitted | Viewport automatically animates/scrolls to latest bottom item | Handled via `LaunchedEffect` and `LazyListState` |
| Auto-scroll Lock Disabled | `isAutoScroll = false`, new logs emitted | Viewport scroll position remains fixed where user scrolled | Preserves user scroll position |
| Copy Filtered Logs | User filters for `"ERROR"` and taps "Copy Logs" | Copies formatted filtered errors to clipboard and shows `"Copied 3 filtered logs to clipboard"` toast | No-op with feedback if log list is empty |
| Copy All Logs (No Filter) | User has no filter and taps "Copy Logs" | Copies all formatted logs to clipboard and shows `"Copied 45 logs to clipboard"` toast | No-op if empty |
| Clear Logs Action | User taps "Clear Logs" icon | Invokes `viewModel.onClearLogs()`, resets filtered view, and shows `"No logs captured yet."` | Buffer cleanly emptied |
| Empty Log Buffer | No logs emitted yet | Displays centered placeholder `"No logs captured yet."` in monospace font | Graceful empty state |

</frozen-after-approval>

## Code Map

- `app/src/main/java/com/hermes/node/ui/screens/LogsScreen.kt` -- Full Logs tab screen with search bar, severity filter chips, auto-scroll toggle, clipboard export, clear action, and LazyColumn terminal stream.
- `app/src/main/java/com/hermes/node/ui/components/LogLineItem.kt` -- Monospaced log entry row component with timestamp formatting, severity color tags, and text wrapping.
- `app/src/main/java/com/hermes/node/ui/components/LogExportHelper.kt` -- Formatter utility converting `List<LogEntry>` into standard clipboard text with timestamps and level brackets, plus pure filtering helper functions.
- `app/src/main/java/com/hermes/node/ui/components/StatusPill.kt` -- Extracted reusable server and runtime status pill badge.
- `app/src/main/java/com/hermes/node/ui/components/ServerControlCard.kt` -- Extracted master daemon Start/Stop hero card.
- `app/src/main/java/com/hermes/node/ui/components/RuntimeIntegrityCard.kt` -- Extracted runtime integrity and repair action card.
- `app/src/main/java/com/hermes/node/ui/components/MetricCard.kt` -- Extracted telemetry metric card component.
- `app/src/main/java/com/hermes/node/ui/screens/DashboardScreen.kt` -- Updated dashboard consuming extracted modular components from `com.hermes.node.ui.components`.
- `app/src/test/java/com/hermes/node/ui/LogExportHelperTest.kt` -- Unit tests for log filtering, search matching, timestamp formatting, and clipboard export string serialization.

## Tasks & Acceptance

**Execution:**
- [x] `app/src/main/java/com/hermes/node/ui/components/LogExportHelper.kt` -- Create log formatter and filtering helper functions for testable log processing and clipboard formatting.
- [x] `app/src/main/java/com/hermes/node/ui/components/LogLineItem.kt` -- Create dedicated monospaced log entry composable with level-coded color pills and timestamps.
- [x] `app/src/main/java/com/hermes/node/ui/screens/LogsScreen.kt` -- Refine `LogsScreen` with reactive search, severity chip toggling, auto-scroll lock, copy feedback, and empty states.
- [x] `app/src/main/java/com/hermes/node/ui/components/StatusPill.kt` -- Extract `StatusPill` into dedicated component file (Retro Item 2).
- [x] `app/src/main/java/com/hermes/node/ui/components/ServerControlCard.kt` -- Extract `ServerControlCard` into dedicated component file (Retro Item 2).
- [x] `app/src/main/java/com/hermes/node/ui/components/RuntimeIntegrityCard.kt` -- Extract `RuntimeIntegrityCard` into dedicated component file (Retro Item 2).
- [x] `app/src/main/java/com/hermes/node/ui/components/MetricCard.kt` -- Extract `MetricCard` and `formatUptime` into dedicated component file (Retro Item 2).
- [x] `app/src/main/java/com/hermes/node/ui/screens/DashboardScreen.kt` -- Update `DashboardScreen` to use extracted components.
- [x] `app/src/test/java/com/hermes/node/ui/LogExportHelperTest.kt` -- Implement unit tests verifying log filtering logic, search case-insensitivity, level filtering, and clipboard string formatting.

**Acceptance Criteria:**
- Given the Logs screen with streaming logs, when typing in the search bar or clicking severity chips (`INFO`, `WARN`, `ERROR`, `DEBUG`), then the terminal list updates in real-time to display only matching entries.
- Given active log filtering, when clicking "Copy Logs", then only the filtered entries are formatted with timestamps and copied to the Android clipboard with a confirmation message.
- Given active log streaming, when toggling auto-scroll off, then incoming logs do not force scroll the list, allowing manual inspection of past log entries.
- Given the UI components package, all dashboard and terminal card components are decoupled into clean, modular files under `com.hermes.node.ui.components`.

## Spec Change Log

_None._

## Design Notes

- **Log Formatting Standard:**
  ```text
  [12:34:56.789] [INFO ] Hermes Node daemon running on port 8000
  [12:35:01.120] [WARN ] High memory utilization detected
  [12:35:10.450] [ERROR] Telegram gateway connection lost
  ```
- **Filter Heuristics:**
  - `selectedLevel == null`: Match all levels
  - `searchQuery.isBlank()`: Match all messages
  - Case-insensitive substring matching on `entry.message`
- **Compose LazyColumn Keys:**
  - Use stable item keys `key = { index, item -> "${item.timestamp}_${index}" }` to optimize recomposition performance during log bursts.

## Verification

**Commands:**
- `./gradlew test --tests "com.hermes.node.ui.LogExportHelperTest"` -- expected: 100% tests pass
- `./gradlew test` -- expected: All unit tests in test suite pass successfully
- `./gradlew assembleDebug` -- expected: Build succeeds producing `app-debug.apk`

**Manual checks (if no CLI):**
- Verify `LogsScreen` renders search bar, severity filter chips, auto-scroll toggle icon, copy button, and clear button with dark cyber-terminal theming.

## Suggested Review Order

**Console Terminal UI & Stream Filtering**

- Pure helper utility for log formatting, padded level tags, search, and export
  [`LogExportHelper.kt:13`](../../app/src/main/java/com/hermes/node/ui/components/LogExportHelper.kt#L13)

- Monospaced log entry row with selectable text and color-coded severity tags
  [`LogLineItem.kt:27`](../../app/src/main/java/com/hermes/node/ui/components/LogLineItem.kt#L27)

- Terminal console screen with reactive search, chip filters, and auto-scroll control
  [`LogsScreen.kt:61`](../../app/src/main/java/com/hermes/node/ui/screens/LogsScreen.kt#L61)

**Decoupled Dashboard Components (Retro Item 2)**

- Reusable server daemon control hero card with power state animation
  [`ServerControlCard.kt:45`](../../app/src/main/java/com/hermes/node/ui/components/ServerControlCard.kt#L45)

- Reusable runtime integrity alert and one-tap environment repair card
  [`RuntimeIntegrityCard.kt:36`](../../app/src/main/java/com/hermes/node/ui/components/RuntimeIntegrityCard.kt#L36)

- Reusable status pill badge displaying server lifecycle and repair states
  [`StatusPill.kt:32`](../../app/src/main/java/com/hermes/node/ui/components/StatusPill.kt#L32)

- Hardware telemetry metric card and uptime formatting utility
  [`MetricCard.kt:31`](../../app/src/main/java/com/hermes/node/ui/components/MetricCard.kt#L31)

- Dashboard screen integration consuming decoupled modular UI cards
  [`DashboardScreen.kt:80`](../../app/src/main/java/com/hermes/node/ui/screens/DashboardScreen.kt#L80)

**Test Suite**

- Unit tests for log filtering, search matching, timestamp formatting, and clipboard feedback
  [`LogExportHelperTest.kt:13`](../../app/src/test/java/com/hermes/node/ui/LogExportHelperTest.kt#L13)

