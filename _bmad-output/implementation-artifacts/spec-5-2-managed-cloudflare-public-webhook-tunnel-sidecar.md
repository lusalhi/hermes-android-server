---
title: '5-2-managed-cloudflare-public-webhook-tunnel-sidecar'
type: 'feature'
created: '2026-08-31'
status: 'done'
baseline_commit: '04fb9ab77f597b25d560f84583f777c204e4b8b3'
review_loop_iteration: 0
context:
  - _bmad-output/planning-artifacts/architecture/architecture-hermes-android-server-2026-08-24/ARCHITECTURE-SPINE.md
  - _bmad-output/planning-artifacts/prds/prd-hermes-android-server-2026-08-24/prd.md
  - _bmad-output/implementation-artifacts/epic-5-context.md
---

<frozen-after-approval reason="human-owned intent — do not modify unless human renegotiates">

## Intent

**Problem:** Users hosting an autonomous Hermes agent on Android cannot expose their local webhooks (e.g. WhatsApp, Slack, custom integrations) or local web dashboard to the public internet without complex router port-forwarding or public static IPv4 addresses.

**Approach:** Implement a managed `TunnelManager` sidecar engine that launches the `cloudflared` ARM64 binary, parses the ephemeral `https://*.trycloudflare.com` URL from process logs using regular expressions, coordinates tunnel lifecycle with `ServerViewModel` and `HermesServerService`, and renders an interactive Dashboard tunnel card with a clipboard copy action and a dedicated QR code dialog.

## Boundaries & Constraints

**Always:**
- Manage `cloudflared` as a secondary, decoupled sidecar process via `TunnelManager` without blocking the main agent sub-process or the Android UI thread.
- Asynchronously parse `stdout` and `stderr` streams on `Dispatchers.IO` using regex `https://[a-zA-Z0-9.-]+\.trycloudflare\.com` to dynamically capture the public tunnel URL.
- Expose immutable tunnel state (`STOPPED`, `STARTING`, `RUNNING`, `ERROR`) and the active URL via `StateFlow` in `TunnelManager` and `ServerUiState`.
- Provide a clean QR code generator and Compose dialog on the Dashboard for scanning the public URL from mobile devices or external browsers.
- Gracefully stop the tunnel process with `SIGTERM` and a 5-second `SIGKILL` timeout whenever the server stops or when the user disables the tunnel switch.

**Ask First:**
- Bundling external binary assets or changing binary extraction paths outside `/data/data/com.hermes.node/files/usr/bin/cloudflared`.
- Changing default local forwarding target port away from the configured REST API port (default: 8000).

**Never:**
- Never crash or freeze the Android UI if `cloudflared` fails to spawn, times out, or encounters a network error.
- Never hardcode mock tunnel URLs in production execution paths.
- Never leave orphaned `cloudflared` processes running in the background when the parent foreground service or application is destroyed.

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| Start Server with Tunnel Enabled | `isPublicTunnelEnabled = true`, Server starts on port 8000 | `TunnelManager` spawns `cloudflared`, extracts `https://<id>.trycloudflare.com`, updates `uiState.tunnelUrl` and logs success | Timeout after 30s if URL not detected; transition to `TunnelState.ERROR` |
| Start Server with Tunnel Disabled | `isPublicTunnelEnabled = false`, Server starts | `cloudflared` is not spawned; `tunnelUrl` remains `null` | No-op |
| Toggle Tunnel Enabled while Server Running | User toggles switch to `true` while `status == RUNNING` | Dynamically starts `TunnelManager`, logs startup, sets `tunnelUrl` | Surfaces error log if process fails |
| Toggle Tunnel Disabled while Server Running | User toggles switch to `false` while `status == RUNNING` | Stops `TunnelManager`, clears `tunnelUrl`, logs shutdown | Graceful stop with fallback SIGKILL |
| Stop Server with Active Tunnel | Server stops via UI toggle, service stop, or crash | `TunnelManager` stops `cloudflared`, resets `tunnelUrl = null` | Cleans up process and releases streams |
| Missing `cloudflared` Binary | Binary not present or not executable | `TunnelManager.start()` returns `Result.failure`, sets `TunnelState.ERROR`, logs warning | Does not crash server; agent daemon continues locally |
| Display QR Code | User taps QR icon on active tunnel card | Opens `QrCodeDialog` rendering 2D QR matrix for `tunnelUrl` | Dismiss on close or outside tap |

