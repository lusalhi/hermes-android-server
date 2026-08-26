---
title: '3-2-native-child-process-controller-with-graceful-signal-handling'
type: 'feature'
created: '2026-08-25'
status: 'done'
baseline_commit: '95362c10a0f3ea0c0f72c25371d986919e72e26a'
review_loop_iteration: 0
context: []
---

<frozen-after-approval reason="human-owned intent — do not modify unless human renegotiates">

## Intent

**Problem:** The Hermes AI daemon runs as an isolated POSIX child process that must be cleanly launched, monitored, and terminated without leaving orphan zombie processes, resource leaks, or corrupted database state when stopped.

**Approach:** Implement `ProcessController` (and `ProcessControllerInterface`) in `com.hermes.node.engine` to orchestrate `ProcessBuilder` execution, stream access, and graceful termination (sending `SIGTERM` with a strict 5-second timeout fallback to `SIGKILL`), integrated into `HermesServerService` alongside WakeLock management.

## Boundaries & Constraints

**Always:**
- Send `SIGTERM` (`destroy()`) first when terminating a running process and give the sub-process up to 5 seconds to exit cleanly.
- Execute fallback `SIGKILL` (`destroyForcibly()`) if the process does not terminate within the 5-second timeout.
- Ensure `WakeLock` is safely released when the sub-process terminates (whether gracefully, forcefully, or unexpectedly).
- Provide non-blocking coroutine-based execution and monitoring on `Dispatchers.IO`.
- Track and expose `ProcessState` (`STOPPED`, `STARTING`, `RUNNING`, `STOPPING`, `ERROR`, `TERMINATED`) via `StateFlow`.
- Safely close stream handles (`stdout`, `stderr`, `stdin`) on process termination to avoid file descriptor leaks.

**Ask First:**
- Adding additional native binary dependencies beyond PRoot and Python userland.
- Altering the 5-second SIGKILL timeout value.

**Never:**
- Never block the main Android UI thread with synchronous process `waitFor()` calls.
- Never retain `WakeLock` if process launch fails or process terminates.
- Never allow zombie/orphan sub-processes when the service is stopped or destroyed.

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| Start Process (Happy Path) | Valid executable path and arguments | Sub-process launches, state becomes `RUNNING`, PID is recorded, streams are available | Returns `Result.success` |
| Stop Process (Graceful SIGTERM) | Running sub-process | `SIGTERM` sent; process terminates within <5s, state becomes `STOPPED`, WakeLock released | Returns `ProcessStopResult.GRACEFUL_SIGTERM` |
| Stop Process (SIGKILL Timeout) | Running sub-process that ignores `SIGTERM` (unresponsive) | `SIGTERM` sent; 5s timeout expires; `SIGKILL` sent forcibly terminating process; WakeLock released | Returns `ProcessStopResult.FORCED_SIGKILL` |
| Stop When Already Stopped | Sub-process is already `STOPPED` | No-op, returns cleanly without exception | Returns `ProcessStopResult.ALREADY_STOPPED` |
| Executable Not Found / Permission Denied | Invalid or missing binary path | Process fails to spawn, state becomes `ERROR`, WakeLock not held | Returns `Result.failure` with detailed error |
| Unexpected Process Exit | Sub-process crashes or terminates externally | Process watcher detects exit, records exit code, transitions state to `TERMINATED`, notifies service | Logs warning and cleans up resources |

</frozen-after-approval>

## Code Map

- `app/src/main/java/com/hermes/node/engine/ProcessController.kt` -- Define `ProcessControllerInterface`, `ProcessState`, `ProcessStopResult`, `ProcessConfig`, and `ProcessController` managing `ProcessBuilder`, signal handling, timeouts, streams, and state flow.
- `app/src/main/java/com/hermes/node/service/HermesServerService.kt` -- Integrate `ProcessController` into `HermesServerService`, managing process startup in `startForegroundServiceInternal`, graceful shutdown in `stopForegroundServiceInternal`, and cleanup in `onDestroy`.
- `app/src/main/java/com/hermes/node/viewmodel/ServerViewModel.kt` -- Ensure ViewModel and UI reflect process lifecycle events and errors seamlessly.
- `app/src/test/java/com/hermes/node/engine/ProcessControllerTest.kt` -- Unit tests covering process start, graceful SIGTERM, 5s timeout SIGKILL fallback, exit code capture, error handling, and streams.
- `app/src/test/java/com/hermes/node/service/HermesServerServiceTest.kt` -- Unit tests verifying `HermesServerService` interaction with `ProcessController`, WakeLock lifecycle, and signal handling.
- `app/src/test/java/com/hermes/node/viewmodel/ServerViewModelTest.kt` -- Unit tests verifying ViewModel state transitions with service and process controller.

## Tasks & Acceptance

