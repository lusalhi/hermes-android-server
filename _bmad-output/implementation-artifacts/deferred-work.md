# Deferred Work

- source_spec: `_bmad-output/implementation-artifacts/spec-init-android-project-scaffold.md`
  summary: Implement Jetpack DataStore / EncryptedSharedPreferences persistence for ServerViewModel configuration.
  evidence: Settings state is currently kept in-memory in ServerViewModel and will reset across app restarts until the dedicated persistence story is implemented.

- source_spec: `_bmad-output/implementation-artifacts/spec-init-android-project-scaffold.md`
  summary: Implement HermesServerService Android ForegroundService with WakeLock and Special-Use metadata.
  evidence: Foreground service permissions are declared in AndroidManifest.xml, and the service implementation will be added in the dedicated daemon service story.

- source_spec: `_bmad-output/implementation-artifacts/spec-init-android-project-scaffold.md`
  summary: Implement BootCompletedReceiver for auto-start on boot functionality.
  evidence: RECEIVE_BOOT_COMPLETED is declared in manifest and toggled in UI, and the receiver will be wired when ForegroundService is created.

## Deferred from: code review of spec-4-3-real-time-hardware-telemetry-device-health-monitor.md (2026-08-29)

- **Child Node Subprocess CPU Tracking**: When running under restricted `/proc/stat` Android sandbox policies, `SystemTelemetryCollector.collectProcessCpuFallback()` measures host JVM CPU rather than child Node processes. Deferred as future enhancement for multi-process telemetry architecture.
- **Adaptive Low Battery Styling**: Adding dynamic critical status color transitions and warning icons (`BatteryAlert`) when battery charge drops below 15-20% while discharging. Deferred as future UX enhancement.

## Deferred from: code review of spec-5-1-multi-platform-messaging-gateway-adapters-telegram-discord-s.md (2026-08-29)

- **Active Messaging Gateway Status Indicators on Dashboard**: Adding active gateway chip badges (Telegram, Discord, Slack, WhatsApp, REST API) to the Dashboard screen. Deferred to Epic 5.2 alongside Cloudflare Public Tunnel dashboard widgets.
- **Runtime JSON schema not contract-tested against daemon**: `ConfigSerializer.generateJson` gateway schema (`telegram`, `discord`, `slack`, `whatsapp`, `rest_api`) is only asserted by Android unit tests; add daemon-consumed fixture/contract test to catch drift [ConfigSerializer.kt:51-83] — deferred from 2026-08-29 review.
- **Epic context adapter lifecycle clarification**: Context describes “gateway adapters” but diff implements config/serialization/UI without adapter lifecycle or connection-state; clarify docs that adapters are daemon-provided or add observable status seam in Epic 5.2 [epic-5-context.md:9-22] — deferred from 2026-08-29 review.

- source_spec: `_bmad-output/implementation-artifacts/spec-5-2-managed-cloudflare-public-webhook-tunnel-sidecar.md`
  summary: Implement exponential backoff auto-recovery for Cloudflare Tunnel reconnection during network transitions.
  evidence: Transient cellular to Wi-Fi network handoffs or DNS changes currently transition the tunnel to Stopped/Error state without automatic background retry.

- source_spec: `_bmad-output/implementation-artifacts/spec-5-2-managed-cloudflare-public-webhook-tunnel-sidecar.md`
  summary: Display public tunnel URL in HermesServerService ongoing foreground notification.
  evidence: HermesServerService notification currently shows server status and port; adding the active tunnel URL allows users to quickly view or copy the public link while the app runs in the background.

## Deferred from: code review of spec-5-2-managed-cloudflare-public-webhook-tunnel-sidecar.md (2026-08-31 — Hemat re-run)

- **Exponential backoff auto-recovery (confirmed deferred):** Already tracked above — no duplicate action needed. Re-confirmed in hemat code review 2026-08-31.
- **Notification tunnel URL (confirmed deferred):** Already tracked above — no duplicate action needed. Re-confirmed in hemat code review 2026-08-31.

- source_spec: `_bmad-output/implementation-artifacts/spec-6-1-skill-manifest-viewer-tool-enablement-toggles.md`
  summary: Implement runtime hot-reload signal for PRoot agent daemon when skill toggles change without manual daemon restart.
  evidence: Toggling skills updates hermes.json immediately, but the running agent process will only pick up skill changes upon process reload or next execution cycle.

- source_spec: `_bmad-output/implementation-artifacts/spec-6-1-skill-manifest-viewer-tool-enablement-toggles.md`
  summary: Provide in-app marketplace/editor UI to dynamically install, configure, and delete custom skills.
  evidence: Custom skills are currently deserialized and supported from hermes.json, but cannot be authored or uninstalled directly from the mobile Settings interface.


- source_spec: `_bmad-output/implementation-artifacts/spec-6-4-agent-toolchain-shims-and-storage-bridge.md`
  summary: Add extended query shims for apt-cache and dpkg in PRoot userland
  evidence: Surfaced by Blind Hunter during review of Story 6.4; out of scope for MVP apt/sudo shims.

- source_spec: `_bmad-output/implementation-artifacts/spec-6-4-agent-toolchain-shims-and-storage-bridge.md`
  summary: Display runtime restart prompt when changing shared storage setting while daemon is active
  evidence: PRoot bind mounts require daemon restart; surfaced during review of Story 6.4.
- source_spec: `_bmad-output/implementation-artifacts/spec-6-5-full-package-manager-and-dynamic-rootfs-extraction.md`
  summary: Query dynamic installed packages list via apk info in UI instead of static tools summary
  evidence: Surfaced by Blind Hunter during review of Story 6.5.

- source_spec: `_bmad-output/implementation-artifacts/spec-6-5-full-package-manager-and-dynamic-rootfs-extraction.md`
  summary: Resolve DNS servers dynamically via Android ConnectivityManager in resolv.conf
  evidence: Surfaced by Blind Hunter during review of Story 6.5.
