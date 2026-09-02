package com.hermes.node.engine

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
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

@OptIn(ExperimentalCoroutinesApi::class)
class ProcessControllerTest {

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
    fun initialState_isStopped_withNullStreamsAndPid() {
        val controller = ProcessController(ioDispatcher = testDispatcher)
        assertEquals(ProcessState.STOPPED, controller.state.value)
        assertNull(controller.pid)
        assertNull(controller.exitCode)
        assertNull(controller.stdout)
        assertNull(controller.stderr)
        assertNull(controller.stdin)
        assertFalse(controller.isAlive)
    }

    @Test
    fun start_happyPath_launchesProcess_transitionsToRunning_capturesPidAndStreams() = runTest(testDispatcher) {
        val fakeProcess = FakeProcess(fakePid = 4321L)
        val fakeRunner = FakeProcessRunner(fakeProcess)
        val controller = ProcessController(processRunner = fakeRunner, ioDispatcher = testDispatcher)

        val config = ProcessConfig(executable = "/bin/dummy", arguments = listOf("arg1", "arg2"))
        val result = controller.start(config)

        assertTrue(result.isSuccess)
        assertEquals(4321L, result.getOrNull())
        assertEquals(4321L, controller.pid)
        assertEquals(ProcessState.RUNNING, controller.state.value)
        assertTrue(controller.isAlive)
        assertNotNull(controller.stdout)
        assertNotNull(controller.stderr)
        assertNotNull(controller.stdin)
        assertEquals(listOf("/bin/dummy", "arg1", "arg2"), fakeRunner.lastExecutedConfig?.fullCommand)

        controller.stop()
    }

    @Test
    fun start_whenAlreadyRunning_returnsFailure() = runTest(testDispatcher) {
        val fakeProcess = FakeProcess(fakePid = 1001L)
        val controller = ProcessController(processRunner = FakeProcessRunner(fakeProcess), ioDispatcher = testDispatcher)

        val config = ProcessConfig(executable = "/bin/dummy")
        val firstStart = controller.start(config)
        assertTrue(firstStart.isSuccess)
        assertEquals(ProcessState.RUNNING, controller.state.value)

        val secondStart = controller.start(config)
        assertTrue(secondStart.isFailure)
        assertTrue(secondStart.exceptionOrNull() is IllegalStateException)
        assertEquals(ProcessState.RUNNING, controller.state.value)

        controller.stop()
    }

    @Test
    fun start_whenBinaryNotFoundOrThrows_transitionsToErrorState_returnsFailure() = runTest(testDispatcher) {
        val brokenRunner = object : ProcessRunner {
            override fun run(config: ProcessConfig): Process {
                throw IOException("Cannot run program '/usr/bin/missing': error=2, No such file or directory")
            }
        }
        val controller = ProcessController(processRunner = brokenRunner, ioDispatcher = testDispatcher)

        val config = ProcessConfig(executable = "/usr/bin/missing")
        val result = controller.start(config)

        assertTrue(result.isFailure)
        assertEquals(ProcessState.ERROR, controller.state.value)
        assertNull(controller.pid)
        assertNull(controller.stdout)
        assertNull(controller.stderr)
        assertNull(controller.stdin)
        assertFalse(controller.isAlive)
    }

    @Test
    fun stop_whenRunning_sendsGracefulSigterm_transitionsToStopped() = runTest(testDispatcher) {
        val fakeProcess = FakeProcess(fakePid = 5555L, ignoreSigterm = false, exitCodeValue = 0)
        val controller = ProcessController(processRunner = FakeProcessRunner(fakeProcess), ioDispatcher = testDispatcher)

        controller.start(ProcessConfig(executable = "/bin/dummy"))
        assertEquals(ProcessState.RUNNING, controller.state.value)

        val stopResult = controller.stop(timeoutMs = 5000L)

        assertEquals(ProcessStopResult.GRACEFUL_SIGTERM, stopResult)
        assertTrue(fakeProcess.destroyCalled)
        assertFalse(fakeProcess.destroyForciblyCalled)
        assertEquals(ProcessState.STOPPED, controller.state.value)
        assertNull(controller.pid)
        assertNull(controller.stdout)
        assertNull(controller.stderr)
        assertNull(controller.stdin)
        assertFalse(controller.isAlive)
        assertEquals(0, controller.exitCode)
    }