</frozen-after-approval>

## Code Map

- `app/src/main/java/com/hermes/node/engine/TunnelManager.kt` -- `TunnelManagerInterface`, `TunnelState` sealed class, and `CloudflareTunnelManager` implementation managing sub-process lifecycle, regex URL parsing, and state flow.
- `app/src/main/java/com/hermes/node/ui/components/QrCodeGenerator.kt` -- Pure Kotlin QR code matrix generator / encoder producing boolean 2D grid from text.
- `app/src/main/java/com/hermes/node/ui/components/QrCodeDialog.kt` -- Material 3 Compose dialog rendering the QR matrix on Canvas with title, URL text, Copy button, and Dismiss action.
- `app/src/main/java/com/hermes/node/viewmodel/ServerUiState.kt` -- Add `tunnelState: TunnelState` and `showQrCodeDialog: Boolean` to state contract.
- `app/src/main/java/com/hermes/node/viewmodel/ServerViewModel.kt` -- Integrate `TunnelManagerInterface`, handle start/stop coordination, dynamic toggle handling, and QR dialog actions (`onShowQrCodeDialog`, `onDismissQrCodeDialog`).
- `app/src/main/java/com/hermes/node/ui/screens/DashboardScreen.kt` -- Update Cloudflare tunnel card with status indicator, QR code button, and render `QrCodeDialog` when requested.
- `app/src/test/java/com/hermes/node/engine/TunnelManagerTest.kt` -- Unit tests for `TunnelManager` lifecycle, regex parsing, error recovery, and process timeouts.
- `app/src/test/java/com/hermes/node/ui/components/QrCodeGeneratorTest.kt` -- Unit tests verifying QR matrix dimensions, encoding validity, and edge-cases.
- `app/src/test/java/com/hermes/node/viewmodel/ServerViewModelTest.kt` -- Unit tests for ViewModel tunnel lifecycle, QR dialog state, and dynamic toggle coordination.

## Tasks & Acceptance

**Execution:**
- [x] `app/src/main/java/com/hermes/node/engine/TunnelManager.kt` -- Implement `TunnelManagerInterface`, `TunnelState`, and `CloudflareTunnelManager` with coroutine I/O stream parsing and signal handling -- Provides sidecar process management for `cloudflared`.
- [x] `app/src/main/java/com/hermes/node/ui/components/QrCodeGenerator.kt` -- Implement pure Kotlin QR Code matrix encoder -- Enables rendering QR codes without heavy third-party dependencies.
- [x] `app/src/main/java/com/hermes/node/ui/components/QrCodeDialog.kt` -- Build Compose Material 3 dialog with Canvas QR renderer and copy action -- Provides quick mobile scan accessibility on Dashboard.
- [x] `app/src/main/java/com/hermes/node/viewmodel/ServerUiState.kt` -- Add `tunnelState` and `showQrCodeDialog` fields -- Supports Unidirectional Data Flow for tunnel and QR UI.
- [x] `app/src/main/java/com/hermes/node/viewmodel/ServerViewModel.kt` -- Wire `TunnelManagerInterface` into server start/stop and toggle actions -- Connects UI state to the tunnel engine.
- [x] `app/src/main/java/com/hermes/node/ui/screens/DashboardScreen.kt` -- Update tunnel card UI with QR trigger and show `QrCodeDialog` when active -- Delivers user-facing Dashboard tunnel experience.
- [x] `app/src/test/java/com/hermes/node/engine/TunnelManagerTest.kt` -- Add unit tests for `CloudflareTunnelManager` -- Verifies process execution, regex parsing, and error handling.
- [x] `app/src/test/java/com/hermes/node/ui/components/QrCodeGeneratorTest.kt` -- Add unit tests for QR matrix generation -- Verifies matrix dimensions, module validity, and empty input handling.
- [x] `app/src/test/java/com/hermes/node/viewmodel/ServerViewModelTest.kt` -- Add test cases for tunnel lifecycle and QR dialog handling -- Verifies regression-free state updates and cleanup.

