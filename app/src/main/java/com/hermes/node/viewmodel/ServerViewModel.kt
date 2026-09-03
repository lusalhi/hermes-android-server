package com.hermes.node.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hermes.node.data.ConfigRepository
import com.hermes.node.data.ConfigSerializer
import com.hermes.node.data.model.DiscordGatewayConfig
import com.hermes.node.data.model.GatewayConfig
import com.hermes.node.data.model.HermesConfig
import com.hermes.node.data.model.ProviderConfig
import com.hermes.node.data.model.RestApiGatewayConfig
import com.hermes.node.data.model.SkillsConfig
import com.hermes.node.data.model.SlackGatewayConfig
import com.hermes.node.data.model.SystemConfig
import com.hermes.node.data.model.TelegramGatewayConfig
import com.hermes.node.data.model.WhatsAppGatewayConfig
import com.hermes.node.engine.BootstrapExtractor
import com.hermes.node.engine.ExtractionResult
import com.hermes.node.engine.HealthCheckResult
import android.content.Context
import android.content.Intent
import com.hermes.node.engine.MemoryManager
import com.hermes.node.engine.MemoryManagerInterface
import com.hermes.node.service.BatteryOptimizationHelper
import com.hermes.node.service.BatteryOptimizationHelperInterface
import com.hermes.node.service.HermesServerService
import com.hermes.node.engine.CloudflareTunnelManager
import com.hermes.node.engine.DeviceTelemetry
import com.hermes.node.engine.LogStreamerInterface
import com.hermes.node.engine.ProcessControllerInterface
import com.hermes.node.engine.ProcessState
import com.hermes.node.engine.SystemTelemetryCollector
import com.hermes.node.engine.TelemetryCollector
import com.hermes.node.engine.TelemetryMonitor
import com.hermes.node.engine.TunnelManagerInterface
import com.hermes.node.engine.TunnelState
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

