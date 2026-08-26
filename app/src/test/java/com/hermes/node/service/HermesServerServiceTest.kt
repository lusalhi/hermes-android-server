package com.hermes.node.service

import android.app.Service
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.ApplicationInfo
import com.hermes.node.engine.ProcessConfig
import com.hermes.node.engine.ProcessControllerInterface
import com.hermes.node.engine.ProcessState
import com.hermes.node.engine.ProcessStopResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.InputStream
import java.io.OutputStream

class HermesServerServiceTest {

    private lateinit var fakeWakeLock: FakeWakeLockManager
    private lateinit var fakeProcessController: FakeProcessController

    @Before
    fun setUp() {
        fakeWakeLock = FakeWakeLockManager()
        fakeProcessController = FakeProcessController()
        HermesServerService.setRunningForTest(false)
    }

    @After
    fun tearDown() {
        HermesServerService.setRunningForTest(false)
    }

    @Test
    fun constants_andInitialState() {
        assertEquals("com.hermes.node.action.START", HermesServerService.ACTION_START)
        assertEquals("com.hermes.node.action.STOP", HermesServerService.ACTION_STOP)
        assertFalse(HermesServerService.isRunning.value)
    }

    @Test
    fun setRunningForTest_updatesStateFlow() {
        assertFalse(HermesServerService.isRunning.value)
        HermesServerService.setRunningForTest(true)
        assertTrue(HermesServerService.isRunning.value)
        HermesServerService.setRunningForTest(false)
        assertFalse(HermesServerService.isRunning.value)
    }

    @Test
    fun startAndStop_managesWakeLockAndProcessController() {
        val service = TestableHermesServerService(fakeWakeLock, fakeProcessController)

        assertFalse(HermesServerService.isRunning.value)
        assertFalse(fakeWakeLock.isHeld)
        assertEquals(0, fakeProcessController.startCalls)

        val startResult = service.startForegroundServiceInternal()
        assertTrue(startResult)

        assertTrue(HermesServerService.isRunning.value)
        assertTrue(fakeWakeLock.isHeld)
        assertEquals(1, fakeWakeLock.acquireCalls)
        assertEquals(1, fakeProcessController.startCalls)
        assertTrue(fakeProcessController.isAlive)

        // Calling start again when already running is idempotent
        val secondStartResult = service.startForegroundServiceInternal()
        assertTrue(secondStartResult)
        assertEquals(1, fakeWakeLock.acquireCalls)
        assertEquals(1, fakeProcessController.startCalls)

        val stopResult = service.stopForegroundServiceInternal()

        assertEquals(ProcessStopResult.GRACEFUL_SIGTERM, stopResult)
        assertFalse(HermesServerService.isRunning.value)
        assertFalse(fakeWakeLock.isHeld)
        assertEquals(1, fakeWakeLock.releaseCalls)
        assertEquals(1, fakeProcessController.stopCalls)
        assertFalse(fakeProcessController.isAlive)
        assertTrue(service.stopSelfCalled)
    }

    @Test
    fun start_whenProcessLaunchFails_releasesWakeLockAndReturnsFalse() {
        fakeProcessController.startResult = Result.failure(java.io.IOException("Cannot exec binary"))
        val service = TestableHermesServerService(fakeWakeLock, fakeProcessController)

        val startResult = service.startForegroundServiceInternal()
        assertFalse(startResult)

        assertFalse(HermesServerService.isRunning.value)
        assertFalse(fakeWakeLock.isHeld)
        assertTrue(service.stopSelfCalled)
    }

    @Test
    fun onStartCommand_withActionStart_startsForeground_andReturnsStartNotSticky() {
        val service = TestableHermesServerService(fakeWakeLock, fakeProcessController)
        service.testAction = HermesServerService.ACTION_START
        val intent = Intent()
        val result = service.onStartCommand(intent, 0, 1)

        assertEquals(Service.START_NOT_STICKY, result)
        assertTrue(HermesServerService.isRunning.value)
        assertTrue(fakeWakeLock.isHeld)
        assertEquals(1, fakeProcessController.startCalls)
    }

    @Test
    fun onStartCommand_withActionStop_stopsForeground_andReturnsStartNotSticky() {
        val service = TestableHermesServerService(fakeWakeLock, fakeProcessController)
        service.startForegroundServiceInternal()
        assertTrue(HermesServerService.isRunning.value)

        service.testAction = HermesServerService.ACTION_STOP
        val intent = Intent()
        val result = service.onStartCommand(intent, 0, 2)

        assertEquals(Service.START_NOT_STICKY, result)
        assertFalse(HermesServerService.isRunning.value)
        assertFalse(fakeWakeLock.isHeld)
        assertEquals(1, fakeProcessController.stopCalls)
        assertTrue(service.stopSelfCalled)
    }

    @Test
    fun onStartCommand_withNullIntent_defaultsToStart_andReturnsStartNotSticky() {
        val service = TestableHermesServerService(fakeWakeLock, fakeProcessController)
        service.testAction = null
        val result = service.onStartCommand(null, 0, 3)

        assertEquals(Service.START_NOT_STICKY, result)
        assertTrue(HermesServerService.isRunning.value)
        assertTrue(fakeWakeLock.isHeld)
        assertEquals(1, fakeProcessController.startCalls)
    }

