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
    val logs: List<LogEntry> = emptyList(),
    val isAutoStartEnabled: Boolean = false,
    val selectedProvider: String = "nous_portal",
    val apiKey: String = "",
    val telegramToken: String = "",
    val customModel: String = "",
    val customBaseUrl: String = "",
    val isPublicTunnelEnabled: Boolean = false,
    val tunnelUrl: String? = null,
    val errorMessage: String? = null
)
