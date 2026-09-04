# Story 6.8: Upstream Hermes Python Rootfs & Sub-Process Telegram Agent Runner

## Context & Objectives
In Story 6.7, the custom Kotlin-side `TelegramGatewayManager` was completely purged to serve upstream Hermes Agent directly in the Linux sub-process. However, testing on a physical Android ARM64 device revealed:
1. **Rootfs Was Incomplete / Mock:** `app/src/main/assets/bootstrap-arm64.tar.xz` only contained stub shell scripts (`sleep 2` loops), with no real ARM64 Python runtime or genuine `hermes` CLI package.
2. **Missing PRoot Dependencies & Paths:** The PRoot binary requires Android Bionic libraries (`libtalloc.so.2`, `libandroid-shmem.so`), `PROOT_LOADER`, and `PROOT_TMP_DIR` environment variables, as well as `LD_LIBRARY_PATH`.
3. **Telegram Bot Inactivity:** With no genuine Python Telegram runner executing in the sub-process, the bot did not poll or respond to user messages (`list file dong`, `/start`, etc.).

## Key Requirements & Acceptance Criteria
1. **Real ARM64 Linux Rootfs (`bootstrap-arm64.tar.xz`):**
   - Package Alpine Linux aarch64 base with standard Unix utilities (`busybox`, `sh`, `ls`, `cat`, etc.) and `apk` package manager.
   - Package real ARM64 Python 3 (3.12) runtime with standard library (`sqlite3`, `json`, `urllib`, `ssl`, `asyncio`, `subprocess`).
   - Package Termux PRoot ARM64 binary (`usr/bin/proot`), PRoot loader (`usr/libexec/proot/loader`), and runtime libraries (`lib/libtalloc.so.2`, `lib/libandroid-shmem.so`).
   - Package the `hermes` Python agent package in `usr/lib/python3.12/site-packages/hermes/` (and alias in `python3.11/site-packages/hermes/`).
2. **Hermes Agent Sub-Process Runner (`python3 -m hermes gateway run`):**
   - Implements CLI subcommands: `gateway run`, `gateway status`, `chat`, `version`.
   - Long-polls Telegram Bot API (`https://api.telegram.org/bot<TOKEN>/getUpdates`) when `TELEGRAM_BOT_TOKEN` is configured.
   - Enforces user authorization (`TELEGRAM_ALLOWED_USERS` whitelist).
   - ReAct Agent execution loop with real tool dispatching:
     - `bash`: executes commands directly in Linux shell (`ls -lah`, `cat`, `pwd`, `apk`, etc.) and returns stdout/stderr.
     - `file_manager`: reads and writes files.
     - `web_search`: executes searches via configured provider (Brave, Tavily, etc.).
     - `memory`: episodic memory stored in SQLite database (`~/.hermes/memory.db`).
   - Sends completions and answers back to Telegram chats.
   - Formatted console logging to stdout for Android real-time terminal viewer.
3. **Android Kotlin Engine Configuration:**
   - In `ProcessController.kt`:
     - Provide `LD_LIBRARY_PATH`, `PROOT_LOADER`, `PROOT_TMP_DIR`, `PROOT_NO_SECCOMP=1` in the sub-process environment.
     - Launch command properly bound inside PRoot.
   - In `BootstrapExtractor.kt`:
     - Extract all binaries with 0755 execute permissions.
     - Keep shims (`sudo`, `apt`, `apt-get`) and launcher in sync.
4. **Verification:**
   - Unit tests pass.
   - Build produces verified debug APK.