**Acceptance Criteria:**
- Given `isPublicTunnelEnabled` is true, when the server starts, then `TunnelManager` launches `cloudflared`, extracts the `https://*.trycloudflare.com` URL, and displays it on the Dashboard card.
- Given an active tunnel URL on the Dashboard, when tapping the QR code button, then a dialog opens rendering a readable QR code with a copy button.
- Given a running server with an active tunnel, when stopping the server or toggling public tunnel off, then `cloudflared` is terminated gracefully and `tunnelUrl` is cleared.
- Given a failed `cloudflared` launch (e.g. missing binary or network error), when starting the tunnel, then an error is logged and `TunnelState.ERROR` is set without crashing the application.

## Spec Change Log

_None._

## Design Notes

- **Regex URL Matcher:** Standard `cloudflared quick-tunnel` output produces log lines such as `INF +--------------------------------------------------------------------------------------------+` followed by `INF |  Your quick Tunnel has been created! Visit it at:                                         |` and `INF |  https://random-subdomain.trycloudflare.com                                                |`. The regex `https://[a-zA-Z0-9.-]+\.trycloudflare\.com` matches this reliably across `cloudflared` versions.
- **Pure Kotlin QR Generator:** A self-contained ISO/IEC 18004 QR code matrix encoder (Byte mode, Error Correction Level L/M) generates a boolean 2D array drawn efficiently on a Compose `Canvas` with high contrast and configurable quiet zone.

## Verification

**Commands:**
- `./gradlew testDebugUnitTest` -- expected: `BUILD SUCCESSFUL` with all unit tests passing.
- `./gradlew assembleDebug` -- expected: `BUILD SUCCESSFUL` compiling the debug APK.

**Manual checks (if no CLI):**
- Verify Dashboard displays "Cloudflare Quick Tunnel" card with active URL and QR button when enabled.
- Verify tapping the QR button opens the QR Code modal dialog with clipboard copy action.

### Review Findings (Code Review 2026-08-31)

**Review mode:** `full` — spec: `spec-5-2-managed-cloudflare-public-webhook-tunnel-sidecar.md` + `ARCHITECTURE-SPINE.md` + `epic-5-context.md`
**Diff stats:** 13 files, 1515 insertions(+), 55 deletions(-) — baseline `04fb9ab` -> `532751d` (1914 lines)
**Layers:** blind-hunter, edge-case-hunter, verification-gap, acceptance-auditor (all completed, 0 failed)
**Triage:** 1 decision-needed, 10 patch, 0 defer, 2 dismissed (merged duplicates/noise)

#### Decision-needed (requires human input)
- [x] [Review][Decision] QR ErrorCorrectionLevel API ignored — capacities, ECC, and format bits hard-coded to M while `encode(level)` accepts L/M/Q/H — **RESOLVED 2026-08-31: chose restrict to M-only** — added `require(level==M)` with clear error, matching current capacity/ECC tables and format 0x5412. Alternative per-level tables deferred. [QrCodeGenerator.kt:108-125,278]

