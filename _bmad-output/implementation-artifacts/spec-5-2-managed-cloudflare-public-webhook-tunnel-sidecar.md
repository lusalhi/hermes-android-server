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

