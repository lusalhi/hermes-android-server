package com.hermes.node.service

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.ServiceCompat
import com.hermes.node.engine.CloudflareTunnelManager
import com.hermes.node.engine.LogStreamer
import com.hermes.node.engine.LogStreamerInterface
import com.hermes.node.engine.ProcessConfig
import com.hermes.node.engine.ProcessController
import com.hermes.node.engine.ProcessControllerInterface
import com.hermes.node.engine.ProcessState
import com.hermes.node.engine.ProcessStopResult
import com.hermes.node.engine.TunnelManagerInterface
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.io.File

open class HermesServerService : Service() {

    var wakeLockManager: WakeLockManagerInterface? = null
    var processController: ProcessControllerInterface? = null
    var logStreamer: LogStreamerInterface? = null
    var tunnelManager: TunnelManagerInterface? = null
    var telegramGatewayManager: com.hermes.node.engine.TelegramGatewayManagerInterface? = null
    internal var ioDispatcher: CoroutineDispatcher = Dispatchers.IO
    internal var serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var processExitListener: ((Int) -> Unit)? = null
    private var processStateCollectorJob: Job? = null

    internal fun ensureTelegramGatewayManager(): com.hermes.node.engine.TelegramGatewayManagerInterface {
        if (telegramGatewayManager == null) {
            telegramGatewayManager = com.hermes.node.engine.TelegramGatewayManager(ioDispatcher = ioDispatcher)
        }
        return telegramGatewayManager!!
    }

    internal fun ensureTunnelManager(): TunnelManagerInterface {
        if (tunnelManager == null) {
            tunnelManager = try {
                CloudflareTunnelManager(filesDir = safeFilesDir, ioDispatcher = ioDispatcher)
            } catch (_: Throwable) {
                null
            }
        }
        return tunnelManager ?: CloudflareTunnelManager(filesDir = safeFilesDir, ioDispatcher = ioDispatcher).also { tunnelManager = it }
    }

    internal fun stopTunnelBlocking() {
        val mgr = tunnelManager ?: return
        try {
            // Avoid blocking the main thread (onDestroy etc) — fire-and-forget on serviceScope
            serviceScope.launch(ioDispatcher) {
                try { mgr.stop() } catch (_: Throwable) {}
            }
        } catch (_: Throwable) {}
    }

    private fun stopTunnelBlockingSync(timeoutMs: Long = 1500L) {
        val mgr = tunnelManager ?: return
        try {
            // Only for contexts where brief blocking is acceptable (e.g. background thread)
            kotlinx.coroutines.runBlocking(ioDispatcher) { mgr.stop(timeoutMs) }
        } catch (_: Throwable) {}
    }

    private val safeFilesDir: File
        get() = try {
            filesDir ?: File(".")
        } catch (_: Throwable) {
            File(".")
        }

    inner class LocalBinder : Binder() {
        fun getService(): HermesServerService = this@HermesServerService
    }

    private val binder = LocalBinder()

    override fun onCreate() {
        try {
            super.onCreate()
        } catch (ignored: Throwable) {}
        if (wakeLockManager == null) {
            wakeLockManager = WakeLockManager.create(this)
        }
        if (processController == null) {
            processController = ProcessController(filesDir = safeFilesDir)
        }
        if (logStreamer == null) {
            logStreamer = sharedLogStreamer
        }
        // Ensure tunnel manager is initialized so stop paths are not no-ops
        try { ensureTunnelManager() } catch (_: Throwable) {}
        setupProcessExitListener()
        NotificationHelper.createNotificationChannel(this)
    }

