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
