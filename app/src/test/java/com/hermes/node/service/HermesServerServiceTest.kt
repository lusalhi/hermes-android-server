package com.hermes.node.service

import com.hermes.node.viewmodel.LogEntry
import com.hermes.node.engine.LogStreamerInterface
import com.hermes.node.engine.ProcessConfig
import com.hermes.node.engine.ProcessControllerInterface
import com.hermes.node.engine.ProcessState
import com.hermes.node.engine.ProcessStopResult
import com.hermes.node.engine.TunnelManagerInterface
import com.hermes.node.engine.TunnelState
import com.hermes.node.viewmodel.LogLevel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.io.InputStream
import java.io.OutputStream

@OptIn(ExperimentalCoroutinesApi::class)
class HermesServerServiceTest {

    private val testDispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun onDestroy_callsTunnelManagerStopOnce() = runTest(testDispatcher) {
        val fake = FakeTunnelManager()
        val service = TestService(fake)
        service.initForTest(Dispatchers.Unconfined)
        service.tunnelManager = fake
        service.onDestroy()
        // stopTunnelBlocking uses runBlocking(Unconfined) so completes synchronously
        assertEquals(1, fake.stopCalls)
    }

    @Test
    fun onProcessTerminatedUnexpectedly_callsTunnelManagerStopOnce() = runTest(testDispatcher) {
        val fake = FakeTunnelManager()
        val service = TestService(fake)
        service.initForTest(testDispatcher)
        service.tunnelManager = fake
        service.callOnProcessTerminated(1)
        advanceUntilIdle()
        assertEquals(1, fake.stopCalls)
    }

    @Test
    fun stopForegroundServiceInternal_callsTunnelManagerStopOnce() = runTest(testDispatcher) {
        val fake = FakeTunnelManager()
        val service = TestService(fake)
        service.initForTest(testDispatcher)
        service.tunnelManager = fake
        service.stopForegroundServiceInternal()
        advanceUntilIdle()
        assertEquals(1, fake.stopCalls)
    }

    @Test
    fun ensureTunnelManager_initializesWhenNull() {
        val service = TestService(FakeTunnelManager())
        service.initForTest(Dispatchers.Unconfined)
        service.tunnelManager = null
        val mgr = service.callEnsureTunnelManager()
        assertNotNull(mgr)
        assertNotNull(service.tunnelManager)
    }

    @Test
    fun setLastErrorMessageForTest_updatesStateFlow() {
        HermesServerService.setLastErrorMessageForTest("Custom error message")
        assertEquals("Custom error message", HermesServerService.lastErrorMessage.value)
        HermesServerService.setLastErrorMessageForTest(null)
    }

    @Test
    fun startForegroundServiceInternal_withTelegramEnabled_passesConfigToProcessController_andLogsStatus() = runTest(testDispatcher) {
        val fakeTunnel = FakeTunnelManager()
        val fakeProcess = FakeProcessController()
        val fakeLogStreamer = FakeLogStreamer()
        val service = TestService(fakeTunnel)
        service.initForTest(Dispatchers.Unconfined)
        service.tunnelManager = fakeTunnel
        service.processController = fakeProcess
        service.logStreamer = fakeLogStreamer

        val telegramConfig = ProcessConfig(
            executable = "/data/data/com.hermes.node/files/usr/bin/proot",
            arguments = listOf("gateway", "run"),
            environment = mapOf("TELEGRAM_BOT_TOKEN" to "123456:SERVICE_TEST_TOKEN")
        )

        val started = service.startForegroundServiceInternal(config = telegramConfig)
        assertTrue(started)
        assertEquals("123456:SERVICE_TEST_TOKEN", fakeProcess.capturedConfig?.environment?.get("TELEGRAM_BOT_TOKEN"))
        assertTrue(fakeLogStreamer.messages.any { it.contains("Telegram gateway enabled: launching upstream Hermes daemon") })
    }

    private class TestService(private val fake: FakeTunnelManager) : HermesServerService() {
        fun initForTest(dispatcher: kotlinx.coroutines.CoroutineDispatcher) {
            ioDispatcher = dispatcher
            serviceScope = kotlinx.coroutines.CoroutineScope(dispatcher)
        }
        // Expose protected methods for test
        fun callOnProcessTerminated(code: Int) = onProcessTerminatedUnexpectedly(code)
        fun callEnsureTunnelManager() = ensureTunnelManager()
        override fun stopSelfService() { /* no-op for test */ }
    }

    private class FakeTunnelManager : TunnelManagerInterface {
        private val _state = MutableStateFlow<TunnelState>(TunnelState.Stopped)
        override val state: StateFlow<TunnelState> = _state
        private val _url = MutableStateFlow<String?>(null)
        override val tunnelUrl: StateFlow<String?> = _url
        override val isRunning: Boolean get() = _state.value is TunnelState.Running
        var stopCalls = 0
        override suspend fun start(port: Int): Result<String> = Result.success("https://fake.trycloudflare.com")
        override suspend fun stop(timeoutMs: Long): Result<Unit> {
            stopCalls++
            return Result.success(Unit)
        }
    }

    private class FakeLogStreamer : LogStreamerInterface {
        val messages = mutableListOf<String>()
        override val logsFlow: StateFlow<List<LogEntry>> = MutableStateFlow(emptyList())
        override val capacity: Int = 100
        override val isStreaming: Boolean = false
        override fun start(stdout: InputStream?, stderr: InputStream?) {}
        override fun stop() {}
        override fun clear() {}
        override fun append(entry: LogEntry) { messages.add(entry.message) }
        override fun append(message: String, level: LogLevel) { messages.add(message) }
        override fun getLogs(): List<LogEntry> = emptyList()
    }

    private class FakeProcessController : ProcessControllerInterface {
        private val _state = MutableStateFlow(ProcessState.STOPPED)
        override val state: StateFlow<ProcessState> = _state
        override val pid: Long? = 1234L
        override val exitCode: Int? = null
        override val stdout: InputStream? = null
        override val stderr: InputStream? = null
        override val stdin: OutputStream? = null
        override val isAlive: Boolean = true
        var capturedConfig: ProcessConfig? = null

        override suspend fun start(config: ProcessConfig): Result<Long> {
            capturedConfig = config
            _state.value = ProcessState.RUNNING
            return Result.success(1234L)
        }
        override suspend fun stop(timeoutMs: Long): ProcessStopResult = ProcessStopResult.GRACEFUL_SIGTERM
        override suspend fun waitForExit(): Int? = 0
        override fun addExitListener(listener: (Int) -> Unit) {}
        override fun removeExitListener(listener: (Int) -> Unit) {}
    }
}
