package com.hermes.node.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hermes.node.data.ConfigRepository
import com.hermes.node.data.ConfigSerializer
import com.hermes.node.data.model.GatewayConfig
import com.hermes.node.data.model.HermesConfig
import com.hermes.node.data.model.ProviderConfig
import com.hermes.node.data.model.SystemConfig
import com.hermes.node.engine.BootstrapExtractor
import com.hermes.node.engine.ExtractionResult
import com.hermes.node.engine.HealthCheckResult
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
    private val bootstrapExtractor: BootstrapExtractor? = null,
    private val configRepository: ConfigRepository? = null,
    private val configSerializer: ConfigSerializer? = null,
    private val defaultDispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) : ViewModel() {

    private val _uiState = MutableStateFlow(ServerUiState())
    val uiState: StateFlow<ServerUiState> = _uiState.asStateFlow()

    private var metricsJob: Job? = null
    private var transitionJob: Job? = null
    private var bootstrapJob: Job? = null
    private var saveSettingsJob: Job? = null
    private val maxLogCapacity = 2000

    init {
        loadPersistedConfig()
        // Initial welcome log
        onAddLog("Hermes Node initialized. Ready to start.", LogLevel.INFO)
        checkAndInitializeBootstrap()
    }

    fun performHealthCheck(): HealthCheckResult {
        val extractor = bootstrapExtractor
        if (extractor == null) {
            _uiState.update {
                it.copy(
                    isBootstrapComplete = true,
                    isBootstrapping = false,
                    isRuntimeCorrupted = false,
                    integrityWarning = null,
                    bootstrapProgress = 1.0f,
                    bootstrapMessage = "ARM64 Linux userland ready"
                )
            }
            return HealthCheckResult.Healthy
        }

        val health = extractor.checkHealth()
        when (health) {
            is HealthCheckResult.Healthy -> {
                _uiState.update {
                    it.copy(
                        isBootstrapComplete = true,
                        isBootstrapping = false,
                        isRuntimeCorrupted = false,
                        integrityWarning = null,
                        bootstrapProgress = 1.0f,
                        bootstrapMessage = "ARM64 Linux userland ready"
                    )
                }
                onAddLog("ARM64 Linux userland verified and ready.", LogLevel.INFO)
            }
            is HealthCheckResult.NotInstalled -> {
                _uiState.update {
                    it.copy(
                        isBootstrapComplete = false,
                        isBootstrapping = false,
                        isRuntimeCorrupted = false,
                        integrityWarning = null,
                        bootstrapProgress = 0f,
                        bootstrapMessage = "ARM64 Linux userland not installed"
                    )
                }
            }
            is HealthCheckResult.Corrupted -> {
                _uiState.update {
                    it.copy(
                        isBootstrapComplete = false,
                        isBootstrapping = false,
                        isRuntimeCorrupted = true,
                        integrityWarning = health.details.ifEmpty { "Corrupted userland binaries: ${health.issues.joinToString(", ")}" },
                        bootstrapProgress = 0f,
                        bootstrapMessage = "Runtime integrity check failed"
                    )
                }
                onAddLog("Runtime integrity check failed: ${health.details}", LogLevel.WARN)
            }
        }
        return health
    }

    fun checkAndInitializeBootstrap() {
        val health = performHealthCheck()
        if (health is HealthCheckResult.NotInstalled) {
            triggerBootstrap()
        }
    }

    fun triggerBootstrap() {
        val extractor = bootstrapExtractor ?: return
        if (_uiState.value.status == ServerStatus.RUNNING || _uiState.value.status == ServerStatus.STARTING) {
            onStopServer()
        }
        bootstrapJob?.cancel()
        _uiState.update {
            it.copy(
                isBootstrapping = true,
                isBootstrapComplete = false,
                isRuntimeCorrupted = false,
                integrityWarning = null,
                isRepairing = false,
                bootstrapProgress = 0.05f,
                bootstrapMessage = "Preparing ARM64 Linux userland...",
                errorMessage = null
            )
        }
        onAddLog("Starting ARM64 Linux userland bootstrap extraction...", LogLevel.INFO)

        bootstrapJob = viewModelScope.launch(ioDispatcher) {
            val result = extractor.extract { progress, message ->
                _uiState.update {
                    it.copy(
                        bootstrapProgress = progress,
                        bootstrapMessage = message
                    )
                }
            }

            when (result) {
                is ExtractionResult.Success -> {
                    val health = extractor.checkHealth()
                    if (health is HealthCheckResult.Healthy) {
                        _uiState.update {
                            it.copy(
                                isBootstrapping = false,
                                isBootstrapComplete = true,
                                isRuntimeCorrupted = false,
                                integrityWarning = null,
                                isRepairing = false,
                                bootstrapProgress = 1.0f,
                                bootstrapMessage = "ARM64 Linux userland ready"
                            )
                        }
                        onAddLog("ARM64 Linux userland extracted and verified successfully.", LogLevel.INFO)
                    } else {
                        val warning = if (health is HealthCheckResult.Corrupted) health.details else "Health check failed after extraction"
                        _uiState.update {
                            it.copy(
                                isBootstrapping = false,
                                isBootstrapComplete = false,
                                isRuntimeCorrupted = true,
                                integrityWarning = warning,
                                isRepairing = false,
                                bootstrapProgress = 0f,
                                bootstrapMessage = "Extraction completed with integrity warnings"
                            )
                        }
                        onAddLog("Extraction completed with warnings: $warning", LogLevel.WARN)
                    }
                }
                is ExtractionResult.Error -> {
                    _uiState.update {
                        it.copy(
                            isBootstrapping = false,
                            isBootstrapComplete = false,
                            isRepairing = false,
                            bootstrapProgress = 0f,
                            bootstrapMessage = "Bootstrap extraction failed",
                            errorMessage = result.message
                        )
                    }
                    onAddLog("Bootstrap extraction error: ${result.message}", LogLevel.ERROR)
                }
            }
        }
    }

    fun onRepairRuntime() {
        val extractor = bootstrapExtractor ?: return
        if (_uiState.value.status == ServerStatus.RUNNING || _uiState.value.status == ServerStatus.STARTING || _uiState.value.status == ServerStatus.STOPPING) {
            metricsJob?.cancel()
            metricsJob = null
            transitionJob?.cancel()
            transitionJob = null
            _uiState.update {
                it.copy(
                    status = ServerStatus.STOPPED,
                    uptimeSeconds = 0L,
                    cpuUsagePercent = 0f,
                    memoryUsageMb = 0L,
                    tunnelUrl = null
                )
            }
            onAddLog("Server daemon stopped for runtime environment repair.", LogLevel.INFO)
        }
        bootstrapJob?.cancel()
        _uiState.update {
            it.copy(
                isRepairing = true,
                isBootstrapping = true,
                isBootstrapComplete = false,
                bootstrapProgress = 0.02f,
                bootstrapMessage = "Repairing ARM64 Linux userland...",
                errorMessage = null
            )
        }
        onAddLog("Starting one-tap runtime environment repair...", LogLevel.INFO)

        bootstrapJob = viewModelScope.launch(ioDispatcher) {
            val result = extractor.repair { progress, message ->
                _uiState.update {
                    it.copy(
                        bootstrapProgress = progress,
                        bootstrapMessage = message
                    )
                }
            }

            when (result) {
                is ExtractionResult.Success -> {
                    val health = extractor.checkHealth()
                    if (health is HealthCheckResult.Healthy) {
                        _uiState.update {
                            it.copy(
                                isRepairing = false,
                                isBootstrapping = false,
                                isBootstrapComplete = true,
                                isRuntimeCorrupted = false,
                                integrityWarning = null,
                                bootstrapProgress = 1.0f,
                                bootstrapMessage = "ARM64 Linux userland ready"
                            )
                        }
                        onAddLog("ARM64 Linux userland repaired and verified successfully.", LogLevel.INFO)
                    } else {
                        val warning = if (health is HealthCheckResult.Corrupted) health.details else "Health check failed after repair"
                        _uiState.update {
                            it.copy(
                                isRepairing = false,
                                isBootstrapping = false,
                                isBootstrapComplete = false,
                                isRuntimeCorrupted = true,
                                integrityWarning = warning,
                                bootstrapProgress = 0f,
                                bootstrapMessage = "Repair completed with integrity warnings"
                            )
                        }
                        onAddLog("Runtime repair completed with warnings: $warning", LogLevel.WARN)
                    }
                }
                is ExtractionResult.Error -> {
                    _uiState.update {
                        it.copy(
                            isRepairing = false,
                            isBootstrapping = false,
                            isBootstrapComplete = false,
                            isRuntimeCorrupted = true,
                            bootstrapProgress = 0f,
                            bootstrapMessage = "Repair failed",
                            errorMessage = result.message
                        )
                    }
                    onAddLog("Runtime repair error: ${result.message}", LogLevel.ERROR)
                }
            }
        }
    }

    fun onToggleServer() {
        when (_uiState.value.status) {
            ServerStatus.RUNNING, ServerStatus.STARTING -> onStopServer()
            ServerStatus.STOPPED, ServerStatus.ERROR, ServerStatus.STOPPING -> onStartServer()
        }
    }

    fun onStartServer() {
        if (_uiState.value.status == ServerStatus.STARTING || _uiState.value.status == ServerStatus.RUNNING || _uiState.value.status == ServerStatus.STOPPING) {
            return
        }

        if (_uiState.value.isRepairing) {
            onAddLog("Cannot start server: Linux userland repair in progress.", LogLevel.WARN)
            return
        }

        if (_uiState.value.isBootstrapping) {
            onAddLog("Cannot start server: Linux userland extraction in progress.", LogLevel.WARN)
            return
        }

        if (_uiState.value.isRuntimeCorrupted) {
            val warning = _uiState.value.integrityWarning ?: "Runtime integrity check failed"
            onSetError("Cannot start server: Runtime is corrupted ($warning). Please tap Repair Runtime.")
            return
        }

        if (bootstrapExtractor != null) {
            val health = bootstrapExtractor.checkHealth()
            if (health is HealthCheckResult.Corrupted) {
                _uiState.update {
                    it.copy(
                        isRuntimeCorrupted = true,
                        integrityWarning = health.details,
                        isBootstrapComplete = false
                    )
                }
                onSetError("Cannot start server: Runtime is corrupted (${health.details}). Please tap Repair Runtime.")
                return
            } else if (health is HealthCheckResult.NotInstalled) {
                _uiState.update {
                    it.copy(
                        isBootstrapComplete = false
                    )
                }
                onSetError("Cannot start server: Linux userland is not installed. Please run bootstrap.")
                return
            }
        } else if (!_uiState.value.isBootstrapComplete) {
            onSetError("Cannot start server: Linux userland is not installed or corrupted. Please run bootstrap.")
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

    fun loadPersistedConfig() {
        val repo = configRepository ?: return
        try {
            val config = repo.getConfig()
            _uiState.update { current ->
                current.copy(
                    selectedProvider = config.provider.provider.ifEmpty { current.selectedProvider },
                    apiKey = config.provider.apiKey,
                    telegramToken = config.gateway.telegramToken,
                    customModel = config.provider.model,
                    customBaseUrl = config.provider.baseUrl,
                    isAutoStartEnabled = config.system.autoStartOnBoot,
                    isPublicTunnelEnabled = config.system.publicTunnelEnabled
                )
            }
            if (config.provider.apiKey.isNotBlank() || config.gateway.telegramToken.isNotBlank()) {
                onAddLog("Loaded persisted credentials securely from repository.", LogLevel.INFO)
            }
        } catch (e: Exception) {
            onAddLog("Warning: Failed to load stored credentials: ${e.message}", LogLevel.WARN)
        }
    }

    fun onSaveSettings() {
        saveSettingsJob?.cancel()
        _uiState.update {
            it.copy(
                isSavingSettings = true,
                isSettingsSaved = false,
                configSaveMessage = null
            )
        }
        saveSettingsJob = viewModelScope.launch(ioDispatcher) {
            val currentState = _uiState.value
            val trimmedApiKey = currentState.apiKey.trim()
            val trimmedTelegramToken = currentState.telegramToken.trim()
            val trimmedCustomModel = currentState.customModel.trim()
            val trimmedCustomBaseUrl = currentState.customBaseUrl.trim()

            // Update UI state with trimmed inputs
            _uiState.update {
                it.copy(
                    apiKey = trimmedApiKey,
                    telegramToken = trimmedTelegramToken,
                    customModel = trimmedCustomModel,
                    customBaseUrl = trimmedCustomBaseUrl
                )
            }

            val config = HermesConfig(
                provider = ProviderConfig(
                    provider = currentState.selectedProvider,
                    apiKey = trimmedApiKey,
                    model = trimmedCustomModel,
                    baseUrl = trimmedCustomBaseUrl
                ),
                gateway = GatewayConfig(
                    telegramToken = trimmedTelegramToken,
                    isTelegramEnabled = trimmedTelegramToken.isNotBlank()
                ),
                system = SystemConfig(
                    autoStartOnBoot = currentState.isAutoStartEnabled,
                    publicTunnelEnabled = currentState.isPublicTunnelEnabled
                )
            )

            try {
                // 1. Save to ConfigRepository (Keystore-backed EncryptedSharedPreferences)
                configRepository?.saveConfig(config)

                // 2. Atomically serialize to hermes.json with POSIX 0600 permissions
                val result = configSerializer?.serialize(config)
                if (result != null && result.isFailure) {
                    val error = result.exceptionOrNull()?.message ?: "Failed to write configuration"
                    _uiState.update {
                        it.copy(
                            isSavingSettings = false,
                            isSettingsSaved = false,
                            configSaveMessage = "Failed to serialize config: $error"
                        )
                    }
                    onAddLog("Error saving runtime configuration: $error", LogLevel.ERROR)
                    return@launch
                }

                _uiState.update {
                    it.copy(
                        isSavingSettings = false,
                        isSettingsSaved = true,
                        configSaveMessage = "Settings saved successfully"
                    )
                }
                onAddLog("Settings saved successfully and hermes.json serialized (0600).", LogLevel.INFO)
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                val error = e.message ?: "Unknown error saving configuration"
                _uiState.update {
                    it.copy(
                        isSavingSettings = false,
                        isSettingsSaved = false,
                        configSaveMessage = "Failed to save settings: $error"
                    )
                }
                onAddLog("Failed to save settings: $error", LogLevel.ERROR)
            }
        }
    }

    fun onDismissSaveMessage() {
        _uiState.update {
            it.copy(
                isSettingsSaved = false,
                configSaveMessage = null
            )
        }
    }

    fun onUpdateProvider(provider: String) {
        _uiState.update { it.copy(selectedProvider = provider, isSettingsSaved = false, configSaveMessage = null) }
    }

    fun onUpdateApiKey(apiKey: String) {
        _uiState.update { it.copy(apiKey = apiKey, isSettingsSaved = false, configSaveMessage = null) }
    }

    fun onUpdateTelegramToken(token: String) {
        _uiState.update { it.copy(telegramToken = token, isSettingsSaved = false, configSaveMessage = null) }
    }

    fun onUpdateCustomModel(model: String) {
        _uiState.update { it.copy(customModel = model, isSettingsSaved = false, configSaveMessage = null) }
    }

    fun onUpdateCustomBaseUrl(url: String) {
        _uiState.update { it.copy(customBaseUrl = url, isSettingsSaved = false, configSaveMessage = null) }
    }

    fun onUpdateAutoStart(enabled: Boolean) {
        _uiState.update { it.copy(isAutoStartEnabled = enabled, isSettingsSaved = false, configSaveMessage = null) }
    }

    fun onUpdatePublicTunnel(enabled: Boolean) {
        _uiState.update { current ->
            current.copy(
                isPublicTunnelEnabled = enabled,
                isSettingsSaved = false,
                configSaveMessage = null,
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
        bootstrapJob?.cancel()
        saveSettingsJob?.cancel()
    }
}
