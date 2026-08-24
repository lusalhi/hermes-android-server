---
stepsCompleted:
  - step-01-validate-prerequisites
  - step-02-design-epics
  - step-03-create-stories
  - step-04-final-validation
inputDocuments:
  - _bmad-output/planning-artifacts/prds/prd-hermes-android-server-2026-08-24/prd.md
  - _bmad-output/planning-artifacts/prds/prd-hermes-android-server-2026-08-24/addendum.md
  - _bmad-output/planning-artifacts/architecture/architecture-hermes-android-server-2026-08-24/ARCHITECTURE-SPINE.md
---

# Hermes Android Server - Epic Breakdown

## Overview

This document provides the complete epic and story breakdown for Hermes Android Server (Hermes Node), decomposing the requirements from the PRD, PRD Addendum, and Architecture Spine into implementable, value-driven user stories.

## Requirements Inventory

### Functional Requirements

- **FR-1**: Automated Runtime Bootstrap — Unpack and initialize complete Python 3.11 + Hermes runtime environment into internal app sandbox (`/data/data/com.hermes.node/files/usr`) on first launch in <15s with verified POSIX execution permissions.
- **FR-2**: Runtime Health & Integrity Check — Verify presence and executable status of core binaries (`python3`, `proot`, `hermes`) on startup with one-tap repair/reinstall action.
- **FR-3**: LLM Provider Configuration — Configure LLM provider presets (Nous Portal, OpenRouter, OpenAI, Anthropic, Gemini, Groq, Ollama/Custom Base URL) with masked API key inputs and strict POSIX `0600` configuration output.
- **FR-4**: Messaging Platform Adapters (Gateways) — Configure credentials and enable/disable switches for upstream Hermes messaging gateways (Telegram Bot Token + Admin IDs, Discord Bot Token + Channel IDs, Slack App/Bot Tokens, WhatsApp Webhooks, and local REST API on port 8000).
- **FR-5**: Secure Storage for Credentials — Encrypt all API keys, bot tokens, and webhook secrets at rest using Android Keystore / `EncryptedSharedPreferences`.
- **FR-6**: Master Daemon Control (Start / Stop / Restart) — Prominent dashboard switch to start, stop, or restart the Hermes server process with graceful `SIGTERM` and 5-second `SIGKILL` fallback.
- **FR-7**: Persistent Foreground Notification — Display ongoing Android notification with live server status, active gateways, and quick actions (Stop / Open Dashboard) that cannot be swiped away while active.
- **FR-8**: Battery Optimization Whitelisting Prompt — Detect battery optimization status and request `ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` exemption with OEM-specific DontKillMyApp guidance.
- **FR-9**: Auto-Start on Device Boot — Automatically trigger foreground service and start Hermes daemon on Android system boot via `RECEIVE_BOOT_COMPLETED`.
- **FR-10**: Real-Time Status & Resource Monitor — Live dashboard updating server state (`RUNNING`, `STARTING`, `STOPPED`, `ERROR`), device telemetry (CPU %, RAM MB/GB, Battery %/⚡, Battery Temp °C), and active gateway connection health.
- **FR-11**: Live Console Log Streamer — Real-time monospaced log stream of `stdout`/`stderr` with level filtering (`INFO`, `WARN`, `ERROR`, `DEBUG`), search, copy-to-clipboard, and circular 2,000-line memory buffer.
- **FR-12**: Skill Manifest Viewer & Toggles — Inspect installed Hermes agent skills (web search, file manager, bash runner, cron scheduler) and toggle individual skills on/off.
- **FR-13**: Memory & State Management (Backup & Clear) — Inspect memory storage size, export SQLite database and conversation checkpoints to Android Downloads / Share Sheet, or perform a factory reset.
- **FR-14**: Optional Built-in Cloudflare Tunnel — Launch a lightweight `cloudflared` ARM64 sidecar process to expose the local dashboard/webhook via a secure `https://<random>.trycloudflare.com` URL with QR code and clipboard copy.

