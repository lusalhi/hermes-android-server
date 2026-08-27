---
title: '4-1-asynchronous-coroutine-process-i-o-streamer-with-circular-ri'
type: 'feature'
created: '2026-08-26'
status: 'done'
baseline_commit: 'da04489ce132d9b7ed8f2fa9e8c4b73b44725441'
review_loop_iteration: 0
context:
  - _bmad-output/planning-artifacts/architecture/architecture-hermes-android-server-2026-08-24/ARCHITECTURE-SPINE.md
  - _bmad-output/planning-artifacts/prds/prd-hermes-android-server-2026-08-24/prd.md
  - _bmad-output/implementation-artifacts/epic-4-context.md
---

<frozen-after-approval reason="human-owned intent — do not modify unless human renegotiates">

## Intent

**Problem:** The native Hermes sub-process outputs continuous `stdout` and `stderr` streams that can grow without bound, risking Out-Of-Memory (OOM) crashes, GC pauses, and UI thread stutter if consumed synchronously or unbounded in memory.

**Approach:** Implement a thread-safe generic circular `RingBuffer<T>` capped at 2,000 entries and an asynchronous `LogStreamer` running on `Dispatchers.IO` to read, ANSI-strip, parse severity for sub-process I/O streams, and emit throttled updates via `StateFlow`.

## Boundaries & Constraints

**Always:**
- Execute all stream reading asynchronously on `Dispatchers.IO` using non-blocking Coroutine I/O.
- Enforce strict 2,000-line capacity cap in `RingBuffer` with O(1) overwriting of oldest items when full without memory leaks.
- Strip ANSI escape sequences (e.g. `\u001B[31m`, `\u001B[0m`) from raw log messages to prevent UI layout corruption.
- Correctly parse log severity levels (`INFO`, `WARN`, `ERROR`, `DEBUG`) from stream prefixes and stderr fallback.
- Support thread-safe concurrent reads and writes across background I/O coroutines and UI collector threads.
- Gracefully handle stream closing, EOF, `IOException`, and process termination without throwing unhandled exceptions.

**Ask First:**
- Modifying default buffer capacity (2,000 lines) or introducing persistent disk-based log buffering.

**Never:**
- Never read `stdout` or `stderr` streams synchronously on `Dispatchers.Main` or block the UI thread.
- Never allow unbounded collection growth that could cause Android process OOM.
- Never fail or crash the parent daemon service if a child process emits invalid UTF-8 or malformed stream bytes.

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| Happy Path stdout | Stream emitting `"2026-08-26 [INFO] Gateway started on port 8000"` | Parsed into `LogEntry(level=INFO, message="2026-08-26 [INFO] Gateway started on port 8000")` added to buffer | N/A |
| Warning / Error stream | Stream emitting `"[WARN] High memory usage"` or `"[ERROR] Connection failed"` | Parsed with `LogLevel.WARN` or `LogLevel.ERROR` | N/A |
| Raw stderr without prefix | stderr emitting `"Traceback (most recent call last):"` | Parsed with `LogLevel.ERROR` | N/A |
| ANSI formatted stream | Stream emitting `"\u001B[32m[INFO]\u001B[0m Model loaded"` | Strips ANSI codes resulting in cleaned message `"[INFO] Model loaded"` | N/A |
| Buffer overflow (>2,000 entries) | 2,500 lines emitted consecutively | Buffer size remains exactly 2,000; oldest 500 lines pruned in FIFO order | Verified via unit test |
| Corrupted / invalid stream bytes | Stream emitting malformed byte sequence | Replaces invalid UTF-8 characters gracefully without throwing exceptions | Replace invalid chars |
| Stream EOF / abrupt process close | Process terminated / stream closed | Stream reader loop terminates cleanly without leaking coroutines | Closes reader cleanly |
| Clear logs action | `clear()` called on streamer/buffer | Buffer emptied (`size == 0`), empty list emitted to flow | N/A |

</frozen-after-approval>

## Code Map

