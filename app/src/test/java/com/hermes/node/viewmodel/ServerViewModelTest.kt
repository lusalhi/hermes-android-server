package com.hermes.node.viewmodel

import android.content.Intent
import com.hermes.node.data.ConfigRepository
import com.hermes.node.data.ConfigSerializer
import com.hermes.node.data.EncryptedConfigRepository
import com.hermes.node.data.FakeSharedPreferences
import com.hermes.node.data.model.GatewayConfig
import com.hermes.node.data.model.HermesConfig
import com.hermes.node.data.model.ProviderConfig
import com.hermes.node.data.model.SkillsConfig
import com.hermes.node.data.model.SystemConfig
import com.hermes.node.engine.DeviceTelemetry
import com.hermes.node.engine.TelemetryCollector
import com.hermes.node.engine.TunnelManagerInterface
import com.hermes.node.engine.TunnelState
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ServerViewModelTest {

    private val testDispatcher = StandardTestDispatcher()
    private lateinit var viewModel: ServerViewModel

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        viewModel = ServerViewModel(defaultDispatcher = testDispatcher)
    }

    @After
    fun tearDown() {
        viewModel.stopMonitoring()
        Dispatchers.resetMain()
    }

    @Test
    fun initialState_isStopped_withDefaultValues() {
        val state = viewModel.uiState.value
        assertEquals(ServerStatus.STOPPED, state.status)
        assertEquals(0L, state.uptimeSeconds)
        assertEquals(0f, state.cpuUsagePercent, 0.001f)
        assertEquals(0L, state.memoryUsageMb)
        assertEquals("nous_portal", state.selectedProvider)
        assertEquals("", state.apiKey)
        assertEquals("", state.telegramToken)
        assertEquals("", state.customModel)
        assertEquals("", state.customBaseUrl)
        assertFalse(state.isAutoStartEnabled)
        assertFalse(state.isPublicTunnelEnabled)
        assertNull(state.tunnelUrl)
        assertNull(state.errorMessage)
        assertTrue(state.logs.isNotEmpty())
        assertEquals("Hermes Node initialized. Ready to start.", state.logs.first().message)
    }

    @Test
    fun onStartServer_transitionsToStartingThenRunning() = runTest(testDispatcher) {
        viewModel.onStartServer()

        // Immediate state should be STARTING
        assertEquals(ServerStatus.STARTING, viewModel.uiState.value.status)

        // Advance past delay (600ms)
        advanceTimeBy(650)

        val runningState = viewModel.uiState.value
        assertEquals(ServerStatus.RUNNING, runningState.status)
        assertEquals(0L, runningState.uptimeSeconds)
        assertTrue(runningState.logs.any { it.message.contains("Hermes Node daemon running") })

        viewModel.stopMonitoring()
    }

    @Test
    fun onStartServer_withMetricsMonitoring_incrementsUptimeOverTime() = runTest(testDispatcher) {
        viewModel.onStartServer()
        advanceTimeBy(650)
        assertEquals(ServerStatus.RUNNING, viewModel.uiState.value.status)
        assertEquals(0L, viewModel.uiState.value.uptimeSeconds)

        // Advance 3 seconds of virtual time
        advanceTimeBy(3000)

        val runningState = viewModel.uiState.value
        assertEquals(3L, runningState.uptimeSeconds)

        viewModel.stopMonitoring()
    }

    @Test
    fun onStartServer_withPublicTunnel_setsTunnelUrl_andResetsOnStop() = runTest(testDispatcher) {
        val fakeTunnel = FakeTunnelManager(startResult = Result.success("https://hermes-node.trycloudflare.com"))
        val vm = ServerViewModel(
            tunnelManager = fakeTunnel,
            defaultDispatcher = testDispatcher,
            ioDispatcher = testDispatcher
        )
        vm.onUpdatePublicTunnel(true)
        assertTrue(vm.uiState.value.isPublicTunnelEnabled)
        assertNull(vm.uiState.value.tunnelUrl)

        vm.onStartServer()
        advanceTimeBy(650)

        val runningState = vm.uiState.value
        assertEquals(ServerStatus.RUNNING, runningState.status)
        assertEquals("https://hermes-node.trycloudflare.com", runningState.tunnelUrl)

        vm.onStopServer()
        advanceTimeBy(450)

        val stoppedState = vm.uiState.value
        assertEquals(ServerStatus.STOPPED, stoppedState.status)
        assertNull(stoppedState.tunnelUrl)

        vm.stopMonitoring()
    }

    @Test
    fun onStartServer_withTelegramToken_logsGatewayConnection() = runTest(testDispatcher) {
        viewModel.onUpdateTelegramEnabled(true)
        viewModel.onUpdateTelegramToken("123456:ABC-DEF")
        viewModel.onStartServer()
        advanceTimeBy(650)

        assertTrue(viewModel.uiState.value.logs.any {
            it.message.contains("Telegram Gateway configured and enabled")
        })

        viewModel.stopMonitoring()
    }

    @Test
    fun onStopServer_transitionsToStoppingThenStopped() = runTest(testDispatcher) {
        viewModel.onStartServer()
        advanceTimeBy(650)
        assertEquals(ServerStatus.RUNNING, viewModel.uiState.value.status)

        viewModel.onStopServer()
        assertEquals(ServerStatus.STOPPING, viewModel.uiState.value.status)

        advanceTimeBy(450)

        val stoppedState = viewModel.uiState.value
        assertEquals(ServerStatus.STOPPED, stoppedState.status)
        assertEquals(0L, stoppedState.uptimeSeconds)
        assertEquals(0f, stoppedState.cpuUsagePercent, 0.001f)
        assertEquals(0L, stoppedState.memoryUsageMb)
        assertTrue(stoppedState.logs.any { it.message.contains("Hermes Node daemon stopped") })
    }

    @Test
    fun onToggleServer_togglesStateCorrectly() = runTest(testDispatcher) {
        assertEquals(ServerStatus.STOPPED, viewModel.uiState.value.status)

        // First toggle: START
        viewModel.onToggleServer()
        assertEquals(ServerStatus.STARTING, viewModel.uiState.value.status)
        advanceTimeBy(650)
        assertEquals(ServerStatus.RUNNING, viewModel.uiState.value.status)

        // Second toggle: STOP
        viewModel.onToggleServer()
        assertEquals(ServerStatus.STOPPING, viewModel.uiState.value.status)
        advanceTimeBy(450)
        assertEquals(ServerStatus.STOPPED, viewModel.uiState.value.status)
    }

    @Test
    fun onAddLog_appendsLogEntriesInOrder() {
        viewModel.onClearLogs()
        assertTrue(viewModel.uiState.value.logs.isEmpty())

        viewModel.onAddLog("Test message 1", LogLevel.INFO)
        viewModel.onAddLog("Warning message", LogLevel.WARN)
        viewModel.onAddLog("Error message", LogLevel.ERROR)

        val logs = viewModel.uiState.value.logs
        assertEquals(3, logs.size)
        assertEquals("Test message 1", logs[0].message)
        assertEquals(LogLevel.INFO, logs[0].level)
        assertEquals("Warning message", logs[1].message)
        assertEquals(LogLevel.WARN, logs[1].level)
        assertEquals("Error message", logs[2].message)
        assertEquals(LogLevel.ERROR, logs[2].level)
    }

    @Test
    fun onClearLogs_emptiesLogList() {
        viewModel.onAddLog("Log 1", LogLevel.INFO)
        viewModel.onAddLog("Log 2", LogLevel.INFO)
        assertFalse(viewModel.uiState.value.logs.isEmpty())

        viewModel.onClearLogs()
        assertTrue(viewModel.uiState.value.logs.isEmpty())
    }

    @Test
    fun onUpdateSettings_updatesAllConfigFields() {
        viewModel.onUpdateProvider("openrouter")
        assertEquals("openrouter", viewModel.uiState.value.selectedProvider)

        viewModel.onUpdateApiKey("sk-or-v1-test-key-12345")
        assertEquals("sk-or-v1-test-key-12345", viewModel.uiState.value.apiKey)

        viewModel.onUpdateTelegramToken("123456:ABC-DEF1234ghIkl-zyx57W2v1u123ew11")
        assertEquals("123456:ABC-DEF1234ghIkl-zyx57W2v1u123ew11", viewModel.uiState.value.telegramToken)

        viewModel.onUpdateCustomModel("hermes-3-llama-3.1-8b")
        assertEquals("hermes-3-llama-3.1-8b", viewModel.uiState.value.customModel)

        viewModel.onUpdateCustomBaseUrl("http://192.168.1.50:11434/v1")
        assertEquals("http://192.168.1.50:11434/v1", viewModel.uiState.value.customBaseUrl)

        viewModel.onUpdateAutoStart(true)
        assertTrue(viewModel.uiState.value.isAutoStartEnabled)

        viewModel.onUpdatePublicTunnel(true)
        assertTrue(viewModel.uiState.value.isPublicTunnelEnabled)
    }

    @Test
    fun onSetError_updatesStatusAndMessage_andDismissErrorResets() {
        viewModel.onSetError("Subprocess failed with exit code 1")
        val errorState = viewModel.uiState.value
        assertEquals(ServerStatus.ERROR, errorState.status)
        assertEquals("Subprocess failed with exit code 1", errorState.errorMessage)
        assertTrue(errorState.logs.any { it.level == LogLevel.ERROR && it.message.contains("Subprocess failed") })

        viewModel.onDismissError()
        val dismissedState = viewModel.uiState.value
        assertEquals(ServerStatus.STOPPED, dismissedState.status)
        assertNull(dismissedState.errorMessage)
    }

    @Test
    fun onSetError_resetsMetricsAndTunnelUrl() = runTest(testDispatcher) {
        val fakeTunnel = FakeTunnelManager(startResult = Result.success("https://hermes-node.trycloudflare.com"))
        val vm = ServerViewModel(
            tunnelManager = fakeTunnel,
            defaultDispatcher = testDispatcher,
            ioDispatcher = testDispatcher
        )
        vm.onUpdatePublicTunnel(true)
        vm.onStartServer()
        advanceTimeBy(650)
        advanceTimeBy(2000)

        val runningState = vm.uiState.value
        assertEquals(ServerStatus.RUNNING, runningState.status)
        assertTrue(runningState.uptimeSeconds > 0L)
        assertNotNull(runningState.tunnelUrl)

        vm.onSetError("Fatal error encountered")

        val errorState = vm.uiState.value
        assertEquals(ServerStatus.ERROR, errorState.status)
        assertEquals(0L, errorState.uptimeSeconds)
        assertEquals(0f, errorState.cpuUsagePercent, 0.001f)
        assertEquals(0L, errorState.memoryUsageMb)
        assertNull(errorState.tunnelUrl)
        assertEquals("Fatal error encountered", errorState.errorMessage)

        vm.stopMonitoring()
    }

    @Test
    fun init_withoutExtractor_setsBootstrapCompleteTrue() {
        val state = viewModel.uiState.value
        assertTrue(state.isBootstrapComplete)
        assertFalse(state.isBootstrapping)
    }

    @Test
    fun init_withInstalledExtractor_setsBootstrapCompleteTrue() {
        val fakeExtractor = FakeBootstrapExtractor(installed = true)
        val vm = ServerViewModel(
            bootstrapExtractor = fakeExtractor,
            defaultDispatcher = testDispatcher,
            ioDispatcher = testDispatcher
        )

        val state = vm.uiState.value
        assertTrue(state.isBootstrapComplete)
        assertFalse(state.isBootstrapping)
        assertEquals(0, fakeExtractor.extractCalls)
        assertTrue(state.logs.any { it.message.contains("ARM64 Linux userland verified") })
        vm.stopMonitoring()
    }

    @Test
    fun init_withUninstalledExtractor_triggersExtractionAndCompletes() = runTest(testDispatcher) {
        val fakeExtractor = FakeBootstrapExtractor(installed = false, shouldSucceed = true)
        val vm = ServerViewModel(
            bootstrapExtractor = fakeExtractor,
            defaultDispatcher = testDispatcher,
            ioDispatcher = testDispatcher
        )

        testScheduler.advanceUntilIdle()

        val state = vm.uiState.value
        assertTrue(state.isBootstrapComplete)
        assertFalse(state.isBootstrapping)
        assertEquals(1, fakeExtractor.extractCalls)
        assertEquals(1.0f, state.bootstrapProgress, 0.001f)
        assertTrue(state.logs.any { it.message.contains("extracted and verified successfully") })
        vm.stopMonitoring()
    }

    @Test
    fun init_withFailingExtractor_setsErrorState() = runTest(testDispatcher) {
        val fakeExtractor = FakeBootstrapExtractor(installed = false, shouldSucceed = false, errorMessage = "Corrupt tar.xz")
        val vm = ServerViewModel(
            bootstrapExtractor = fakeExtractor,
            defaultDispatcher = testDispatcher,
            ioDispatcher = testDispatcher
        )

        testScheduler.advanceUntilIdle()

        val state = vm.uiState.value
        assertFalse(state.isBootstrapComplete)
        assertFalse(state.isBootstrapping)
        assertEquals("Corrupt tar.xz", state.errorMessage)
        assertTrue(state.logs.any { it.level == LogLevel.ERROR && it.message.contains("Corrupt tar.xz") })
        vm.stopMonitoring()
    }

    @Test
    fun onStartServer_whenBootstrapIncomplete_setsError() = runTest(testDispatcher) {
        val fakeExtractor = FakeBootstrapExtractor(installed = false, shouldSucceed = false)
        val vm = ServerViewModel(
            bootstrapExtractor = fakeExtractor,
            defaultDispatcher = testDispatcher,
            ioDispatcher = testDispatcher
        )

        testScheduler.advanceUntilIdle()
        assertFalse(vm.uiState.value.isBootstrapComplete)

        vm.onStartServer()

        val state = vm.uiState.value
        assertEquals(ServerStatus.ERROR, state.status)
        assertTrue(state.errorMessage?.contains("Linux userland is not installed") == true)
        vm.stopMonitoring()
    }

    @Test
    fun onStartServer_whenBootstrapping_doesNotStart() {
        val fakeExtractor = FakeBootstrapExtractor(installed = false, shouldSucceed = false)
        val vm = ServerViewModel(
            bootstrapExtractor = fakeExtractor,
            defaultDispatcher = testDispatcher,
            ioDispatcher = testDispatcher
        )

        assertTrue(vm.uiState.value.isBootstrapping)
        vm.onStartServer()

        val state = vm.uiState.value
        assertEquals(ServerStatus.STOPPED, state.status)
        assertTrue(state.logs.any { it.message.contains("Linux userland extraction in progress") })
        vm.stopMonitoring()
    }

    @Test
    fun onStartServer_whenRepairing_doesNotStart() {
        val fakeExtractor = FakeBootstrapExtractor(
            healthResult = com.hermes.node.engine.HealthCheckResult.Corrupted(listOf("Missing python3"), "Missing python3"),
            shouldRepairSucceed = false
        )
        val vm = ServerViewModel(
            bootstrapExtractor = fakeExtractor,
            defaultDispatcher = testDispatcher,
            ioDispatcher = testDispatcher
        )

        vm.onRepairRuntime()
        assertTrue(vm.uiState.value.isRepairing)

        vm.onStartServer()

        val state = vm.uiState.value
        assertEquals(ServerStatus.STOPPED, state.status)
        assertTrue(state.logs.any { it.message.contains("Linux userland repair in progress") })
        vm.stopMonitoring()
    }

    @Test
    fun triggerBootstrap_retriesExtractionSuccessfully() = runTest(testDispatcher) {
        val fakeExtractor = FakeBootstrapExtractor(installed = false, shouldSucceed = false)
        val vm = ServerViewModel(
            bootstrapExtractor = fakeExtractor,
            defaultDispatcher = testDispatcher,
            ioDispatcher = testDispatcher
        )

        testScheduler.advanceUntilIdle()
        assertFalse(vm.uiState.value.isBootstrapComplete)

        // Fix extractor to succeed
        fakeExtractor.shouldSucceed = true
        vm.triggerBootstrap()
        testScheduler.advanceUntilIdle()

        val state = vm.uiState.value
        assertTrue(state.isBootstrapComplete)
        assertFalse(state.isBootstrapping)
        assertNull(state.errorMessage)
        vm.stopMonitoring()
    }

    @Test
    fun triggerBootstrap_stopsServer_ifRunning() = runTest(testDispatcher) {
        val fakeExtractor = FakeBootstrapExtractor(installed = true)
        val vm = ServerViewModel(
            bootstrapExtractor = fakeExtractor,
            defaultDispatcher = testDispatcher,
            ioDispatcher = testDispatcher
        )

        vm.onStartServer()
        advanceTimeBy(650)
        assertEquals(ServerStatus.RUNNING, vm.uiState.value.status)

        vm.triggerBootstrap()
        assertEquals(ServerStatus.STOPPING, vm.uiState.value.status)
        assertTrue(vm.uiState.value.isBootstrapping)

        advanceTimeBy(450)
        testScheduler.advanceUntilIdle()
        assertEquals(ServerStatus.STOPPED, vm.uiState.value.status)
        vm.stopMonitoring()
    }

    @Test
    fun onStartServer_whenStopping_doesNotStart() = runTest(testDispatcher) {
        viewModel.onStartServer()
        advanceTimeBy(650)
        assertEquals(ServerStatus.RUNNING, viewModel.uiState.value.status)

        viewModel.onStopServer()
        assertEquals(ServerStatus.STOPPING, viewModel.uiState.value.status)

        // Attempt start while stopping
        viewModel.onStartServer()
        assertEquals(ServerStatus.STOPPING, viewModel.uiState.value.status)
        advanceTimeBy(450)
        assertEquals(ServerStatus.STOPPED, viewModel.uiState.value.status)
    }

    @Test
    fun init_withCorruptedExtractor_setsRuntimeCorruptedState_andIntegrityWarning() {
        val fakeExtractor = FakeBootstrapExtractor(
            healthResult = com.hermes.node.engine.HealthCheckResult.Corrupted(
                listOf("Missing critical binary: bin/python3"),
                "Missing critical binary: bin/python3"
            )
        )
        val vm = ServerViewModel(
            bootstrapExtractor = fakeExtractor,
            defaultDispatcher = testDispatcher,
            ioDispatcher = testDispatcher
        )

        val state = vm.uiState.value
        assertTrue(state.isRuntimeCorrupted)
        assertFalse(state.isBootstrapComplete)
        assertEquals("Missing critical binary: bin/python3", state.integrityWarning)
        assertEquals(0, fakeExtractor.extractCalls)
        assertTrue(state.logs.any { it.level == LogLevel.WARN && it.message.contains("Runtime integrity check failed") })
        vm.stopMonitoring()
    }

    @Test
    fun onStartServer_whenRuntimeCorrupted_blocksServerStart_andSetsErrorState() {
        val fakeExtractor = FakeBootstrapExtractor(
            healthResult = com.hermes.node.engine.HealthCheckResult.Corrupted(
                listOf("Missing critical binary: bin/python3"),
                "Missing critical binary: bin/python3"
            )
        )
        val vm = ServerViewModel(
            bootstrapExtractor = fakeExtractor,
            defaultDispatcher = testDispatcher,
            ioDispatcher = testDispatcher
        )

        vm.onStartServer()

        val state = vm.uiState.value
        assertEquals(ServerStatus.ERROR, state.status)
        assertTrue(state.errorMessage?.contains("Runtime is corrupted") == true)
        assertTrue(state.errorMessage?.contains("Missing critical binary: bin/python3") == true)
        vm.stopMonitoring()
    }

    @Test
    fun onRepairRuntime_whenSuccessful_restoresUserland_andTransitionsToHealthyState() = runTest(testDispatcher) {
        val fakeExtractor = FakeBootstrapExtractor(
            healthResult = com.hermes.node.engine.HealthCheckResult.Corrupted(
                listOf("Missing critical binary: bin/hermes"),
                "Missing critical binary: bin/hermes"
            ),
            shouldRepairSucceed = true
        )
        val vm = ServerViewModel(
            bootstrapExtractor = fakeExtractor,
            defaultDispatcher = testDispatcher,
            ioDispatcher = testDispatcher
        )
        assertTrue(vm.uiState.value.isRuntimeCorrupted)

        vm.onRepairRuntime()
        assertTrue(vm.uiState.value.isRepairing)
        assertTrue(vm.uiState.value.isBootstrapping)

        testScheduler.advanceUntilIdle()

        val state = vm.uiState.value
        assertFalse(state.isRepairing)
        assertFalse(state.isBootstrapping)
        assertFalse(state.isRuntimeCorrupted)
        assertTrue(state.isBootstrapComplete)
        assertNull(state.integrityWarning)
        assertEquals(1, fakeExtractor.repairCalls)
        assertTrue(state.logs.any { it.message.contains("repaired and verified successfully") })
        vm.stopMonitoring()
    }

    @Test
    fun onRepairRuntime_stopsRunningServer_beforeRepairing() = runTest(testDispatcher) {
        val fakeExtractor = FakeBootstrapExtractor(installed = true)
        val vm = ServerViewModel(
            bootstrapExtractor = fakeExtractor,
            defaultDispatcher = testDispatcher,
            ioDispatcher = testDispatcher
        )

        vm.onStartServer()
        advanceTimeBy(650)
        assertEquals(ServerStatus.RUNNING, vm.uiState.value.status)

        fakeExtractor.healthResult = com.hermes.node.engine.HealthCheckResult.Corrupted(listOf("Damaged"), "Damaged")
        vm.onRepairRuntime()

        // Server is immediately stopped to prevent race conditions during userland cleanup
        assertEquals(ServerStatus.STOPPED, vm.uiState.value.status)
        assertTrue(vm.uiState.value.isRepairing)

        testScheduler.advanceUntilIdle()

        assertEquals(ServerStatus.STOPPED, vm.uiState.value.status)
        assertTrue(vm.uiState.value.isBootstrapComplete)
        assertFalse(vm.uiState.value.isRuntimeCorrupted)
        vm.stopMonitoring()
    }

    @Test
    fun onRepairRuntime_whenRepairFails_setsErrorState_andPreservesCorruptedFlag() = runTest(testDispatcher) {
        val fakeExtractor = FakeBootstrapExtractor(
            healthResult = com.hermes.node.engine.HealthCheckResult.Corrupted(listOf("Missing python3"), "Missing python3"),
            shouldRepairSucceed = false,
            errorMessage = "I/O Disk full during repair"
        )
        val vm = ServerViewModel(
            bootstrapExtractor = fakeExtractor,
            defaultDispatcher = testDispatcher,
            ioDispatcher = testDispatcher
        )

        vm.onRepairRuntime()
        testScheduler.advanceUntilIdle()

        val state = vm.uiState.value
        assertFalse(state.isRepairing)
        assertFalse(state.isBootstrapping)
        assertTrue(state.isRuntimeCorrupted)
        assertEquals("I/O Disk full during repair", state.errorMessage)
        assertTrue(state.logs.any { it.level == LogLevel.ERROR && it.message.contains("I/O Disk full during repair") })
        vm.stopMonitoring()
    }

    @Test
    fun performHealthCheck_dynamicallyUpdatesUiState_whenStatusChanges() {
        val fakeExtractor = FakeBootstrapExtractor(installed = true)
        val vm = ServerViewModel(
            bootstrapExtractor = fakeExtractor,
            defaultDispatcher = testDispatcher,
            ioDispatcher = testDispatcher
        )
        assertTrue(vm.uiState.value.isBootstrapComplete)
        assertFalse(vm.uiState.value.isRuntimeCorrupted)

        // Simulate external corruption
        fakeExtractor.healthResult = com.hermes.node.engine.HealthCheckResult.Corrupted(listOf("Missing proot"), "Missing proot")
        val result = vm.performHealthCheck()

        assertTrue(result is com.hermes.node.engine.HealthCheckResult.Corrupted)
        assertTrue(vm.uiState.value.isRuntimeCorrupted)
        assertFalse(vm.uiState.value.isBootstrapComplete)
        assertEquals("Missing proot", vm.uiState.value.integrityWarning)
        vm.stopMonitoring()
    }

    @Test
    fun onStartServer_catchesDynamicDiskCorruption_whenPreviouslyHealthy() {
        val fakeExtractor = FakeBootstrapExtractor(installed = true)
        val vm = ServerViewModel(
            bootstrapExtractor = fakeExtractor,
            defaultDispatcher = testDispatcher,
            ioDispatcher = testDispatcher
        )
        assertTrue(vm.uiState.value.isBootstrapComplete)
        assertFalse(vm.uiState.value.isRuntimeCorrupted)

        // Dynamic corruption occurs on disk before start is tapped
        fakeExtractor.healthResult = com.hermes.node.engine.HealthCheckResult.Corrupted(
            listOf("Missing critical binary: bin/hermes"),
            "Missing critical binary: bin/hermes"
        )

        vm.onStartServer()

        val state = vm.uiState.value
        assertEquals(ServerStatus.ERROR, state.status)
        assertTrue(state.isRuntimeCorrupted)
        assertFalse(state.isBootstrapComplete)
        assertEquals("Missing critical binary: bin/hermes", state.integrityWarning)
        assertTrue(state.errorMessage?.contains("Runtime is corrupted") == true)
        vm.stopMonitoring()
    }

    @Test
    fun init_withPersistedConfig_populatesUiStateOnStartup() = runTest(testDispatcher) {
        val fakePrefs = FakeSharedPreferences()
        val repository = EncryptedConfigRepository(fakePrefs)
        repository.saveConfig(
            HermesConfig(
                provider = ProviderConfig(
                    provider = "openrouter",
                    apiKey = "sk-or-v1-saved-key-12345",
                    model = "nousresearch/hermes-3-llama-3.1-405b",
                    baseUrl = "https://openrouter.ai/api/v1"
                ),
                gateway = GatewayConfig(
                    telegramToken = "987654:SAVED-TELEGRAM-TOKEN",
                    isTelegramEnabled = true
                ),
                system = SystemConfig(
                    autoStartOnBoot = true,
                    publicTunnelEnabled = true
                )
            )
        )

        val vm = ServerViewModel(
            configRepository = repository,
            defaultDispatcher = testDispatcher,
            ioDispatcher = testDispatcher
        )

        val state = vm.uiState.value
        assertEquals("openrouter", state.selectedProvider)
        assertEquals("sk-or-v1-saved-key-12345", state.apiKey)
        assertEquals("987654:SAVED-TELEGRAM-TOKEN", state.telegramToken)
        assertEquals("nousresearch/hermes-3-llama-3.1-405b", state.customModel)
        assertEquals("https://openrouter.ai/api/v1", state.customBaseUrl)
        assertTrue(state.isAutoStartEnabled)
        assertTrue(state.isPublicTunnelEnabled)
        assertTrue(state.logs.any { it.message.contains("Loaded persisted credentials") })

        vm.stopMonitoring()
    }

    @Test
    fun onSaveSettings_persistsToRepository_andSerializesConfig() = runTest(testDispatcher) {
        val fakePrefs = FakeSharedPreferences()
        val repository = EncryptedConfigRepository(fakePrefs)
        val tempDir = File(System.getProperty("java.io.tmpdir") ?: "/tmp", "hermes_vm_test_${System.currentTimeMillis()}")
        tempDir.mkdirs()
        val configFile = File(tempDir, "hermes.json")
        val serializer = ConfigSerializer(configFile)

        val vm = ServerViewModel(
            configRepository = repository,
            configSerializer = serializer,
            defaultDispatcher = testDispatcher,
            ioDispatcher = testDispatcher
        )

        // Provide inputs with surrounding whitespace to test trimming
        vm.onUpdateProvider("anthropic")
        vm.onUpdateApiKey("  sk-ant-api-test-key  ")
        vm.onUpdateTelegramToken("  123456:BOT-TOKEN  ")
        vm.onUpdateCustomModel("  claude-3-5-sonnet  ")
        vm.onUpdateCustomBaseUrl("  https://api.anthropic.com  ")
        vm.onUpdateAutoStart(true)
        vm.onUpdatePublicTunnel(true)

        vm.onSaveSettings()
        // Verify isSavingSettings is set immediately
        assertTrue(vm.uiState.value.isSavingSettings)

        testScheduler.advanceUntilIdle()

        val state = vm.uiState.value
        assertFalse(state.isSavingSettings)
        assertTrue(state.isSettingsSaved)
        assertEquals("Settings saved successfully", state.configSaveMessage)
        assertTrue(state.logs.any { it.message.contains("Settings saved successfully") })

        // Verify inputs were trimmed in UI state
        assertEquals("sk-ant-api-test-key", state.apiKey)
        assertEquals("123456:BOT-TOKEN", state.telegramToken)
        assertEquals("claude-3-5-sonnet", state.customModel)
        assertEquals("https://api.anthropic.com", state.customBaseUrl)

        // Verify repository was updated with trimmed values
        val persistedConfig = repository.getConfig()
        assertEquals("anthropic", persistedConfig.provider.provider)
        assertEquals("sk-ant-api-test-key", persistedConfig.provider.apiKey)
        assertEquals("123456:BOT-TOKEN", persistedConfig.gateway.telegramToken)
        assertEquals("claude-3-5-sonnet", persistedConfig.provider.model)
        assertEquals("https://api.anthropic.com", persistedConfig.provider.baseUrl)
        assertTrue(persistedConfig.system.autoStartOnBoot)
        assertTrue(persistedConfig.system.publicTunnelEnabled)

        // Verify file was written with valid permissions
        assertTrue(configFile.exists())
        assertTrue(configFile.canRead())
        assertTrue(configFile.canWrite())
        assertFalse(configFile.canExecute())
        assertTrue(configFile.readText(Charsets.UTF_8).contains("sk-ant-api-test-key"))

        tempDir.deleteRecursively()
        vm.stopMonitoring()
    }

    @Test
    fun onSaveSettings_whenSerializationFails_surfacesErrorFeedback() = runTest(testDispatcher) {
        val fakePrefs = FakeSharedPreferences()
        val repository = EncryptedConfigRepository(fakePrefs)
        val invalidFile = File(System.getProperty("java.io.tmpdir") ?: "/tmp")
        val brokenSerializer = object : ConfigSerializer(invalidFile) {
            override fun serialize(config: HermesConfig): Result<File> {
                return Result.failure(java.io.IOException("Disk I/O failure on hermes.json"))
            }
        }

        val vm = ServerViewModel(
            configRepository = repository,
            configSerializer = brokenSerializer,
            defaultDispatcher = testDispatcher,
            ioDispatcher = testDispatcher
        )

        vm.onSaveSettings()
        assertTrue(vm.uiState.value.isSavingSettings)

        testScheduler.advanceUntilIdle()

        val state = vm.uiState.value
        assertFalse(state.isSavingSettings)
        assertFalse(state.isSettingsSaved)
        assertTrue(state.configSaveMessage?.contains("Disk I/O failure") == true)
        assertTrue(state.logs.any { it.level == LogLevel.ERROR && it.message.contains("Error saving runtime configuration") })

        vm.stopMonitoring()
    }

    @Test
    fun onDismissSaveMessage_resetsSaveStatusAndMessage() = runTest(testDispatcher) {
        val fakePrefs = FakeSharedPreferences()
        val repository = EncryptedConfigRepository(fakePrefs)
        val tempDir = File(System.getProperty("java.io.tmpdir") ?: "/tmp", "hermes_dismiss_test_${System.currentTimeMillis()}")
        tempDir.mkdirs()
        val configFile = File(tempDir, "hermes.json")
        val serializer = ConfigSerializer(configFile)

        val vm = ServerViewModel(
            configRepository = repository,
            configSerializer = serializer,
            defaultDispatcher = testDispatcher,
            ioDispatcher = testDispatcher
        )

        vm.onSaveSettings()
        testScheduler.advanceUntilIdle()
        assertTrue(vm.uiState.value.isSettingsSaved)

        vm.onDismissSaveMessage()
        assertFalse(vm.uiState.value.isSettingsSaved)
        assertNull(vm.uiState.value.configSaveMessage)

        tempDir.deleteRecursively()
        vm.stopMonitoring()
    }

    @Test
    fun onUpdateSettings_resetsSavedStateAndMessage() = runTest(testDispatcher) {
        val fakePrefs = FakeSharedPreferences()
        val repository = EncryptedConfigRepository(fakePrefs)
        val tempDir = File(System.getProperty("java.io.tmpdir") ?: "/tmp", "hermes_update_test_${System.currentTimeMillis()}")
        tempDir.mkdirs()
        val configFile = File(tempDir, "hermes.json")
        val serializer = ConfigSerializer(configFile)

        val vm = ServerViewModel(
            configRepository = repository,
            configSerializer = serializer,
            defaultDispatcher = testDispatcher,
            ioDispatcher = testDispatcher
        )

        vm.onSaveSettings()
        testScheduler.advanceUntilIdle()
        assertTrue(vm.uiState.value.isSettingsSaved)

        vm.onUpdateApiKey("new-edited-key")
        assertFalse(vm.uiState.value.isSettingsSaved)
        assertNull(vm.uiState.value.configSaveMessage)

        tempDir.deleteRecursively()
        vm.stopMonitoring()
    }

    @Test
    fun onStartServer_triggersStartServiceAction_whenContextProvided() = runTest(testDispatcher) {
        var startServiceCalled = false
        var stopServiceCalled = false
        val dummyContext = android.content.ContextWrapper(null)

        val vm = ServerViewModel(
            context = dummyContext,
            defaultDispatcher = testDispatcher,
            ioDispatcher = testDispatcher,
            startServiceAction = { startServiceCalled = true },
            stopServiceAction = { stopServiceCalled = true }
        )

        vm.onStartServer()
        assertTrue(startServiceCalled)
        assertFalse(stopServiceCalled)

        advanceTimeBy(650)
        vm.stopMonitoring()
    }

    @Test
    fun onStopServer_triggersStopServiceAction_whenContextProvided() = runTest(testDispatcher) {
        var startServiceCalled = false
        var stopServiceCalled = false
        val dummyContext = android.content.ContextWrapper(null)

        val vm = ServerViewModel(
            context = dummyContext,
            defaultDispatcher = testDispatcher,
            ioDispatcher = testDispatcher,
            startServiceAction = { startServiceCalled = true },
            stopServiceAction = { stopServiceCalled = true }
        )

        vm.onStartServer()
        advanceTimeBy(650)
        assertEquals(ServerStatus.RUNNING, vm.uiState.value.status)

        vm.onStopServer()
        assertTrue(stopServiceCalled)

        advanceTimeBy(450)
        assertEquals(ServerStatus.STOPPED, vm.uiState.value.status)
        vm.stopMonitoring()
    }

    @Test
    fun serviceObserver_whenServiceStoppedExternally_transitionsViewModelToStopped() = runTest(testDispatcher) {
        val runningFlow = kotlinx.coroutines.flow.MutableStateFlow(true)
        val vm = ServerViewModel(
            defaultDispatcher = testDispatcher,
            ioDispatcher = testDispatcher,
            serviceRunningFlow = runningFlow
        )

        vm.onStartServer()
        advanceTimeBy(650)
        assertEquals(ServerStatus.RUNNING, vm.uiState.value.status)

        // Notification "Stop" action or external service shutdown occurs
        runningFlow.value = false
        advanceTimeBy(100)

        val state = vm.uiState.value
        assertEquals(ServerStatus.STOPPED, state.status)
        assertEquals(0L, state.uptimeSeconds)
        assertTrue(state.logs.any { it.message.contains("Hermes Node daemon stopped") })

        vm.stopMonitoring()
    }

    @Test
    fun serviceObserver_whenServiceStartedExternally_transitionsViewModelToRunning() = runTest(testDispatcher) {
        val runningFlow = kotlinx.coroutines.flow.MutableStateFlow(false)
        val vm = ServerViewModel(
            defaultDispatcher = testDispatcher,
            ioDispatcher = testDispatcher,
            serviceRunningFlow = runningFlow
        )

        assertEquals(ServerStatus.STOPPED, vm.uiState.value.status)

        // External service startup occurs
        runningFlow.value = true
        advanceTimeBy(100)

        val state = vm.uiState.value
        assertEquals(ServerStatus.RUNNING, state.status)
        assertTrue(state.logs.any { it.message.contains("Hermes Node daemon running on port 8000") })

        vm.stopMonitoring()
    }

    @Test
    fun onSetError_triggersStopServiceAction_whenContextProvided() = runTest(testDispatcher) {
        var stopServiceCalled = false
        val dummyContext = android.content.ContextWrapper(null)

        val vm = ServerViewModel(
            context = dummyContext,
            defaultDispatcher = testDispatcher,
            ioDispatcher = testDispatcher,
            startServiceAction = {},
            stopServiceAction = { stopServiceCalled = true }
        )

        vm.onStartServer()
        advanceTimeBy(650)
        assertEquals(ServerStatus.RUNNING, vm.uiState.value.status)

        vm.onSetError("Fatal daemon crash")
        assertTrue(stopServiceCalled)
        assertEquals(ServerStatus.ERROR, vm.uiState.value.status)

        vm.stopMonitoring()
    }

    @Test
    fun onRepairRuntime_triggersStopServiceAction_whenRunning() = runTest(testDispatcher) {
        var stopServiceCalled = false
        val dummyContext = android.content.ContextWrapper(null)
        val fakeExtractor = FakeBootstrapExtractor(installed = true)

        val vm = ServerViewModel(
            context = dummyContext,
            bootstrapExtractor = fakeExtractor,
            defaultDispatcher = testDispatcher,
            ioDispatcher = testDispatcher,
            startServiceAction = {},
            stopServiceAction = { stopServiceCalled = true }
        )

        vm.onStartServer()
        advanceTimeBy(650)
        assertEquals(ServerStatus.RUNNING, vm.uiState.value.status)

        fakeExtractor.healthResult = com.hermes.node.engine.HealthCheckResult.Corrupted(listOf("Damaged"), "Damaged")
        vm.onRepairRuntime()
        assertTrue(stopServiceCalled)
        assertEquals(ServerStatus.STOPPED, vm.uiState.value.status)

        advanceTimeBy(100)
        vm.stopMonitoring()
    }

    @Test
    fun processObserver_whenProcessTerminated_transitionsViewModelToStopped() = runTest(testDispatcher) {
        val processStateFlow = kotlinx.coroutines.flow.MutableStateFlow(com.hermes.node.engine.ProcessState.RUNNING)
        val vm = ServerViewModel(
            defaultDispatcher = testDispatcher,
            ioDispatcher = testDispatcher,
            processStateFlow = processStateFlow
        )

        vm.onStartServer()
        advanceTimeBy(650)
        assertEquals(ServerStatus.RUNNING, vm.uiState.value.status)

        // Process unexpectedly terminated
        processStateFlow.value = com.hermes.node.engine.ProcessState.TERMINATED
        advanceTimeBy(100)

        val state = vm.uiState.value
        assertEquals(ServerStatus.STOPPED, state.status)
        assertEquals(0L, state.uptimeSeconds)
        assertTrue(state.logs.any { it.message.contains("Sub-process terminated unexpectedly") })

        vm.stopMonitoring()
    }

    @Test
    fun processObserver_whenProcessError_transitionsViewModelToError() = runTest(testDispatcher) {
        val processStateFlow = kotlinx.coroutines.flow.MutableStateFlow(com.hermes.node.engine.ProcessState.STARTING)
        val vm = ServerViewModel(
            defaultDispatcher = testDispatcher,
            ioDispatcher = testDispatcher,
            processStateFlow = processStateFlow
        )

        vm.onStartServer()
        assertEquals(ServerStatus.STARTING, vm.uiState.value.status)

        // Process startup failed with ERROR
        processStateFlow.value = com.hermes.node.engine.ProcessState.ERROR
        advanceTimeBy(100)

        val state = vm.uiState.value
        assertEquals(ServerStatus.ERROR, state.status)
        assertTrue(state.errorMessage?.contains("Sub-process execution failed") == true)

        vm.stopMonitoring()
    }

    @Test
    fun batteryOptimization_initialState_defaults() {
        val state = viewModel.uiState.value
        assertFalse(state.isBatteryOptimizationIgnored)
        assertFalse(state.showBatteryOptimizationPrompt)
    }

    @Test
    fun checkBatteryOptimizationStatus_whenIgnoring_updatesStateToIgnored() {
        val fakeHelper = FakeBatteryOptimizationHelper(isIgnored = true)
        val dummyContext = android.content.ContextWrapper(null)
        val vm = ServerViewModel(
            context = dummyContext,
            batteryOptimizationHelper = fakeHelper,
            defaultDispatcher = testDispatcher,
            ioDispatcher = testDispatcher
        )

        val state = vm.uiState.value
        assertTrue(state.isBatteryOptimizationIgnored)
        assertFalse(state.showBatteryOptimizationPrompt)
        vm.stopMonitoring()
    }

    @Test
    fun checkBatteryOptimizationStatus_whenNotIgnoringAndAutoStartEnabled_showsPrompt() {
        val fakeHelper = FakeBatteryOptimizationHelper(isIgnored = false)
        val dummyContext = android.content.ContextWrapper(null)
        val vm = ServerViewModel(
            context = dummyContext,
            batteryOptimizationHelper = fakeHelper,
            defaultDispatcher = testDispatcher,
            ioDispatcher = testDispatcher
        )

        assertFalse(vm.uiState.value.isBatteryOptimizationIgnored)
        assertFalse(vm.uiState.value.showBatteryOptimizationPrompt)

        vm.onUpdateAutoStart(true)
        assertTrue(vm.uiState.value.isAutoStartEnabled)
        assertTrue(vm.uiState.value.showBatteryOptimizationPrompt)

        // Toggling auto-start to false when STOPPED should reset prompt
        vm.onUpdateAutoStart(false)
        assertFalse(vm.uiState.value.isAutoStartEnabled)
        assertFalse(vm.uiState.value.showBatteryOptimizationPrompt)

        vm.stopMonitoring()
    }

    @Test
    fun onUpdateAutoStart_whenDisabledWhileRunning_keepsPromptTrue() = runTest(testDispatcher) {
        val fakeHelper = FakeBatteryOptimizationHelper(isIgnored = false)
        val dummyContext = android.content.ContextWrapper(null)
        val vm = ServerViewModel(
            context = dummyContext,
            batteryOptimizationHelper = fakeHelper,
            defaultDispatcher = testDispatcher,
            ioDispatcher = testDispatcher
        )

        vm.onStartServer()
        advanceTimeBy(650)
        assertEquals(ServerStatus.RUNNING, vm.uiState.value.status)
        assertTrue(vm.uiState.value.showBatteryOptimizationPrompt)

        // Disabling auto-start while server is RUNNING preserves warning prompt
        vm.onUpdateAutoStart(false)
        assertFalse(vm.uiState.value.isAutoStartEnabled)
        assertTrue(vm.uiState.value.showBatteryOptimizationPrompt)

        vm.stopMonitoring()
    }

    @Test
    fun checkBatteryOptimizationStatus_withActivityContext_refreshesState() {
        val fakeHelper = FakeBatteryOptimizationHelper(isIgnored = false)
        val dummyContext = android.content.ContextWrapper(null)
        val activityContext = android.content.ContextWrapper(null)
        val vm = ServerViewModel(
            context = dummyContext,
            batteryOptimizationHelper = fakeHelper,
            defaultDispatcher = testDispatcher,
            ioDispatcher = testDispatcher
        )

        assertFalse(vm.uiState.value.isBatteryOptimizationIgnored)

        // User returned from OS settings, now whitelisted
        fakeHelper.isIgnored = true
        vm.checkBatteryOptimizationStatus(activityContext)

        assertTrue(vm.uiState.value.isBatteryOptimizationIgnored)
        assertFalse(vm.uiState.value.showBatteryOptimizationPrompt)

        vm.stopMonitoring()
    }

    @Test
    fun onDismissBatteryOptimizationPrompt_resetsPromptFlag() {
        val fakeHelper = FakeBatteryOptimizationHelper(isIgnored = false)
        val dummyContext = android.content.ContextWrapper(null)
        val vm = ServerViewModel(
            context = dummyContext,
            batteryOptimizationHelper = fakeHelper,
            defaultDispatcher = testDispatcher,
            ioDispatcher = testDispatcher
        )

        vm.onUpdateAutoStart(true)
        assertTrue(vm.uiState.value.showBatteryOptimizationPrompt)

        vm.onDismissBatteryOptimizationPrompt()
        assertFalse(vm.uiState.value.showBatteryOptimizationPrompt)

        vm.stopMonitoring()
    }

    @Test
    fun onRequestBatteryExemption_invokesHelper_andLogsAction() {
        val fakeHelper = FakeBatteryOptimizationHelper(isIgnored = false, exemptionResult = true)
        val dummyContext = android.content.ContextWrapper(null)
        val vm = ServerViewModel(
            context = dummyContext,
            batteryOptimizationHelper = fakeHelper,
            defaultDispatcher = testDispatcher,
            ioDispatcher = testDispatcher
        )

        val result = vm.onRequestBatteryExemption()
        assertTrue(result)
        assertEquals(1, fakeHelper.requestCalls)
        assertTrue(vm.uiState.value.logs.any { it.message.contains("Requested battery optimization exemption") })

        vm.stopMonitoring()
    }

    @Test
    fun onRequestBatteryExemption_whenHelperFails_logsWarning() {
        val fakeHelper = FakeBatteryOptimizationHelper(isIgnored = false, exemptionResult = false)
        val dummyContext = android.content.ContextWrapper(null)
        val vm = ServerViewModel(
            context = dummyContext,
            batteryOptimizationHelper = fakeHelper,
            defaultDispatcher = testDispatcher,
            ioDispatcher = testDispatcher
        )

        val result = vm.onRequestBatteryExemption()
        assertFalse(result)
        assertEquals(1, fakeHelper.requestCalls)
        assertTrue(vm.uiState.value.logs.any { it.level == LogLevel.WARN && it.message.contains("Unable to open battery optimization settings") })

        vm.stopMonitoring()
    }

    @Test
    fun onStartServer_whenBatteryNotIgnored_triggersPrompt() = runTest(testDispatcher) {
        val fakeHelper = FakeBatteryOptimizationHelper(isIgnored = false)
        val dummyContext = android.content.ContextWrapper(null)
        val vm = ServerViewModel(
            context = dummyContext,
            batteryOptimizationHelper = fakeHelper,
            defaultDispatcher = testDispatcher,
            ioDispatcher = testDispatcher
        )

        assertFalse(vm.uiState.value.showBatteryOptimizationPrompt)

        vm.onStartServer()
        advanceTimeBy(650)

        assertEquals(ServerStatus.RUNNING, vm.uiState.value.status)
        assertTrue(vm.uiState.value.showBatteryOptimizationPrompt)

        vm.stopMonitoring()
    }

    @Test
    fun getDontKillMyAppUrl_returnsUrlFromHelper() {
        val fakeHelper = FakeBatteryOptimizationHelper(isIgnored = true, oemUrl = "https://dontkillmyapp.com/samsung")
        val vm = ServerViewModel(
            batteryOptimizationHelper = fakeHelper,
            defaultDispatcher = testDispatcher,
            ioDispatcher = testDispatcher
        )

        assertEquals("https://dontkillmyapp.com/samsung", vm.getDontKillMyAppUrl())
        vm.stopMonitoring()
    }

    private class FakeBatteryOptimizationHelper(
        var isIgnored: Boolean = false,
        var exemptionResult: Boolean = true,
        var oemUrl: String = "https://dontkillmyapp.com"
    ) : com.hermes.node.service.BatteryOptimizationHelperInterface {
        var requestCalls = 0

        override fun isIgnoringBatteryOptimizations(context: android.content.Context): Boolean = isIgnored
        override fun createRequestExemptionIntent(context: android.content.Context): Intent = Intent()
        override fun createBatteryOptimizationSettingsIntent(): Intent = Intent()
        override fun requestExemption(context: android.content.Context): Boolean {
            requestCalls++
            return exemptionResult
        }
        override fun getDontKillMyAppUrl(manufacturer: String?): String = oemUrl
    }

    private class FakeBootstrapExtractor(
        var healthResult: com.hermes.node.engine.HealthCheckResult = com.hermes.node.engine.HealthCheckResult.NotInstalled,
        var shouldSucceed: Boolean = true,
        var errorMessage: String = "Failed extraction",
        var shouldRepairSucceed: Boolean = true
    ) : com.hermes.node.engine.BootstrapExtractor(filesDir = java.io.File("."), assetManager = null) {
        constructor(installed: Boolean, shouldSucceed: Boolean = true, errorMessage: String = "Failed extraction") : this(
            healthResult = if (installed) com.hermes.node.engine.HealthCheckResult.Healthy else com.hermes.node.engine.HealthCheckResult.NotInstalled,
            shouldSucceed = shouldSucceed,
            errorMessage = errorMessage
        )

        var extractCalls = 0
        var repairCalls = 0

        override fun checkHealth(): com.hermes.node.engine.HealthCheckResult = healthResult

        override fun isBootstrapInstalled(): Boolean = healthResult is com.hermes.node.engine.HealthCheckResult.Healthy

        override suspend fun extract(
            assetName: String,
            onProgress: ((progress: Float, message: String) -> Unit)?
        ): com.hermes.node.engine.ExtractionResult {
            extractCalls++
            onProgress?.invoke(0.5f, "Extracting fake...")
            return if (shouldSucceed) {
                healthResult = com.hermes.node.engine.HealthCheckResult.Healthy
                onProgress?.invoke(1.0f, "Complete")
                com.hermes.node.engine.ExtractionResult.Success(java.io.File("./usr"))
            } else {
                com.hermes.node.engine.ExtractionResult.Error(errorMessage)
            }
        }

        override suspend fun repair(
            assetName: String,
            onProgress: ((progress: Float, message: String) -> Unit)?
        ): com.hermes.node.engine.ExtractionResult {
            repairCalls++
            onProgress?.invoke(0.1f, "Cleaning fake...")
            onProgress?.invoke(0.5f, "Repairing fake...")
            return if (shouldRepairSucceed) {
                healthResult = com.hermes.node.engine.HealthCheckResult.Healthy
                onProgress?.invoke(1.0f, "Repair Complete")
                com.hermes.node.engine.ExtractionResult.Success(java.io.File("./usr"))
            } else {
                com.hermes.node.engine.ExtractionResult.Error(errorMessage)
            }
        }
    }

    @Test
    fun logStreamer_integrationWithViewModel() = runTest(testDispatcher) {
        val fakeLogStreamer = FakeLogStreamer()
        val vm = ServerViewModel(
            logStreamer = fakeLogStreamer,
            defaultDispatcher = testDispatcher,
            ioDispatcher = testDispatcher
        )

        testScheduler.advanceUntilIdle()

        // Initial welcome log added to fakeLogStreamer
        assertEquals(1, fakeLogStreamer.appendCalls)
        assertTrue(fakeLogStreamer.getLogs().any { it.message.contains("Hermes Node initialized") })
        assertTrue(vm.uiState.value.logs.any { it.message.contains("Hermes Node initialized") })

        // onAddLog calls streamer
        vm.onAddLog("Custom engine log", LogLevel.WARN)
        testScheduler.advanceUntilIdle()

        assertEquals(2, fakeLogStreamer.appendCalls)
        assertTrue(vm.uiState.value.logs.any { it.level == LogLevel.WARN && it.message == "Custom engine log" })

        // External log pushed by engine/streamer
        fakeLogStreamer.append("External log line", LogLevel.ERROR)
        testScheduler.advanceUntilIdle()

        assertTrue(vm.uiState.value.logs.any { it.level == LogLevel.ERROR && it.message == "External log line" })

        // onClearLogs clears streamer and flow
        vm.onClearLogs()
        testScheduler.advanceUntilIdle()

        assertEquals(1, fakeLogStreamer.clearCalls)
        assertTrue(vm.uiState.value.logs.isEmpty())
        assertTrue(fakeLogStreamer.getLogs().isEmpty())

        vm.stopMonitoring()
    }

    private class FakeLogStreamer : com.hermes.node.engine.LogStreamerInterface {
        private val _logsFlow = kotlinx.coroutines.flow.MutableStateFlow<List<LogEntry>>(emptyList())
        override val logsFlow: kotlinx.coroutines.flow.StateFlow<List<LogEntry>> = _logsFlow
        override val capacity: Int = 2000
        override var isStreaming: Boolean = false

        var appendCalls = 0
        var clearCalls = 0

        override fun start(stdout: java.io.InputStream?, stderr: java.io.InputStream?) {
            isStreaming = true
        }

        override fun stop() {
            isStreaming = false
        }

        override fun clear() {
            clearCalls++
            _logsFlow.value = emptyList()
        }

        override fun append(entry: LogEntry) {
            appendCalls++
            _logsFlow.value = _logsFlow.value + entry
        }

        override fun append(message: String, level: LogLevel) {
            append(LogEntry(message = message, level = level))
        }

        override fun getLogs(): List<LogEntry> = _logsFlow.value
    }

    @Test
    fun telemetry_integrationWithViewModel_pollsAndUpdateState() = runTest(testDispatcher) {
        val fakeCollector = FakeTelemetryCollector(
            currentReading = DeviceTelemetry(
                cpuPercent = 5.0f,
                usedMemoryMb = 1200L,
                totalMemoryMb = 4096L,
                batteryPercent = 90,
                isCharging = false,
                batteryTemperatureCelsius = 31.0f
            )
        )

        val vm = ServerViewModel(
            telemetryCollector = fakeCollector,
            defaultDispatcher = testDispatcher,
            ioDispatcher = testDispatcher
        )

        testScheduler.advanceTimeBy(10)

        // Initial sample
        val state1 = vm.uiState.value
        assertEquals(5.0f, state1.cpuUsagePercent, 0.001f)
        assertEquals(1200L, state1.memoryUsageMb)
        assertEquals(4096L, state1.totalMemoryMb)
        assertEquals(90, state1.batteryPercent)
        assertFalse(state1.isCharging)
        assertEquals(31.0f, state1.batteryTemperatureCelsius, 0.001f)

        // Advance 2 seconds with updated hardware vitals
        fakeCollector.currentReading = DeviceTelemetry(
            cpuPercent = 82.5f,
            usedMemoryMb = 2100L,
            totalMemoryMb = 4096L,
            batteryPercent = 89,
            isCharging = true,
            batteryTemperatureCelsius = 39.5f
        )
        testScheduler.advanceTimeBy(2000)

        val state2 = vm.uiState.value
        assertEquals(82.5f, state2.cpuUsagePercent, 0.001f)
        assertEquals(2100L, state2.memoryUsageMb)
        assertEquals(4096L, state2.totalMemoryMb)
        assertEquals(89, state2.batteryPercent)
        assertTrue(state2.isCharging)
        assertEquals(39.5f, state2.batteryTemperatureCelsius, 0.001f)

        vm.stopMonitoring()
    }

    @Test
    fun telemetry_continuesPollingWhenServerStopped_whileUptimeRemainsZero() = runTest(testDispatcher) {
        val fakeCollector = FakeTelemetryCollector(
            currentReading = DeviceTelemetry(
                cpuPercent = 3.0f,
                usedMemoryMb = 1000L,
                totalMemoryMb = 4096L,
                batteryPercent = 100,
                isCharging = true,
                batteryTemperatureCelsius = 28.0f
            )
        )

        val vm = ServerViewModel(
            telemetryCollector = fakeCollector,
            defaultDispatcher = testDispatcher,
            ioDispatcher = testDispatcher
        )

        testScheduler.advanceTimeBy(10)
        assertEquals(ServerStatus.STOPPED, vm.uiState.value.status)
        assertEquals(0L, vm.uiState.value.uptimeSeconds)
        assertEquals(3.0f, vm.uiState.value.cpuUsagePercent, 0.001f)

        // Hardware reading changes while server is STOPPED
        fakeCollector.currentReading = DeviceTelemetry(
            cpuPercent = 12.0f,
            usedMemoryMb = 1100L,
            totalMemoryMb = 4096L,
            batteryPercent = 99,
            isCharging = false,
            batteryTemperatureCelsius = 29.5f
        )
        testScheduler.advanceTimeBy(2000)

        val stoppedState = vm.uiState.value
        assertEquals(ServerStatus.STOPPED, stoppedState.status)
        assertEquals(0L, stoppedState.uptimeSeconds) // Uptime does NOT increment when STOPPED
        assertEquals(12.0f, stoppedState.cpuUsagePercent, 0.001f)
        assertEquals(1100L, stoppedState.memoryUsageMb)
        assertEquals(99, stoppedState.batteryPercent)
        assertFalse(stoppedState.isCharging)
        assertEquals(29.5f, stoppedState.batteryTemperatureCelsius, 0.001f)

        vm.stopMonitoring()
    }

    @Test
    fun telemetry_lifecyclePauseAndResume_controlsPollingLoop() = runTest(testDispatcher) {
        val fakeCollector = FakeTelemetryCollector(
            currentReading = DeviceTelemetry(cpuPercent = 10.0f)
        )

        val vm = ServerViewModel(
            telemetryCollector = fakeCollector,
            defaultDispatcher = testDispatcher,
            ioDispatcher = testDispatcher
        )

        testScheduler.advanceTimeBy(10)
        assertEquals(10.0f, vm.uiState.value.cpuUsagePercent, 0.001f)

        // Pause telemetry (app backgrounded)
        vm.pauseTelemetry()

        fakeCollector.currentReading = DeviceTelemetry(cpuPercent = 55.0f)
        testScheduler.advanceTimeBy(4000)

        // While paused, UI state is not updated
        assertEquals(10.0f, vm.uiState.value.cpuUsagePercent, 0.001f)

        // Resume telemetry (app foregrounded)
        vm.resumeTelemetry()
        testScheduler.advanceTimeBy(2000)

        assertEquals(55.0f, vm.uiState.value.cpuUsagePercent, 0.001f)

        vm.stopMonitoring()
    }

    @Test
    fun telemetry_handlesCollectorErrorsGracefully_retainingPreviousValidReading() = runTest(testDispatcher) {
        val fakeCollector = FakeTelemetryCollector(
            currentReading = DeviceTelemetry(
                cpuPercent = 22.0f,
                usedMemoryMb = 1400L,
                totalMemoryMb = 4096L,
                batteryPercent = 75,
                isCharging = false,
                batteryTemperatureCelsius = 35.0f
            )
        )

        val vm = ServerViewModel(
            telemetryCollector = fakeCollector,
            defaultDispatcher = testDispatcher,
            ioDispatcher = testDispatcher
        )

        testScheduler.advanceTimeBy(10)
        assertEquals(22.0f, vm.uiState.value.cpuUsagePercent, 0.001f)

        // Simulate sensor failure
        fakeCollector.shouldFail = true
        testScheduler.advanceTimeBy(2000)

        // Retains previous valid reading without crashing
        val state = vm.uiState.value
        assertEquals(22.0f, state.cpuUsagePercent, 0.001f)
        assertEquals(1400L, state.memoryUsageMb)
        assertEquals(75, state.batteryPercent)
        assertEquals(35.0f, state.batteryTemperatureCelsius, 0.001f)

        vm.stopMonitoring()
    }

    @Test
    fun onUpdateGatewaySettings_updatesAllGatewayStateFields() {
        viewModel.onUpdateTelegramEnabled(true)
        assertTrue(viewModel.uiState.value.isTelegramEnabled)

        viewModel.onUpdateTelegramToken("12345:TG")
        assertEquals("12345:TG", viewModel.uiState.value.telegramToken)

        viewModel.onUpdateTelegramAdminUserIds("111,222")
        assertEquals("111,222", viewModel.uiState.value.telegramAdminUserIds)

        viewModel.onUpdateDiscordEnabled(true)
        assertTrue(viewModel.uiState.value.isDiscordEnabled)

        viewModel.onUpdateDiscordToken("OTg3.DISCORD")
        assertEquals("OTg3.DISCORD", viewModel.uiState.value.discordToken)

        viewModel.onUpdateDiscordChannelIds("ch-1,ch-2")
        assertEquals("ch-1,ch-2", viewModel.uiState.value.discordChannelIds)

        viewModel.onUpdateSlackEnabled(true)
        assertTrue(viewModel.uiState.value.isSlackEnabled)

        viewModel.onUpdateSlackAppToken("xapp-123")
        assertEquals("xapp-123", viewModel.uiState.value.slackAppToken)

        viewModel.onUpdateSlackBotToken("xoxb-456")
        assertEquals("xoxb-456", viewModel.uiState.value.slackBotToken)

        viewModel.onUpdateWhatsAppEnabled(true)
        assertTrue(viewModel.uiState.value.isWhatsAppEnabled)

        viewModel.onUpdateWhatsAppSessionLink("https://wa.me/test")
        assertEquals("https://wa.me/test", viewModel.uiState.value.whatsAppSessionLink)

        viewModel.onUpdateWhatsAppWebhookToken("wh-tok-99")
        assertEquals("wh-tok-99", viewModel.uiState.value.whatsAppWebhookToken)

        viewModel.onUpdateRestApiEnabled(false)
        assertFalse(viewModel.uiState.value.isRestApiEnabled)

        viewModel.onUpdateRestApiPort("9090")
        assertEquals("9090", viewModel.uiState.value.restApiPort)
    }

    @Test
    fun onSaveSettings_withAllGateways_persistsEncryptedAndTrimsInputs() = runTest(testDispatcher) {
        val fakePrefs = FakeSharedPreferences()
        val repository = EncryptedConfigRepository(fakePrefs)
        val tempDir = File(System.getProperty("java.io.tmpdir") ?: "/tmp", "hermes_gateways_test_${System.currentTimeMillis()}")
        tempDir.mkdirs()
        val configFile = File(tempDir, "hermes.json")
        val serializer = ConfigSerializer(configFile)

        val vm = ServerViewModel(
            configRepository = repository,
            configSerializer = serializer,
            defaultDispatcher = testDispatcher,
            ioDispatcher = testDispatcher
        )

        // Set inputs with leading/trailing whitespace
        vm.onUpdateTelegramEnabled(true)
        vm.onUpdateTelegramToken("  12345:TG-TOKEN  ")
        vm.onUpdateTelegramAdminUserIds("  111,222  ")

        vm.onUpdateDiscordEnabled(true)
        vm.onUpdateDiscordToken("  OTg3.DISCORD_TOKEN  ")
        vm.onUpdateDiscordChannelIds("  ch-1,ch-2  ")

        vm.onUpdateSlackEnabled(true)
        vm.onUpdateSlackAppToken("  xapp-test-app  ")
        vm.onUpdateSlackBotToken("  xoxb-test-bot  ")

        vm.onUpdateWhatsAppEnabled(true)
        vm.onUpdateWhatsAppSessionLink("  https://wa.me/test  ")
        vm.onUpdateWhatsAppWebhookToken("  wh-secret-tok  ")

        vm.onUpdateRestApiEnabled(true)
        vm.onUpdateRestApiPort("  9000  ")

        vm.onSaveSettings()
        testScheduler.advanceUntilIdle()

        val state = vm.uiState.value
        assertTrue(state.isSettingsSaved)

        // Verify trimming in UI state
        assertEquals("12345:TG-TOKEN", state.telegramToken)
        assertEquals("111,222", state.telegramAdminUserIds)
        assertEquals("OTg3.DISCORD_TOKEN", state.discordToken)
        assertEquals("ch-1,ch-2", state.discordChannelIds)
        assertEquals("xapp-test-app", state.slackAppToken)
        assertEquals("xoxb-test-bot", state.slackBotToken)
        assertEquals("https://wa.me/test", state.whatsAppSessionLink)
        assertEquals("wh-secret-tok", state.whatsAppWebhookToken)
        assertEquals("9000", state.restApiPort)

        // Verify repository persistence
        val savedConfig = repository.getConfig()
        assertTrue(savedConfig.gateway.telegram.enabled)
        assertEquals("12345:TG-TOKEN", savedConfig.gateway.telegram.botToken)
        assertEquals("111,222", savedConfig.gateway.telegram.adminUserIds)

        assertTrue(savedConfig.gateway.discord.enabled)
        assertEquals("OTg3.DISCORD_TOKEN", savedConfig.gateway.discord.botToken)
        assertEquals("ch-1,ch-2", savedConfig.gateway.discord.channelIds)

        assertTrue(savedConfig.gateway.slack.enabled)
        assertEquals("xapp-test-app", savedConfig.gateway.slack.appToken)
        assertEquals("xoxb-test-bot", savedConfig.gateway.slack.botToken)

        assertTrue(savedConfig.gateway.whatsapp.enabled)
        assertEquals("https://wa.me/test", savedConfig.gateway.whatsapp.sessionLink)
        assertEquals("wh-secret-tok", savedConfig.gateway.whatsapp.webhookToken)

        assertTrue(savedConfig.gateway.restApi.enabled)
        assertEquals(9000, savedConfig.gateway.restApi.port)

        // Verify hermes.json content
        val json = org.json.JSONObject(configFile.readText(Charsets.UTF_8))
        val gateways = json.getJSONObject("gateways")
        assertEquals("12345:TG-TOKEN", gateways.getJSONObject("telegram").getString("bot_token"))
        assertEquals("OTg3.DISCORD_TOKEN", gateways.getJSONObject("discord").getString("bot_token"))
        assertEquals("xapp-test-app", gateways.getJSONObject("slack").getString("app_token"))
        assertEquals("https://wa.me/test", gateways.getJSONObject("whatsapp").getString("session_link"))
        assertEquals(9000, gateways.getJSONObject("rest_api").getInt("port"))

        tempDir.deleteRecursively()
        vm.stopMonitoring()
    }

    @Test
    fun onSaveSettings_withInvalidPort_fallsBackTo8000Gracefully() = runTest(testDispatcher) {
        val fakePrefs = FakeSharedPreferences()
        val repository = EncryptedConfigRepository(fakePrefs)
        val tempDir = File(System.getProperty("java.io.tmpdir") ?: "/tmp", "hermes_port_test_${System.currentTimeMillis()}")
        tempDir.mkdirs()
        val configFile = File(tempDir, "hermes.json")
        val serializer = ConfigSerializer(configFile)

        val vm = ServerViewModel(
            configRepository = repository,
            configSerializer = serializer,
            defaultDispatcher = testDispatcher,
            ioDispatcher = testDispatcher
        )

        vm.onUpdateRestApiPort("invalid_port_number")
        vm.onSaveSettings()
        testScheduler.advanceUntilIdle()

        val savedConfig = repository.getConfig()
        assertEquals(8000, savedConfig.gateway.restApi.port)
        assertEquals("8000", vm.uiState.value.restApiPort)

        tempDir.deleteRecursively()
        vm.stopMonitoring()
    }

    @Test
    fun loadPersistedConfig_withAllGateways_populatesUiStateCorrectly() = runTest(testDispatcher) {
        val fakePrefs = FakeSharedPreferences()
        val repository = EncryptedConfigRepository(fakePrefs)
        repository.saveConfig(
            HermesConfig(
                gateway = GatewayConfig(
                    telegram = com.hermes.node.data.model.TelegramGatewayConfig(
                        enabled = true,
                        botToken = "tg-token-saved",
                        adminUserIds = "100,200"
                    ),
                    discord = com.hermes.node.data.model.DiscordGatewayConfig(
                        enabled = true,
                        botToken = "disc-token-saved",
                        channelIds = "ch-1"
                    ),
                    slack = com.hermes.node.data.model.SlackGatewayConfig(
                        enabled = true,
                        appToken = "slack-app-saved",
                        botToken = "slack-bot-saved"
                    ),
                    whatsapp = com.hermes.node.data.model.WhatsAppGatewayConfig(
                        enabled = true,
                        sessionLink = "wa-link-saved",
                        webhookToken = "wa-tok-saved"
                    ),
                    restApi = com.hermes.node.data.model.RestApiGatewayConfig(
                        enabled = true,
                        port = 8888
                    )
                )
            )
        )

        val vm = ServerViewModel(
            configRepository = repository,
            defaultDispatcher = testDispatcher,
            ioDispatcher = testDispatcher
        )

        val state = vm.uiState.value
        assertTrue(state.isTelegramEnabled)
        assertEquals("tg-token-saved", state.telegramToken)
        assertEquals("100,200", state.telegramAdminUserIds)

        assertTrue(state.isDiscordEnabled)
        assertEquals("disc-token-saved", state.discordToken)
        assertEquals("ch-1", state.discordChannelIds)

        assertTrue(state.isSlackEnabled)
        assertEquals("slack-app-saved", state.slackAppToken)
        assertEquals("slack-bot-saved", state.slackBotToken)

        assertTrue(state.isWhatsAppEnabled)
        assertEquals("wa-link-saved", state.whatsAppSessionLink)
        assertEquals("wa-tok-saved", state.whatsAppWebhookToken)

        assertTrue(state.isRestApiEnabled)
        assertEquals("8888", state.restApiPort)

        vm.stopMonitoring()
    }

    @Test
    fun onStartServer_withTunnelEnabled_startsTunnelManager_andUpdatesUiState() = runTest(testDispatcher) {
        val fakeTunnelManager = FakeTunnelManager()
        val vm = ServerViewModel(
            tunnelManager = fakeTunnelManager,
            defaultDispatcher = testDispatcher,
            ioDispatcher = testDispatcher
        )

        vm.onUpdatePublicTunnel(true)
        vm.onStartServer()
        advanceTimeBy(700)

        assertEquals(1, fakeTunnelManager.startCalls)
        assertEquals("https://fake-tunnel.trycloudflare.com", vm.uiState.value.tunnelUrl)
        assertTrue(vm.uiState.value.tunnelState is TunnelState.Running)
        assertTrue(vm.uiState.value.logs.any { it.message.contains("Cloudflare Tunnel connected") })

        vm.stopMonitoring()
    }

    @Test
    fun onStartServer_withTunnelDisabled_doesNotStartTunnelManager() = runTest(testDispatcher) {
        val fakeTunnelManager = FakeTunnelManager()
        val vm = ServerViewModel(
            tunnelManager = fakeTunnelManager,
            defaultDispatcher = testDispatcher,
            ioDispatcher = testDispatcher
        )

        vm.onUpdatePublicTunnel(false)
        vm.onStartServer()
        advanceTimeBy(700)

        assertEquals(0, fakeTunnelManager.startCalls)
        assertNull(vm.uiState.value.tunnelUrl)
        assertEquals(TunnelState.Stopped, vm.uiState.value.tunnelState)

        vm.stopMonitoring()
    }

    @Test
    fun onUpdatePublicTunnel_whileServerRunning_dynamicallyStartsAndStopsTunnel() = runTest(testDispatcher) {
        val fakeTunnelManager = FakeTunnelManager()
        val vm = ServerViewModel(
            tunnelManager = fakeTunnelManager,
            defaultDispatcher = testDispatcher,
            ioDispatcher = testDispatcher
        )

        vm.onUpdatePublicTunnel(false)
        vm.onStartServer()
        advanceTimeBy(700)
        assertEquals(0, fakeTunnelManager.startCalls)

        // Dynamically enable
        vm.onUpdatePublicTunnel(true)
        advanceTimeBy(100)
        assertEquals(1, fakeTunnelManager.startCalls)
        assertEquals("https://fake-tunnel.trycloudflare.com", vm.uiState.value.tunnelUrl)

        // Dynamically disable
        vm.onUpdatePublicTunnel(false)
        advanceTimeBy(100)
        assertEquals(1, fakeTunnelManager.stopCalls)
        assertNull(vm.uiState.value.tunnelUrl)
        assertEquals(TunnelState.Stopped, vm.uiState.value.tunnelState)

        vm.stopMonitoring()
    }

    @Test
    fun onStopServer_stopsActiveTunnel() = runTest(testDispatcher) {
        val fakeTunnelManager = FakeTunnelManager()
        val vm = ServerViewModel(
            tunnelManager = fakeTunnelManager,
            defaultDispatcher = testDispatcher,
            ioDispatcher = testDispatcher
        )

        vm.onUpdatePublicTunnel(true)
        vm.onStartServer()
        advanceTimeBy(700)
        assertEquals(1, fakeTunnelManager.startCalls)

        vm.onStopServer()
        advanceTimeBy(500)

        assertTrue(fakeTunnelManager.stopCalls >= 1)
        assertNull(vm.uiState.value.tunnelUrl)
        assertEquals(TunnelState.Stopped, vm.uiState.value.tunnelState)

        vm.stopMonitoring()
    }

    @Test
    fun onShowAndDismissQrCodeDialog_updatesUiState() = runTest(testDispatcher) {
        val fakeTunnel = FakeTunnelManager()
        val vm = ServerViewModel(
            tunnelManager = fakeTunnel,
            defaultDispatcher = testDispatcher,
            ioDispatcher = testDispatcher
        )
        // Without active tunnel, dialog should not open (guard)
        assertFalse(vm.uiState.value.showQrCodeDialog)
        vm.onShowQrCodeDialog()
        assertFalse(vm.uiState.value.showQrCodeDialog)

        // With active tunnel, dialog should open
        vm.onUpdatePublicTunnel(true)
        vm.onStartServer()
        advanceTimeBy(700)
        assertTrue(vm.uiState.value.tunnelState is TunnelState.Running)
        vm.onShowQrCodeDialog()
        assertTrue(vm.uiState.value.showQrCodeDialog)

        vm.onDismissQrCodeDialog()
        assertFalse(vm.uiState.value.showQrCodeDialog)

        vm.stopMonitoring()
    }

    @Test
    fun onShowQrCodeDialog_guardDoesNotOpenWhenNoUrl() = runTest(testDispatcher) {
        val vm = ServerViewModel(
            defaultDispatcher = testDispatcher,
            ioDispatcher = testDispatcher
        )
        vm.onShowQrCodeDialog()
        assertFalse(vm.uiState.value.showQrCodeDialog)
        vm.stopMonitoring()
    }

    @Test
    fun tunnelStartFailure_setsErrorStateAndLogsWarning() = runTest(testDispatcher) {
        val fakeTunnel = FakeTunnelManager(startResult = Result.failure(IllegalStateException("binary missing")))
        val vm = ServerViewModel(
            tunnelManager = fakeTunnel,
            defaultDispatcher = testDispatcher,
            ioDispatcher = testDispatcher
        )
        vm.onUpdatePublicTunnel(true)
        vm.onStartServer()
        advanceTimeBy(700)
        assertTrue(vm.uiState.value.tunnelState is TunnelState.Error)
        assertNull(vm.uiState.value.tunnelUrl)
        assertEquals(ServerStatus.RUNNING, vm.uiState.value.status) // server still running
        assertTrue(vm.uiState.value.logs.any { it.message.contains("Cloudflare Tunnel error") })
        vm.stopMonitoring()
    }

    @Test
    fun tunnelError_clearsQrDialogFlag() = runTest(testDispatcher) {
        val fakeTunnel = FakeTunnelManager()
        val vm = ServerViewModel(
            tunnelManager = fakeTunnel,
            defaultDispatcher = testDispatcher,
            ioDispatcher = testDispatcher
        )
        vm.onUpdatePublicTunnel(true)
        vm.onStartServer()
        advanceTimeBy(700)
        // Force Running state with URL so dialog can be shown
        fakeTunnel.emitRunning("https://fake-tunnel.trycloudflare.com")
        runCurrent()
        vm.onShowQrCodeDialog()
        assertTrue(vm.uiState.value.showQrCodeDialog)
        // Simulate tunnel error via state flow — observer should clear dialog and URL
        fakeTunnel.emitError("network")
        runCurrent()
        assertFalse(vm.uiState.value.showQrCodeDialog)
        assertNull(vm.uiState.value.tunnelUrl)
        assertTrue(vm.uiState.value.tunnelState is TunnelState.Error)
        vm.stopMonitoring()
    }

    @Test
    fun onToggleSkill_disablingSkill_updatesUiStateAndPersistsToDiskAndRepo() = runTest(testDispatcher) {
        val fakePrefs = FakeSharedPreferences()
        val repository = EncryptedConfigRepository(fakePrefs)
        val tempDir = File(System.getProperty("java.io.tmpdir") ?: "/tmp", "hermes_skills_test_${System.currentTimeMillis()}")
        tempDir.mkdirs()
        val configFile = File(tempDir, "hermes.json")
        val serializer = ConfigSerializer(configFile)

        val vm = ServerViewModel(
            configRepository = repository,
            configSerializer = serializer,
            defaultDispatcher = testDispatcher,
            ioDispatcher = testDispatcher
        )

        // Initial default: all enabled
        assertTrue(vm.uiState.value.skillsConfig.bashRunner)
        assertTrue(vm.uiState.value.installedSkills.first { it.id == SkillsConfig.SKILL_BASH_RUNNER }.enabled)

        // Toggle bash_runner OFF
        vm.onToggleSkill(SkillsConfig.SKILL_BASH_RUNNER, false)

        // UI state immediately updated
        assertFalse(vm.uiState.value.skillsConfig.bashRunner)
        assertFalse(vm.uiState.value.installedSkills.first { it.id == SkillsConfig.SKILL_BASH_RUNNER }.enabled)

        testScheduler.advanceUntilIdle()

        // Persisted to EncryptedSharedPreferences repository
        assertFalse(repository.isSkillEnabled(SkillsConfig.SKILL_BASH_RUNNER))
        assertFalse(repository.getSkillsConfig().bashRunner)

        // Persisted to hermes.json with 0600 permissions
        assertTrue(configFile.exists())
        val json = org.json.JSONObject(configFile.readText(Charsets.UTF_8))
        assertTrue(json.has("skills"))
        assertFalse(json.getJSONObject("skills").getBoolean("bash_runner"))
        assertTrue(json.getJSONObject("skills").getBoolean("web_search"))
        assertTrue(vm.uiState.value.logs.any { it.message.contains("Skill 'bash_runner' disabled") })

        tempDir.deleteRecursively()
        vm.stopMonitoring()
    }

    @Test
    fun onToggleSkill_enablingSkill_updatesUiStateAndPersists() = runTest(testDispatcher) {
        val fakePrefs = FakeSharedPreferences()
        val repository = EncryptedConfigRepository(fakePrefs)
        repository.saveSkillEnabled(SkillsConfig.SKILL_WEB_SEARCH, false)

        val tempDir = File(System.getProperty("java.io.tmpdir") ?: "/tmp", "hermes_skills_enable_test_${System.currentTimeMillis()}")
        tempDir.mkdirs()
        val configFile = File(tempDir, "hermes.json")
        val serializer = ConfigSerializer(configFile)

        val vm = ServerViewModel(
            configRepository = repository,
            configSerializer = serializer,
            defaultDispatcher = testDispatcher,
            ioDispatcher = testDispatcher
        )

        // Verify loaded as false
        assertFalse(vm.uiState.value.skillsConfig.webSearch)

        // Toggle web_search ON
        vm.onToggleSkill(SkillsConfig.SKILL_WEB_SEARCH, true)

        assertTrue(vm.uiState.value.skillsConfig.webSearch)
        assertTrue(vm.uiState.value.installedSkills.first { it.id == SkillsConfig.SKILL_WEB_SEARCH }.enabled)

        testScheduler.advanceUntilIdle()

        assertTrue(repository.isSkillEnabled(SkillsConfig.SKILL_WEB_SEARCH))
        val json = org.json.JSONObject(configFile.readText(Charsets.UTF_8))
        assertTrue(json.getJSONObject("skills").getBoolean("web_search"))

        tempDir.deleteRecursively()
        vm.stopMonitoring()
    }

    @Test
    fun onToggleSkill_duringRunningServer_daemonRemainsRunning() = runTest(testDispatcher) {
        val fakePrefs = FakeSharedPreferences()
        val repository = EncryptedConfigRepository(fakePrefs)
        val tempDir = File(System.getProperty("java.io.tmpdir") ?: "/tmp", "hermes_skills_running_${System.currentTimeMillis()}")
        tempDir.mkdirs()
        val configFile = File(tempDir, "hermes.json")
        val serializer = ConfigSerializer(configFile)

        val vm = ServerViewModel(
            configRepository = repository,
            configSerializer = serializer,
            defaultDispatcher = testDispatcher,
            ioDispatcher = testDispatcher
        )

        vm.onStartServer()
        advanceTimeBy(650)
        assertEquals(ServerStatus.RUNNING, vm.uiState.value.status)

        // Toggle skill while running
        vm.onToggleSkill(SkillsConfig.SKILL_CRON_SCHEDULER, false)
        runCurrent()
        advanceTimeBy(100)

        // Server remains RUNNING uninterrupted
        assertEquals(ServerStatus.RUNNING, vm.uiState.value.status)
        assertFalse(vm.uiState.value.skillsConfig.cronScheduler)

        val json = org.json.JSONObject(configFile.readText(Charsets.UTF_8))
        assertFalse(json.getJSONObject("skills").getBoolean("cron_scheduler"))

        tempDir.deleteRecursively()
        vm.stopMonitoring()
    }

    @Test
    fun onToggleSkill_whenSerializationFails_revertsUiStateAndSurfacesError() = runTest(testDispatcher) {
        val fakePrefs = FakeSharedPreferences()
        val repository = EncryptedConfigRepository(fakePrefs)
        val invalidFile = File(System.getProperty("java.io.tmpdir") ?: "/tmp")
        val brokenSerializer = object : ConfigSerializer(invalidFile) {
            override fun serialize(config: HermesConfig): Result<File> {
                return Result.failure(java.io.IOException("Disk full during skill update"))
            }
        }

        val vm = ServerViewModel(
            configRepository = repository,
            configSerializer = brokenSerializer,
            defaultDispatcher = testDispatcher,
            ioDispatcher = testDispatcher
        )

        // Initial state is true
        assertTrue(vm.uiState.value.skillsConfig.fileManager)

        // Toggle file_manager to false
        vm.onToggleSkill(SkillsConfig.SKILL_FILE_MANAGER, false)

        testScheduler.advanceUntilIdle()

        // State reverted back to true
        assertTrue(vm.uiState.value.skillsConfig.fileManager)
        assertTrue(vm.uiState.value.installedSkills.first { it.id == SkillsConfig.SKILL_FILE_MANAGER }.enabled)
        assertTrue(vm.uiState.value.configSaveMessage?.contains("Disk full during skill update") == true)
        assertTrue(vm.uiState.value.logs.any { it.level == LogLevel.ERROR && it.message.contains("Failed to serialize skill configuration") })

        vm.stopMonitoring()
    }

    @Test
    fun onToggleSkill_customSkill_updatesCustomSkillsMapAndPersists() = runTest(testDispatcher) {
        val fakePrefs = FakeSharedPreferences()
        val repository = EncryptedConfigRepository(fakePrefs)
        val tempDir = File(System.getProperty("java.io.tmpdir") ?: "/tmp", "hermes_custom_skill_${System.currentTimeMillis()}")
        tempDir.mkdirs()
        val configFile = File(tempDir, "hermes.json")
        val serializer = ConfigSerializer(configFile)

        val vm = ServerViewModel(
            configRepository = repository,
            configSerializer = serializer,
            defaultDispatcher = testDispatcher,
            ioDispatcher = testDispatcher
        )

        vm.onToggleSkill("pdf_converter", true)
        testScheduler.advanceUntilIdle()

        assertTrue(vm.uiState.value.skillsConfig.customSkills["pdf_converter"] == true)
        assertTrue(vm.uiState.value.installedSkills.any { it.id == "pdf_converter" && it.enabled })
        assertTrue(repository.isSkillEnabled("pdf_converter"))

        val json = org.json.JSONObject(configFile.readText(Charsets.UTF_8))
        assertTrue(json.getJSONObject("skills").getBoolean("pdf_converter"))

        tempDir.deleteRecursively()
        vm.stopMonitoring()
    }

    @Test
    fun loadPersistedConfig_populatesSkillsInUiState() = runTest(testDispatcher) {
        val fakePrefs = FakeSharedPreferences()
        val repository = EncryptedConfigRepository(fakePrefs)
        repository.saveSkillsConfig(
            SkillsConfig(
                webSearch = true,
                fileManager = false,
                bashRunner = false,
                cronScheduler = true
            )
        )

        val vm = ServerViewModel(
            configRepository = repository,
            defaultDispatcher = testDispatcher,
            ioDispatcher = testDispatcher
        )

        val state = vm.uiState.value
        assertTrue(state.skillsConfig.webSearch)
        assertFalse(state.skillsConfig.fileManager)
        assertFalse(state.skillsConfig.bashRunner)
        assertTrue(state.skillsConfig.cronScheduler)

        val bashSkill = state.installedSkills.first { it.id == SkillsConfig.SKILL_BASH_RUNNER }
        assertFalse(bashSkill.enabled)

        vm.stopMonitoring()
    }

    @Test
    fun onToggleSkill_multipleTogglesInSequence_persistsAllCorrectly() = runTest(testDispatcher) {
        val fakePrefs = FakeSharedPreferences()
        val repository = EncryptedConfigRepository(fakePrefs)
        val tempDir = File(System.getProperty("java.io.tmpdir") ?: "/tmp", "hermes_skills_seq_${System.currentTimeMillis()}")
        tempDir.mkdirs()
        val configFile = File(tempDir, "hermes.json")
        val serializer = ConfigSerializer(configFile)

        val vm = ServerViewModel(
            configRepository = repository,
            configSerializer = serializer,
            defaultDispatcher = testDispatcher,
            ioDispatcher = testDispatcher
        )

        // Toggle multiple skills in rapid succession
        vm.onToggleSkill(SkillsConfig.SKILL_WEB_SEARCH, false)
        vm.onToggleSkill(SkillsConfig.SKILL_BASH_RUNNER, false)
        vm.onToggleSkill(SkillsConfig.SKILL_CRON_SCHEDULER, false)

        testScheduler.advanceUntilIdle()

        assertFalse(vm.uiState.value.skillsConfig.webSearch)
        assertFalse(vm.uiState.value.skillsConfig.bashRunner)
        assertFalse(vm.uiState.value.skillsConfig.cronScheduler)
        assertTrue(vm.uiState.value.skillsConfig.fileManager)

        val retrievedSkills = repository.getSkillsConfig()
        assertFalse(retrievedSkills.webSearch)
        assertFalse(retrievedSkills.bashRunner)
        assertFalse(retrievedSkills.cronScheduler)
        assertTrue(retrievedSkills.fileManager)

        val json = org.json.JSONObject(configFile.readText(Charsets.UTF_8))
        val skillsJson = json.getJSONObject("skills")
        assertFalse(skillsJson.getBoolean("web_search"))
        assertFalse(skillsJson.getBoolean("bash_runner"))
        assertFalse(skillsJson.getBoolean("cron_scheduler"))
        assertTrue(skillsJson.getBoolean("file_manager"))

        tempDir.deleteRecursively()
        vm.stopMonitoring()
    }

    private class FakeTunnelManager(
        var startResult: Result<String> = Result.success("https://fake-tunnel.trycloudflare.com"),
        var stopResult: Result<Unit> = Result.success(Unit)
    ) : TunnelManagerInterface {
        private val _state = MutableStateFlow<TunnelState>(TunnelState.Stopped)
        override val state: StateFlow<TunnelState> = _state.asStateFlow()

        private val _tunnelUrl = MutableStateFlow<String?>(null)
        override val tunnelUrl: StateFlow<String?> = _tunnelUrl.asStateFlow()

        override val isRunning: Boolean
            get() = _state.value is TunnelState.Running

        var startCalls = 0
        var stopCalls = 0
        var lastPort: Int? = null

        override suspend fun start(port: Int): Result<String> {
            startCalls++
            lastPort = port
            if (startResult.isSuccess) {
                val url = startResult.getOrThrow()
                _tunnelUrl.value = url
                _state.value = TunnelState.Running(url)
            } else {
                _tunnelUrl.value = null
                _state.value = TunnelState.Error(startResult.exceptionOrNull()?.message ?: "Failed to start tunnel")
            }
            return startResult
        }

        override suspend fun stop(timeoutMs: Long): Result<Unit> {
            stopCalls++
            _tunnelUrl.value = null
            _state.value = TunnelState.Stopped
            return stopResult
        }

        fun emitRunning(url: String) {
            _tunnelUrl.value = url
            _state.value = TunnelState.Running(url)
        }

        fun emitError(message: String) {
            _tunnelUrl.value = null
            _state.value = TunnelState.Error(message)
        }
    }

    private class FakeTelemetryCollector(
        var currentReading: DeviceTelemetry = DeviceTelemetry(),
        var shouldFail: Boolean = false
    ) : TelemetryCollector {
        var collectCalls = 0

        override suspend fun collect(): DeviceTelemetry {
            collectCalls++
            if (shouldFail) throw RuntimeException("Simulated hardware sensor read failure")
            return currentReading
        }
    }
}
