# Hermes Android Server — Technical Addendum & Architectural Notes

This document captures deep technical rationale, architectural trade-offs, and runtime design notes for **Hermes Android Server** generated during PRD discovery.

---

## 1. Runtime Architecture Options Considered

| Approach | How it Works | Pros | Cons | Verdict |
| :--- | :--- | :--- | :--- | :--- |
| **A. Embedded Linux Userland (PRoot / Termux Engine Bootstrap)** | Bundles a minimal rootfs with Python 3.11, pip dependencies, `proot` binary in APK internal storage (`files/usr`). | • Full Linux toolchain compatibility.<br>• Hermes agent skills (bash scripts, git, ripgrep, python tool execution) work identically to a Linux VPS.<br>• Zero app-hopping (no Termux app needed). | • APK size is ~50–80MB.<br>• Requires `execve`/PRoot orchestration. | **RECOMMENDED (Standard)** |
| **B. Chaquopy / Python-for-Android JNI** | Directly embeds CPython into the JVM/ART process via JNI. | • Smaller APK size.<br>• Native Java/Kotlin object bridging. | • Subprocess/bash tools in Hermes agent fail without full userland.<br>• Pure C extensions must be compiled for Android NDK. | Rejected for v1 |
| **C. External Termux Intent Bridge** | Relies on user having Termux installed and sending `RUN_COMMAND` intents. | • Minimal APK size. | • Poor UX: user must manually install Termux, grant permissions, setup scripts.<br>• Violates "One-Click / Zero-VPS" vision. | Rejected |

---

## 2. Process Lifecycle & Android OS Constraints

### 2.1 Android Doze Mode & WakeLock
To prevent Android from killing or freezing the Hermes Python process when the screen locks:
1. **Foreground Service:** Uses `Service.startForeground()` with a permanent notification.
2. **Partial WakeLock:** `PowerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "HermesNode::ServerWakeLock")` keeps CPU active while allowing screen to sleep.
3. **Battery Optimization Exemption:** Prompting `Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`.

### 2.2 Android 12+ Phantom Process Killer
- In Android 12+, child processes spawned by an app are limited to 32 and monitored by the PhantomProcessKiller.
- Hermes server runs as a single persistent daemon, optionally spawning 1–2 tool subprocesses at a time, keeping process count safely below the threshold.

---

## 3. Configuration Mapping (`hermes.json` / `.env`)

The Android GUI writes to `/data/data/com.hermes.node/files/hermes.json` before starting the daemon:

```json
{
  "model_provider": "nous_portal",
  "api_key": "YOUR_API_KEY",
  "model": "nousresearch/hermes-3-llama-3.1-405b",
  "gateways": {
    "telegram": {
      "enabled": true,
      "bot_token": "123456789:ABCDefghIJKLmnOPQRstuv",
      "allowed_users": ["123456789"]
    },
    "discord": {
      "enabled": false,
      "bot_token": ""
    },
    "web_server": {
      "enabled": true,
      "port": 8000
    }
  },
  "storage_path": "/data/data/com.hermes.node/files/agent_data"
}
```

---

## 4. Cloudflare Tunnel Integration (`cloudflared` ARM64)
- Binary: Bundled `cloudflared-linux-arm64` (~15MB).
- Command: `cloudflared tunnel --url http://127.0.0.1:8000`
- Regex Parser extracts `https://[a-zA-Z0-9-]+\.trycloudflare\.com` from stderr and exposes it to the Dashboard UI as a live remote URL.
