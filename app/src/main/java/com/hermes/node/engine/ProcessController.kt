package com.hermes.node.engine

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Lifecycle states of the native sub-process.
 */
enum class ProcessState {
    STOPPED,
    STARTING,
    RUNNING,
    STOPPING,
    ERROR,
    TERMINATED
}

/**
 * Termination result statuses for stop operations.
 */
enum class ProcessStopResult {
    GRACEFUL_SIGTERM,
    FORCED_SIGKILL,
    ALREADY_STOPPED
}

/**
 * Process execution configuration.
 */
data class ProcessConfig(
    val executable: String,
    val arguments: List<String> = emptyList(),
    val workingDir: File? = null,
    val environment: Map<String, String> = emptyMap(),
    val redirectErrorStream: Boolean = false
) {
    val fullCommand: List<String>
        get() = listOf(executable) + arguments

    companion object {
        const val DAEMON_CONFIG_FILENAME = "hermes.json"

        /**
         * Helper to construct the default PRoot / native launch configuration for the Hermes daemon.
         */
        fun createHermesDaemonConfig(
            filesDir: File,
            customEnv: Map<String, String> = emptyMap()
        ): ProcessConfig {
            val usrDir = File(filesDir, BootstrapExtractor.USR_DIR_NAME)
            val prootBin = File(usrDir, "bin/proot").absolutePath
            val hermesBin = File(usrDir, "bin/hermes").absolutePath
            val pythonBin = File(usrDir, "bin/python3").absolutePath
            val configFile = File(filesDir, DAEMON_CONFIG_FILENAME).absolutePath
            val tmpDir = File(filesDir, "tmp").apply { if (!exists()) mkdirs() }

            val env = mutableMapOf(
                "HOME" to filesDir.absolutePath,
                "PREFIX" to usrDir.absolutePath,
                "PATH" to "${usrDir.absolutePath}/bin:/system/bin:/system/xbin",
                "TMPDIR" to tmpDir.absolutePath,
                "PYTHONHOME" to usrDir.absolutePath,
                "PYTHONPATH" to "${usrDir.absolutePath}/lib/python3.11/site-packages",
                "HERMES_CONFIG_PATH" to configFile
            )
            env.putAll(customEnv)

            val useProot = File(prootBin).exists() && File(prootBin).canExecute()
            val executable = if (useProot) prootBin else if (File(hermesBin).exists()) hermesBin else pythonBin
            val arguments = if (useProot) {
                listOf(
                    "-r", usrDir.absolutePath,
                    "-0",
                    "-b", "/dev",
                    "-b", "/proc",
                    "-b", filesDir.absolutePath,
                    "-w", filesDir.absolutePath,
                    hermesBin,
                    "gateway", "run"
                )
            } else if (File(hermesBin).exists()) {
                listOf("gateway", "run")
            } else {
                listOf("-m", "hermes", "gateway", "run")
            }

            return ProcessConfig(
                executable = executable,
                arguments = arguments,
                workingDir = filesDir,
                environment = env,
                redirectErrorStream = false
            )
        }
    }
}

/**
 * Runner interface abstracting ProcessBuilder for dependency injection and unit testing.
 */
interface ProcessRunner {
    fun run(config: ProcessConfig): Process
}

/**
 * Default process runner executing native ProcessBuilder commands.
 */
class DefaultProcessRunner : ProcessRunner {
    override fun run(config: ProcessConfig): Process {
        val pb = ProcessBuilder(config.fullCommand)
        if (config.workingDir != null) {
            pb.directory(config.workingDir)
        }
        if (config.environment.isNotEmpty()) {
            pb.environment().putAll(config.environment)
        }
        pb.redirectErrorStream(config.redirectErrorStream)
        return pb.start()
    }
}

/**
 * Contract for native child process lifecycle management.
 */
interface ProcessControllerInterface {
    val state: StateFlow<ProcessState>
    val pid: Long?
    val exitCode: Int?
    val stdout: InputStream?
    val stderr: InputStream?
    val stdin: OutputStream?
    val isAlive: Boolean