- `app/src/main/java/com/hermes/node/engine/RingBuffer.kt` -- Thread-safe, fixed-capacity circular buffer with O(1) append, snapshot retrieval, and FIFO eviction.
- `app/src/main/java/com/hermes/node/engine/LogStreamer.kt` -- Asynchronous coroutine I/O streamer, ANSI cleaner, severity parser, and StateFlow publisher.
- `app/src/main/java/com/hermes/node/service/HermesServerService.kt` -- Wires `LogStreamer` to active `ProcessController` streams upon service start and stops on destroy.
- `app/src/main/java/com/hermes/node/viewmodel/ServerViewModel.kt` -- Bridges `LogStreamer` flows to `ServerUiState.logs` with bounded memory.
- `app/src/test/java/com/hermes/node/engine/RingBufferTest.kt` -- Unit tests for `RingBuffer` capacity bounds, thread safety, and FIFO order.
- `app/src/test/java/com/hermes/node/engine/LogStreamerTest.kt` -- Unit tests for coroutine stream parsing, ANSI stripping, severity categorization, and stream lifecycle.

## Tasks & Acceptance

**Execution:**
- [x] `app/src/main/java/com/hermes/node/engine/RingBuffer.kt` -- Create generic thread-safe circular `RingBuffer<T>` with fixed capacity (default 2000), `add()`, `toList()`, `clear()`, `size`, `capacity`, and `isFull` properties.
- [x] `app/src/main/java/com/hermes/node/engine/LogStreamer.kt` -- Create `LogStreamerInterface` and `LogStreamer` class implementing coroutine-based asynchronous reading of stdout/stderr, ANSI escape sequence removal, severity level parsing, and `StateFlow<List<LogEntry>>` streaming.
- [x] `app/src/main/java/com/hermes/node/service/HermesServerService.kt` -- Integrate `LogStreamer` into service lifecycle, starting stream reading when child process starts and stopping when child process terminates.
- [x] `app/src/main/java/com/hermes/node/viewmodel/ServerViewModel.kt` -- Update log collection to observe `LogStreamer` flow and support log clearing.
- [x] `app/src/test/java/com/hermes/node/engine/RingBufferTest.kt` -- Implement comprehensive unit tests verifying bounded size, thread-safe concurrent access, and FIFO eviction.
- [x] `app/src/test/java/com/hermes/node/engine/LogStreamerTest.kt` -- Implement unit tests for stream decoding, ANSI stripping, log level detection, coroutine cancellation, and error handling.

### Review Findings

