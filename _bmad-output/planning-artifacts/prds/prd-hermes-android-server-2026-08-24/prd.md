---
title: Hermes Android Server (Hermes Node) PRD
created: 2026-08-24
updated: 2026-08-24
status: draft
---

# PRD: Hermes Android Server (Hermes Node)

## 0. Document Purpose
This Product Requirements Document (PRD) defines the specifications for **Hermes Android Server** (working title: *Hermes Node*), an open-source Android application that packages and runs the [Hermes Agent](https://github.com/NousResearch/hermes-agent) runtime locally on Android smartphones. This document guides product development, system architecture, UX design, and QA verification. Technical implementation details, runtime comparisons (PRoot vs. Chaquopy), and process management recipes are cataloged in the accompanying `addendum.md`.

---

## 1. Vision
Every year, hundreds of millions of Android smartphones are retired to drawers despite having capable multi-core ARM64 processors, 4–8GB of RAM, built-in battery backup (UPS), and cellular/Wi-Fi connectivity. Simultaneously, autonomous AI agents like **Hermes Agent** are transitioning from ephemeral single-turn bots to persistent, long-running companions with continuous memory, scheduled tasks, and multi-channel messaging adapters (Telegram, Discord, Slack, WhatsApp).

Running an always-on agent traditionally requires renting a cloud VPS ($5–$20/month) or leaving a noisy desktop computer powered on 24/7. **Hermes Android Server** democratizes personal AI hosting by transforming any spare or primary Android phone into a silent, zero-cost, battery-backed autonomous agent server. With a seamless native interface, non-technical users can install the APK, configure their LLM provider and messaging tokens, tap **START**, and immediately interact with their self-hosted agent worldwide—with zero terminal commands required.

---

## 2. Target User

### 2.1 Jobs To Be Done (JTBD)
- **Core Functional JTBD:** "When I want my own 24/7 autonomous AI assistant, I want to host Hermes Agent on my Android phone without paying for a cloud VPS or typing Linux commands in Termux, so that my bot is always online and reachable via Telegram/Discord."
- **Hardware Recycling JTBD:** "When I have an old Android phone sitting unused, I want to repurpose it into a dedicated, low-power personal server."
- **Privacy & Ownership JTBD:** "When I interact with an AI agent, I want my agent's local sqlite memory, session history, and credentials stored locally under my control rather than on a third-party hosted SaaS."

### 2.2 Non-Users (v1)
- **Local On-Device LLM Enthusiasts (v1):** Users looking to run large quantized GGUF weights (e.g. 70B LLMs) locally on the phone's NPU/GPU. Hermes Node serves as the *orchestrator and gateway* connecting to cloud LLM APIs (Nous Portal, OpenRouter, OpenAI, Anthropic, Groq, Ollama), not a local model execution benchmark.

### 2.3 Key User Journeys

#### UJ-1. Budi turns an old phone into an always-on Telegram AI Assistant (First Launch & Setup)
- **Persona + context:** Budi, a non-technical tech enthusiast with a spare Xiaomi phone, wants an AI agent on Telegram that remembers his notes and executes daily web research.
- **Entry state:** Downloaded and installed `HermesNode.apk`.
- **Path:** 
  1. Budi opens the app. The app automatically unpacks the core runtime assets in 5–10 seconds with a clean progress bar.
  2. Budi enters his LLM API Key (Nous Portal / OpenRouter) and pastes his Telegram Bot Token.
  3. Budi toggles the main switch to **START SERVER**.
  4. Android prompts him once to disable battery optimizations; Budi taps "Allow".
- **Climax:** The status badge changes to glowing green `● ACTIVE`. A persistent notification appears: *"Hermes Agent Running - Telegram Connected"*. Budi opens Telegram on his laptop, sends `/start` to his bot, and receives an instant, intelligent greeting from Hermes.
- **Resolution:** Budi plugs the phone into a charger and leaves it on his shelf. The bot continues running uninterrupted for days.
- **Edge case:** If the phone loses Wi-Fi, the app seamlessly switches to cellular data (or queues messages locally) without crashing the daemon.

#### UJ-2. Sarah monitors agent logs and manages API keys
- **Persona + context:** Sarah, an active user, wants to check why Hermes didn't execute a specific web search tool.
- **Entry state:** Hermes Node is running in background.
- **Path:** Sarah taps the persistent notification to open Hermes Node. She switches to the **Logs** tab and sees real-time, colorized stdout/stderr messages. She spots an "Invalid Search API Key" warning, navigates to **Settings**, updates her Tavily/SerpAPI key, and taps **Save & Reload**.
- **Climax:** The daemon hot-reloads configuration without killing the session history, and Sarah sees the green confirmation log in the console.

#### UJ-3. Device unexpected reboot & recovery
- **Persona + context:** Budi's phone restarts due to an automatic OS security patch.
- **Entry state:** Device finishes booting to lock screen.
- **Path:** The app's `BootReceiver` triggers `HermesServerService` in the background. WakeLock is acquired and Hermes Agent restarts automatically.
- **Climax:** Budi's Telegram bot is back online within 30 seconds of system boot without Budi having to unlock the phone or manually open the app.

---

## 3. Glossary

- **Hermes Node:** The complete Android application package (APK) providing UI, service lifecycle, and embedded runtime.
- **Hermes Agent Core:** The upstream Python-based autonomous agent engine created by Nous Research.
- **Runtime Engine:** The embedded Linux userland environment (PRoot + ARM64 Python 3.11 rootfs) bundled inside the app's internal sandbox.
- **Foreground Service:** The persistent Android system service displaying an ongoing notification to prevent OS termination.
- **WakeLock:** An Android `PowerManager.PARTIAL_WAKE_LOCK` preventing the CPU from entering deep sleep when the screen is locked.
- **Gateway:** Hermes Agent's multi-platform server adapter handling bi-directional communication with Telegram, Discord, Slack, WhatsApp, and Webhooks.
- **Skill:** An autonomous tool module in Hermes Agent allowing the agent to run terminal commands, web searches, file edits, or cron jobs.
- **Checkpoint & Memory:** The local SQLite and file-based state storing episodic memory, conversation history, and skill evolution data.

---

## 4. Features & Functional Requirements

### 4.1 One-Click Engine Bootstrap & Runtime Extraction
**Description:** On first launch (or after an app update), the app unpacks a compressed minimal ARM64 Linux userland containing Python 3.11, pip packages, and `hermes-agent` binaries into the internal app data folder (`/data/data/com.hermes.node/files/usr`). Realizes UJ-1. `[ASSUMPTION: Pre-built rootfs tar.gz is compressed with Zstandard/XZ and bundled in APK assets or downloaded on first run if APK size needs to be under 50MB]`.

#### FR-1: Automated Runtime Bootstrap
The application can unpack and initialize the complete Python 3.11 + Hermes environment upon first launch without requiring user shell input. Realizes UJ-1.
**Consequences (testable):**
- Initial extraction completes in < 15 seconds on standard ARM64 devices.
- File permissions (e.g. `chmod +x` on binaries and python interpreters) are validated and verified before starting.

#### FR-2: Runtime Health & Integrity Check
The application can verify the presence and executable status of core binaries (`python3`, `proot`, `hermes`) on startup.
**Consequences (testable):**
- If files are corrupted or missing, the app offers a one-tap "Repair / Reinstall Runtime" action.

---

### 4.2 Configuration & Secrets Management
**Description:** A clean, intuitive GUI for all Hermes Agent parameters. Stores secrets securely using Android Keystore / `EncryptedSharedPreferences`. Generates upstream-compatible configuration files (`config.yaml` / `.env` / `hermes.json`). Realizes UJ-1, UJ-2.

#### FR-3: LLM Provider Configuration
The user can select an LLM provider from presets (Nous Portal, OpenRouter, OpenAI, Anthropic, Gemini, Groq, Ollama/Custom Base URL) and input the corresponding API key and default model name. Realizes UJ-1.
**Consequences (testable):**
- API keys are masked by default in the UI with a toggle to reveal.
- Configuration is written to the app's internal storage sandbox with strict POSIX permissions (`600`).

#### FR-4: Messaging Platform Adapters (Gateways)
The user can toggle and configure credentials for all upstream Hermes gateways:
- Telegram (Bot Token, Allowed Admin User IDs)
- Discord (Bot Token, Channel IDs)
- Slack (App Token, Bot Token)
- WhatsApp (Session link / Webhook token)
- Local Web Server / REST API (Port configuration, default: 8000)
**Consequences (testable):**
- Enabling a platform updates the Hermes configuration; disabling it prevents the gateway adapter from binding.

#### FR-5: Secure Storage for Credentials
All API keys, bot tokens, and webhook secrets must be encrypted at rest using Android Keystore-backed encryption.
**Consequences (testable):**
- Sensitive keys cannot be extracted via plain text inspection of app preferences.

---

### 4.3 Always-On Background Daemon & Power Management
**Description:** Manages the background process lifecycle using Android `ForegroundService`, `PowerManager.PARTIAL_WAKE_LOCK`, and battery optimization exception handlers. Ensures 24/7 uptime even when the screen is locked or device memory is under pressure. Realizes UJ-1, UJ-3.

#### FR-6: Master Daemon Control (Start / Stop / Restart)
The user can start, stop, or restart the Hermes server process via a single prominent switch on the Dashboard. Realizes UJ-1.
**Consequences (testable):**
- Tapping **START** transitions state from `STOPPED` $\rightarrow$ `STARTING` $\rightarrow$ `RUNNING` within 3 seconds.
- Tapping **STOP** gracefully terminates the daemon process via `SIGTERM`, with a fallback to `SIGKILL` after 5 seconds timeout.

#### FR-7: Persistent Foreground Notification
When the server is active, the app displays a persistent Android notification displaying live server status, active gateways, and quick actions (Stop / Open Dashboard). Realizes UJ-1.
**Consequences (testable):**
- Notification cannot be swiped away while the server is active.
- Tapping the notification immediately brings Hermes Node to the foreground.

#### FR-8: Battery Optimization Whitelisting Prompt
The app detects if battery optimization is enabled for Hermes Node and presents a standard Android system intent prompt to request exemption (`ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`). Realizes UJ-1.
**Consequences (testable):**
- App displays OEM-specific guidance (Xiaomi/MIUI, Samsung/OneUI, Oppo/ColorOS) linking to *DontKillMyApp* settings if background process termination is detected.

#### FR-9: Auto-Start on Device Boot
The user can toggle "Auto-start on boot" in Settings. When enabled, the app's `BootReceiver` starts the Foreground Service automatically when the Android OS finishes booting. Realizes UJ-3.
**Consequences (testable):**
- Hermes server starts without requiring manual device unlocking if Direct Boot / Credential storage allows.

---

### 4.4 Live Telemetry, Console Stream & Health Dashboard
**Description:** Real-time visibility into the agent's brain: status indicators, CPU/RAM usage, phone temperature, battery level/charging state, and a colorized terminal log streamer. Realizes UJ-2.

#### FR-10: Real-Time Status & Resource Monitor
The Dashboard displays:
- Server State: `RUNNING`, `STARTING`, `STOPPED`, `ERROR`
- Device Metrics: CPU Utilization %, RAM used (MB/GB), Battery % & Charging indicator (⚡), Battery Temperature (°C)
- Active Gateways: Telegram (Connected/Error), Discord, etc.
**Consequences (testable):**
- Metrics update at a configurable interval (default: 2 seconds when UI is visible; paused when UI is hidden to save power).

#### FR-11: Live Console Log Streamer
The Logs screen streams standard output (`stdout`) and standard error (`stderr`) from the Hermes sub-process with syntax highlighting (INFO, WARN, ERROR) and auto-scroll. Realizes UJ-2.
**Consequences (testable):**
- User can search/filter logs, copy logs to clipboard, or export full log file.
- Buffer holds at least 2,000 lines in memory with circular pruning to prevent UI memory leaks.

---

### 4.5 Skills, Checkpoint & Memory Management
**Description:** Allows users to inspect and toggle Hermes skills and manage persistent agent memory/checkpoints directly from the mobile UI.

#### FR-12: Skill Manifest Viewer & Toggles
The user can view installed Hermes skills (e.g. web search, file manager, bash runner, cron scheduler) and toggle individual skills on or off.
**Consequences (testable):**
- Disabling a skill updates the skill configuration and disables the tool in the agent prompt loop.

#### FR-13: Memory & State Management (Backup & Clear)
The user can inspect memory storage size, export a backup of the agent's SQLite database/checkpoints, or perform a factory reset of agent memory.
**Consequences (testable):**
- Backups can be saved to the Android `Downloads` directory or shared via system Share Sheet.

---

### 4.6 Remote Access & Webhook Tunneling (Adapt-In)
**Description:** Enables remote access to Hermes local Web UI / REST webhooks without requiring public IPv4 or manual router port-forwarding. `[ASSUMPTION: Optional built-in Cloudflare Quick Tunnel (cloudflared ARM64) or Tailscale integration].`

#### FR-14: Optional Built-in Cloudflare Tunnel
The user can toggle "Enable Public Tunnel" to automatically launch a lightweight `cloudflared` tunnel, generating a secure `https://<random>.trycloudflare.com` URL displayed in the dashboard.
**Consequences (testable):**
- Cloudflare URL is displayed with a "Copy URL" button and QR code for easy scanning from another phone or browser.

---

## 5. Non-Goals (Explicit)

- **Local LLM Inference Engine:** Hermes Node is **not** an on-device model runner (like Ollama/Llama.cpp on phone). It connects to cloud APIs or local LAN servers.
- **Full-featured Linux Desktop / Terminal Client:** Hermes Node is **not** an open-ended terminal emulator like Termux; it is a dedicated, zero-CLI appliance for Hermes Agent.
- **Root-Only Privileges Requirement:** The app must operate 100% on **non-rooted** standard Android devices (Android 9.0+ / API 28+).

---

## 6. MVP Scope

### 6.1 In Scope (v1.0 MVP)
- Standalone APK with bundled ARM64 Python 3.11 + Hermes Agent runtime.
- Automated first-run environment extraction.
- Configuration UI for LLM Providers (Nous, OpenRouter, OpenAI, Anthropic, Gemini, Groq, Ollama) and Gateways (Telegram, Discord, Webhook/Web UI).
- Background Foreground Service + WakeLock + Battery Optimization handler.
- Dashboard with Start/Stop switch, status indicators, and device hardware telemetry.
- Live streaming log viewer with export capability.
- Auto-start on device boot (`RECEIVE_BOOT_COMPLETED`).
- Encrypted storage for API keys.

### 6.2 Out of Scope for MVP (Deferred to v1.1+)
- Multi-agent orchestration / clustering multiple phones together `[Deferred to v1.2]`.
- Voice assistant wake-word listener on phone microphone `[Deferred to v1.2]`.
- On-device local small SLM model runner (e.g. SmolLM 135M / Qwen 0.5B fallback for offline logic) `[Deferred to v2.0]`.

---

## 7. Success Metrics

### 7.1 Primary Metrics
- **SM-1 (Setup Time):** Median time from APK installation to active Telegram bot response is **< 3 minutes** for first-time users. (Validates FR-1, FR-3, FR-4, FR-6).
- **SM-2 (Uptime Reliability):** Continuous server background uptime **> 72 hours** on standard Android devices without unexpected OS process termination when plugged into power. (Validates FR-6, FR-7, FR-8).
- **SM-3 (Zero-CLI Friction):** 100% of core configuration, startup, and recovery flows achievable with zero shell commands. (Validates FR-1, FR-3, FR-6).

### 7.2 Counter-Metrics (Do Not Optimize)
- **SM-C1 (Battery Drain Rate when unplugged):** Do not compromise WakeLock reliability to save minor battery percentages while the server is active. If the server is ON, background responsiveness takes precedence over aggressive sleep. (Users are instructed to keep server phones plugged in).

---

## 8. Open Questions & Technical Spikes

1. **Rootfs Distribution Mechanism:** Should the minimal rootfs (~35-60MB compressed) be bundled inside the APK assets (creating a ~50-70MB APK) or downloaded during first launch from GitHub Releases? `[Recommendation: Bundle inside APK for 100% offline/one-click experience without extra CDN dependencies]`.
2. **Android 12+ Phantom Process Killer:** Android 12+ limits background child processes spawned by an app to 32. Since Hermes spawns only 1-3 subprocesses, this should be safe, but needs testing on Android 14/15.
3. **UI Tech Stack:** Kotlin + Jetpack Compose vs. Flutter. (Jetpack Compose offers tighter native Service/Notification/JNI lifecycle control; Flutter offers faster multi-platform UI iteration. `[NOTE FOR ARCHITECT]`).

---

## 9. Assumptions Index

- `[ASSUMPTION: §4.1]` Pre-built ARM64 Linux userland / Python 3.11 rootfs can be bundled directly in APK assets or fetched seamlessly.
- `[ASSUMPTION: §4.3]` User is willing to grant "Ignore Battery Optimization" and keep the phone plugged into AC power for dedicated server use.
- `[ASSUMPTION: §4.6]` Cloudflare quick tunnel binary (`cloudflared` ARM64) is included as an optional lightweight helper for public webhook routing.
