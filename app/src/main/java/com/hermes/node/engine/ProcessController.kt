package com.hermes.node.engine

import android.content.Context
import android.util.Log
import com.hermes.node.data.ConfigSerializer
import com.hermes.node.data.model.HermesConfig
import com.hermes.node.data.model.SkillsConfig
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
            customEnv: Map<String, String> = emptyMap(),
            hermesConfig: HermesConfig? = null
        ): ProcessConfig {
            val usrDir = File(filesDir, BootstrapExtractor.USR_DIR_NAME)
            val prootBin = File(usrDir, "bin/proot").absolutePath
            val hermesBin = File(usrDir, "bin/hermes").absolutePath
            val pythonBin = File(usrDir, "bin/python3").absolutePath
            val configFile = File(filesDir, DAEMON_CONFIG_FILENAME).absolutePath
            val tmpDir = File(filesDir, "tmp").apply { if (!exists()) mkdirs() }

            val effectiveConfig = hermesConfig ?: run {
                val cFile = File(configFile)
                if (cFile.exists()) {
                    ConfigSerializer(cFile).deserialize().getOrNull() ?: HermesConfig()
                } else {
                    HermesConfig()
                }
            }

            val env = mutableMapOf(
                "HOME" to filesDir.absolutePath,
                "PREFIX" to usrDir.absolutePath,
                "PATH" to "${usrDir.absolutePath}/bin:/system/bin:/system/xbin",
                "TMPDIR" to tmpDir.absolutePath,
                "PYTHONHOME" to usrDir.absolutePath,
                "PYTHONPATH" to "${usrDir.absolutePath}/lib/python3.11/site-packages",
                "HERMES_CONFIG_PATH" to configFile
            )

            val trimmedSearchKey = effectiveConfig.skills.searchApiKey.trim()
            val rawSearchProvider = effectiveConfig.skills.searchProvider.lowercase().trim()
            val searchProvider = if (rawSearchProvider in SkillsConfig.SUPPORTED_SEARCH_PROVIDERS) {
                rawSearchProvider
            } else {
                SkillsConfig.SEARCH_PROVIDER_BRAVE
            }

            val isSearchActive = effectiveConfig.skills.webSearch && trimmedSearchKey.isNotBlank()
            if (isSearchActive) {
                when (searchProvider) {
                    "brave" -> {
                        env["BRAVE_SEARCH_API_KEY"] = trimmedSearchKey
                        env["BRAVE_API_KEY"] = trimmedSearchKey
                    }
                    "tavily" -> env["TAVILY_API_KEY"] = trimmedSearchKey
                    "firecrawl" -> env["FIRECRAWL_API_KEY"] = trimmedSearchKey
                    "exa" -> env["EXA_API_KEY"] = trimmedSearchKey
                }
                env["HERMES_SEARCH_PROVIDER"] = searchProvider
            }

            env.putAll(customEnv)

            syncHermesConfig(filesDir, effectiveConfig)

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

        /**
         * Synchronizes search provider configuration to ${filesDir}/.hermes/config.yaml
         * and ${filesDir}/.hermes/.env with strict POSIX 0600 permissions.
         */
        fun syncHermesConfig(filesDir: File, config: HermesConfig) {
            try {
                val hermesDir = File(filesDir, ".hermes")
                if (!hermesDir.exists()) {
                    hermesDir.mkdirs()
                }

                val trimmedKey = config.skills.searchApiKey.trim()
                val rawProvider = config.skills.searchProvider.lowercase().trim()
                val provider = if (rawProvider in SkillsConfig.SUPPORTED_SEARCH_PROVIDERS) {
                    rawProvider
                } else {
                    SkillsConfig.SEARCH_PROVIDER_BRAVE
                }

                // Sanitize values (strip CRLF, escape double quotes)
                val safeKey = trimmedKey.replace("\r", "").replace("\n", "").replace("\"", "\\\"")
                val safeProvider = provider.replace("\r", "").replace("\n", "").replace("\"", "\\\"")
                val isSearchActive = config.skills.webSearch && safeKey.isNotBlank()

                // 1. Sync .env (preserve all non-search variables)
                val envFile = File(hermesDir, ".env")
                val existingEnvLines = if (envFile.exists()) {
                    try {
                        envFile.readLines(Charsets.UTF_8).filter { line ->
                            val trimmed = line.trim()
                            !trimmed.startsWith("HERMES_SEARCH_PROVIDER=") &&
                            !trimmed.startsWith("BRAVE_SEARCH_API_KEY=") &&
                            !trimmed.startsWith("BRAVE_API_KEY=") &&
                            !trimmed.startsWith("TAVILY_API_KEY=") &&
                            !trimmed.startsWith("FIRECRAWL_API_KEY=") &&
                            !trimmed.startsWith("EXA_API_KEY=")
                        }
                    } catch (_: Throwable) {
                        emptyList()
                    }
                } else {
                    emptyList()
                }

                val updatedEnvLines = existingEnvLines.toMutableList()
                if (isSearchActive) {
                    updatedEnvLines.add("HERMES_SEARCH_PROVIDER=$safeProvider")
                    when (safeProvider) {
                        "brave" -> {
                            updatedEnvLines.add("BRAVE_SEARCH_API_KEY=$safeKey")
                            updatedEnvLines.add("BRAVE_API_KEY=$safeKey")
                        }
                        "tavily" -> updatedEnvLines.add("TAVILY_API_KEY=$safeKey")
                        "firecrawl" -> updatedEnvLines.add("FIRECRAWL_API_KEY=$safeKey")
                        "exa" -> updatedEnvLines.add("EXA_API_KEY=$safeKey")
                    }
                }

                val envTmp = File(hermesDir, ".env.tmp")
                envTmp.writeText(updatedEnvLines.joinToString("\n") + if (updatedEnvLines.isNotEmpty()) "\n" else "", Charsets.UTF_8)
                ConfigSerializer.applyPosix0600Permissions(envTmp)
                if (!envTmp.renameTo(envFile)) {
                    envTmp.copyTo(envFile, overwrite = true)
                    envTmp.delete()
                }
                ConfigSerializer.applyPosix0600Permissions(envFile)

                // 2. Sync config.yaml (preserve all non-search lines and sections)
                val yamlFile = File(hermesDir, "config.yaml")
                val searchKeys = setOf("search_provider:", "search_api_key:", "web_search:")
                val existingYamlLines = if (yamlFile.exists()) {
                    try {
                        val lines = yamlFile.readLines(Charsets.UTF_8)
                        val filtered = mutableListOf<String>()
                        var skippingWebBlock = false
                        for (line in lines) {
                            val trimmed = line.trim()
                            if (trimmed == "web:" || trimmed.startsWith("web:")) {
                                skippingWebBlock = true
                                continue
                            }
                            if (skippingWebBlock) {
                                if (line.startsWith(" ") || line.startsWith("\t")) {
                                    continue
                                } else {
                                    skippingWebBlock = false
                                }
                            }
                            if (searchKeys.any { trimmed.startsWith(it) }) {
                                continue
                            }
                            filtered.add(line)
                        }
                        while (filtered.isNotEmpty() && filtered.last().isBlank()) {
                            filtered.removeAt(filtered.size - 1)
                        }
                        filtered
                    } catch (_: Throwable) {
                        emptyList()
                    }
                } else {
                    emptyList()
                }

                val yamlContent = buildString {
                    if (existingYamlLines.isNotEmpty()) {
                        append(existingYamlLines.joinToString("\n"))
                        append("\n")
                    }
                    if (isSearchActive) {
                        appendLine("search_provider: \"$safeProvider\"")
                        appendLine("search_api_key: \"$safeKey\"")
                        appendLine("skills:")
                        appendLine("  web_search: true")
                        appendLine("  search_provider: \"$safeProvider\"")
                        appendLine("  search_api_key: \"$safeKey\"")
                        appendLine("web:")
                        appendLine("  provider: \"$safeProvider\"")
                        appendLine("  api_key: \"$safeKey\"")
                    } else {
                        appendLine("skills:")
                        appendLine("  web_search: ${config.skills.webSearch}")
                    }
                }

                val yamlTmp = File(hermesDir, "config.yaml.tmp")
                yamlTmp.writeText(yamlContent, Charsets.UTF_8)
                ConfigSerializer.applyPosix0600Permissions(yamlTmp)
                if (!yamlTmp.renameTo(yamlFile)) {
                    yamlTmp.copyTo(yamlFile, overwrite = true)
                    yamlTmp.delete()
                }
                ConfigSerializer.applyPosix0600Permissions(yamlFile)
            } catch (e: Throwable) {
                try {
                    Log.w("ProcessController", "Failed to synchronize .hermes config: ${e.message}")
                } catch (_: Throwable) {}
            }
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
 * Default process runner executing native ProcessBuilder commands with Android interpreter fallback.
 */
class DefaultProcessRunner : ProcessRunner {
    override fun run(config: ProcessConfig): Process {
        val finalCommand = resolveExecutableCommand(config)
        val pb = ProcessBuilder(finalCommand)
        if (config.workingDir != null) {
            pb.directory(config.workingDir)
        }
        if (config.environment.isNotEmpty()) {
            pb.environment().putAll(config.environment)
        }
        pb.redirectErrorStream(config.redirectErrorStream)
        return pb.start()
    }

    companion object {
        fun resolveExecutableCommand(config: ProcessConfig): List<String> {
            val exeFile = File(config.executable)
            if (!exeFile.exists()) {
                return config.fullCommand
            }

            val isAndroid = File("/system/bin/sh").exists()
            if (isAndroid) {
                // On Android 10+ (API 29+), SELinux blocks direct execve() on files inside /data/user/0/ or /data/data/ (error=13 Permission denied).
                // 1. For scripts / mock binaries, invoke via /system/bin/sh
                // 2. For ELF binaries, invoke via /system/bin/linker64 or /system/bin/linker
                val isElf = isElfBinary(exeFile)
                return if (!isElf) {
                    listOf("/system/bin/sh", config.executable) + config.arguments
                } else {
                    val linker = when {
                        File("/system/bin/linker64").exists() -> "/system/bin/linker64"
                        File("/system/bin/linker").exists() -> "/system/bin/linker"
                        else -> null
                    }
                    if (linker != null) {
                        listOf(linker, config.executable) + config.arguments
                    } else {
                        config.fullCommand
                    }
                }
            }

            // On standard desktop Linux, if script has missing interpreter, use available shell
            if (isScriptWithMissingInterpreter(exeFile)) {
                val availableShell = when {
                    File("/bin/sh").exists() -> "/bin/sh"
                    File("/usr/bin/sh").exists() -> "/usr/bin/sh"
                    else -> null
                }
                if (availableShell != null) {
                    return listOf(availableShell, config.executable) + config.arguments
                }
            }
            return config.fullCommand
        }

        private fun isElfBinary(file: File): Boolean {
            return try {
                if (!file.isFile || file.length() < 4) return false
                file.inputStream().use { stream ->
                    val magic = ByteArray(4)
                    val read = stream.read(magic)
                    read == 4 && magic[0] == 0x7F.toByte() && magic[1] == 'E'.code.toByte() && magic[2] == 'L'.code.toByte() && magic[3] == 'F'.code.toByte()
                }
            } catch (_: Throwable) {
                false
            }
        }

        private fun isScriptWithMissingInterpreter(file: File): Boolean {
            return try {
                if (!file.isFile || file.length() < 2) return false
                file.bufferedReader().use { reader ->
                    val firstLine = reader.readLine() ?: ""
                    if (firstLine.startsWith("#!")) {
                        val interpreter = firstLine.removePrefix("#!").trim().split(" ").firstOrNull() ?: ""
                        interpreter.isNotEmpty() && !File(interpreter).exists()
                    } else {
                        false
                    }
                }
            } catch (_: Throwable) {
                false
            }
        }
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
    val lastErrorMessage: String? get() = null

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

    private var _lastErrorMessage: String? = null
    override val lastErrorMessage: String?
        get() = _lastErrorMessage

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
            val err = "Process is already in state: $currentState"
            _lastErrorMessage = err
            return@withContext Result.failure(
                IllegalStateException(err)
            )
        }

        _state.value = ProcessState.STARTING
        _exitCode = null
        _lastErrorMessage = null

        val exeFile = File(config.executable)
        try {
            Log.i(
                TAG,
                "Starting process: ${config.executable} (args=${config.arguments.joinToString(" ")}, exists=${exeFile.exists()}, canExecute=${exeFile.canExecute()}, size=${if (exeFile.exists()) exeFile.length() else 0}B, workingDir=${config.workingDir?.absolutePath})"
            )
        } catch (_: Throwable) {}

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
            val diagnostic = "Failed to launch '${config.executable}': [${e.javaClass.simpleName}] ${e.message} (file exists=${exeFile.exists()}, canExecute=${exeFile.canExecute()})"
            _lastErrorMessage = diagnostic
            try {
                Log.e(TAG, diagnostic, e)
            } catch (ignored: Throwable) {}
            Result.failure(Exception(diagnostic, e))
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
            if (code != 0) {
                _lastErrorMessage = "Sub-process exited with exit code $code"
            }
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