    fun setupProcessExitListener() {
        processExitListener?.let { processController?.removeExitListener(it) }
        val listener: (Int) -> Unit = { exitCode ->
            _processState.value = ProcessState.TERMINATED
            try {
                Log.w(TAG, "Sub-process terminated with exit code: $exitCode. Cleaning up service state...")
            } catch (ignored: Throwable) {}
            onProcessTerminatedUnexpectedly(exitCode)
        }
        processExitListener = listener
        processController?.addExitListener(listener)

        processStateCollectorJob?.cancel()
        processController?.let { controller ->
            processStateCollectorJob = serviceScope.launch {
                controller.state.collect { pState ->
                    _processState.value = pState
                }
            }
        }
    }

    internal open fun onProcessTerminatedUnexpectedly(exitCode: Int) {
        try {
            logStreamer?.append("Sub-process terminated unexpectedly with exit code $exitCode", com.hermes.node.viewmodel.LogLevel.WARN)
        } catch (_: Throwable) {}
        try {
            stopTunnelBlocking()
        } catch (_: Throwable) {}
        try {
            serviceScope.launch(ioDispatcher) {
                try { telegramGatewayManager?.stop() } catch (_: Throwable) {}
            }
        } catch (_: Throwable) {}
        try {
            if (wakeLockManager?.isHeld == true) {
                wakeLockManager?.release()
            }
        } catch (e: Exception) {
            try {
                Log.w(TAG, "Error releasing WakeLock on unexpected exit: ${e.message}")
            } catch (ignored: Throwable) {}
        }
        try {
            logStreamer?.stop()
        } catch (ignored: Throwable) {}
        _isRunning.value = false
        _processState.value = ProcessState.TERMINATED
        try {
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        } catch (ignored: Throwable) {}
        try {
            NotificationHelper.cancelNotification(this)
        } catch (ignored: Throwable) {}
        stopSelfService()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        try { ensureTunnelManager() } catch (_: Throwable) {}
        val action = getActionFromIntent(intent)
        when (action) {
            ACTION_STOP -> {
                stopForegroundServiceInternal()
            }
            ACTION_START -> {
                startForegroundServiceInternal()
            }
            else -> {
                startForegroundServiceInternal()
            }
        }
        return START_NOT_STICKY
    }

    internal open fun getActionFromIntent(intent: Intent?): String? = intent?.action

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onDestroy() {
        processExitListener?.let { processController?.removeExitListener(it) }
        processExitListener = null
        processStateCollectorJob?.cancel()
        processStateCollectorJob = null
        try {
            stopTunnelBlocking()
        } catch (_: Throwable) {}
        try {
            runBlocking(ioDispatcher) {
                telegramGatewayManager?.stop()
            }
        } catch (_: Throwable) {}
        try {
            logStreamer?.stop()
        } catch (ignored: Throwable) {}
        try {
            if (processController != null) {
                runBlocking(ioDispatcher) {
                    processController?.stop(1500L)
                    processController?.close()
                }
            }
        } catch (e: Exception) {
            try {
                Log.w(TAG, "Error stopping process on destroy: ${e.message}")
            } catch (ignored: Throwable) {}
        }
        try {
            if (wakeLockManager?.isHeld == true) {
                wakeLockManager?.release()
            }
        } catch (e: Exception) {
            try {
                Log.w(TAG, "Error releasing WakeLock on destroy: ${e.message}")
            } catch (ignored: Throwable) {}
        }
        _isRunning.value = false
        _processState.value = ProcessState.STOPPED
        try {
            serviceScope.cancel()
        } catch (ignored: Throwable) {}
        try {
            NotificationHelper.cancelNotification(this)
        } catch (ignored: Throwable) {}
        try {
            super.onDestroy()
        } catch (ignored: Throwable) {}
    }