#### Patch (fixable without ambiguity)
- [x] [Review][Patch] Tunnel not owned by HermesServerService — `CloudflareTunnelManager` scoped to `ViewModel` survives `onDestroy()`/`stopForegroundServiceInternal()` and notification stop paths, allowing orphaned `cloudflared`; ViewModel `onCleared/stopMonitoring` only cancels jobs, never calls `manager.stop()` on completed running process [ServerViewModel.kt:79-86,1079-1153] [HermesServerService.kt:138-176,271-312] — violates Never: orphaned processes and AD-6. (high)
- [x] [Review][Patch] TunnelManager race + misreported termination — `start()`/`stop()` mutate `activeProcess`/`processWatcherJob`/`isIntentionalStop` unsynchronized; URL discovery races EOF (`handleProcessExit` vs unconditional `Running`); immediate exit before URL reported as generic 30s timeout; `stopInternal` discards failure when `destroyForcibly` also fails and still reports Stopped [TunnelManager.kt:63-185] (high)
- [x] [Review][Patch] Binary resolution violates approved path — `resolveCloudflaredBinary()` searches `files/bin`, `files/cloudflared`, `/system/bin`, `/usr/local/bin`, `/usr/bin` besides canonical `files/usr/bin/cloudflared` with no spec approval [TunnelManager.kt:218-228] — violates Ask First. (medium)
- [x] [Review][Patch] QR format-information placement malformed and over-reserved — first copy duplicates (8,7) for indices 6/8 and misses required coords; second copy not per ISO; reservation loop marks 9 modules per strip including non-format modules, removing them from data placement and corrupting bit stream [QrCodeGenerator.kt:238-291] (high)
- [x] [Review][Patch] QR versions 7-10 omit mandatory version-information — encode reserves/encodes up to version 10 (57x57) but never writes the two 18-bit version-info blocks required for version >=7, allowing data to overwrite them; matrices unscannable [QrCodeGenerator.kt:260-310,70-77] (high)
- [x] [Review][Patch] Dashboard omits Starting/Error tunnel feedback — card rendered only when `tunnelUrl != null` despite `ServerUiState.tunnelState` and CODE MAP status indicator promise; users see no progress or error during `Starting`/`Error` [DashboardScreen.kt:443-516] (medium)
- [x] [Review][Patch] QR dialog flag stale — `onShowQrCodeDialog()` sets `showQrCodeDialog=true` without checking `tunnelUrl != null && Running`, and `observeTunnelState` Running->Stopped does not clear dialog flag consistently; dialog can auto-open when later URL arrives after stale flag [ServerViewModel.kt:909-915,949-978] (medium)
- [x] [Review][Patch] QR dialog fractional pixel rendering — `Canvas` computes `moduleSize = size.width / totalModules` as fractional and draws rects per module, causing uneven rasterization/antialias seams that reduce scan reliability; use integer module size centered with quiet zone [QrCodeDialog.kt:103-127] (low)
- [x] [Review][Patch] Tunnel manager construction failure silent — `effectiveTunnelManager ?: null` when `context==null` or construction throws; `onStartServer` and dynamic toggle log "Starting..." then silently no-op, leaving `Stopped` with no error state or log correlation [ServerViewModel.kt:51-88] (medium)
- [x] [Review][Patch] Verification gaps leave regressions undetectable — no QR round-trip decode assertion (ZXing/standards reader), no forced `destroyForcibly()` after 5s timeout test, no `ViewModel` test for `TunnelState.Error` clearing URL + warning log, and `TunnelManagerTest` happy-path fake keeps `isAlive=true` after EOF masking exit races [QrCodeGeneratorTest.kt:11-85] [TunnelManagerTest.kt:52-139,72-98] [ServerViewModelTest.kt:1765-1910] (medium)

#### Defer (pre-existing, not caused by this change)
_None— no pre-existing deferrals from this review._

#### Dismissed
- Merged duplicates: concurrent lifecycle + EOF races counted once; version-info and format-info kept distinct; 2 prose-only findings without location merged into above.

### Review Findings (Code Review 2026-08-31 — Hemat re-run, baseline 04fb9ab → working tree)