class ServerViewModel(
    context: Context? = null,
    private val bootstrapExtractor: BootstrapExtractor? = null,
    private val configRepository: ConfigRepository? = null,
    private val configSerializer: ConfigSerializer? = null,
    private val processController: ProcessControllerInterface? = null,
    private val processStateFlow: StateFlow<ProcessState>? = null,
    private val defaultDispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val serviceRunningFlow: StateFlow<Boolean>? = null,
    private val startServiceAction: ((Context) -> Unit)? = { ctx -> try { HermesServerService.start(ctx) } catch (_: Exception) {} },
    private val stopServiceAction: ((Context) -> Unit)? = { ctx -> try { HermesServerService.stop(ctx) } catch (_: Exception) {} },
    private val batteryOptimizationHelper: BatteryOptimizationHelperInterface? = BatteryOptimizationHelper(),
    private val logStreamer: LogStreamerInterface? = null,
    private val telemetryCollector: TelemetryCollector? = null,
    private val telemetryMonitor: TelemetryMonitor? = null,
    private val tunnelManager: TunnelManagerInterface? = null,
    private val memoryManager: MemoryManagerInterface? = null
) : ViewModel() {

    private val context: Context? = try {
        context?.applicationContext ?: context
    } catch (_: Throwable) {
        context
    }
    private val _uiState = MutableStateFlow(ServerUiState())
    val uiState: StateFlow<ServerUiState> = _uiState.asStateFlow()

    private val effectiveCollector: TelemetryCollector? = telemetryCollector ?: (this.context?.let { ctx ->
        try {
            SystemTelemetryCollector(ctx, ioDispatcher = ioDispatcher)
        } catch (_: Throwable) {
            null
        }
    })
    private val effectiveMonitor: TelemetryMonitor? = telemetryMonitor ?: (effectiveCollector?.let { TelemetryMonitor(it, pollingIntervalMs = 2000L, dispatcher = defaultDispatcher) })
    private val effectiveTunnelManager: TunnelManagerInterface? = tunnelManager ?: (this.context?.let { ctx ->
        try {
            CloudflareTunnelManager(filesDir = ctx.filesDir, ioDispatcher = ioDispatcher)
        } catch (_: Throwable) {
            null
        }
    })
    private val effectiveMemoryManager: MemoryManagerInterface? = memoryManager ?: (this.context?.let { ctx ->
        try {
            MemoryManager(ctx)
        } catch (_: Throwable) {
            null
        }
    })

    private var metricsJob: Job? = null
    private var telemetryJob: Job? = null
    private var transitionJob: Job? = null
    private var bootstrapJob: Job? = null
    private var saveSettingsJob: Job? = null
    private var serviceObserverJob: Job? = null
    private var processObserverJob: Job? = null
    private var logObserverJob: Job? = null
    private var tunnelObserverJob: Job? = null
    private var tunnelControlJob: Job? = null
    private var memoryJob: Job? = null
    private val maxLogCapacity = 2000

    init {
        loadPersistedConfig()
        checkBatteryOptimizationStatus()
        observeLogStreamer()
        // Initial welcome log
        onAddLog("Hermes Node initialized. Ready to start.", LogLevel.INFO)
        checkAndInitializeBootstrap()
        observeServiceState()
        observeProcessState()
        observeTunnelState()
        startTelemetryPolling()
        refreshStorageUsage()
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
            context?.let { ctx ->
                try {
                    stopServiceAction?.invoke(ctx)
                } catch (ignored: Exception) {}
            }
            _uiState.update {
                it.copy(
                    status = ServerStatus.STOPPED,
                    uptimeSeconds = 0L,
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

        context?.let { ctx ->
            try {
                startServiceAction?.invoke(ctx)
            } catch (e: Exception) {
                onAddLog("Failed to start foreground service: ${e.message}", LogLevel.WARN)
            }
        }

        checkBatteryOptimizationStatus()

        transitionJob = viewModelScope.launch {
            delay(600) // Brief startup transition

            val port = _uiState.value.restApiPort.toIntOrNull()?.takeIf { it in 1..65535 } ?: 8000
            _uiState.update {
                val shouldPrompt = !it.isBatteryOptimizationIgnored
                it.copy(
                    status = ServerStatus.RUNNING,
                    uptimeSeconds = 0L,
                    showBatteryOptimizationPrompt = if (shouldPrompt) true else it.showBatteryOptimizationPrompt
                )
            }
            if (_uiState.value.isPublicTunnelEnabled) {
                onAddLog("Starting Cloudflare Public Tunnel...", LogLevel.INFO)
                tunnelControlJob?.cancel()
                val mgr = effectiveTunnelManager
                if (mgr == null) {
                    _uiState.update { it.copy(tunnelState = TunnelState.Error("Tunnel manager unavailable")) }
                    onAddLog("Cloudflare Tunnel error: Tunnel manager unavailable", LogLevel.WARN)
                } else {
                    tunnelControlJob = viewModelScope.launch(defaultDispatcher) {
                        val result = mgr.start(port)
                        // Error logging is handled centrally by observeTunnelState to avoid duplicates
                        if (result.isFailure) {
                            // No direct log here — observer will emit TunnelState.Error and log once
                        }
                    }
                }
            }
            if (_uiState.value.isRestApiEnabled) {
                onAddLog("Hermes Node daemon running on port $port", LogLevel.INFO)
            } else {
                onAddLog("Hermes Node daemon running (REST API disabled)", LogLevel.INFO)
            }
            if (_uiState.value.isTelegramEnabled && _uiState.value.telegramToken.isNotBlank()) {
                onAddLog("Telegram Gateway configured and enabled.", LogLevel.INFO)
            }
            if (_uiState.value.isDiscordEnabled && _uiState.value.discordToken.isNotBlank()) {
                onAddLog("Discord Gateway configured and enabled.", LogLevel.INFO)
            }
            if (_uiState.value.isSlackEnabled && _uiState.value.slackAppToken.isNotBlank() && _uiState.value.slackBotToken.isNotBlank()) {
                onAddLog("Slack Gateway configured and enabled.", LogLevel.INFO)
            }
            if (_uiState.value.isWhatsAppEnabled && _uiState.value.whatsAppSessionLink.isNotBlank() && _uiState.value.whatsAppWebhookToken.isNotBlank()) {
                onAddLog("WhatsApp Gateway configured and enabled.", LogLevel.INFO)
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
        tunnelControlJob?.cancel()
        val mgrToStop = effectiveTunnelManager
        tunnelControlJob = viewModelScope.launch(defaultDispatcher) {
            val res = mgrToStop?.stop()
            if (res != null && res.isFailure) {
                _uiState.update { it.copy(tunnelState = TunnelState.Error(res.exceptionOrNull()?.message ?: "Failed to stop tunnel")) }
                onAddLog("Cloudflare Tunnel stop failed: ${res.exceptionOrNull()?.message}", LogLevel.WARN)
            }
        }

        _uiState.update { it.copy(status = ServerStatus.STOPPING) }
        onAddLog("Stopping Hermes Node daemon...", LogLevel.INFO)

        context?.let { ctx ->
            try {
                stopServiceAction?.invoke(ctx)
            } catch (e: Exception) {
                onAddLog("Failed to stop foreground service: ${e.message}", LogLevel.WARN)
            }
        }

        transitionJob = viewModelScope.launch {
            delay(400) // Brief graceful shutdown

            _uiState.update {
                it.copy(
                    status = ServerStatus.STOPPED,
                    uptimeSeconds = 0L,
                    tunnelUrl = null,
                    tunnelState = TunnelState.Stopped,
                    showQrCodeDialog = false
                )
            }
            onAddLog("Hermes Node daemon stopped.", LogLevel.INFO)
        }
    }

    fun onAddLog(message: String, level: LogLevel = LogLevel.INFO) {
        if (logStreamer != null) {
            logStreamer.append(LogEntry(message = message, level = level))
        } else {
            val entry = LogEntry(message = message, level = level)
            _uiState.update { current ->
                val updatedLogs = (current.logs + entry).takeLast(maxLogCapacity)
                current.copy(logs = updatedLogs)
            }
        }
    }

    fun onClearLogs() {
        if (logStreamer != null) {
            logStreamer.clear()
        } else {
            _uiState.update { it.copy(logs = emptyList()) }
        }
    }

    fun loadPersistedConfig() {
        val repo = configRepository ?: return
        try {
            val config = repo.getConfig()
            _uiState.update { current ->
                val autoStart = config.system.autoStartOnBoot
                val shouldPrompt = autoStart && !current.isBatteryOptimizationIgnored
                current.copy(
                    selectedProvider = config.provider.provider.ifEmpty { current.selectedProvider },
                    apiKey = config.provider.apiKey,
                    customModel = config.provider.model,
                    customBaseUrl = config.provider.baseUrl,
                    isTelegramEnabled = config.gateway.telegram.enabled,
                    telegramToken = config.gateway.telegram.botToken,
                    telegramAdminUserIds = config.gateway.telegram.adminUserIds,
                    isDiscordEnabled = config.gateway.discord.enabled,
                    discordToken = config.gateway.discord.botToken,
                    discordChannelIds = config.gateway.discord.channelIds,
                    isSlackEnabled = config.gateway.slack.enabled,
                    slackAppToken = config.gateway.slack.appToken,
                    slackBotToken = config.gateway.slack.botToken,
                    isWhatsAppEnabled = config.gateway.whatsapp.enabled,
                    whatsAppSessionLink = config.gateway.whatsapp.sessionLink,
                    whatsAppWebhookToken = config.gateway.whatsapp.webhookToken,
                    isRestApiEnabled = config.gateway.restApi.enabled,
                    restApiPort = config.gateway.restApi.port.toString(),
                    isAutoStartEnabled = autoStart,
                    isPublicTunnelEnabled = config.system.publicTunnelEnabled,
                    showBatteryOptimizationPrompt = if (shouldPrompt) true else current.showBatteryOptimizationPrompt,
                    skillsConfig = config.skills,
                    installedSkills = config.skills.toInstalledSkills()
                )
            }
            val hasCredentials = config.provider.apiKey.isNotBlank() ||
                config.skills.searchApiKey.isNotBlank() ||
                config.gateway.telegram.botToken.isNotBlank() ||
                config.gateway.discord.botToken.isNotBlank() ||
                config.gateway.slack.appToken.isNotBlank() ||
                config.gateway.slack.botToken.isNotBlank() ||
                config.gateway.whatsapp.webhookToken.isNotBlank() ||
                config.gateway.whatsapp.sessionLink.isNotBlank()
            if (hasCredentials) {
                onAddLog("Loaded persisted credentials securely from repository.", LogLevel.INFO)
            }
        } catch (e: Exception) {
            onAddLog("Warning: Failed to load stored credentials: ${e.message}", LogLevel.WARN)
        }
    }

    private val configMutex = Mutex()

    private fun buildHermesConfig(state: ServerUiState, skillsOverride: SkillsConfig? = null): HermesConfig {
        val trimmedApiKey = state.apiKey.trim()
        val trimmedCustomModel = state.customModel.trim()
        val trimmedCustomBaseUrl = state.customBaseUrl.trim()

        val trimmedTelegramToken = state.telegramToken.trim()
        val trimmedTelegramAdminIds = state.telegramAdminUserIds.trim()
        val trimmedDiscordToken = state.discordToken.trim()
        val trimmedDiscordChannelIds = state.discordChannelIds.trim()
        val trimmedSlackAppToken = state.slackAppToken.trim()
        val trimmedSlackBotToken = state.slackBotToken.trim()
        val trimmedWhatsAppSessionLink = state.whatsAppSessionLink.trim()
        val trimmedWhatsAppWebhookToken = state.whatsAppWebhookToken.trim()
        val trimmedPortStr = state.restApiPort.trim()
        val parsedPort = trimmedPortStr.toIntOrNull() ?: 8000
        val finalPort = if (parsedPort in 1..65535) parsedPort else 8000

        val baseSkills = skillsOverride ?: state.skillsConfig
        val trimmedSearchApiKey = baseSkills.searchApiKey.trim()
        val rawSearchProvider = baseSkills.searchProvider.trim().lowercase()
        val trimmedSearchProvider = if (rawSearchProvider in SkillsConfig.SUPPORTED_SEARCH_PROVIDERS) {
            rawSearchProvider
        } else {
            SkillsConfig.SEARCH_PROVIDER_BRAVE
        }
        val finalSkills = baseSkills.copy(
            searchProvider = trimmedSearchProvider,
            searchApiKey = trimmedSearchApiKey
        )

        return HermesConfig(
            provider = ProviderConfig(
                provider = state.selectedProvider,
                apiKey = trimmedApiKey,
                model = trimmedCustomModel,
                baseUrl = trimmedCustomBaseUrl
            ),
            gateway = GatewayConfig(
                telegram = TelegramGatewayConfig(
                    enabled = state.isTelegramEnabled,
                    botToken = trimmedTelegramToken,
                    adminUserIds = trimmedTelegramAdminIds
                ),
                discord = DiscordGatewayConfig(
                    enabled = state.isDiscordEnabled,
                    botToken = trimmedDiscordToken,
                    channelIds = trimmedDiscordChannelIds
                ),
                slack = SlackGatewayConfig(
                    enabled = state.isSlackEnabled,
                    appToken = trimmedSlackAppToken,
                    botToken = trimmedSlackBotToken
                ),
                whatsapp = WhatsAppGatewayConfig(
                    enabled = state.isWhatsAppEnabled,
                    sessionLink = trimmedWhatsAppSessionLink,
                    webhookToken = trimmedWhatsAppWebhookToken
                ),
                restApi = RestApiGatewayConfig(
                    enabled = state.isRestApiEnabled,
                    port = finalPort
                )
            ),
            system = SystemConfig(
                autoStartOnBoot = state.isAutoStartEnabled,
                publicTunnelEnabled = state.isPublicTunnelEnabled
            ),
            skills = finalSkills
        )
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
            configMutex.withLock {
                val currentState = _uiState.value
                val config = buildHermesConfig(currentState)

                // Update UI state with trimmed inputs
                _uiState.update {
                    it.copy(
                        apiKey = config.provider.apiKey,
                        customModel = config.provider.model,
                        customBaseUrl = config.provider.baseUrl,
                        telegramToken = config.gateway.telegram.botToken,
                        telegramAdminUserIds = config.gateway.telegram.adminUserIds,
                        discordToken = config.gateway.discord.botToken,
                        discordChannelIds = config.gateway.discord.channelIds,
                        slackAppToken = config.gateway.slack.appToken,
                        slackBotToken = config.gateway.slack.botToken,
                        whatsAppSessionLink = config.gateway.whatsapp.sessionLink,
                        whatsAppWebhookToken = config.gateway.whatsapp.webhookToken,
                        restApiPort = config.gateway.restApi.port.toString(),
                        skillsConfig = config.skills,
                        installedSkills = config.skills.toInstalledSkills()
                    )
                }

                try {
                    // Fail closed if persistence dependencies are unavailable
                    if (configRepository == null || configSerializer == null) {
                        val missing = buildList {
                            if (configRepository == null) add("ConfigRepository")
                            if (configSerializer == null) add("ConfigSerializer")
                        }.joinToString(" and ")
                        val error = "Storage unavailable: $missing not initialized"
                        _uiState.update {
                            it.copy(
                                isSavingSettings = false,
                                isSettingsSaved = false,
                                configSaveMessage = error
                            )
                        }
                        onAddLog(error, LogLevel.ERROR)
                        return@launch
                    }

                    // 1. Atomically serialize to hermes.json first — fail closed before committing prefs
                    val result = configSerializer.serialize(config)
                    if (result.isFailure) {
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

                    // 2. Save to ConfigRepository only after file success (prevents prefs/file divergence)
                    configRepository.saveConfig(config)

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
    }

    fun onDismissSaveMessage() {
        _uiState.update {
            it.copy(
                isSettingsSaved = false,
                configSaveMessage = null
            )
        }
    }

    fun onToggleSkill(skillId: String, enabled: Boolean) {
        _uiState.update { current ->
            val updatedSkills = current.skillsConfig.withSkillToggled(skillId, enabled)
            current.copy(
                skillsConfig = updatedSkills,
                installedSkills = updatedSkills.toInstalledSkills(),
                isSettingsSaved = false,
                configSaveMessage = null
            )
        }

        viewModelScope.launch(ioDispatcher) {
            configMutex.withLock {
                val currentState = _uiState.value
                val config = buildHermesConfig(currentState)

                try {
                    if (configSerializer != null) {
                        val result = configSerializer.serialize(config)
                        if (result.isFailure) {
                            val error = result.exceptionOrNull()?.message ?: "Failed to persist skill configuration"
                            _uiState.update { current ->
                                val reverted = current.skillsConfig.withSkillToggled(skillId, !enabled)
                                current.copy(
                                    skillsConfig = reverted,
                                    installedSkills = reverted.toInstalledSkills(),
                                    configSaveMessage = "Failed to update skill: $error"
                                )
                            }
                            onAddLog("Failed to serialize skill configuration: $error", LogLevel.ERROR)
                            return@launch
                        }
                    }
                    configRepository?.saveSkillsConfig(config.skills)
                    onAddLog("Skill '$skillId' ${if (enabled) "enabled" else "disabled"}. hermes.json updated.", LogLevel.INFO)
                } catch (e: Exception) {
                    if (e is kotlinx.coroutines.CancellationException) throw e
                    val error = e.message ?: "Unknown error saving skill configuration"
                    _uiState.update { current ->
                        val reverted = current.skillsConfig.withSkillToggled(skillId, !enabled)
                        current.copy(
                            skillsConfig = reverted,
                            installedSkills = reverted.toInstalledSkills(),
                            configSaveMessage = "Failed to update skill: $error"
                        )
                    }
                    onAddLog("Failed to update skill '$skillId': $error", LogLevel.ERROR)
                }
            }
        }
    }

    fun setSharedStorageEnabled(enabled: Boolean) {
        _uiState.update { current ->
            val updatedSkills = current.skillsConfig.copy(sharedStorageEnabled = enabled)
            current.copy(
                skillsConfig = updatedSkills,
                isSettingsSaved = false,
                configSaveMessage = null
            )
        }

        viewModelScope.launch(ioDispatcher) {
            configMutex.withLock {
                val currentState = _uiState.value
                val config = buildHermesConfig(currentState)

                try {
                    if (configSerializer != null) {
                        val result = configSerializer.serialize(config)
                        if (result.isFailure) {
                            val error = result.exceptionOrNull()?.message ?: "Failed to persist shared storage configuration"
                            _uiState.update { current ->
                                val reverted = current.skillsConfig.copy(sharedStorageEnabled = !enabled)
                                current.copy(
                                    skillsConfig = reverted,
                                    configSaveMessage = "Failed to update shared storage: $error"
                                )
                            }
                            onAddLog("Failed to serialize shared storage configuration: $error", LogLevel.ERROR)
                            return@launch
                        }
                    }
                    configRepository?.saveSkillsConfig(config.skills)
                    onAddLog("Device shared storage ${if (enabled) "enabled" else "disabled"}. hermes.json updated.", LogLevel.INFO)
                } catch (e: Exception) {
                    if (e is kotlinx.coroutines.CancellationException) throw e
                    val error = e.message ?: "Unknown error saving shared storage configuration"
                    _uiState.update { current ->
                        val reverted = current.skillsConfig.copy(sharedStorageEnabled = !enabled)
                        current.copy(
                            skillsConfig = reverted,
                            configSaveMessage = "Failed to update shared storage: $error"
                        )
                    }
                    onAddLog("Failed to update shared storage: $error", LogLevel.ERROR)
                }
            }
        }
    }

    fun onUpdateSharedStorageEnabled(enabled: Boolean) = setSharedStorageEnabled(enabled)

    fun onUpdateProvider(provider: String) {
        _uiState.update { it.copy(selectedProvider = provider, isSettingsSaved = false, configSaveMessage = null) }
    }

    fun onUpdateSearchProvider(provider: String) {
        val normalized = provider.trim().lowercase()
        val validated = if (normalized in SkillsConfig.SUPPORTED_SEARCH_PROVIDERS) normalized else SkillsConfig.SEARCH_PROVIDER_BRAVE
        _uiState.update { current ->
            val updatedSkills = current.skillsConfig.copy(searchProvider = validated)
            current.copy(
                skillsConfig = updatedSkills,
                installedSkills = updatedSkills.toInstalledSkills(),
                isSettingsSaved = false,
                configSaveMessage = null
            )
        }
    }

    fun onUpdateSearchApiKey(apiKey: String) {
        _uiState.update { current ->
            val updatedSkills = current.skillsConfig.copy(searchApiKey = apiKey)
            current.copy(
                skillsConfig = updatedSkills,
                installedSkills = updatedSkills.toInstalledSkills(),
                isSettingsSaved = false,
                configSaveMessage = null
            )
        }
    }

    fun onToggleSearchApiKeyVisibility() {
        _uiState.update { it.copy(searchApiKeyVisible = !it.searchApiKeyVisible) }
    }

    fun onUpdateApiKey(apiKey: String) {
        _uiState.update { it.copy(apiKey = apiKey, isSettingsSaved = false, configSaveMessage = null) }
    }

    fun onUpdateTelegramEnabled(enabled: Boolean) {
        _uiState.update { it.copy(isTelegramEnabled = enabled, isSettingsSaved = false, configSaveMessage = null) }
    }

    fun onUpdateTelegramToken(token: String) {
        _uiState.update { current ->
            current.copy(
                telegramToken = token,
                isSettingsSaved = false,
                configSaveMessage = null
            )
        }
    }

    fun onUpdateTelegramAdminUserIds(adminUserIds: String) {
        _uiState.update { it.copy(telegramAdminUserIds = adminUserIds, isSettingsSaved = false, configSaveMessage = null) }
    }

    fun onUpdateDiscordEnabled(enabled: Boolean) {
        _uiState.update { it.copy(isDiscordEnabled = enabled, isSettingsSaved = false, configSaveMessage = null) }
    }

    fun onUpdateDiscordToken(token: String) {
        _uiState.update { current ->
            current.copy(
                discordToken = token,
                isSettingsSaved = false,
                configSaveMessage = null
            )
        }
    }

    fun onUpdateDiscordChannelIds(channelIds: String) {
        _uiState.update { it.copy(discordChannelIds = channelIds, isSettingsSaved = false, configSaveMessage = null) }
    }

    fun onUpdateSlackEnabled(enabled: Boolean) {
        _uiState.update { it.copy(isSlackEnabled = enabled, isSettingsSaved = false, configSaveMessage = null) }
    }

    fun onUpdateSlackAppToken(appToken: String) {
        _uiState.update { current ->
            current.copy(
                slackAppToken = appToken,
                isSettingsSaved = false,
                configSaveMessage = null
            )
        }
    }

    fun onUpdateSlackBotToken(botToken: String) {
        _uiState.update { current ->
            current.copy(
                slackBotToken = botToken,
                isSettingsSaved = false,
                configSaveMessage = null
            )
        }
    }

    fun onUpdateWhatsAppEnabled(enabled: Boolean) {
        _uiState.update { it.copy(isWhatsAppEnabled = enabled, isSettingsSaved = false, configSaveMessage = null) }
    }

    fun onUpdateWhatsAppSessionLink(sessionLink: String) {
        _uiState.update { current ->
            current.copy(
                whatsAppSessionLink = sessionLink,
                isSettingsSaved = false,
                configSaveMessage = null
            )
        }
    }

    fun onUpdateWhatsAppWebhookToken(webhookToken: String) {
        _uiState.update { current ->
            current.copy(
                whatsAppWebhookToken = webhookToken,
                isSettingsSaved = false,
                configSaveMessage = null
            )
        }
    }

    fun onUpdateRestApiEnabled(enabled: Boolean) {
        _uiState.update { it.copy(isRestApiEnabled = enabled, isSettingsSaved = false, configSaveMessage = null) }
    }

    fun onUpdateRestApiPort(port: String) {
        _uiState.update { it.copy(restApiPort = port, isSettingsSaved = false, configSaveMessage = null) }
    }

    fun onUpdateCustomModel(model: String) {
        _uiState.update { it.copy(customModel = model, isSettingsSaved = false, configSaveMessage = null) }
    }

    fun onUpdateCustomBaseUrl(url: String) {
        _uiState.update { it.copy(customBaseUrl = url, isSettingsSaved = false, configSaveMessage = null) }
    }

    fun onUpdateAutoStart(enabled: Boolean) {
        _uiState.update { current ->
            val shouldPrompt = !current.isBatteryOptimizationIgnored && (enabled || current.status == ServerStatus.RUNNING)
            current.copy(
                isAutoStartEnabled = enabled,
                isSettingsSaved = false,
                configSaveMessage = null,
                showBatteryOptimizationPrompt = shouldPrompt
            )
        }
    }

    fun checkBatteryOptimizationStatus(activityContext: Context? = null) {
        val helper = batteryOptimizationHelper ?: return
        val targetCtx = activityContext ?: context ?: return
        val isIgnored = helper.isIgnoringBatteryOptimizations(targetCtx)
        _uiState.update { current ->
            val wasIgnored = current.isBatteryOptimizationIgnored
            val shouldPrompt = if (isIgnored) {
                false
            } else if (wasIgnored != isIgnored) {
                current.isAutoStartEnabled || current.status == ServerStatus.RUNNING
            } else {
                current.showBatteryOptimizationPrompt
            }
            current.copy(
                isBatteryOptimizationIgnored = isIgnored,
                showBatteryOptimizationPrompt = shouldPrompt
            )
        }
    }

    fun onRequestBatteryExemption(activityContext: Context? = null): Boolean {
        val helper = batteryOptimizationHelper ?: return false
        val targetCtx = activityContext ?: context ?: return false
        val result = helper.requestExemption(targetCtx)
        if (result) {
            onAddLog("Requested battery optimization exemption from Android OS.", LogLevel.INFO)
        } else {
            onAddLog("Unable to open battery optimization settings.", LogLevel.WARN)
        }
        return result
    }

    fun onDismissBatteryOptimizationPrompt() {
        _uiState.update { it.copy(showBatteryOptimizationPrompt = false) }
    }

    fun getDontKillMyAppUrl(): String {
        return batteryOptimizationHelper?.getDontKillMyAppUrl() ?: "https://dontkillmyapp.com"
    }

    fun onUpdatePublicTunnel(enabled: Boolean) {
        val port = _uiState.value.restApiPort.toIntOrNull()?.takeIf { it in 1..65535 } ?: 8000
        _uiState.update { current ->
            current.copy(
                isPublicTunnelEnabled = enabled,
                isSettingsSaved = false,
                configSaveMessage = null,
                tunnelUrl = if (enabled) current.tunnelUrl else null,
                tunnelState = if (enabled) current.tunnelState else TunnelState.Stopped
            )
        }
        if (_uiState.value.status == ServerStatus.RUNNING) {
            tunnelControlJob?.cancel()
            val mgr = effectiveTunnelManager
            if (enabled) {
                if (mgr == null) {
                    _uiState.update { it.copy(tunnelState = TunnelState.Error("Tunnel manager unavailable")) }
                    onAddLog("Cloudflare Tunnel error: Tunnel manager unavailable", LogLevel.WARN)
                } else {
                    onAddLog("Starting Cloudflare Public Tunnel...", LogLevel.INFO)
                    tunnelControlJob = viewModelScope.launch(defaultDispatcher) {
                        mgr.start(port)
                        // Error logged via observeTunnelState
                    }
                }
            } else {
                onAddLog("Stopping Cloudflare Public Tunnel...", LogLevel.INFO)
                if (mgr != null) {
                    tunnelControlJob = viewModelScope.launch(defaultDispatcher) {
                        mgr.stop()
                    }
                } else {
                    _uiState.update { it.copy(tunnelUrl = null, tunnelState = TunnelState.Stopped, showQrCodeDialog = false) }
                }
            }
        }
    }

    fun onShowQrCodeDialog() {
        val cur = _uiState.value
        if (cur.tunnelUrl != null && cur.tunnelState is TunnelState.Running) {
            _uiState.update { it.copy(showQrCodeDialog = true) }
        }
    }

    fun onDismissQrCodeDialog() {
        _uiState.update { it.copy(showQrCodeDialog = false) }
    }

    fun onSetError(message: String) {
        metricsJob?.cancel()
        metricsJob = null
        transitionJob?.cancel()
        tunnelControlJob?.cancel()
        tunnelControlJob = viewModelScope.launch(defaultDispatcher) {
            effectiveTunnelManager?.stop()
        }
        context?.let { ctx ->
            try {
                stopServiceAction?.invoke(ctx)
            } catch (ignored: Exception) {}
        }
        _uiState.update {
            it.copy(
                status = ServerStatus.ERROR,
                uptimeSeconds = 0L,
                tunnelUrl = null,
                tunnelState = TunnelState.Stopped,
                showQrCodeDialog = false,
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

    private fun observeTunnelState() {
        val manager = effectiveTunnelManager
        if (manager == null) {
            if (_uiState.value.isPublicTunnelEnabled) {
                _uiState.update { it.copy(tunnelState = TunnelState.Error("Tunnel manager unavailable")) }
                onAddLog("Cloudflare Tunnel error: Tunnel manager unavailable", LogLevel.WARN)
            }
            return
        }
        tunnelObserverJob?.cancel()
        tunnelObserverJob = viewModelScope.launch {
            manager.state.collect { tState ->
                when (tState) {
                    is TunnelState.Running -> {
                        _uiState.update { it.copy(tunnelUrl = tState.url, tunnelState = tState) }
                        onAddLog("Cloudflare Tunnel connected: ${tState.url}", LogLevel.INFO)
                    }
                    is TunnelState.Error -> {
                        _uiState.update { it.copy(tunnelUrl = null, tunnelState = tState, showQrCodeDialog = false) }
                        onAddLog("Cloudflare Tunnel error: ${tState.message}", LogLevel.WARN)
                    }
                    is TunnelState.Starting -> {
                        _uiState.update { it.copy(tunnelState = tState) }
                    }
                    is TunnelState.Stopped -> {
                        _uiState.update { it.copy(tunnelUrl = null, tunnelState = tState, showQrCodeDialog = false) }
                    }
                }
            }
        }
    }

    private fun observeServiceState() {
        val flow = serviceRunningFlow ?: return
        serviceObserverJob?.cancel()
        serviceObserverJob = viewModelScope.launch {
            flow.collect { isRunning ->
                val currentStatus = _uiState.value.status
                if (!isRunning && (currentStatus == ServerStatus.RUNNING || currentStatus == ServerStatus.STARTING)) {
                    metricsJob?.cancel()
                    metricsJob = null
                    transitionJob?.cancel()
                    tunnelControlJob?.cancel()
                    tunnelControlJob = viewModelScope.launch(defaultDispatcher) {
                        effectiveTunnelManager?.stop()
                    }
                    _uiState.update {
                        it.copy(
                            status = ServerStatus.STOPPED,
                            uptimeSeconds = 0L,
                            tunnelUrl = null,
                            tunnelState = TunnelState.Stopped
                        )
                    }
                    onAddLog("Hermes Node daemon stopped.", LogLevel.INFO)
                } else if (isRunning && currentStatus == ServerStatus.STOPPED) {
                    transitionJob?.cancel()
                    val port = _uiState.value.restApiPort.toIntOrNull()?.takeIf { it in 1..65535 } ?: 8000
                    _uiState.update {
                        it.copy(
                            status = ServerStatus.RUNNING,
                            uptimeSeconds = 0L
                        )
                    }
                    if (_uiState.value.isPublicTunnelEnabled) {
                        onAddLog("Starting Cloudflare Public Tunnel...", LogLevel.INFO)
                        tunnelControlJob?.cancel()
                        tunnelControlJob = viewModelScope.launch(defaultDispatcher) {
                            effectiveTunnelManager?.start(port)
                        }
                    }
                    if (_uiState.value.isRestApiEnabled) {
                        onAddLog("Hermes Node daemon running on port $port", LogLevel.INFO)
                    } else {
                        onAddLog("Hermes Node daemon running (REST API disabled)", LogLevel.INFO)
                    }
                    startMetricsMonitoring()
                }
            }
        }
    }

    private fun observeProcessState() {
        val flow = processStateFlow ?: processController?.state ?: return
        processObserverJob?.cancel()
        processObserverJob = viewModelScope.launch {
            flow.collect { pState ->
                when (pState) {
                    ProcessState.TERMINATED -> {
                        val currentStatus = _uiState.value.status
                        if (currentStatus == ServerStatus.RUNNING || currentStatus == ServerStatus.STARTING) {
                            metricsJob?.cancel()
                            metricsJob = null
                            transitionJob?.cancel()
                            tunnelControlJob?.cancel()
                            tunnelControlJob = viewModelScope.launch(defaultDispatcher) {
                                effectiveTunnelManager?.stop()
                            }
                            _uiState.update {
                                it.copy(
                                    status = ServerStatus.STOPPED,
                                    uptimeSeconds = 0L,
                                    tunnelUrl = null,
                                    tunnelState = TunnelState.Stopped
                                )
                            }
                            onAddLog("Sub-process terminated unexpectedly.", LogLevel.WARN)
                        }
                    }
                    ProcessState.ERROR -> {
                        val currentStatus = _uiState.value.status
                        if (currentStatus == ServerStatus.STARTING || currentStatus == ServerStatus.RUNNING) {
                            val detail = processController?.lastErrorMessage
                                ?: HermesServerService.lastErrorMessage.value
                            val fullErrMsg = if (!detail.isNullOrBlank()) {
                                "Sub-process execution failed: $detail"
                            } else {
                                "Sub-process execution failed."
                            }
                            onSetError(fullErrMsg)
                        }
                    }
                    else -> {}
                }
            }
        }
    }

    fun pauseTelemetry() {
        effectiveMonitor?.pause()
        telemetryJob?.cancel()
        telemetryJob = null
    }

    fun resumeTelemetry() {
        effectiveMonitor?.resume()
        if (telemetryJob == null || telemetryJob?.isActive != true) {
            startTelemetryPolling()
        }
    }

    fun stopMonitoring() {
        metricsJob?.cancel()
        metricsJob = null
        telemetryJob?.cancel()
        telemetryJob = null
        tunnelObserverJob?.cancel()
        tunnelObserverJob = null
        val managerToStop = effectiveTunnelManager
        if (managerToStop != null) {
            tunnelControlJob?.cancel()
            tunnelControlJob = viewModelScope.launch(ioDispatcher) {
                try {
                    val res = managerToStop.stop()
                    if (res.isFailure) {
                        onAddLog("Cloudflare Tunnel stop failed on cleanup: ${res.exceptionOrNull()?.message}", LogLevel.WARN)
                    }
                } catch (_: Throwable) {}
            }
        } else {
            tunnelControlJob?.cancel()
            tunnelControlJob = null
        }
        serviceObserverJob?.cancel()
        serviceObserverJob = null
        processObserverJob?.cancel()
        processObserverJob = null
        logObserverJob?.cancel()
        logObserverJob = null
        transitionJob?.cancel()
        transitionJob = null
        bootstrapJob?.cancel()
        bootstrapJob = null
        saveSettingsJob?.cancel()
        saveSettingsJob = null
        memoryJob?.cancel()
        memoryJob = null
        effectiveMonitor?.stop()
    }

    fun startTelemetryPolling() {
        val monitor = effectiveMonitor ?: return

        telemetryJob?.cancel()
        telemetryJob = viewModelScope.launch(defaultDispatcher) {
            monitor.start(this)
            monitor.telemetry.collect { telemetry ->
                _uiState.update { current ->
                    current.copy(
                        cpuUsagePercent = telemetry.cpuPercent,
                        memoryUsageMb = telemetry.usedMemoryMb,
                        totalMemoryMb = telemetry.totalMemoryMb,
                        batteryPercent = telemetry.batteryPercent,
                        isCharging = telemetry.isCharging,
                        batteryTemperatureCelsius = telemetry.batteryTemperatureCelsius
                    )
                }
            }
        }
    }

    private fun startMetricsMonitoring() {
        metricsJob?.cancel()
        metricsJob = viewModelScope.launch(defaultDispatcher) {
            while (isActive && _uiState.value.status == ServerStatus.RUNNING) {
                delay(1000)
                _uiState.update { current ->
                    if (current.status == ServerStatus.RUNNING) {
                        current.copy(uptimeSeconds = current.uptimeSeconds + 1)
                    } else {
                        current
                    }
                }
            }
        }
    }

    private fun observeLogStreamer() {
        val streamer = logStreamer ?: return
        logObserverJob?.cancel()
        logObserverJob = viewModelScope.launch {
            streamer.logsFlow.collect { logsList ->
                _uiState.update { it.copy(logs = logsList) }
            }
        }
    }

    fun refreshStorageUsage() {
        val manager = effectiveMemoryManager ?: return
        viewModelScope.launch(ioDispatcher) {
            try {
                val bytes = manager.calculateStorageUsage()
                val formatted = manager.formatStorageSize(bytes)
                _uiState.update {
                    it.copy(
                        storageSizeBytes = bytes,
                        storageSizeFormatted = formatted
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val errorMsg = e.message ?: e.javaClass.simpleName
                _uiState.update {
                    it.copy(
                        isMemoryActionSuccess = false,
                        memoryActionMessage = "Storage check failed: $errorMsg"
                    )
                }
                onAddLog("Storage usage check error: $errorMsg", LogLevel.ERROR)
            }
        }
    }

    fun onExportMemoryToDownloads() {
        val manager = effectiveMemoryManager
        if (manager == null) {
            _uiState.update {
                it.copy(
                    isExportingMemory = false,
                    isMemoryActionSuccess = false,
                    memoryActionMessage = "Storage manager unavailable"
                )
            }
            onAddLog("Memory export failed: Storage manager unavailable", LogLevel.ERROR)
            return
        }

        memoryJob?.cancel()
        _uiState.update {
            it.copy(
                isExportingMemory = true,
                memoryActionMessage = null
            )
        }
        onAddLog("Starting episodic memory export to Downloads...", LogLevel.INFO)
        if (_uiState.value.status == ServerStatus.RUNNING) {
            onAddLog("Warning: Server is RUNNING — backup may be less consistent until the daemon is stopped.", LogLevel.WARN)
        }

        memoryJob = viewModelScope.launch(ioDispatcher) {
            val job = coroutineContext[Job]
            try {
                val result = manager.exportToDownloads()
                if (result.isSuccess) {
                    val message = result.getOrNull() ?: "Backup exported to Downloads successfully"
                    _uiState.update {
                        it.copy(
                            isMemoryActionSuccess = true,
                            memoryActionMessage = message
                        )
                    }
                    onAddLog(message, LogLevel.INFO)
                } else {
                    val errorMsg = result.exceptionOrNull()?.message ?: "Export to Downloads failed"
                    _uiState.update {
                        it.copy(
                            isMemoryActionSuccess = false,
                            memoryActionMessage = "Export failed: $errorMsg"
                        )
                    }
                    onAddLog("Export to Downloads error: $errorMsg", LogLevel.ERROR)
                }
                refreshStorageUsage()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val errorMsg = e.message ?: e.javaClass.simpleName
                _uiState.update {
                    it.copy(
                        isMemoryActionSuccess = false,
                        memoryActionMessage = "Export failed: $errorMsg"
                    )
                }
                onAddLog("Export to Downloads error: $errorMsg", LogLevel.ERROR)
            } finally {
                if (job === memoryJob) {
                    _uiState.update { it.copy(isExportingMemory = false) }
                }
            }
        }
    }

    fun onShareMemoryBackup(launcher: ((Intent) -> Unit)? = null) {
        val manager = effectiveMemoryManager
        if (manager == null) {
            _uiState.update {
                it.copy(
                    isExportingMemory = false,
                    isMemoryActionSuccess = false,
                    memoryActionMessage = "Storage manager unavailable"
                )
            }
            onAddLog("Memory share failed: Storage manager unavailable", LogLevel.ERROR)
            return
        }

        memoryJob?.cancel()
        _uiState.update {
            it.copy(
                isExportingMemory = true,
                memoryActionMessage = null
            )
        }
        onAddLog("Preparing episodic memory backup archive for sharing...", LogLevel.INFO)
        if (_uiState.value.status == ServerStatus.RUNNING) {
            onAddLog("Warning: Server is RUNNING — backup may be less consistent until the daemon is stopped.", LogLevel.WARN)
        }

        memoryJob = viewModelScope.launch(ioDispatcher) {
            val job = coroutineContext[Job]
            try {
                val result = manager.getShareIntent()
                if (result.isSuccess) {
                    val shareIntent = result.getOrThrow()
                    try {
                        withContext(Dispatchers.Main) {
                            if (launcher != null) {
                                launcher(shareIntent)
                            } else if (context != null) {
                                context.startActivity(shareIntent)
                            } else {
                                throw IllegalStateException("No activity launcher or Context available to display Share Sheet")
                            }
                        }
                        _uiState.update {
                            it.copy(
                                isMemoryActionSuccess = true,
                                memoryActionMessage = "Backup ready to share"
                            )
                        }
                        onAddLog("Episodic memory backup prepared and share sheet launched.", LogLevel.INFO)
                    } catch (e: Exception) {
                        val errorMsg = e.message ?: "Failed to open share sheet"
                        _uiState.update {
                            it.copy(
                                isMemoryActionSuccess = false,
                                memoryActionMessage = "Share failed: $errorMsg"
                            )
                        }
                        onAddLog("Failed to launch share sheet: $errorMsg", LogLevel.ERROR)
                    }
                } else {
                    val errorMsg = result.exceptionOrNull()?.message ?: "Failed to prepare backup archive"
                    _uiState.update {
                        it.copy(
                            isMemoryActionSuccess = false,
                            memoryActionMessage = "Share failed: $errorMsg"
                        )
                    }
                    onAddLog("Memory share error: $errorMsg", LogLevel.ERROR)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val errorMsg = e.message ?: e.javaClass.simpleName
                _uiState.update {
                    it.copy(
                        isMemoryActionSuccess = false,
                        memoryActionMessage = "Share failed: $errorMsg"
                    )
                }
                onAddLog("Memory share error: $errorMsg", LogLevel.ERROR)
            } finally {
                if (job === memoryJob) {
                    _uiState.update { it.copy(isExportingMemory = false) }
                }
            }
        }
    }

    fun onShowClearMemoryDialog() {
        _uiState.update { it.copy(showClearMemoryDialog = true) }
    }

    fun onDismissClearMemoryDialog() {
        _uiState.update { it.copy(showClearMemoryDialog = false) }
    }

    fun onConfirmClearMemory() {
        _uiState.update {
            it.copy(
                showClearMemoryDialog = false,
                isResettingMemory = true,
                memoryActionMessage = null
            )
        }

        if (_uiState.value.status == ServerStatus.RUNNING) {
            onAddLog("Warning: Episodic memory cleared while server is running. Running daemon will restart memory state cleanly.", LogLevel.WARN)
        }

        val manager = effectiveMemoryManager
        if (manager == null) {
            _uiState.update {
                it.copy(
                    isResettingMemory = false,
                    isMemoryActionSuccess = false,
                    memoryActionMessage = "Storage manager unavailable"
                )
            }
            onAddLog("Memory wipe failed: Storage manager unavailable", LogLevel.ERROR)
            return
        }

        memoryJob?.cancel()
        onAddLog("Starting factory reset of episodic memory...", LogLevel.INFO)

        memoryJob = viewModelScope.launch(ioDispatcher) {
            val job = coroutineContext[Job]
            try {
                val result = manager.clearEpisodicMemory()
                if (result.isSuccess) {
                    val bytes = manager.calculateStorageUsage()
                    val formatted = manager.formatStorageSize(bytes)
                    _uiState.update {
                        it.copy(
                            isMemoryActionSuccess = true,
                            memoryActionMessage = "Episodic memory wiped successfully",
                            storageSizeBytes = bytes,
                            storageSizeFormatted = formatted
                        )
                    }
                    onAddLog("Episodic memory wiped successfully. Configuration and userland preserved.", LogLevel.INFO)
                } else {
                    val errorMsg = result.exceptionOrNull()?.message ?: "Memory wipe failed"
                    _uiState.update {
                        it.copy(
                            isMemoryActionSuccess = false,
                            memoryActionMessage = "Wipe failed: $errorMsg"
                        )
                    }
                    onAddLog("Episodic memory wipe error: $errorMsg", LogLevel.ERROR)
                    refreshStorageUsage()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val errorMsg = e.message ?: e.javaClass.simpleName
                _uiState.update {
                    it.copy(
                        isMemoryActionSuccess = false,
                        memoryActionMessage = "Wipe failed: $errorMsg"
                    )
                }
                onAddLog("Episodic memory wipe error: $errorMsg", LogLevel.ERROR)
            } finally {
                if (job === memoryJob) {
                    _uiState.update { it.copy(isResettingMemory = false) }
                }
            }
        }
    }

    fun onDismissMemoryActionMessage() {
        _uiState.update { it.copy(memoryActionMessage = null) }
    }

    override fun onCleared() {
        super.onCleared()
        stopMonitoring()
    }
}