    @Test
    fun stop_whenProcessIgnoresSigterm_timesOutAndSendsSigkill() = runTest(testDispatcher) {
        val unresponsiveProcess = FakeProcess(fakePid = 9999L, ignoreSigterm = true, exitCodeValue = 137)
        val controller = ProcessController(processRunner = FakeProcessRunner(unresponsiveProcess), ioDispatcher = testDispatcher)

        controller.start(ProcessConfig(executable = "/bin/unresponsive"))
        assertEquals(ProcessState.RUNNING, controller.state.value)

        val stopResult = controller.stop(timeoutMs = 100L)

        assertEquals(ProcessStopResult.FORCED_SIGKILL, stopResult)
        assertTrue(unresponsiveProcess.destroyCalled)
        assertTrue(unresponsiveProcess.destroyForciblyCalled)
        assertEquals(ProcessState.STOPPED, controller.state.value)
        assertNull(controller.pid)
        assertFalse(controller.isAlive)
        assertEquals(137, controller.exitCode)
    }

    @Test
    fun stop_whenAlreadyStopped_isNoOp_returnsAlreadyStopped() = runTest(testDispatcher) {
        val controller = ProcessController(ioDispatcher = testDispatcher)
        assertEquals(ProcessState.STOPPED, controller.state.value)

        val stopResult = controller.stop()
        assertEquals(ProcessStopResult.ALREADY_STOPPED, stopResult)
        assertEquals(ProcessState.STOPPED, controller.state.value)
    }

    @Test
    fun stop_whenErrorState_returnsAlreadyStopped() = runTest(testDispatcher) {
        val brokenRunner = object : ProcessRunner {
            override fun run(config: ProcessConfig): Process = throw SecurityException("Permission denied")
        }
        val controller = ProcessController(processRunner = brokenRunner, ioDispatcher = testDispatcher)
        controller.start(ProcessConfig(executable = "/root/secret"))
        assertEquals(ProcessState.ERROR, controller.state.value)

        val stopResult = controller.stop()
        assertEquals(ProcessStopResult.ALREADY_STOPPED, stopResult)
        assertEquals(ProcessState.STOPPED, controller.state.value)
    }

    @Test
    fun unexpectedExit_transitionsToTerminated_andNotifiesExitListeners() = runTest(testDispatcher) {
        val fakeProcess = FakeProcess(fakePid = 7777L, exitCodeValue = 1)
        val controller = ProcessController(processRunner = FakeProcessRunner(fakeProcess), ioDispatcher = testDispatcher)

        var listenerReceivedCode: Int? = null
        val listener: (Int) -> Unit = { code -> listenerReceivedCode = code }
        controller.addExitListener(listener)

        controller.start(ProcessConfig(executable = "/bin/crash_soon"))
        assertEquals(ProcessState.RUNNING, controller.state.value)

        // Simulate crash
        fakeProcess.simulateUnexpectedExit(139) // SIGSEGV (128 + 11)
        testScheduler.advanceUntilIdle()

        assertEquals(ProcessState.TERMINATED, controller.state.value)
        assertEquals(139, listenerReceivedCode)
        assertEquals(139, controller.exitCode)
        assertNull(controller.stdout)
        assertNull(controller.stderr)
        assertNull(controller.stdin)
        assertFalse(controller.isAlive)

        // Remove listener
        controller.removeExitListener(listener)
    }

    @Test
    fun createHermesDaemonConfig_buildsValidConfig() {
        val tempDir = File(System.getProperty("java.io.tmpdir") ?: "/tmp", "hermes_test_env_${System.currentTimeMillis()}")
        tempDir.mkdirs()

        val config = ProcessConfig.createHermesDaemonConfig(
            filesDir = tempDir,
            customEnv = mapOf("CUSTOM_VAR" to "123")
        )

        assertEquals(tempDir, config.workingDir)
        assertEquals("123", config.environment["CUSTOM_VAR"])
        assertTrue(config.environment.containsKey("HOME"))
        assertTrue(config.environment.containsKey("PREFIX"))
        assertTrue(config.environment.containsKey("PATH"))
        assertTrue(config.environment.containsKey("TMPDIR"))
        assertTrue(config.environment.containsKey("PYTHONHOME"))
        assertTrue(config.environment.containsKey("HERMES_CONFIG_PATH"))
        assertTrue(config.fullCommand.isNotEmpty())
        assertEquals(listOf("-m", "hermes", "gateway", "run"), config.arguments)

        tempDir.deleteRecursively()
    }