    suspend fun start(config: ProcessConfig): Result<Long>
    suspend fun stop(timeoutMs: Long = ProcessController.DEFAULT_SIGKILL_TIMEOUT_MS): ProcessStopResult
    suspend fun waitForExit(): Int?
    fun addExitListener(listener: (Int) -> Unit)
    fun removeExitListener(listener: (Int) -> Unit)
    fun close() {}
}

/**
 * Manages the lifecycle of child processes (PRoot / Python / Hermes daemon), providing
 * non-blocking coroutine execution, PID extraction, asynchronous exit observation,
 * and graceful SIGTERM termination with a 5-second SIGKILL timeout fallback.
 */
open class ProcessController(
    val filesDir: File? = null,
    private val processRunner: ProcessRunner = DefaultProcessRunner(),
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) : ProcessControllerInterface {

    constructor(context: Context) : this(
        filesDir = context.filesDir,
        processRunner = DefaultProcessRunner(),
        ioDispatcher = Dispatchers.IO
    )

    companion object {
        const val TAG = "ProcessController"
        const val DEFAULT_SIGKILL_TIMEOUT_MS = 5000L
    }

    private val _state = MutableStateFlow(ProcessState.STOPPED)
    override val state: StateFlow<ProcessState> = _state.asStateFlow()

    private var activeProcess: Process? = null

    private var _pid: Long? = null
    override val pid: Long?
        get() = _pid

    private var _exitCode: Int? = null
    override val exitCode: Int?
        get() = _exitCode

    private var _stdout: InputStream? = null
    override val stdout: InputStream?
        get() = _stdout

    private var _stderr: InputStream? = null
    override val stderr: InputStream?
        get() = _stderr

    private var _stdin: OutputStream? = null
    override val stdin: OutputStream?
        get() = _stdin

    override val isAlive: Boolean
        get() = activeProcess?.isAlive == true

    private val exitListeners = CopyOnWriteArrayList<(Int) -> Unit>()
    private val controllerScope = CoroutineScope(ioDispatcher + SupervisorJob())
    private var watcherJob: Job? = null

    override suspend fun start(config: ProcessConfig): Result<Long> = withContext(ioDispatcher) {
        val currentState = _state.value
        if (currentState == ProcessState.RUNNING || currentState == ProcessState.STARTING || currentState == ProcessState.STOPPING) {
            return@withContext Result.failure(
                IllegalStateException("Process is already in state: $currentState")
            )
        }

        _state.value = ProcessState.STARTING
        _exitCode = null

        try {
            val process = processRunner.run(config)
            activeProcess = process

            val extractedPid = extractPid(process)
            _pid = extractedPid

            _stdout = process.inputStream
            _stderr = process.errorStream
            _stdin = process.outputStream

            _state.value = ProcessState.RUNNING
            try {
                Log.i(TAG, "Process started successfully (PID: $extractedPid, command: ${config.fullCommand.firstOrNull()})")
            } catch (ignored: Throwable) {}

            // Launch exit watcher
            watcherJob?.cancel()
            watcherJob = controllerScope.launch {
                try {
                    val code = withContext(ioDispatcher) {
                        process.waitFor()
                    }
                    _exitCode = code
                    handleProcessExit(code)
                } catch (e: CancellationException) {
                    // Cancelled during normal stop operation
                } catch (e: Throwable) {
                    try {
                        Log.w(TAG, "Exception in process exit watcher: ${e.message}")
                    } catch (ignored: Throwable) {}
                }
            }

            Result.success(extractedPid ?: 0L)
        } catch (e: Throwable) {
            _state.value = ProcessState.ERROR
            closeStreams()
            activeProcess = null
            _pid = null
            try {
                Log.e(TAG, "Failed to start process: ${e.message}", e)
            } catch (ignored: Throwable) {}
            Result.failure(e)
        }
    }

    override suspend fun stop(timeoutMs: Long): ProcessStopResult = withContext(ioDispatcher) {
        val process = activeProcess
        if (process == null || !process.isAlive) {
            _state.value = ProcessState.STOPPED
            closeStreams()
            activeProcess = null
            _pid = null
            return@withContext ProcessStopResult.ALREADY_STOPPED
        }

        _state.value = ProcessState.STOPPING
        watcherJob?.cancel()
        watcherJob = null

        // 1. Send SIGTERM (destroy())
        try {
            process.destroy()
            try {
                Log.i(TAG, "Sent SIGTERM (destroy) to process (PID: $_pid)")
            } catch (ignored: Throwable) {}
        } catch (e: Throwable) {
            try {
                Log.w(TAG, "Failed to send SIGTERM: ${e.message}")
            } catch (ignored: Throwable) {}
        }

        // 2. Wait up to timeoutMs for process to terminate cleanly
        val gracefulExit = waitForProcessTermination(process, timeoutMs)

        val result = if (gracefulExit) {
            try {
                Log.i(TAG, "Process terminated gracefully with SIGTERM")
            } catch (ignored: Throwable) {}
            ProcessStopResult.GRACEFUL_SIGTERM
        } else {
            // 3. Fallback SIGKILL (destroyForcibly())
            try {
                Log.w(TAG, "Process did not terminate within ${timeoutMs}ms. Sending fallback SIGKILL (destroyForcibly)...")
            } catch (ignored: Throwable) {}
            try {
                process.destroyForcibly()
            } catch (e: Throwable) {
                try {
                    Log.e(TAG, "Failed to send SIGKILL: ${e.message}", e)
                } catch (ignored: Throwable) {}
            }
            waitForProcessTermination(process, 1000L)
            ProcessStopResult.FORCED_SIGKILL
        }

        _exitCode = try {
            process.exitValue()
        } catch (e: Throwable) {
            null
        }

        closeStreams()
        activeProcess = null
        _state.value = ProcessState.STOPPED
        _pid = null

        result
    }

    override suspend fun waitForExit(): Int? = withContext(ioDispatcher) {
        val process = activeProcess ?: return@withContext _exitCode
        try {
            val code = process.waitFor()
            _exitCode = code
            code
        } catch (e: Throwable) {
            null
        }
    }

    override fun addExitListener(listener: (Int) -> Unit) {
        exitListeners.add(listener)
    }

    override fun removeExitListener(listener: (Int) -> Unit) {
        exitListeners.remove(listener)
    }

    override fun close() {
        watcherJob?.cancel()
        watcherJob = null
        closeStreams()
    }

    private suspend fun waitForProcessTermination(process: Process, timeoutMs: Long): Boolean = withContext(ioDispatcher) {
        if (!process.isAlive) return@withContext true
        try {
            process.waitFor(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)
        } catch (e: Throwable) {
            !process.isAlive
        }
    }

    private fun handleProcessExit(code: Int) {
        val currentState = _state.value
        if (currentState == ProcessState.RUNNING) {
            _state.value = ProcessState.TERMINATED
            closeStreams()
            activeProcess = null
            try {
                Log.w(TAG, "Sub-process exited unexpectedly with exit code $code")
            } catch (ignored: Throwable) {}
            for (listener in exitListeners) {
                try {
                    listener.invoke(code)
                } catch (e: Throwable) {
                    try {
                        Log.e(TAG, "Error invoking exit listener: ${e.message}", e)
                    } catch (ignored: Throwable) {}
                }
            }
        }
    }

    private fun closeStreams() {
        try {
            _stdin?.close()
        } catch (ignored: Throwable) {}
        _stdin = null

        try {
            _stdout?.close()
        } catch (ignored: Throwable) {}
        _stdout = null

        try {
            _stderr?.close()
        } catch (ignored: Throwable) {}
        _stderr = null
    }

    /**
     * Extracts PID using Java 9+ Process.pid() or reflection across Android / Linux JVM implementations.
     */
    fun extractPid(process: Process): Long? {
        try {
            val pidMethod = process.javaClass.getMethod("pid")
            val result = pidMethod.invoke(process)
            if (result is Number) return result.toLong()
        } catch (ignored: Throwable) {}

        try {
            val field = process.javaClass.getDeclaredField("pid")
            field.isAccessible = true
            val result = field.get(process)
            if (result is Number) return result.toLong()
        } catch (ignored: Throwable) {}

        return null
    }
}