### NonFunctional Requirements

- **NFR-1 (Setup Time)**: Median time from APK installation to active Telegram bot response is < 3 minutes on standard ARM64 Android devices.
- **NFR-2 (Uptime Reliability)**: Continuous background uptime > 72 hours without unexpected process termination when plugged into AC power.
- **NFR-3 (Zero-CLI Friction)**: 100% of core configuration, startup, runtime recovery, and logging achieved via GUI with zero terminal commands.
- **NFR-4 (Compatibility & Privileges)**: 100% operation on standard non-rooted ARM64 devices running Android 9.0+ (API 28+), targeting API 34/35.
- **NFR-5 (Resource Footprint)**: Idle memory usage of Android host service < 120MB RAM, CPU consumption < 5% when agent is idle.
- **NFR-6 (Security at Rest)**: Keystore-backed AES-256 GCM encryption for stored secrets; generated config files restricted to app UID (`0600`).

### Additional Requirements

- **ARCH-1 (Starter Template & Project Scaffold)**: Multi-module Android project (Gradle 8.9, Kotlin 2.0.0, Compose BOM 2024.06.00, Material 3, minSdk 28, compileSdk 34) with UDF state management (`ServerViewModel` & `ServerUiState`).
- **ARCH-2 (Embedded PRoot Linux Userland)**: Pre-compiled ARM64 PRoot binary and compressed Linux rootfs (`bootstrap-arm64.tar.xz`) unpacked into `/data/data/com.hermes.node/files/usr` with POSIX file permissions.
- **ARCH-3 (Android Foreground Service & WakeLock Engine)**: `HermesServerService` lifecycle manager acquiring `PowerManager.PARTIAL_WAKE_LOCK`, notification channel management, and bound IPC.
- **ARCH-4 (Coroutine IO Log Streamer & RingBuffer)**: Asynchronous stdout/stderr capture on `Dispatchers.IO` using a thread-safe circular `RingBuffer` capped at 2,000 lines.
- **ARCH-5 (EncryptedSharedPreferences & Config Serialization)**: `ConfigRepository` wrapping AndroidX Security Crypto, serializing atomic `hermes.json` / `.env` files with `0600` permissions.
- **ARCH-6 (Cloudflared Tunnel Sidecar Process)**: Secondary sidecar process manager for `cloudflared` ARM64 binary with URL regex parsing into `ServerUiState`.

### UX Design Requirements

- **UX-DR1 (Material 3 Cyber Theme & Tokens)**: Cyber-terminal aesthetic with high-contrast status colors (`StatusRunning`, `StatusStarting`, `StatusStopped`, `StatusError`), monospaced code typography, and dynamic dark/light theme support.
- **UX-DR2 (Dashboard Hero & Metric Cards)**: Large status pill badge, Start/Stop toggle button, 2x2 telemetry grid (CPU, RAM, Uptime, Temp), and dynamic gateway indicator card.
- **UX-DR3 (Console Log Streamer UX)**: Monospaced terminal window with level filtering chips (`ALL`, `INFO`, `WARN`, `ERROR`, `DEBUG`), search bar, auto-scroll lock toggle, clear logs, and copy-all action.
- **UX-DR4 (Settings Configuration Forms)**: Provider dropdown selector, password-masked API Key and Telegram Bot Token inputs with visibility toggles, custom endpoint text fields, and auto-start / public tunnel switches.
- **UX-DR5 (Battery Optimization & OEM Exemption UX)**: In-app warning banner and direct intent launcher for `ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` with OEM guidance.

### FR Coverage Map