    @Test
    fun onStartCommand_withUnknownAction_defaultsToStart_andReturnsStartNotSticky() {
        val service = TestableHermesServerService(fakeWakeLock, fakeProcessController)
        service.testAction = "com.hermes.node.action.UNKNOWN"
        val intent = Intent()
        val result = service.onStartCommand(intent, 0, 4)

        assertEquals(Service.START_NOT_STICKY, result)
        assertTrue(HermesServerService.isRunning.value)
        assertTrue(fakeWakeLock.isHeld)
        assertEquals(1, fakeProcessController.startCalls)
    }

    @Test
    fun onUnexpectedProcessExit_releasesWakeLock_andStopsService() {
        val service = TestableHermesServerService(fakeWakeLock, fakeProcessController)
        service.startForegroundServiceInternal()
        assertTrue(HermesServerService.isRunning.value)
        assertTrue(fakeWakeLock.isHeld)

        // Trigger unexpected exit from process
        fakeProcessController.triggerUnexpectedExit(137)

        assertFalse(fakeWakeLock.isHeld)
        assertFalse(HermesServerService.isRunning.value)
        assertTrue(service.stopSelfCalled)
    }

    @Test
    fun onDestroy_releasesWakeLock_andStopsProcessIfHeld() {
        val service = TestableHermesServerService(fakeWakeLock, fakeProcessController)
        service.startForegroundServiceInternal()
        assertTrue(fakeWakeLock.isHeld)
        assertTrue(HermesServerService.isRunning.value)
        assertTrue(fakeProcessController.isAlive)

        service.onDestroy()

        assertFalse(fakeWakeLock.isHeld)
        assertFalse(HermesServerService.isRunning.value)
        assertEquals(1, fakeWakeLock.releaseCalls)
        assertEquals(1, fakeProcessController.stopCalls)
    }

    @Test
    fun localBinder_returnsServiceInstance() {
        val service = TestableHermesServerService(fakeWakeLock, fakeProcessController)
        val binder = service.LocalBinder()
        assertEquals(service, binder.getService())
    }

    private class TestableHermesServerService(
        fakeWl: FakeWakeLockManager,
        fakePc: FakeProcessController
    ) : HermesServerService() {
        var stopSelfCalled = false
        var testAction: String? = null

        init {
            this.wakeLockManager = fakeWl
            this.processController = fakePc
            setupProcessExitListener()
            val mockContext = object : ContextWrapper(null) {
                private val appInfo = ApplicationInfo().apply { targetSdkVersion = 34 }
                override fun getApplicationInfo(): ApplicationInfo = appInfo
                override fun getPackageName(): String = "com.hermes.node"
                override fun getApplicationContext(): Context = this
            }
            try {
                val method = ContextWrapper::class.java.getDeclaredMethod(
                    "attachBaseContext",
                    Context::class.java
                )
                method.isAccessible = true
                method.invoke(this, mockContext)
            } catch (ignored: Throwable) {}
        }

        override fun getActionFromIntent(intent: Intent?): String? {
            return testAction ?: intent?.action
        }

        override fun stopSelfService() {
            stopSelfCalled = true
        }
    }

    private class FakeWakeLockManager : WakeLockManagerInterface {
        private var _isHeld = false
        var acquireCalls = 0
        var releaseCalls = 0

        override val isHeld: Boolean
            get() = _isHeld

        override fun acquire(timeoutMs: Long?): Boolean {
            _isHeld = true
            acquireCalls++
            return true
        }

        override fun release(): Boolean {
            if (_isHeld) {
                _isHeld = false
                releaseCalls++
            }
            return true
        }
    }

    private class FakeProcessController(
        var startResult: Result<Long> = Result.success(1234L),
        var stopResult: ProcessStopResult = ProcessStopResult.GRACEFUL_SIGTERM,
        override var isAlive: Boolean = false
    ) : ProcessControllerInterface {
        private val _state = MutableStateFlow(ProcessState.STOPPED)
        override val state: StateFlow<ProcessState> = _state.asStateFlow()
        override var pid: Long? = null
        override var exitCode: Int? = null
        override var stdout: InputStream? = null
        override var stderr: InputStream? = null
        override var stdin: OutputStream? = null

        var startCalls = 0
        var stopCalls = 0
        val exitListeners = mutableListOf<(Int) -> Unit>()

        override suspend fun start(config: ProcessConfig): Result<Long> {
            startCalls++
            return if (startResult.isSuccess) {
                _state.value = ProcessState.RUNNING
                isAlive = true
                pid = startResult.getOrNull()
                startResult
            } else {
                _state.value = ProcessState.ERROR
                isAlive = false
                pid = null
                startResult
            }
        }

        override suspend fun stop(timeoutMs: Long): ProcessStopResult {
            stopCalls++
            _state.value = ProcessState.STOPPED
            isAlive = false
            pid = null
            return stopResult
        }

        override suspend fun waitForExit(): Int? = exitCode

        override fun addExitListener(listener: (Int) -> Unit) {
            exitListeners.add(listener)
        }

        override fun removeExitListener(listener: (Int) -> Unit) {
            exitListeners.remove(listener)
        }

        fun triggerUnexpectedExit(code: Int) {
            _state.value = ProcessState.TERMINATED
            isAlive = false
            exitCode = code
            exitListeners.forEach { it.invoke(code) }
        }
    }
}