    fun startForegroundServiceInternal(config: ProcessConfig? = null): Boolean {
        if (_isRunning.value) {
            return true
        }

        try {
            NotificationHelper.createNotificationChannel(this)
            val notification = NotificationHelper.buildNotification(
                this,
                "Hermes Node daemon running on port 8000"
            )

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                ServiceCompat.startForeground(
                    this,
                    NotificationHelper.NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                )
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ServiceCompat.startForeground(
                    this,
                    NotificationHelper.NOTIFICATION_ID,
                    notification,
                    0
                )
            } else {
                startForeground(NotificationHelper.NOTIFICATION_ID, notification)
            }
        } catch (e: Throwable) {
            try {
                Log.e(TAG, "Failed to start foreground notification: ${e.message}", e)
            } catch (ignored: Throwable) {}
            try {
                if (wakeLockManager?.isHeld == true) {
                    wakeLockManager?.release()
                }
            } catch (ignored: Throwable) {}
            _isRunning.value = false
            stopSelfService()
            return false
        }

        try {
            wakeLockManager?.acquire()
        } catch (e: Exception) {
            try {
                Log.e(TAG, "Failed to acquire WakeLock: ${e.message}", e)
            } catch (ignored: Throwable) {}
        }

        val controller = processController
        if (controller != null) {
            val targetConfig = config ?: ProcessConfig.createHermesDaemonConfig(safeFilesDir)
            try {
                logStreamer?.append("Launching Hermes daemon: ${targetConfig.fullCommand.joinToString(" ")}", com.hermes.node.viewmodel.LogLevel.INFO)
                if (targetConfig.environment.containsKey("HERMES_SEARCH_PROVIDER")) {
                    val prov = targetConfig.environment["HERMES_SEARCH_PROVIDER"]
                    logStreamer?.append("Web search skill active with provider: $prov", com.hermes.node.viewmodel.LogLevel.INFO)
                }
            } catch (_: Throwable) {}
            val startResult = runBlocking(ioDispatcher) {
                controller.start(targetConfig)
            }
            if (startResult.isFailure) {
                val err = startResult.exceptionOrNull()
                val errorMsg = err?.message ?: "Unknown child process failure"
                try {
                    Log.e(TAG, "Failed to start child process: $errorMsg", err)
                } catch (ignored: Throwable) {}
                try {
                    logStreamer?.append("Error: Sub-process execution failed: $errorMsg", com.hermes.node.viewmodel.LogLevel.ERROR)
                } catch (_: Throwable) {}
                try {
                    if (wakeLockManager?.isHeld == true) {
                        wakeLockManager?.release()
                    }
                } catch (ignored: Throwable) {}
                _lastErrorMessage.value = errorMsg
                _isRunning.value = false
                _processState.value = ProcessState.ERROR
                try {
                    ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
                } catch (ignored: Throwable) {}
                try {
                    NotificationHelper.cancelNotification(this)
                } catch (ignored: Throwable) {}
                stopSelfService()
                return false
            } else {
                _processState.value = ProcessState.RUNNING
                try {
                    logStreamer?.start(controller.stdout, controller.stderr)
                    logStreamer?.append("Daemon process started successfully (PID: ${controller.pid ?: "N/A"})", com.hermes.node.viewmodel.LogLevel.INFO)
                } catch (ignored: Throwable) {}
            }
        }

        _isRunning.value = true
        try {
            Log.i(TAG, "HermesServerService started in foreground")
        } catch (ignored: Throwable) {}

        // Connect Telegram Gateway if enabled in configuration
        val hermesConfig = try {
            val configFile = File(safeFilesDir, "hermes.json")
            com.hermes.node.data.ConfigSerializer(configFile).deserialize().getOrNull()
        } catch (_: Throwable) { null }

        if (hermesConfig?.gateway?.telegram?.enabled == true && hermesConfig.gateway.telegram.botToken.isNotBlank()) {
            val tgManager = ensureTelegramGatewayManager()
            serviceScope.launch(ioDispatcher) {
                try {
                    logStreamer?.append("Telegram Gateway: connecting to Telegram Bot API...", com.hermes.node.viewmodel.LogLevel.INFO)
                    val authResult = tgManager.start(
                        botToken = hermesConfig.gateway.telegram.botToken,
                        adminUserIds = hermesConfig.gateway.telegram.adminUserIds,
                        hermesConfig = hermesConfig,
                        logStreamer = logStreamer
                    )
                    if (authResult.isSuccess) {
                        logStreamer?.append("Telegram Gateway active: @${authResult.getOrNull()} is online and listening for messages.", com.hermes.node.viewmodel.LogLevel.INFO)
                    }
                } catch (e: Exception) {
                    logStreamer?.append("Telegram Gateway connection error: ${e.message}", com.hermes.node.viewmodel.LogLevel.WARN)
                }
            }
        }