- **FR-1**: Epic 2 — Automated Runtime Bootstrap
- **FR-2**: Epic 2 — Runtime Health & Integrity Check
- **FR-3**: Epic 2 — LLM Provider Configuration & Secret Persistence
- **FR-4**: Epic 5 — Multi-Platform Messaging Gateways (Telegram, Discord, Slack, WhatsApp)
- **FR-5**: Epic 2 — Keystore-Backed Credential Encryption at Rest
- **FR-6**: Epic 3 — Master Daemon Control & Process Lifecycle (Start / Stop / Restart)
- **FR-7**: Epic 3 — Persistent Foreground Service Notification & WakeLock
- **FR-8**: Epic 3 — Battery Optimization Whitelisting & OEM Exemption Prompts
- **FR-9**: Epic 3 — Auto-Start Daemon on Device Boot
- **FR-10**: Epic 4 — Real-Time Resource Telemetry & Health Dashboard
- **FR-11**: Epic 4 — Asynchronous Live Console Streamer & Ring Buffer
- **FR-12**: Epic 6 — Skill Manifest Inspection & Tool Toggles
- **FR-13**: Epic 6 — Memory & Checkpoint State Backup / Clear
- **FR-14**: Epic 5 — Cloudflare Public Webhook Tunneling Sidecar

## Epic List

### Epic 1: Android Project Scaffold, Material 3 Theming & Compose Navigation Shell
Establish the Android application foundation, Gradle build system, cyber Material 3 visual theme, Unidirectional Data Flow state contracts, and 3-screen tab navigation (Dashboard, Logs, Settings).
**FRs covered:** ARCH-1, UX-DR1, UX-DR2, UX-DR4 *(Completed)*

### Epic 2: Embedded PRoot Userland Extraction, Binary Health & Keystore Secrets Persistence
Unpack the pre-compiled ARM64 PRoot userland (`bootstrap-arm64.tar.xz`) into internal app storage on first launch, verify binary integrity with one-tap repair, and persist LLM API keys and bot tokens securely using Android Keystore / `EncryptedSharedPreferences` to output POSIX `0600` `hermes.json` config.
**FRs covered:** FR-1, FR-2, FR-3, FR-5, ARCH-2, ARCH-5, NFR-1, NFR-6

### Epic 3: Always-On Background Daemon, WakeLock Management & Boot Auto-Start
Implement `HermesServerService` with `PowerManager.PARTIAL_WAKE_LOCK` and ongoing notification to manage the child process lifecycle with graceful `SIGTERM` / `SIGKILL` timeouts, battery optimization prompts, and device boot auto-start via `RECEIVE_BOOT_COMPLETED`.
**FRs covered:** FR-6, FR-7, FR-8, FR-9, ARCH-3, UX-DR5, NFR-2, NFR-4

### Epic 4: Real-Time Console Log Streamer, Memory RingBuffer & System Telemetry
Capture `stdout`/`stderr` from the Hermes sub-process asynchronously on `Dispatchers.IO` into a thread-safe 2,000-line circular `RingBuffer`, streaming to the monospaced terminal UI with level filters, search, clipboard export, and real-time CPU/RAM/Battery hardware stats.
**FRs covered:** FR-10, FR-11, ARCH-4, UX-DR3, NFR-3, NFR-5

### Epic 5: Multi-Platform Messaging Gateways & Cloudflare Public Tunnel Sidecar
Wire upstream Hermes gateway adapters (Telegram Bot, Discord Bot, Slack, WhatsApp, and REST API on port 8000) and provide an optional, managed `cloudflared` ARM64 sidecar process to expose local webhooks via secure public HTTPS URLs with QR codes.
**FRs covered:** FR-4, FR-14, ARCH-6

### Epic 6: Agent Skills Management, Episodic Memory Backups & Checkpoint Reset
Provide a UI to view and toggle installed Hermes agent skills (web search, file manager, bash runner, cron) and manage local SQLite checkpoints/episodic memory with export to Downloads/Share Sheet and factory memory reset.
**FRs covered:** FR-12, FR-13

---

## Epic 1: Android Project Scaffold, Material 3 Theming & Compose Navigation Shell