**Execution:**
- [x] `app/src/main/java/com/hermes/node/engine/ProcessController.kt` -- Implement `ProcessControllerInterface`, `ProcessConfig`, `ProcessState`, `ProcessStopResult`, and `ProcessController` with `ProcessBuilder` execution, asynchronous exit observation, graceful `SIGTERM` with 5-second `SIGKILL` timeout fallback, and safe stream cleanup.
- [x] `app/src/main/java/com/hermes/node/service/HermesServerService.kt` -- Wire `ProcessController` into service start/stop commands, orchestrating process lifecycle, WakeLock release, and notification updates.
- [x] `app/src/main/java/com/hermes/node/viewmodel/ServerViewModel.kt` -- Verify and adapt ViewModel to observe process and service state changes cleanly.
- [x] `app/src/test/java/com/hermes/node/engine/ProcessControllerTest.kt` -- Implement unit tests for `ProcessController` covering start, graceful stop, SIGKILL timeout fallback, PID extraction, exit codes, and error scenarios.
- [x] `app/src/test/java/com/hermes/node/service/HermesServerServiceTest.kt` -- Update service unit tests to verify `ProcessController` coordination and signal dispatching.
- [x] `app/src/test/java/com/hermes/node/viewmodel/ServerViewModelTest.kt` -- Update ViewModel tests for process lifecycle integration.

**Acceptance Criteria:**
- Given a running Hermes daemon process, when the user taps STOP SERVER or service receives `ACTION_STOP`, then `ProcessController` sends `SIGTERM` to the process.
- Given a process that does not terminate within 5 seconds of `SIGTERM`, when the 5-second timeout elapses, then a fallback `SIGKILL` is executed and WakeLock is released.
- Given an unexpected child process termination or launch failure, when observed, then resources are cleaned up, WakeLock is released, and state is reported without crashing the app.

## Spec Change Log

<!-- Append-only. Populated by step-04 during review loops. -->

## Design Notes

- **POSIX Signal Dispatching in JVM/Android**: Java's `Process.destroy()` sends `SIGTERM` on Linux/Android platforms, and `Process.destroyForcibly()` sends `SIGKILL`. We utilize `withTimeoutOrNull(5000L)` on a coroutine waiting for `process.waitFor()` or process exit; if it returns null, we execute `process.destroyForcibly()` to guarantee termination.
- **ProcessController Decoupling**: In accordance with Epic 2 retrospective action item 1, `ProcessController` is decoupled as a standalone engine class with an interface `ProcessControllerInterface`, enabling dependency injection and full unit testability with fake process implementations.

## Verification

**Commands:**
- `./gradlew test` -- expected: `BUILD SUCCESSFUL` with all unit tests passing (100% pass rate).
- `./gradlew assembleDebug` -- expected: `BUILD SUCCESSFUL` generating `app-debug.apk`.

## Suggested Review Order

**Process Management & Signal Handling Engine**

- Master native process controller handling lifecycle, signal dispatching, and streams
  [`ProcessController.kt:160`](../../app/src/main/java/com/hermes/node/engine/ProcessController.kt#L160)

- Graceful SIGTERM dispatch with 5-second SIGKILL timeout fallback
  [`ProcessController.kt:267`](../../app/src/main/java/com/hermes/node/engine/ProcessController.kt#L267)

- PRoot / Python daemon launch command and environment configuration builder
  [`ProcessController.kt:62`](../../app/src/main/java/com/hermes/node/engine/ProcessController.kt#L62)

**Foreground Service Lifecycle Integration**

- Foreground service startup orchestrating ProcessController and WakeLock
  [`HermesServerService.kt:136`](../../app/src/main/java/com/hermes/node/service/HermesServerService.kt#L136)

- Foreground service shutdown executing graceful stop and releasing WakeLock
  [`HermesServerService.kt:221`](../../app/src/main/java/com/hermes/node/service/HermesServerService.kt#L221)

**ViewModel Reactivity & Telemetry**

- ViewModel observer reacting to unexpected sub-process exit and error events
  [`ServerViewModel.kt:667`](../../app/src/main/java/com/hermes/node/viewmodel/ServerViewModel.kt#L667)

**Test Suites & Verification**

- Unit tests for ProcessController (happy path, SIGTERM, SIGKILL timeout fallback, PID, errors)
  [`ProcessControllerTest.kt:41`](../../app/src/test/java/com/hermes/node/engine/ProcessControllerTest.kt#L41)

- Unit tests for HermesServerService with ProcessController and WakeLock coordination
  [`HermesServerServiceTest.kt:59`](../../app/src/test/java/com/hermes/node/service/HermesServerServiceTest.kt#L59)

- Unit tests for ServerViewModel process state observation
  [`ServerViewModelTest.kt:961`](../../app/src/test/java/com/hermes/node/viewmodel/ServerViewModelTest.kt#L961)