    @Test
    fun extractPid_extractsPidProperly() {
        val controller = ProcessController(ioDispatcher = testDispatcher)
        val fakeProcess = FakeProcess(fakePid = 6789L)
        val pid = controller.extractPid(fakeProcess)
        assertEquals(6789L, pid)
    }

    @Test
    fun defaultProcessRunner_instantiationAndCommandBuilding() {
        val runner = DefaultProcessRunner()
        assertNotNull(runner)
        val config = ProcessConfig(
            executable = "echo",
            arguments = listOf("hello", "world"),
            environment = mapOf("TEST" to "true"),
            redirectErrorStream = true
        )
        assertEquals(listOf("echo", "hello", "world"), config.fullCommand)
    }

    @Test
    fun start_whenProcessRunnerFails_setsLastErrorMessageWithDiagnostics() = runTest(testDispatcher) {
        val failingRunner = object : ProcessRunner {
            override fun run(config: ProcessConfig): Process {
                throw java.io.IOException("Cannot run program \"${config.executable}\": error=2, No such file or directory")
            }
        }
        val controller = ProcessController(processRunner = failingRunner, ioDispatcher = testDispatcher)
        val config = ProcessConfig(executable = "/non/existent/path/python3")

        val result = controller.start(config)

        assertTrue(result.isFailure)
        assertEquals(ProcessState.ERROR, controller.state.value)
        assertNotNull(controller.lastErrorMessage)
        assertTrue(controller.lastErrorMessage?.contains("Failed to launch '/non/existent/path/python3'") == true)
        assertTrue(controller.lastErrorMessage?.contains("file exists=false") == true)
    }

    @Test
    fun resolveExecutableCommand_whenScriptHasShebang_handlesCommandResolution() {
        val tempDir = File(System.getProperty("java.io.tmpdir") ?: "/tmp", "script_test_${System.currentTimeMillis()}").apply { mkdirs() }
        val script = File(tempDir, "mock_script.sh").apply {
            writeText("#!/non/existent/interpreter/sh\necho test\n")
            setExecutable(true)
        }

        val config = ProcessConfig(
            executable = script.absolutePath,
            arguments = listOf("arg1", "arg2")
        )

        val resolved = DefaultProcessRunner.resolveExecutableCommand(config)
        assertNotNull(resolved)
        assertTrue(resolved.isNotEmpty())

        tempDir.deleteRecursively()
    }

    private class FakeProcessRunner(private val process: Process) : ProcessRunner {
        var lastExecutedConfig: ProcessConfig? = null

        override fun run(config: ProcessConfig): Process {
            lastExecutedConfig = config
            return process
        }
    }

    private class FakeProcess(
        private val fakePid: Long = 1234L,
        private val inStream: InputStream = ByteArrayInputStream("test output\n".toByteArray()),
        private val errStream: InputStream = ByteArrayInputStream("test error\n".toByteArray()),
        private val outStream: OutputStream = ByteArrayOutputStream(),
        var exitCodeValue: Int = 0,
        var ignoreSigterm: Boolean = false
    ) : Process() {
        private var _isAlive = true
        private val exitLatch = java.util.concurrent.CountDownLatch(1)
        var destroyCalled = false
        var destroyForciblyCalled = false

        override fun getOutputStream(): OutputStream = outStream
        override fun getInputStream(): InputStream = inStream
        override fun getErrorStream(): InputStream = errStream

        override fun waitFor(): Int {
            try {
                exitLatch.await()
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
            }
            return exitCodeValue
        }

        override fun waitFor(timeout: Long, unit: java.util.concurrent.TimeUnit): Boolean {
            return try {
                exitLatch.await(timeout, unit)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                !_isAlive
            }
        }

        override fun exitValue(): Int {
            if (_isAlive) throw IllegalThreadStateException("Process is still running")
            return exitCodeValue
        }

        override fun destroy() {
            destroyCalled = true
            if (!ignoreSigterm) {
                _isAlive = false
                exitLatch.countDown()
            }
        }

        override fun destroyForcibly(): Process {
            destroyForciblyCalled = true
            _isAlive = false
            exitLatch.countDown()
            return this
        }

        fun simulateUnexpectedExit(code: Int) {
            exitCodeValue = code
            _isAlive = false
            exitLatch.countDown()
        }

        fun pid(): Long = fakePid
    }
}