Establish the Android application foundation, Gradle build system, cyber Material 3 visual theme, Unidirectional Data Flow state contracts, and 3-screen tab navigation (Dashboard, Logs, Settings). *(Implemented & Verified in Commit `9deec8d`)*

### Story 1.1: Android Project Gradle Toolchain & Dependency Foundation

As an Android developer,
I want a standardized Gradle multi-layer build configuration with Kotlin 2.0, Compose BOM 2024.06.00, and Android SDK 34 targets,
So that core runtime libraries, Jetpack Compose UI, coroutines, and unit testing frameworks compile reliably with Java 17.

**Acceptance Criteria:**

**Given** a clean project root with Android SDK 34 installed
**When** executing `./gradlew assembleDebug` and `./gradlew test`
**Then** the build completes with `BUILD SUCCESSFUL` producing `app-debug.apk`
**And** all module settings target `minSdk = 28` and `compileSdk = 34` with Java 17 compatibility.

### Story 1.2: Cyber-Terminal Material 3 Theme & Design Tokens

As a user,
I want a cyber-terminal visual design system with high-contrast status colors and monospaced typography,
So that monitoring and interacting with the agent runtime is visually clear and comfortable in both dark and light modes.

**Acceptance Criteria:**

**Given** the application theme `HermesTheme`
**When** applied to the UI in dark mode or light mode
**Then** custom brand tokens (`HermesCyan`, `DarkBackground`, `DarkSurface`, `StatusRunning`, `StatusStopped`, `StatusError`) are rendered accurately
**And** `MonospaceCodeStyle` renders with fixed-width font for all terminal log text.

### Story 1.3: Unidirectional Data Flow State Architecture & Tab Navigation Shell

As a user,
I want a 3-tab bottom navigation bar (Dashboard, Logs, Settings) backed by a central `ServerViewModel`,
So that I can smoothly switch between screens without losing state, input data, or log streams.

**Acceptance Criteria:**

**Given** `MainActivity` hosting `HermesApp`
**When** switching between Dashboard, Logs, and Settings tabs
**Then** the active screen composable is displayed with proper backstack retention
**And** `ServerViewModel` exposes an immutable `StateFlow<ServerUiState>` handling UI events predictably.

---

## Epic 2: Embedded PRoot Userland Extraction, Binary Health & Keystore Secrets Persistence

Unpack the pre-compiled ARM64 PRoot userland (`bootstrap-arm64.tar.xz`) into internal app storage on first launch, verify binary integrity with one-tap repair, and persist LLM API keys and bot tokens securely using Android Keystore / `EncryptedSharedPreferences` to output POSIX `0600` `hermes.json` config.

### Story 2.1: Automated ARM64 Linux Rootfs Bootstrap & Permission Setup

As a non-technical user,
I want the app to automatically unpack the embedded Python 3.11 and PRoot environment on first launch,
So that I never have to manually run terminal setup commands or configure Linux environments.

**Acceptance Criteria:**

**Given** first app launch or updated app version
**When** `BootstrapExtractor` initiates asset extraction
**Then** `bootstrap-arm64.tar.xz` is unpacked into `/data/data/com.hermes.node/files/usr` in <15 seconds
**And** executable file permissions (`chmod 755`) are verified for `proot`, `python3`, and standard binaries.

### Story 2.2: Binary Health Check & One-Tap Environment Repair

As a user,
I want the app to verify that core binaries (`python3`, `proot`, `hermes`) are intact and functional on startup,
So that corrupted runtime files can be identified and repaired with a single tap.

**Acceptance Criteria:**

**Given** an integrity check on application start
**When** any core binary is missing or non-executable
**Then** the UI displays an integrity warning banner with a "Repair Runtime" action
**And** tapping "Repair" cleanly re-extracts the userland without deleting user episodic database checkpoints.

