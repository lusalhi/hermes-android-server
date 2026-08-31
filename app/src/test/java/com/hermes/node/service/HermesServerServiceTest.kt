package com.hermes.node.service

import com.hermes.node.engine.TunnelManagerInterface
import com.hermes.node.engine.TunnelState
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
import org.junit.Before
import org.junit.Test
import java.io.File

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
}