**Review mode:** `full` — spec: `spec-5-2-managed-cloudflare-public-webhook-tunnel-sidecar.md` + 3 context docs
**Diff stats:** 14 files, +1963/-63, 2439 lines — baseline `04fb9ab` → working tree (commit `532751d` + uncommitted)
**Layers:** blind-hunter (reviewer, 18), edge-case-hunter (delegate, 4), verification-gap (delegate, 3+1), acceptance-auditor (delegate, 5) — 2 transient 503s retried via delegate
**Triage:** 0 decision-needed, 20 patch, 2 defer, 1 dismissed (8 merged duplicates)

#### Patch (fixable without ambiguity)
- [x] [Review][Patch] Tunnel ownership split — ViewModel `effectiveTunnelManager` and Service `tunnelManager` are separate instances; service stop cannot terminate ViewModel's cloudflared, orphaned process [ServerViewModel.kt:79][HermesServerService.kt:43] (high)
- [x] [Review][Patch] Service cleanup `runBlocking` on main thread risks ANR — `stopTunnelBlocking()` blocks up to 5s+1s from `onDestroy`/`onProcessTerminatedUnexpectedly` [HermesServerService.kt:56] (high)
- [x] [Review][Patch] `ensureTunnelManager()` never called — `stopTunnelBlocking()` no-ops when `tunnelManager==null` in normal construction [HermesServerService.kt:45] (high)
- [x] [Review][Patch] `activeProcess=null` even when still alive after SIGTERM/SIGKILL — loses handle, orphaned process persists [TunnelManager.kt:235] (high)
- [x] [Review][Patch] `ServerViewModel` ignores `stop()` Result and sets `TunnelState.Stopped` anyway — UI reports stopped while process alive [ServerViewModel.kt:485,1071] (medium)
- [x] [Review][Patch] `handleProcessExit()` races `start()`/`stop()` — `handleProcessExitLocked()` called without `lifecycleMutex` (tryLock else branch) [TunnelManager.kt:247] (high)
- [x] [Review][Patch] Holds `lifecycleMutex` 30s during URL discovery — `stop()` blocked precisely when startup hangs [TunnelManager.kt:75] (high)
- [x] [Review][Patch] QR data-column direction inverted — `((right+1)/2)%2==1` false for `right==matrixSize-1`, bitstream placed backwards [QrCodeGenerator.kt:327] (high)
- [x] [Review][Patch] No ECI for UTF-8 — byte mode emits UTF-8 without ECI, non-ASCII may decode wrong [QrCodeGenerator.kt:145] (low)
- [x] [Review][Patch] Duplicate tunnel-start failure logging — `observeTunnelState` and awaiting `start()` log same `Error` twice [ServerViewModel.kt:448,977] (low)
- [x] [Review][Patch] Detached `CoroutineScope` for final cleanup not retained — `onCleared` launches untracked job, no retry [ServerViewModel.kt:1116] (medium)
- [x] [Review][Patch] `startService`/`stopService` catch-all `Throwable` hides foreground-service rejection — UI transitions to RUNNING with no error [ServerViewModel.kt:54] (medium)
- [x] [Review][Patch] Tautology `assertTrue(isSuccess||isFailure)` always true — no real assertion [TunnelManagerTest.kt:143] (medium)
- [x] [Review][Patch] `start_whenProcessExitsImmediately` accepts timeout message — cannot enforce early-exit contract [TunnelManagerTest.kt:176] (medium)
- [x] [Review][Patch] `QrCodeGeneratorTest` only structural — no decode, allows reversed data/format to pass; format/version checks weak [QrCodeGeneratorTest.kt:11,91] (high)
- [x] [Review][Patch] No `HermesServerService` tests — `onDestroy`/`onProcessTerminatedUnexpectedly` stop paths unverified [HermesServerService.kt:36] (medium)
- [x] [Review][Patch] No Compose tests for tunnel card/QR dialog — Starting/Error, button availability, copy, dismissal not verified [DashboardScreen.kt:440][QrCodeDialog.kt:48] (medium)
- [x] [Review][Patch] QR capacity not guarded — `QrCodeDialog` encodes `tunnelUrl` without `runCatching`, >213 bytes throws in Compose [DashboardScreen.kt:611][QrCodeDialog.kt:50] (medium)
- [x] [Review][Patch] `effectiveTunnelManager==null` while `isPublicTunnelEnabled==true` — service reports RUNNING with no tunnel/error feedback [ServerViewModel.kt:1044] (medium)
- [x] [Review][Patch] Error-clearing test exercises wrong branch — `tunnelError_clearsQrDialogFlag` disables tunnel instead of emitting `TunnelState.Error` [ServerViewModelTest.kt:1930] (medium)
- [x] [Review][Patch] `redirectErrorStream=true` merges stderr — spec requires async separate parse of stdout/stderr on Dispatchers.IO [TunnelManager.kt:96] (low)