### Story 2.3: Keystore-Backed Encrypted Secrets Repository & POSIX 0600 Config Serialization

As a privacy-conscious user,
I want my LLM API keys, Telegram tokens, and provider settings encrypted at rest with Android Keystore,
So that secrets are never stored in plain text and generated configuration files are restricted to app UID permissions (`0600`).

**Acceptance Criteria:**

**Given** entered API keys and provider selections in Settings
**When** tapping "Save Settings"
**Then** secrets are encrypted using `EncryptedSharedPreferences` (AES-256 GCM)
**And** configuration is written atomically to `/data/data/com.hermes.node/files/hermes.json` with strict POSIX `0600` permissions.

---

## Epic 3: Always-On Background Daemon, WakeLock Management & Boot Auto-Start

Implement `HermesServerService` with `PowerManager.PARTIAL_WAKE_LOCK` and ongoing notification to manage the child process lifecycle with graceful `SIGTERM` / `SIGKILL` timeouts, battery optimization prompts, and device boot auto-start via `RECEIVE_BOOT_COMPLETED`.

### Story 3.1: Android Foreground Service with Partial WakeLock

As a user hosting an always-on agent,
I want the Hermes server to run in an Android `ForegroundService` holding a CPU WakeLock,
So that the daemon stays alive and responsive 24/7 even when the phone screen is locked or in Doze mode.

**Acceptance Criteria:**

**Given** the user taps START SERVER on the Dashboard
**When** `HermesServerService` starts as a Foreground Service
**Then** `PowerManager.PARTIAL_WAKE_LOCK` is acquired and an ongoing notification displays live status
**And** the notification cannot be swiped away while the daemon is actively running.

### Story 3.2: Native Child Process Controller with Graceful Signal Handling

As a user,
I want master daemon Start/Stop controls that cleanly launch and terminate the underlying POSIX sub-process,
So that the daemon terminates gracefully without leaving orphan zombie processes or corrupted state.

**Acceptance Criteria:**

**Given** a running Hermes daemon process
**When** the user taps STOP SERVER
**Then** `ProcessManager` sends `SIGTERM` to the process group
**And** if the process does not terminate within 5 seconds, a fallback `SIGKILL` is executed and WakeLock is released.

### Story 3.3: Battery Optimization Exemption & Boot Completed Auto-Start

As a user turning a phone into a dedicated server,
I want battery optimization whitelisting and auto-start on boot,
So that the agent automatically resumes operation after unexpected device reboots or OS memory pressure.

**Acceptance Criteria:**

**Given** "Auto-start on boot" enabled in Settings
**When** device finishes booting (`ACTION_BOOT_COMPLETED`)
**Then** `BootReceiver` triggers `HermesServerService` to launch the daemon in the background
**And** if battery optimization is enabled, the app prompts for `ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` with OEM guidance.

---

## Epic 4: Real-Time Console Log Streamer, Memory RingBuffer & System Telemetry

Capture `stdout`/`stderr` from the Hermes sub-process asynchronously on `Dispatchers.IO` into a thread-safe 2,000-line circular `RingBuffer`, streaming to the monospaced terminal UI with level filters, search, clipboard export, and real-time CPU/RAM/Battery hardware stats.

### Story 4.1: Asynchronous Coroutine Process I/O Streamer with Circular RingBuffer

As a user,
I want the app to capture child process standard output and error streams into a bounded 2,000-line circular buffer,
So that logs are preserved without overflowing device memory or freezing the UI thread.

**Acceptance Criteria:**

**Given** the running Hermes sub-process emitting `stdout` and `stderr`
**When** `LogStreamer` reads streams asynchronously on `Dispatchers.IO`
**Then** lines are parsed into `LogEntry` items with severity (`INFO`, `WARN`, `ERROR`, `DEBUG`)
**And** the buffer automatically prunes old entries beyond 2,000 items without memory leakage.

### Story 4.2: Terminal Console UI with Real-Time Filtering, Search & Export

