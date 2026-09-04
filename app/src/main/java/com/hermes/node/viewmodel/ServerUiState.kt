package com.hermes.node.viewmodel

import com.hermes.node.data.model.SkillInfo
import com.hermes.node.data.model.SkillsConfig
import com.hermes.node.engine.TunnelState

enum class ServerStatus {
    STOPPED,
    STARTING,
    RUNNING,
    STOPPING,
    ERROR
}

enum class LogLevel {
    INFO,
    WARN,
    ERROR,
    DEBUG
}

data class LogEntry(
    val timestamp: Long = System.currentTimeMillis(),
    val message: String,
    val level: LogLevel = LogLevel.INFO
)

data class ServerUiState(
    val status: ServerStatus = ServerStatus.STOPPED,
    val uptimeSeconds: Long = 0L,
    val cpuUsagePercent: Float = 0f,
    val memoryUsageMb: Long = 0L,
    val totalMemoryMb: Long = 0L,
    val batteryPercent: Int = 100,
    val isCharging: Boolean = false,
    val batteryTemperatureCelsius: Float = 0f,
    val logs: List<LogEntry> = emptyList(),
    val isAutoStartEnabled: Boolean = false,
    val selectedProvider: String = "nous_portal",
    val apiKey: String = "",
    val customModel: String = "",
    val customBaseUrl: String = "",
    val isTelegramEnabled: Boolean = false,
    val telegramToken: String = "",
    val telegramAdminUserIds: String = "",
    val isDiscordEnabled: Boolean = false,
    val discordToken: String = "",
    val discordChannelIds: String = "",
    val isSlackEnabled: Boolean = false,
    val slackAppToken: String = "",
    val slackBotToken: String = "",
    val isWhatsAppEnabled: Boolean = false,
    val whatsAppSessionLink: String = "",
    val whatsAppWebhookToken: String = "",
    val isRestApiEnabled: Boolean = true,
    val restApiPort: String = "8000",
    val isPublicTunnelEnabled: Boolean = false,
    val tunnelUrl: String? = null,
    val tunnelState: TunnelState = TunnelState.Stopped,
    val showQrCodeDialog: Boolean = false,
    val errorMessage: String? = null,
    val isBootstrapping: Boolean = false,
    val isBootstrapComplete: Boolean = false,
    val bootstrapProgress: Float = 0f,
    val bootstrapMessage: String = "",
    val isRuntimeCorrupted: Boolean = false,
    val integrityWarning: String? = null,
    val isRepairing: Boolean = false,
    val isSavingSettings: Boolean = false,
    val isSettingsSaved: Boolean = false,
    val configSaveMessage: String? = null,
    val isBatteryOptimizationIgnored: Boolean = false,
    val showBatteryOptimizationPrompt: Boolean = false,
    val skillsConfig: SkillsConfig = SkillsConfig(),
    val installedSkills: List<SkillInfo> = SkillsConfig.DEFAULT_CORE_SKILLS,
    val storageSizeBytes: Long = 0L,
    val storageSizeFormatted: String = "0 B",
    val isExportingMemory: Boolean = false,
    val isResettingMemory: Boolean = false,
    val memoryActionMessage: String? = null,
    val isMemoryActionSuccess: Boolean = false,
    val showClearMemoryDialog: Boolean = false,
    val searchApiKeyVisible: Boolean = false,
    val packageManagerStatus: PackageManagerStatus = PackageManagerStatus.NOT_INSTALLED,
    val packageManagerProgress: Float = 0f,
    val packageManagerMessage: String? = null,
    val installedToolsSummary: String = "apt/apt-get shim (minimal)"
)

enum class PackageManagerStatus {
    NOT_INSTALLED,
    DOWNLOADING,
    EXTRACTING,
    READY,
    ERROR
}

