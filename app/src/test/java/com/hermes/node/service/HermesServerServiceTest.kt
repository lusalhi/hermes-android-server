package com.hermes.node.service

import android.app.Service
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.ApplicationInfo
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class HermesServerServiceTest {

    private lateinit var fakeWakeLock: FakeWakeLockManager

    @Before
    fun setUp() {
        fakeWakeLock = FakeWakeLockManager()
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
    fun startAndStop_managesWakeLockAndRunningState() {
        val service = TestableHermesServerService(fakeWakeLock)

        assertFalse(HermesServerService.isRunning.value)
        assertFalse(fakeWakeLock.isHeld)

        val startResult = service.startForegroundServiceInternal()
        assertTrue(startResult)

        assertTrue(HermesServerService.isRunning.value)
        assertTrue(fakeWakeLock.isHeld)
        assertEquals(1, fakeWakeLock.acquireCalls)

        // Calling start again when already running is idempotent
        val secondStartResult = service.startForegroundServiceInternal()
        assertTrue(secondStartResult)
        assertEquals(1, fakeWakeLock.acquireCalls)

        service.stopForegroundServiceInternal()

        assertFalse(HermesServerService.isRunning.value)
        assertFalse(fakeWakeLock.isHeld)
        assertEquals(1, fakeWakeLock.releaseCalls)
        assertTrue(service.stopSelfCalled)
    }

    @Test
    fun onStartCommand_withActionStart_startsForeground_andReturnsStartNotSticky() {
        val service = TestableHermesServerService(fakeWakeLock)
        service.testAction = HermesServerService.ACTION_START
        val intent = Intent()
        val result = service.onStartCommand(intent, 0, 1)

        assertEquals(Service.START_NOT_STICKY, result)
        assertTrue(HermesServerService.isRunning.value)
        assertTrue(fakeWakeLock.isHeld)
    }

    @Test
    fun onStartCommand_withActionStop_stopsForeground_andReturnsStartNotSticky() {
        val service = TestableHermesServerService(fakeWakeLock)
        service.startForegroundServiceInternal()
        assertTrue(HermesServerService.isRunning.value)

        service.testAction = HermesServerService.ACTION_STOP
        val intent = Intent()
        val result = service.onStartCommand(intent, 0, 2)

        assertEquals(Service.START_NOT_STICKY, result)
        assertFalse(HermesServerService.isRunning.value)
        assertFalse(fakeWakeLock.isHeld)
        assertTrue(service.stopSelfCalled)
    }

    @Test
    fun onStartCommand_withNullIntent_defaultsToStart_andReturnsStartNotSticky() {
        val service = TestableHermesServerService(fakeWakeLock)
        service.testAction = null
        val result = service.onStartCommand(null, 0, 3)

        assertEquals(Service.START_NOT_STICKY, result)
        assertTrue(HermesServerService.isRunning.value)
        assertTrue(fakeWakeLock.isHeld)
    }

    @Test
    fun onStartCommand_withUnknownAction_defaultsToStart_andReturnsStartNotSticky() {
        val service = TestableHermesServerService(fakeWakeLock)
        service.testAction = "com.hermes.node.action.UNKNOWN"
        val intent = Intent()
        val result = service.onStartCommand(intent, 0, 4)

        assertEquals(Service.START_NOT_STICKY, result)
        assertTrue(HermesServerService.isRunning.value)
        assertTrue(fakeWakeLock.isHeld)
    }

    @Test
    fun onDestroy_releasesWakeLock_ifHeld() {
        val service = TestableHermesServerService(fakeWakeLock)
        service.startForegroundServiceInternal()
        assertTrue(fakeWakeLock.isHeld)
        assertTrue(HermesServerService.isRunning.value)

        service.onDestroy()

        assertFalse(fakeWakeLock.isHeld)
        assertFalse(HermesServerService.isRunning.value)
        assertEquals(1, fakeWakeLock.releaseCalls)
    }

    @Test
    fun localBinder_returnsServiceInstance() {
        val service = TestableHermesServerService(fakeWakeLock)
        val binder = service.LocalBinder()
        assertEquals(service, binder.getService())
    }

    private class TestableHermesServerService(
        fakeWl: FakeWakeLockManager
    ) : HermesServerService() {
        var stopSelfCalled = false
        var testAction: String? = null

        init {
            this.wakeLockManager = fakeWl
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
}
