package com.hermes.node.viewmodel

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
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
        assertTrue(runningState.cpuUsagePercent > 0f)
        assertTrue(runningState.memoryUsageMb > 0L)
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
        assertTrue(runningState.cpuUsagePercent > 0f)
        assertTrue(runningState.memoryUsageMb > 0L)

        viewModel.stopMonitoring()
    }

    @Test
    fun onStartServer_withPublicTunnel_setsTunnelUrl_andResetsOnStop() = runTest(testDispatcher) {
        viewModel.onUpdatePublicTunnel(true)
        assertTrue(viewModel.uiState.value.isPublicTunnelEnabled)
        assertNull(viewModel.uiState.value.tunnelUrl)

        viewModel.onStartServer()
        advanceTimeBy(650)

        val runningState = viewModel.uiState.value
        assertEquals(ServerStatus.RUNNING, runningState.status)
        assertEquals("https://hermes-node.trycloudflare.com", runningState.tunnelUrl)

        viewModel.onStopServer()
        advanceTimeBy(450)

        val stoppedState = viewModel.uiState.value
        assertEquals(ServerStatus.STOPPED, stoppedState.status)
        assertNull(stoppedState.tunnelUrl)
    }

    @Test
    fun onStartServer_withTelegramToken_logsGatewayConnection() = runTest(testDispatcher) {
        viewModel.onUpdateTelegramToken("123456:ABC-DEF")
        viewModel.onStartServer()
        advanceTimeBy(650)

        assertTrue(viewModel.uiState.value.logs.any {
            it.message.contains("Telegram Gateway connected successfully")
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
        viewModel.onUpdatePublicTunnel(true)
        viewModel.onStartServer()
        advanceTimeBy(650)
        advanceTimeBy(2000)

        val runningState = viewModel.uiState.value
        assertEquals(ServerStatus.RUNNING, runningState.status)
        assertTrue(runningState.uptimeSeconds > 0L)
        assertNotNull(runningState.tunnelUrl)

        viewModel.onSetError("Fatal error encountered")

        val errorState = viewModel.uiState.value
        assertEquals(ServerStatus.ERROR, errorState.status)
        assertEquals(0L, errorState.uptimeSeconds)
        assertEquals(0f, errorState.cpuUsagePercent, 0.001f)
        assertEquals(0L, errorState.memoryUsageMb)
        assertNull(errorState.tunnelUrl)
        assertEquals("Fatal error encountered", errorState.errorMessage)
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

    private class FakeBootstrapExtractor(
        var installed: Boolean = false,
        var shouldSucceed: Boolean = true,
        var errorMessage: String = "Failed extraction"
    ) : com.hermes.node.engine.BootstrapExtractor(filesDir = java.io.File("."), assetManager = null) {
        var extractCalls = 0

        override fun isBootstrapInstalled(): Boolean = installed

        override suspend fun extract(
            assetName: String,
            onProgress: ((progress: Float, message: String) -> Unit)?
        ): com.hermes.node.engine.ExtractionResult {
            extractCalls++
            onProgress?.invoke(0.5f, "Extracting fake...")
            return if (shouldSucceed) {
                installed = true
                onProgress?.invoke(1.0f, "Complete")
                com.hermes.node.engine.ExtractionResult.Success(java.io.File("./usr"))
            } else {
                com.hermes.node.engine.ExtractionResult.Error(errorMessage)
            }
        }
    }
}