#### Defer (pre-existing, already deferred per `deferred-work.md`)
- [x] [Review][Defer] Exponential backoff auto-recovery for tunnel reconnection — deferred, pre-existing [TunnelManager.kt:254] — deferred, pre-existing
- [x] [Review][Defer] Foreground notification tunnel URL display — deferred, pre-existing [HermesServerService.kt:213] — deferred, pre-existing

#### Dismissed
- `No production binary bundled` — spec allows graceful `Error` when binary missing; not a violation [TunnelManager.kt:288] (1 dismissed)
- Merged 8 duplicate claims (ownership, mutex race, QR structural) into surviving findings above.

---

## Suggested Review Order

**Tunnel Engine Lifecycle & Sidecar**

- Sidecar process spawning, streaming regex URL extraction, and clean shutdown handling
  [`TunnelManager.kt:70`](../../app/src/main/java/com/hermes/node/engine/TunnelManager.kt#L70)

**Pure Kotlin QR Code Generation & Dialog**

- Self-contained ISO/IEC 18004 QR code matrix encoding with multi-block ECC interleaving
  [`QrCodeGenerator.kt:116`](../../app/src/main/java/com/hermes/node/ui/components/QrCodeGenerator.kt#L116)

- Material 3 Dialog rendering QR matrix on Canvas with quiet zone and copy action
  [`QrCodeDialog.kt:48`](../../app/src/main/java/com/hermes/node/ui/components/QrCodeDialog.kt#L48)

**State Management & UI Integration**

- ViewModel orchestration for tunnel start/stop, dynamic toggling, and QR dialog state
  [`ServerViewModel.kt:425`](../../app/src/main/java/com/hermes/node/viewmodel/ServerViewModel.kt#L425)

- Dashboard Cloudflare Quick Tunnel card with live URL, status, and QR code trigger
  [`DashboardScreen.kt:474`](../../app/src/main/java/com/hermes/node/ui/screens/DashboardScreen.kt#L474)

- Navigation wiring connecting ViewModel QR callbacks to DashboardScreen
  [`HermesNavGraph.kt:121`](../../app/src/main/java/com/hermes/node/ui/navigation/HermesNavGraph.kt#L121)

**Unit Test Verification**

- Unit tests for CloudflareTunnelManager process execution and URL regex discovery
  [`TunnelManagerTest.kt:61`](../../app/src/test/java/com/hermes/node/engine/TunnelManagerTest.kt#L61)

- Unit tests for pure Kotlin QR Code matrix encoder
  [`QrCodeGeneratorTest.kt:11`](../../app/src/test/java/com/hermes/node/ui/components/QrCodeGeneratorTest.kt#L11)

- Unit tests for ViewModel tunnel lifecycle, QR actions, and error handling
  [`ServerViewModelTest.kt:1765`](../../app/src/test/java/com/hermes/node/viewmodel/ServerViewModelTest.kt#L1765)

