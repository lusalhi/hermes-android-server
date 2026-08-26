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
import com.hermes.node.engine.ProcessConfig
import com.hermes.node.engine.ProcessController
import com.hermes.node.engine.ProcessControllerInterface
import com.hermes.node.engine.ProcessStopResult
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.runBlocking
import java.io.File

open class HermesServerService : Service() {

    var wakeLockManager: WakeLockManagerInterface? = null
    var processController: ProcessControllerInterface? = null
    internal var ioDispatcher: CoroutineDispatcher = Dispatchers.IO

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
        setupProcessExitListener()
        NotificationHelper.createNotificationChannel(this)
    }

    fun setupProcessExitListener() {
        processController?.addExitListener { exitCode ->
            try {
                Log.w(TAG, "Sub-process terminated with exit code: $exitCode. Cleaning up service state...")
            } catch (ignored: Throwable) {}
            onProcessTerminatedUnexpectedly(exitCode)
        }
    }

    internal open fun onProcessTerminatedUnexpectedly(exitCode: Int) {
        try {
            if (wakeLockManager?.isHeld == true) {
                wakeLockManager?.release()
            }
        } catch (e: Exception) {
            try {
                Log.w(TAG, "Error releasing WakeLock on unexpected termination: ${e.message}")
            } catch (ignored: Throwable) {}
        }
        _isRunning.value = false
        try {
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        } catch (ignored: Throwable) {}
        try {
            NotificationHelper.cancelNotification(this)
        } catch (ignored: Throwable) {}
        stopSelfService()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
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
        try {
            if (processController?.isAlive == true) {
                runBlocking(ioDispatcher) {
                    processController?.stop()
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
            val startResult = runBlocking(ioDispatcher) {
                controller.start(targetConfig)
            }
            if (startResult.isFailure) {
                try {
                    Log.e(TAG, "Failed to start child process: ${startResult.exceptionOrNull()?.message}")
                } catch (ignored: Throwable) {}
                try {
                    if (wakeLockManager?.isHeld == true) {
                        wakeLockManager?.release()
                    }
                } catch (ignored: Throwable) {}
                _isRunning.value = false
                try {
                    ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
                } catch (ignored: Throwable) {}
                try {
                    NotificationHelper.cancelNotification(this)
                } catch (ignored: Throwable) {}
                stopSelfService()
                return false
            }
        }

        _isRunning.value = true
        try {
            Log.i(TAG, "HermesServerService started in foreground")
        } catch (ignored: Throwable) {}
        return true
    }

    fun stopForegroundServiceInternal(): ProcessStopResult {
        val stopResult = processController?.let { controller ->
            runBlocking(ioDispatcher) {
                controller.stop()
            }
        } ?: ProcessStopResult.ALREADY_STOPPED

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

        private val _isRunning = MutableStateFlow(false)
        val isRunning: StateFlow<Boolean> = _isRunning.asStateFlow()

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
    }
}
