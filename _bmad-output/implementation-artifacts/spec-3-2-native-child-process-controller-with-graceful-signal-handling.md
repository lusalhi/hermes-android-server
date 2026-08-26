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

### Review Findings
- [x] [Review][Patch] Eliminate Main UI Thread Blocking via Synchronous runBlocking in Service Lifecycle [app/src/main/java/com/hermes/node/service/HermesServerService.kt:109]
- [x] [Review][Patch] Fix Test Suite Infinite Hang in FakeProcess.waitFor() Blocking Loop [app/src/test/java/com/hermes/node/engine/ProcessControllerTest.kt:272]
- [x] [Review][Patch] Wire Runtime UI and ViewModel with ProcessController and ProcessState Flow [app/src/main/java/com/hermes/node/ui/navigation/HermesNavGraph.kt:48]
- [x] [Review][Patch] Fix Invalid Python Entry-Point Arguments in Non-PRoot Fallback Configuration [app/src/main/java/com/hermes/node/engine/ProcessController.kt:85]
- [x] [Review][Patch] Replace Polling Loop in waitForProcessTermination with Coroutine withTimeoutOrNull [app/src/main/java/com/hermes/node/engine/ProcessController.kt:350]
- [x] [Review][Patch] Add ProcessState.STOPPING Guard to ProcessController.start() [app/src/main/java/com/hermes/node/engine/ProcessController.kt:370]
- [x] [Review][Patch] Fix Leaked / Duplicate Exit Listener Registration in HermesServerService [app/src/main/java/com/hermes/node/service/HermesServerService.kt:57]
- [x] [Review][Patch] Normalize Executable Invocation Path Inside PRoot Chroot [app/src/main/java/com/hermes/node/engine/ProcessController.kt:94]
- [x] [Review][Patch] Standardize stop() Call Invocation in HermesServerService.onDestroy() [app/src/main/java/com/hermes/node/service/HermesServerService.kt:95]
- [x] [Review][Patch] Add Assertions for PRoot and Standalone Invocations in createHermesDaemonConfig Unit Tests [app/src/test/java/com/hermes/node/engine/ProcessControllerTest.kt:203]

**Acceptance Criteria:**
- Given a running Hermes daemon process, when the user taps STOP SERVER or service receives `ACTION_STOP`, then `ProcessController` sends `SIGTERM` to the process.
- Given a process that does not terminate within 5 seconds of `SIGTERM`, when the 5-second timeout elapses, then a fallback `SIGKILL` is executed and WakeLock is released.
- Given an unexpected child process termination or launch failure, when observed, then resources are cleaned up, WakeLock is released, and state is reported without crashing the app.

## Spec Change Log

- **2026-08-26 (Code Review Loop 1)**: Resolved all 10 code review patch findings. Fixed `FakeProcess.waitFor()` blocking loop hang with CountDownLatch and timed wait, eliminated UI thread blocking in service lifecycle, wired `processStateFlow` into `HermesNavGraph` and `ServerViewModel`, added `STOPPING` state guard, fixed Python module fallback invocation arguments (`-m hermes`), and cleaned up service exit listeners. Verified with 100% pass on `./gradlew test` and `./gradlew assembleDebug`.

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

