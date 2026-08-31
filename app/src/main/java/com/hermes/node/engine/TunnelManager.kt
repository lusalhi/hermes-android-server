package com.hermes.node.engine

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

    override suspend fun start(port: Int): Result<String> = withContext(ioDispatcher) {
        if (_state.value is TunnelState.Running) {
            val currentUrl = _tunnelUrl.value
            if (currentUrl != null) {
                return@withContext Result.success(currentUrl)
            }
        }

        stopInternal(preserveError = false)

        isIntentionalStop = false
        _state.value = TunnelState.Starting
        _tunnelUrl.value = null

        val cloudflaredBinary = resolveCloudflaredBinary()
        val targetPort = if (port in 1..65535) port else 8000

        val config = ProcessConfig(
            executable = cloudflaredBinary.absolutePath,
            arguments = listOf("tunnel", "--url", "http://127.0.0.1:$targetPort", "--no-autoupdate"),
            workingDir = filesDir,
            redirectErrorStream = true
        )

        try {
            val process = processRunner.run(config)
            activeProcess = process

            val urlDeferred = kotlinx.coroutines.CompletableDeferred<String>()

            processWatcherJob = scope.launch {
                val stream = process.inputStream
                try {
                    val reader = BufferedReader(InputStreamReader(stream, Charsets.UTF_8))
                    var line: String? = null
                    while (reader.readLine().also { line = it } != null) {
                        val currentLine = line ?: continue
                        val extracted = extractTunnelUrl(currentLine)
                        if (extracted != null && !urlDeferred.isCompleted) {
                            urlDeferred.complete(extracted)
                        }
                    }
                } catch (e: Throwable) {
                    if (e is CancellationException) throw e
                } finally {
                    try {
                        stream.close()
                    } catch (_: Throwable) {}
                    if (!urlDeferred.isCompleted) {
                        urlDeferred.completeExceptionally(
                            IllegalStateException("cloudflared process terminated before URL was discovered")
                        )
                    }
                    handleProcessExit()
                }
            }

            val discoveredUrl = try {
                withTimeoutOrNull(urlDiscoveryTimeoutMs) {
                    urlDeferred.await()
                }
            } catch (e: Throwable) {
                if (e is CancellationException) {
                    stopInternal(preserveError = false)
                    throw e
                }
                null
            }

            if (discoveredUrl != null) {
                _tunnelUrl.value = discoveredUrl
                _state.value = TunnelState.Running(discoveredUrl)
                Result.success(discoveredUrl)
            } else {
                val errorMsg = "Timed out waiting for Cloudflare Tunnel URL after ${urlDiscoveryTimeoutMs / 1000}s"
                stopInternal(preserveError = true)
                _state.value = TunnelState.Error(errorMsg)
                Result.failure(IllegalStateException(errorMsg))
            }
        } catch (e: Throwable) {
            if (e is CancellationException) throw e
            val errorMsg = "Failed to start Cloudflare Tunnel: ${e.message}"
            stopInternal(preserveError = true)
            _state.value = TunnelState.Error(errorMsg)
            _tunnelUrl.value = null
            Result.failure(e)
        }
    }

    override suspend fun stop(timeoutMs: Long): Result<Unit> = withContext(ioDispatcher) {
        stopInternal(preserveError = false, timeoutMs = timeoutMs)
        Result.success(Unit)
    }

    private suspend fun stopInternal(preserveError: Boolean, timeoutMs: Long = 5000L) {
        isIntentionalStop = !preserveError
        val process = activeProcess
        processWatcherJob?.cancel()
        processWatcherJob = null

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
        }
        activeProcess = null
        if (!preserveError) {
            _state.value = TunnelState.Stopped
        }
        _tunnelUrl.value = null
    }

    private fun handleProcessExit() {
        if (isIntentionalStop) return
        if (_state.value is TunnelState.Running || _state.value is TunnelState.Starting) {
            val exitVal = try {
                activeProcess?.exitValue()
            } catch (e: Throwable) {
                null
            }
            if (exitVal != null && exitVal != 0) {
                _state.value = TunnelState.Error("Cloudflare tunnel exited unexpectedly with code $exitVal")
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
        val candidates = listOf(
            File(filesDir, "usr/bin/cloudflared"),
            File(filesDir, "bin/cloudflared"),
            File(filesDir, "cloudflared"),
            File("/system/bin/cloudflared"),
            File("/usr/local/bin/cloudflared"),
            File("/usr/bin/cloudflared")
        )
        return candidates.firstOrNull { it.exists() && it.canExecute() }
            ?: File(filesDir, "usr/bin/cloudflared")
    }

    companion object {
        private const val TAG = "TunnelManager"
        private val TUNNEL_URL_REGEX = Regex("""https://[a-zA-Z0-9.-]+\.trycloudflare\.com""")

        /**
         * Extracts trycloudflare.com URL from log output line.
         */
        fun extractTunnelUrl(logLine: String): String? {
            return TUNNEL_URL_REGEX.find(logLine)?.value
        }
    }
}
