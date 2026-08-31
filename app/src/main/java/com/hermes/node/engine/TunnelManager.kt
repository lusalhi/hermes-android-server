package com.hermes.node.engine

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.util.concurrent.TimeUnit

/**
 * Lifecycle states of the Cloudflare public tunnel sidecar process.
 */
sealed class TunnelState {
    data object Stopped : TunnelState()
    data object Starting : TunnelState()
    data class Running(val url: String) : TunnelState()
    data class Error(val message: String) : TunnelState()
}

/**
 * Contract for managing the Cloudflare Tunnel sidecar process.
 */
interface TunnelManagerInterface {
    val state: StateFlow<TunnelState>
    val tunnelUrl: StateFlow<String?>
    val isRunning: Boolean

    suspend fun start(port: Int = 8000): Result<String>
    suspend fun stop(timeoutMs: Long = 5000L): Result<Unit>
}

/**
 * Manages the secondary independent sidecar process executing the cloudflared ARM64 binary.
 *
 * Spawns cloudflared, streams process standard output and error, parses the ephemeral
 * https://[subdomain].trycloudflare.com URL with regular expressions, and maintains the active tunnel state.
 */
class CloudflareTunnelManager(
    private val filesDir: File = File("."),
    private val processRunner: ProcessRunner = DefaultProcessRunner(),
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val urlDiscoveryTimeoutMs: Long = 30000L
) : TunnelManagerInterface {

    private val _state = MutableStateFlow<TunnelState>(TunnelState.Stopped)
    override val state: StateFlow<TunnelState> = _state.asStateFlow()

    private val _tunnelUrl = MutableStateFlow<String?>(null)
    override val tunnelUrl: StateFlow<String?> = _tunnelUrl.asStateFlow()

    override val isRunning: Boolean
        get() = _state.value is TunnelState.Running

    private var activeProcess: Process? = null
    private var processWatcherJob: Job? = null
    private var isIntentionalStop = false
    private var scope = CoroutineScope(ioDispatcher + SupervisorJob())
    private val lifecycleMutex = Mutex()
    private var generation: Long = 0L

    override suspend fun start(port: Int): Result<String> = withContext(ioDispatcher) {
        // Phase 1: synchronized setup
        val setup: Triple<Process, CompletableDeferred<String>, Long>
        try {
            setup = lifecycleMutex.withLock {
                if (_state.value is TunnelState.Running) {
                    val currentUrl = _tunnelUrl.value
                    if (currentUrl != null) {
                        return@withContext Result.success(currentUrl)
                    }
                }
                stopInternalLocked(preserveError = false)
                isIntentionalStop = false
                _state.value = TunnelState.Starting
                _tunnelUrl.value = null
                val cloudflaredBinary = resolveCloudflaredBinary()
                val targetPort = if (port in 1..65535) port else 8000
                val config = ProcessConfig(
                    executable = cloudflaredBinary.absolutePath,
                    arguments = listOf("tunnel", "--url", "http://127.0.0.1:$targetPort", "--no-autoupdate"),
                    workingDir = filesDir,
                    redirectErrorStream = false
                )
                val process = processRunner.run(config)
                activeProcess = process
                val myGeneration = ++generation
                val urlDeferred = CompletableDeferred<String>()
                processWatcherJob = scope.launch {
                    val stdoutJob = launch {
                        try {
                            BufferedReader(InputStreamReader(process.inputStream, Charsets.UTF_8)).use { reader ->
                                var line: String?
                                while (reader.readLine().also { line = it } != null) {
                                    val extracted = extractTunnelUrl(line!!)
                                    if (extracted != null && !urlDeferred.isCompleted) {
                                        urlDeferred.complete(extracted)
                                    }
                                }
                            }
                        } catch (e: CancellationException) {
                            throw e
                        } catch (_: Throwable) {}
                    }
                    val stderrJob = launch {
                        try {
                            BufferedReader(InputStreamReader(process.errorStream, Charsets.UTF_8)).use { reader ->
                                var line: String?
                                while (reader.readLine().also { line = it } != null) {
                                    val extracted = extractTunnelUrl(line!!)
                                    if (extracted != null && !urlDeferred.isCompleted) {
                                        urlDeferred.complete(extracted)
                                    }
                                }
                            }
                        } catch (e: CancellationException) {
                            throw e
                        } catch (_: Throwable) {}
                    }
                    try {
                        stdoutJob.join()
                        stderrJob.join()
                    } finally {
                        stdoutJob.cancel()
                        stderrJob.cancel()
                        try { process.inputStream.close() } catch (_: Throwable) {}
                        try { process.errorStream.close() } catch (_: Throwable) {}
                        if (!urlDeferred.isCompleted) {
                            urlDeferred.completeExceptionally(
                                IllegalStateException("cloudflared process terminated before URL was discovered")
                            )
                        }
                        if (myGeneration == generation) {
                            scope.launch { handleProcessExit() }
                        }
                    }
                }
                Triple(process, urlDeferred, myGeneration)
            }
        } catch (e: Throwable) {
            if (e is CancellationException) throw e
            lifecycleMutex.withLock {
                val errorMsg = "Failed to start Cloudflare Tunnel: ${e.message}"
                stopInternalLocked(preserveError = true)
                _state.value = TunnelState.Error(errorMsg)
                _tunnelUrl.value = null
            }
            return@withContext Result.failure(e)
        }
        val (process, urlDeferred, myGeneration) = setup
        // Phase 2: await URL outside lock
        var discoveryException: Throwable? = null
        val discoveredUrl: String? = try {
            withTimeoutOrNull(urlDiscoveryTimeoutMs) {
                try {
                    urlDeferred.await()
                } catch (e: Throwable) {
                    if (e is CancellationException) throw e
                    discoveryException = e
                    null
                }
            }
        } catch (e: CancellationException) {
            lifecycleMutex.withLock { stopInternalLocked(preserveError = false) }
            throw e
        }
        // Phase 3: synchronized completion with generation check
        return@withContext lifecycleMutex.withLock {
            if (myGeneration != generation) {
                return@withLock Result.failure(CancellationException("Tunnel generation superseded"))
            }
            if (discoveredUrl != null) {
                if (!process.isAlive) {
                    val exitVal = try { process.exitValue() } catch (_: Throwable) { null }
                    val errMsg = if (discoveryException != null) {
                        "Cloudflare tunnel exited before URL could be used: ${discoveryException?.message} (exit=$exitVal)"
                    } else if (exitVal != null && exitVal != 0) {
                        "Cloudflare tunnel exited unexpectedly with code $exitVal after publishing URL"
                    } else {
                        "Cloudflare tunnel terminated immediately after publishing URL"
                    }
                    _state.value = TunnelState.Error(errMsg)
                    _tunnelUrl.value = null
                    stopInternalLocked(preserveError = true)
                    Result.failure(IllegalStateException(errMsg))
                } else {
                    _tunnelUrl.value = discoveredUrl
                    _state.value = TunnelState.Running(discoveredUrl)
                    Result.success(discoveredUrl)
                }
            } else {
                if (discoveryException != null) {
                    val errMsg = "Cloudflare tunnel terminated before URL discovery: ${discoveryException?.message}"
                    stopInternalLocked(preserveError = true)
                    _state.value = TunnelState.Error(errMsg)
                    Result.failure(discoveryException ?: IllegalStateException(errMsg))
                } else {
                    val errorMsg = "Timed out waiting for Cloudflare Tunnel URL after ${urlDiscoveryTimeoutMs / 1000}s"
                    stopInternalLocked(preserveError = true)
                    _state.value = TunnelState.Error(errorMsg)
                    Result.failure(IllegalStateException(errorMsg))
                }
            }
        }
    }

    override suspend fun stop(timeoutMs: Long): Result<Unit> = withContext(ioDispatcher) {
        var stillAlive = false
        lifecycleMutex.withLock {
            stillAlive = stopInternalLocked(preserveError = false, timeoutMs = timeoutMs)
        }
        if (stillAlive) {
            Result.failure(IllegalStateException("Tunnel process still alive after SIGTERM/SIGKILL"))
        } else {
            Result.success(Unit)
        }
    }

    private suspend fun stopInternal(preserveError: Boolean, timeoutMs: Long = 5000L) {
        lifecycleMutex.withLock {
            stopInternalLocked(preserveError, timeoutMs)
        }
    }

    private suspend fun stopInternalLocked(preserveError: Boolean, timeoutMs: Long = 5000L): Boolean {
        isIntentionalStop = !preserveError
        generation++
        val process = activeProcess
        processWatcherJob?.cancel()
        processWatcherJob = null

        var stillAlive = false
        if (process != null && process.isAlive) {
            try {
                process.destroy()
                val terminated = waitForTermination(process, timeoutMs)
                if (!terminated) {
                    process.destroyForcibly()
                    waitForTermination(process, 1000L)
                }
            } catch (e: Throwable) {
                try {
                    process.destroyForcibly()
                } catch (_: Throwable) {}
            }
            stillAlive = process.isAlive
        }
        // Retain handle if still alive so caller can retry — do not orphan
        if (!stillAlive) {
            activeProcess = null
        }
        if (!preserveError) {
            if (stillAlive) {
                _state.value = TunnelState.Error("Tunnel process still alive after SIGTERM/SIGKILL")
            } else {
                _state.value = TunnelState.Stopped
                activeProcess = null
            }
        } else {
            if (!stillAlive) activeProcess = null
        }
        _tunnelUrl.value = null
        return stillAlive
    }

    private suspend fun handleProcessExit() {
        lifecycleMutex.withLock {
            handleProcessExitLocked()
        }
    }

    private fun handleProcessExitLocked() {
        if (isIntentionalStop) return
        if (_state.value is TunnelState.Running || _state.value is TunnelState.Starting) {
            val exitVal = try {
                activeProcess?.exitValue()
            } catch (e: Throwable) {
                null
            }
            if (exitVal != null && exitVal != 0) {
                _state.value = TunnelState.Error("Cloudflare tunnel exited unexpectedly with code $exitVal")
            } else if (_state.value is TunnelState.Starting) {
                _state.value = TunnelState.Error("Cloudflare tunnel terminated before URL was discovered")
            } else {
                _state.value = TunnelState.Stopped
            }
            _tunnelUrl.value = null
        }
    }

    private suspend fun waitForTermination(process: Process, timeoutMs: Long): Boolean = withContext(ioDispatcher) {
        if (!process.isAlive) return@withContext true
        try {
            process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)
        } catch (e: Throwable) {
            !process.isAlive
        }
    }

    private fun resolveCloudflaredBinary(): File {
        val canonical = File(filesDir, "usr/bin/cloudflared")
        return canonical
    }

    companion object {
        private const val TAG = "TunnelManager"
        private val TUNNEL_URL_REGEX = Regex("""https://[a-zA-Z0-9.-]+\.trycloudflare\.com""")

        fun extractTunnelUrl(logLine: String): String? {
            return TUNNEL_URL_REGEX.find(logLine)?.value
        }
    }
}