- [x] [Review][Patch] Implement StateFlow conflation / throttled snapshot generation to avoid GC churn and UI recomposition flooding [`app/src/main/java/com/hermes/node/engine/LogStreamer.kt:103-106`](file:///home/ubuntu/hermes-android-server/app/src/main/java/com/hermes/node/engine/LogStreamer.kt#L103-L106)
- [x] [Review][Patch] Wrap reader.close() in withContext(NonCancellable + ioDispatcher) within readStream finally block to prevent descriptor leak on cancellation [`app/src/main/java/com/hermes/node/engine/LogStreamer.kt:147-151`](file:///home/ubuntu/hermes-android-server/app/src/main/java/com/hermes/node/engine/LogStreamer.kt#L147-L151)
- [x] [Review][Patch] Drain and stop LogStreamer after ProcessController.stop() completes to preserve shutdown logs [`app/src/main/java/com/hermes/node/service/HermesServerService.kt:92-95`](file:///home/ubuntu/hermes-android-server/app/src/main/java/com/hermes/node/service/HermesServerService.kt#L92-L95)
- [x] [Review][Patch] Eliminate redundant nested withContext(ioDispatcher) in stream reading loop and check current coroutine context isActive [`app/src/main/java/com/hermes/node/engine/LogStreamer.kt:125-133`](file:///home/ubuntu/hermes-android-server/app/src/main/java/com/hermes/node/engine/LogStreamer.kt#L125-L133)
- [x] [Review][Patch] Make _isStreaming @Volatile and correctly reflect active stream status [`app/src/main/java/com/hermes/node/engine/LogStreamer.kt:58-69`](file:///home/ubuntu/hermes-android-server/app/src/main/java/com/hermes/node/engine/LogStreamer.kt#L58-L69)
- [x] [Review][Patch] Improve log level parsing heuristics for stderr 'INFO:' prefix and case-insensitivity [`app/src/main/java/com/hermes/node/engine/LogStreamer.kt:173-196`](file:///home/ubuntu/hermes-android-server/app/src/main/java/com/hermes/node/engine/LogStreamer.kt#L173-L196)
- [x] [Review][Patch] Expand ANSI stripping regex to handle private parameter sequences (e.g. cursor show/hide ?25h) [`app/src/main/java/com/hermes/node/engine/LogStreamer.kt:155`](file:///home/ubuntu/hermes-android-server/app/src/main/java/com/hermes/node/engine/LogStreamer.kt#L155)
- [x] [Review][Patch] Expose HermesServerService.logStreamerFlow or companion accessor to connect live log stream to UI [`app/src/main/java/com/hermes/node/service/HermesServerService.kt:33-36`](file:///home/ubuntu/hermes-android-server/app/src/main/java/com/hermes/node/service/HermesServerService.kt#L33-L36)

**Acceptance Criteria:**
- Given a running process emitting output to stdout and stderr, when `LogStreamer` reads streams on `Dispatchers.IO`, then output lines are parsed into `LogEntry` items with correct `LogLevel` and emitted to `logsFlow`.
- Given log output exceeding 2,000 lines, when new log entries are appended, then the `RingBuffer` retains exactly the 2,000 most recent entries without memory leaks.
- Given ANSI color codes in process output, when lines are streamed, then ANSI escape sequences are cleanly stripped from the message.

## Spec Change Log

_None._

## Design Notes

- **RingBuffer Implementation:** Use an array or fixed-size buffer guarded by `@Synchronized` or Mutex/ReentrantLock to guarantee thread-safe O(1) insertion and snapshot creation (`toList()`).
- **Severity Heuristics:**
  - `ERROR`: Line contains `[ERROR]`, `ERROR:`, `FATAL`, `Exception`, `Traceback`, `Error:` or comes from unformatted `stderr`.
  - `WARN`: Line contains `[WARN]`, `[WARNING]`, `WARNING:`, `WARN:`.
  - `DEBUG`: Line contains `[DEBUG]`, `DEBUG:`.
  - `INFO`: Default for standard stdout lines and `[INFO]` tagged lines.
- **ANSI Stripping Regex:** `Regex("\u001B\\[[;\\d]*[ -/]*[@-~]")` cleanly strips ANSI CSI codes.

## Verification

**Commands:**
- `./gradlew test --tests "com.hermes.node.engine.RingBufferTest"` -- expected: 100% tests pass
- `./gradlew test --tests "com.hermes.node.engine.LogStreamerTest"` -- expected: 100% tests pass
- `./gradlew test` -- expected: All unit tests in test suite pass successfully

## Suggested Review Order

**Log Streaming & RingBuffer Engine**

- Thread-safe circular RingBuffer with strict 2,000 capacity and FIFO eviction
  [`RingBuffer.kt:7`](../../app/src/main/java/com/hermes/node/engine/RingBuffer.kt#L7)

- Coroutine I/O streamer with ANSI stripping and severity level detection
  [`LogStreamer.kt:45`](../../app/src/main/java/com/hermes/node/engine/LogStreamer.kt#L45)

**Service & ViewModel Integration**

- Daemon service lifecycle integration attaching stdout/stderr streams to LogStreamer
  [`HermesServerService.kt:259`](../../app/src/main/java/com/hermes/node/service/HermesServerService.kt#L259)

- ServerViewModel StateFlow observer binding streamed logs to UI state
  [`ServerViewModel.kt:802`](../../app/src/main/java/com/hermes/node/viewmodel/ServerViewModel.kt#L802)

**Test Suites**

- Unit tests for RingBuffer capacity limits, thread-safety, and eviction
  [`RingBufferTest.kt:13`](../../app/src/test/java/com/hermes/node/engine/RingBufferTest.kt#L13)

- Unit tests for LogStreamer parsing, ANSI stripping, and error handling
  [`LogStreamerTest.kt:23`](../../app/src/test/java/com/hermes/node/engine/LogStreamerTest.kt#L23)