As a user debugging agent behavior,
I want a monospaced terminal log viewer with level filtering, text search, auto-scroll lock, and clipboard copy,
So that I can quickly inspect tool calls, agent thoughts, and errors.

**Acceptance Criteria:**

**Given** the Logs screen with active log streaming
**When** filtering by severity chips or typing a search query
**Then** the log list displays only matching entries in real-time
**And** tapping "Copy Logs" copies the formatted log stream with timestamps to the Android clipboard.

### Story 4.3: Real-Time Hardware Telemetry & Device Health Monitor

As a user,
I want to monitor phone CPU utilization, memory consumption, battery level, charging status, and battery temperature in real-time,
So that I can verify the physical server phone is operating safely without overheating.

**Acceptance Criteria:**

**Given** the Dashboard screen
**When** viewing telemetry cards
**Then** CPU %, RAM used (MB), Uptime (HH:MM:SS), and battery temperature update dynamically every 2 seconds
**And** polling pauses when the app is in the background to conserve power.

---

## Epic 5: Multi-Platform Messaging Gateways & Cloudflare Public Tunnel Sidecar

Wire upstream Hermes gateway adapters (Telegram Bot, Discord Bot, Slack, WhatsApp, and REST API on port 8000) and provide an optional, managed `cloudflared` ARM64 sidecar process to expose local webhooks via secure public HTTPS URLs with QR codes.

### Story 5.1: Multi-Platform Messaging Gateway Adapters (Telegram, Discord, Slack, WhatsApp)

As a user,
I want to configure and toggle multiple messaging platforms in Settings,
So that I can interact with my self-hosted Hermes agent across my favorite chat apps worldwide.

**Acceptance Criteria:**

**Given** configured Telegram bot token and admin user ID
**When** the Hermes server starts
**Then** Hermes Agent connects to Telegram Gateway and logs successful connection
**And** sending a message to the bot on Telegram receives an intelligent response generated by the configured LLM provider.

### Story 5.2: Managed Cloudflare Public Webhook Tunnel Sidecar

As a user without a public IPv4 address or router port-forwarding access,
I want to toggle a built-in Cloudflare tunnel,
So that my local Hermes webhooks and dashboard can be reached securely over the public internet.

**Acceptance Criteria:**

**Given** "Cloudflare Public Tunnel" enabled in Settings
**When** the server starts
**Then** `TunnelManager` spawns the `cloudflared` ARM64 sidecar process
**And** the generated `https://*.trycloudflare.com` URL is extracted and displayed on the Dashboard with a copy button and QR code.

---

## Epic 6: Agent Skills Management, Episodic Memory Backups & Checkpoint Reset

Provide a UI to view and toggle installed Hermes agent skills (web search, file manager, bash runner, cron) and manage local SQLite checkpoints/episodic memory with export to Downloads/Share Sheet and factory memory reset.

### Story 6.1: Skill Manifest Viewer & Tool Enablement Toggles

As a user,
I want to view all installed Hermes skills and toggle specific tools on or off,
So that I can control what capabilities and permissions my agent possesses.

**Acceptance Criteria:**

**Given** the Skills section in Settings
**When** the user toggles a skill (e.g. web search or file manager)
**Then** `hermes.json` updates the skill whitelist and reloads the agent tool registry without restarting the phone.

### Story 6.2: Episodic Memory Inspector, Database Backup & Factory Reset

As a user,
I want to inspect my agent's local memory size, export SQLite conversation checkpoints to my phone's Downloads directory, and reset memory if needed,
So that my private notes and memories remain under my complete physical control.

**Acceptance Criteria:**

**Given** Memory Management in Settings
**When** the user taps "Export Memory"
**Then** SQLite database files and conversation checkpoints are exported to the Android Downloads directory or shared via System Share Sheet
**And** tapping "Clear Memory" wipes episodic memory only after explicit user confirmation.
