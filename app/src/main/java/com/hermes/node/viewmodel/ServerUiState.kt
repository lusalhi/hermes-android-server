package com.hermes.node.viewmodel

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
    val telegramToken: String = "",
    val customModel: String = "",
    val customBaseUrl: String = "",
    val isPublicTunnelEnabled: Boolean = false,
    val tunnelUrl: String? = null,
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
    val showBatteryOptimizationPrompt: Boolean = false
)

