# Epic 2 Context: Embedded PRoot Userland Extraction, Binary Health & Keystore Secrets Persistence

<!-- Compiled from planning artifacts. Edit freely. Regenerate with compile-epic-context if planning docs change. -->

## Goal

Unpack the pre-compiled ARM64 PRoot userland (Python 3.11, PRoot binary, dependencies) into internal app storage (`/data/data/com.hermes.node/files/usr`) on first launch, verify binary integrity with one-tap repair, and persist LLM API keys and bot tokens securely using Android Keystore / EncryptedSharedPreferences to output POSIX `0600` `hermes.json` config.

## Stories

- Story 2.1: Automated ARM64 Linux Rootfs Bootstrap & Permission Setup
- Story 2.2: Binary Health Check & One-Tap Environment Repair
- Story 2.3: Keystore-Backed Encrypted Secrets Repository & POSIX 0600 Config Serialization

## Requirements & Constraints

- Automatically unpack embedded Python 3.11 + PRoot userland (`bootstrap-arm64.tar.xz` / assets) into internal app sandbox (`/data/data/com.hermes.node/files/usr`) on first launch in <15s with verified POSIX execution permissions (`chmod 755` on binaries).
- Verify presence and executable status of core binaries (`python3`, `proot`, `hermes`) on startup with a one-tap repair/reinstall action that preserves user data.
- Encrypt all LLM API keys and gateway tokens using Android Keystore via `EncryptedSharedPreferences` (AES-256 GCM) at rest.
- Generate and serialize `hermes.json` configuration file atomically with strict POSIX `0600` file permissions (read/write only by app UID).
- Zero-terminal friction: userland extraction, integrity validation, and configuration must happen seamlessly without requiring root or external Termux app.

## Technical Decisions

- **PRoot Userland Sandbox (AD-2)**: Linux userland unpacked into `/data/data/com.hermes.node/files/usr`. Binaries executed via PRoot ARM64 emulation for full Linux environment compatibility on unrooted devices.
- **Extraction Engine**: `com.hermes.node.engine.BootstrapExtractor` unpacks assets using decompression streams / tar archive handling, creates directory structure, and sets executable POSIX permissions (`File.setExecutable(true, false)` / `chmod 755`).
- **Encrypted Storage (AD-5)**: `com.hermes.node.data.ConfigRepository` uses AndroidX Security Crypto (`EncryptedSharedPreferences`) for storing secret keys and credentials.
- **Config Serialization**: `com.hermes.node.data.ConfigSerializer` writes `hermes.json` atomically with POSIX `0600` permissions (`setReadable(true, true)`, `setWritable(true, true)`, `setExecutable(false, false)`).
- **Package Convention**: Extractor in `com.hermes.node.engine`, Config & persistence in `com.hermes.node.data`.

## UX & Interaction Patterns

- Automatic bootstrap progress indicator on first launch or when runtime needs extraction.
- Settings screen masked API key inputs and credential save flows.
- Integrity error banner with "Repair" action if core runtime binaries fail health check.

## Cross-Story Dependencies

- Story 2.1 establishes the userland directory structure and binaries required by Story 2.2 (health check & repair) and Epic 3 (subprocess execution).
- Story 2.3 creates the configuration file consumed by the child process launched in Epic 3.
