package com.hermes.node.engine

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
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
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@OptIn(ExperimentalCoroutinesApi::class)
class TunnelManagerTest {

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
    fun initialState_isStopped_withNullUrl() {
        val manager = CloudflareTunnelManager(ioDispatcher = testDispatcher)
        assertEquals(TunnelState.Stopped, manager.state.value)
        assertNull(manager.tunnelUrl.value)
        assertFalse(manager.isRunning)
    }

    @Test
    fun extractTunnelUrl_parsesStandardCloudflaredLogOutput() {
        val sampleLog1 = "2026-08-31T04:20:00Z INF |  https://alpha-beta-gamma.trycloudflare.com  |"
        val sampleLog2 = "INF Your quick Tunnel has been created! Visit it at: https://hermes-agent-1234.trycloudflare.com"
        val nonMatchingLog = "INF Starting tunnel daemon on port 8000"

        assertEquals("https://alpha-beta-gamma.trycloudflare.com", CloudflareTunnelManager.extractTunnelUrl(sampleLog1))
        assertEquals("https://hermes-agent-1234.trycloudflare.com", CloudflareTunnelManager.extractTunnelUrl(sampleLog2))
        assertNull(CloudflareTunnelManager.extractTunnelUrl(nonMatchingLog))
    }

    @Test
    fun start_happyPath_discoversUrlAndSetsRunningState() = runTest(testDispatcher) {
        val logOutput = """
            INF Starting cloudflared tunnel
            INF +--------------------------------------------------------------------------------------------+
            INF |  Your quick Tunnel has been created! Visit it at:                                         |
            INF |  https://my-test-subdomain.trycloudflare.com                                                |
            INF +--------------------------------------------------------------------------------------------+
        """.trimIndent() + "\n"

        val fakeProcess = FakeProcess(inStream = ByteArrayInputStream(logOutput.toByteArray()))
        val fakeRunner = FakeProcessRunner(fakeProcess)
        val manager = CloudflareTunnelManager(
            processRunner = fakeRunner,
            ioDispatcher = testDispatcher,
            urlDiscoveryTimeoutMs = 5000L
        )

        val result = manager.start(8000)
        assertTrue(result.isSuccess)
        assertEquals("https://my-test-subdomain.trycloudflare.com", result.getOrNull())
        assertEquals("https://my-test-subdomain.trycloudflare.com", manager.tunnelUrl.value)
        assertTrue(manager.state.value is TunnelState.Running)
        assertTrue(manager.isRunning)

        // Verify config arguments passed to process
        val executedConfig = fakeRunner.lastExecutedConfig
        assertNotNull(executedConfig)
        assertTrue(executedConfig!!.arguments.contains("http://127.0.0.1:8000"))

        manager.stop()
    }

    @Test
    fun start_whenBinaryFailsOrTimesOut_setsErrorState() = runTest(testDispatcher) {
        val logOutput = "INF No URL generated here\n"
        val fakeProcess = FakeProcess(inStream = ByteArrayInputStream(logOutput.toByteArray()))
        val fakeRunner = FakeProcessRunner(fakeProcess)
        val manager = CloudflareTunnelManager(
            processRunner = fakeRunner,
            ioDispatcher = testDispatcher,
            urlDiscoveryTimeoutMs = 100L
        )

        val result = manager.start(8000)
        assertTrue(result.isFailure)
        assertTrue(manager.state.value is TunnelState.Error)
        assertNull(manager.tunnelUrl.value)
        assertFalse(manager.isRunning)

        manager.stop()
    }

    @Test
    fun stop_terminatesProcessGracefully() = runTest(testDispatcher) {
        val logOutput = "INF https://active-tunnel.trycloudflare.com\n"
        val fakeProcess = FakeProcess(inStream = ByteArrayInputStream(logOutput.toByteArray()))
        val fakeRunner = FakeProcessRunner(fakeProcess)
        val manager = CloudflareTunnelManager(
            processRunner = fakeRunner,
            ioDispatcher = testDispatcher
        )

        manager.start(8000)
        assertTrue(manager.isRunning)

        val stopResult = manager.stop()
        assertTrue(stopResult.isSuccess)
        assertEquals(TunnelState.Stopped, manager.state.value)
        assertNull(manager.tunnelUrl.value)
        assertFalse(manager.isRunning)
        assertTrue(fakeProcess.destroyCalled)
    }

    private class FakeProcessRunner(private val process: Process) : ProcessRunner {
        var lastExecutedConfig: ProcessConfig? = null

        override fun run(config: ProcessConfig): Process {
            lastExecutedConfig = config
            return process
        }
    }

    private class FakeProcess(
        private val inStream: InputStream = ByteArrayInputStream("\n".toByteArray()),
        private val errStream: InputStream = ByteArrayInputStream("\n".toByteArray()),
        private val outStream: OutputStream = ByteArrayOutputStream(),
        var exitCodeValue: Int = 0
    ) : Process() {
        private var _isAlive = true
        private val exitLatch = CountDownLatch(1)
        var destroyCalled = false
        var destroyForciblyCalled = false

        override fun getOutputStream(): OutputStream = outStream
        override fun getInputStream(): InputStream = inStream
        override fun getErrorStream(): InputStream = errStream

        override fun waitFor(): Int {
            exitLatch.await()
            return exitCodeValue
        }

        override fun waitFor(timeout: Long, unit: TimeUnit): Boolean {
            return try {
                exitLatch.await(timeout, unit)
            } catch (e: InterruptedException) {
                !_isAlive
            }
        }

        override fun exitValue(): Int {
            if (_isAlive) throw IllegalThreadStateException("Process alive")
            return exitCodeValue
        }

        override fun destroy() {
            destroyCalled = true
            _isAlive = false
            exitLatch.countDown()
        }

        override fun destroyForcibly(): Process {
            destroyForciblyCalled = true
            _isAlive = false
            exitLatch.countDown()
            return this
        }

        override fun isAlive(): Boolean = _isAlive
    }
}
