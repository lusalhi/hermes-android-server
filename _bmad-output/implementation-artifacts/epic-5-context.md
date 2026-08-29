# Epic 5 Context: Multi-Platform Messaging Gateways & Cloudflare Public Tunnel Sidecar

<!-- Compiled from planning artifacts. Edit freely. Regenerate with compile-epic-context if planning docs change. -->

## Goal

Enable multi-platform messaging gateway adapters (Telegram Bot, Discord Bot, Slack, WhatsApp, and REST API) and an optional managed `cloudflared` ARM64 public webhook tunnel sidecar process to expose local webhooks and endpoints securely via public HTTPS URLs and QR codes.

## Stories

- Story 5.1: Multi-Platform Messaging Gateway Adapters (Telegram, Discord, Slack, WhatsApp)
- Story 5.2: Managed Cloudflare Public Webhook Tunnel Sidecar

## Requirements & Constraints

- **Gateway Configuration & Toggles:** Users must be able to configure credentials and toggle adapters in Settings for:
  - Telegram (Bot Token, Allowed Admin User IDs)
  - Discord (Bot Token, Channel IDs)
  - Slack (App Token, Bot Token)
  - WhatsApp (Session link / Webhook token)
  - REST API / Local Web Server (Port configuration, default: 8000)
- **Credential Storage & JSON Generation:** Gateway credentials must be encrypted at rest in `EncryptedSharedPreferences` via `ConfigRepository` and serialized into `hermes.json` with strict POSIX 0600 file permissions.
- **Gateway Enablement Behavior:** Enabling a gateway writes the configuration into `hermes.json`; starting Hermes daemon binds the active adapters. Disabling prevents the adapter from binding.
- **Sidecar Process Management (Tunnel):** For Story 5.2, `cloudflared` ARM64 binary sidecar process must be managed by `TunnelManager` (started/stopped with server or toggled independently), parsing the generated `https://*.trycloudflare.com` public URL into `ServerUiState`.
- **UI Exposure:** Public URL and QR code displayed on Dashboard for quick mobile scanning and clipboard export.

## Technical Decisions

- **Architecture Spine AD-5 (Secure Storage):** API keys, bot tokens, and webhook secrets stored encrypted at rest via Android Keystore (`ConfigRepository`), writing POSIX 0600 `hermes.json`.
- **Architecture Spine AD-6 (Cloudflare Tunnel Sidecar):** Remote tunneling managed as secondary independent sidecar process (`cloudflared` ARM64 binary) parsing generated public URL and pushing to `ServerUiState`.
- **Package Location:** Data models in `com.hermes.node.data.model`, engine sidecars in `com.hermes.node.engine`, UI in `com.hermes.node.ui.screens` / `com.hermes.node.ui.components`.
- **State Management:** Unidirectional Data Flow with `ServerUiState` / `SettingsUiState` exposed through `ServerViewModel`.

## UX & Interaction Patterns

- **Gateway Toggles & Credential Fields:** Cyber-themed cards with toggle switches, masked token input fields with reveal toggles, and admin ID comma-separated inputs.
- **Tunnel Widget & QR Code:** Dashboard card displaying public tunnel status, active URL, Copy button, and QR code rendering for scanning.

## Cross-Story Dependencies

- **Pre-requisites:** Epics 1–4 provide the service lifecycle (`HermesServerService`), `ProcessController`, `ConfigRepository`, and `ConfigSerializer`.
- **Within Epic 5:** Story 5.1 establishes the data models, serialization, and UI for multi-platform gateways; Story 5.2 builds the `TunnelManager` sidecar and QR code UI.
