package com.hermes.node.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class ServerViewModel(
    private val defaultDispatcher: CoroutineDispatcher = Dispatchers.Default
) : ViewModel() {

    private val _uiState = MutableStateFlow(ServerUiState())
    val uiState: StateFlow<ServerUiState> = _uiState.asStateFlow()

    private var metricsJob: Job? = null
    private var transitionJob: Job? = null
    private val maxLogCapacity = 2000

    init {
        // Initial welcome log
        onAddLog("Hermes Node initialized. Ready to start.", LogLevel.INFO)
    }

    fun onToggleServer() {
        when (_uiState.value.status) {
            ServerStatus.RUNNING, ServerStatus.STARTING -> onStopServer()
            ServerStatus.STOPPED, ServerStatus.ERROR, ServerStatus.STOPPING -> onStartServer()
        }
    }

    fun onStartServer() {
        if (_uiState.value.status == ServerStatus.STARTING || _uiState.value.status == ServerStatus.RUNNING) {
            return
        }

        transitionJob?.cancel()
        _uiState.update {
            it.copy(
                status = ServerStatus.STARTING,
                errorMessage = null
            )
        }
        onAddLog("Starting Hermes Node daemon...", LogLevel.INFO)

        transitionJob = viewModelScope.launch {
            delay(600) // Brief startup transition

            _uiState.update {
                it.copy(
                    status = ServerStatus.RUNNING,
                    uptimeSeconds = 0L,
                    cpuUsagePercent = 2.4f,
                    memoryUsageMb = 85L,
                    tunnelUrl = if (it.isPublicTunnelEnabled) "https://hermes-node.trycloudflare.com" else null
                )
            }
            onAddLog("Hermes Node daemon running on port 8000", LogLevel.INFO)
            if (_uiState.value.telegramToken.isNotBlank()) {
                onAddLog("Telegram Gateway connected successfully.", LogLevel.INFO)
            }
            startMetricsMonitoring()
        }
    }

    fun onStopServer() {
        if (_uiState.value.status == ServerStatus.STOPPED || _uiState.value.status == ServerStatus.STOPPING) {
            return
        }

        metricsJob?.cancel()
        metricsJob = null
        transitionJob?.cancel()

        _uiState.update { it.copy(status = ServerStatus.STOPPING) }
        onAddLog("Stopping Hermes Node daemon...", LogLevel.INFO)

        transitionJob = viewModelScope.launch {
            delay(400) // Brief graceful shutdown

            _uiState.update {
                it.copy(
                    status = ServerStatus.STOPPED,
                    uptimeSeconds = 0L,
                    cpuUsagePercent = 0f,
                    memoryUsageMb = 0L,
                    tunnelUrl = null
                )
            }
            onAddLog("Hermes Node daemon stopped.", LogLevel.INFO)
        }
    }

    fun onAddLog(message: String, level: LogLevel = LogLevel.INFO) {
        val entry = LogEntry(message = message, level = level)
        _uiState.update { current ->
            val updatedLogs = (current.logs + entry).takeLast(maxLogCapacity)
            current.copy(logs = updatedLogs)
        }
    }

    fun onClearLogs() {
        _uiState.update { it.copy(logs = emptyList()) }
    }

    fun onUpdateProvider(provider: String) {
        _uiState.update { it.copy(selectedProvider = provider) }
    }

    fun onUpdateApiKey(apiKey: String) {
        _uiState.update { it.copy(apiKey = apiKey) }
    }

    fun onUpdateTelegramToken(token: String) {
        _uiState.update { it.copy(telegramToken = token) }
    }

    fun onUpdateCustomModel(model: String) {
        _uiState.update { it.copy(customModel = model) }
    }

    fun onUpdateCustomBaseUrl(url: String) {
        _uiState.update { it.copy(customBaseUrl = url) }
    }

    fun onUpdateAutoStart(enabled: Boolean) {
        _uiState.update { it.copy(isAutoStartEnabled = enabled) }
    }

    fun onUpdatePublicTunnel(enabled: Boolean) {
        _uiState.update { current ->
            current.copy(
                isPublicTunnelEnabled = enabled,
                tunnelUrl = if (enabled && current.status == ServerStatus.RUNNING) {
                    "https://hermes-node.trycloudflare.com"
                } else {
                    null
                }
            )
        }
    }

    fun onSetError(message: String) {
        metricsJob?.cancel()
        metricsJob = null
        transitionJob?.cancel()
        _uiState.update {
            it.copy(
                status = ServerStatus.ERROR,
                uptimeSeconds = 0L,
                cpuUsagePercent = 0f,
                memoryUsageMb = 0L,
                tunnelUrl = null,
                errorMessage = message
            )
        }
        onAddLog("Error: $message", LogLevel.ERROR)
    }

    fun onDismissError() {
        _uiState.update {
            it.copy(
                status = if (it.status == ServerStatus.ERROR) ServerStatus.STOPPED else it.status,
                errorMessage = null
            )
        }
    }

    fun stopMonitoring() {
        metricsJob?.cancel()
        metricsJob = null
    }

    private fun startMetricsMonitoring() {
        metricsJob?.cancel()
        metricsJob = viewModelScope.launch(defaultDispatcher) {
            while (isActive && _uiState.value.status == ServerStatus.RUNNING) {
                delay(1000)
                _uiState.update { current ->
                    if (current.status == ServerStatus.RUNNING) {
                        current.copy(
                            uptimeSeconds = current.uptimeSeconds + 1,
                            cpuUsagePercent = (1.5f + (Math.random() * 2.5f)).toFloat(),
                            memoryUsageMb = 85L + (current.uptimeSeconds % 10)
                        )
                    } else {
                        current
                    }
                }
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        metricsJob?.cancel()
        metricsJob = null
        transitionJob?.cancel()
    }
}