        return true
    }

    fun stopForegroundServiceInternal(): ProcessStopResult {
        try {
            stopTunnelBlocking()
        } catch (_: Throwable) {}
        try {
            serviceScope.launch(ioDispatcher) {
                try { telegramGatewayManager?.stop() } catch (_: Throwable) {}
            }
        } catch (_: Throwable) {}
        val stopResult = processController?.let { controller ->
            runBlocking(ioDispatcher) {
                controller.stop()
            }
        } ?: ProcessStopResult.ALREADY_STOPPED

        try {
            logStreamer?.stop()
        } catch (ignored: Throwable) {}

        try {
            if (wakeLockManager?.isHeld == true) {
                wakeLockManager?.release()
            }
        } catch (e: Exception) {
            try {
                Log.w(TAG, "Error releasing WakeLock on stop: ${e.message}")
            } catch (ignored: Throwable) {}
        }

        _isRunning.value = false
        _processState.value = ProcessState.STOPPED

        try {
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        } catch (e: Throwable) {
            try {
                @Suppress("DEPRECATION")
                stopForeground(true)
            } catch (ignored: Throwable) {}
        }

        try {
            NotificationHelper.cancelNotification(this)
        } catch (ignored: Throwable) {}

        stopSelfService()
        try {
            Log.i(TAG, "HermesServerService stopped")
        } catch (ignored: Throwable) {}
        return stopResult
    }

    internal open fun stopSelfService() {
        try {
            stopSelf()
        } catch (ignored: Throwable) {}
    }

    companion object {
        const val TAG = "HermesServerService"
        const val ACTION_START = NotificationHelper.ACTION_START
        const val ACTION_STOP = NotificationHelper.ACTION_STOP

        val sharedLogStreamer: LogStreamerInterface = LogStreamer()

        private val _isRunning = MutableStateFlow(false)
        val isRunning: StateFlow<Boolean> = _isRunning.asStateFlow()

        private val _processState = MutableStateFlow(com.hermes.node.engine.ProcessState.STOPPED)
        val processState: StateFlow<com.hermes.node.engine.ProcessState> = _processState.asStateFlow()

        private val _lastErrorMessage = MutableStateFlow<String?>(null)
        val lastErrorMessage: StateFlow<String?> = _lastErrorMessage.asStateFlow()

        fun start(context: Context): Boolean {
            val intent = Intent(context, HermesServerService::class.java).apply {
                action = ACTION_START
            }
            return try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
                true
            } catch (e: Exception) {
                try {
                    Log.e(TAG, "Failed to start service: ${e.message}", e)
                } catch (ignored: Throwable) {}
                false
            }
        }

        fun stop(context: Context): Boolean {
            val intent = Intent(context, HermesServerService::class.java).apply {
                action = ACTION_STOP
            }
            return try {
                context.startService(intent)
                true
            } catch (e: Exception) {
                try {
                    Log.e(TAG, "Failed to stop service: ${e.message}", e)
                } catch (ignored: Throwable) {}
                false
            }
        }

        internal fun setRunningForTest(running: Boolean) {
            _isRunning.value = running
        }

        internal fun setProcessStateForTest(state: com.hermes.node.engine.ProcessState) {
            _processState.value = state
        }

        internal fun setLastErrorMessageForTest(msg: String?) {
            _lastErrorMessage.value = msg
        }
    }
}
