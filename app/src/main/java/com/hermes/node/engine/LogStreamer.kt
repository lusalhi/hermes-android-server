package com.hermes.node.engine

import com.hermes.node.viewmodel.LogEntry
import com.hermes.node.viewmodel.LogLevel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStream
import java.io.InputStreamReader
import java.nio.charset.CodingErrorAction

/**
 * Interface contract for asynchronous process log streaming.
 */
interface LogStreamerInterface {
    val logsFlow: StateFlow<List<LogEntry>>
    val capacity: Int
    val isStreaming: Boolean

    fun start(stdout: InputStream?, stderr: InputStream?)
    fun stop()
    fun clear()
    fun append(entry: LogEntry)
    fun append(message: String, level: LogLevel = LogLevel.INFO)
    fun getLogs(): List<LogEntry>
}

/**
 * Asynchronous process I/O streamer that reads stdout/stderr streams on Dispatchers.IO,
 * removes ANSI escape sequences, parses log severity levels, stores entries in a bounded
 * RingBuffer (default 2,000 lines), and emits updates via StateFlow.
 */
open class LogStreamer(
    override val capacity: Int = RingBuffer.DEFAULT_CAPACITY,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val buffer: RingBuffer<LogEntry> = RingBuffer(capacity)
) : LogStreamerInterface {

    private val _logsFlow = MutableStateFlow<List<LogEntry>>(buffer.toList())
    override val logsFlow: StateFlow<List<LogEntry>> = _logsFlow.asStateFlow()

    private var streamerScope: CoroutineScope? = null
    private var stdoutJob: Job? = null
    private var stderrJob: Job? = null
    private var stdoutStream: InputStream? = null
    private var stderrStream: InputStream? = null

    @Volatile
    private var _isStreaming = false
    override val isStreaming: Boolean
        get() = _isStreaming

    @Synchronized
    override fun start(stdout: InputStream?, stderr: InputStream?) {
        stop()

        if (stdout == null && stderr == null) {
            return
        }

        stdoutStream = stdout
        stderrStream = stderr
        val scope = CoroutineScope(ioDispatcher + SupervisorJob())
        streamerScope = scope
        _isStreaming = true

        if (stdout != null) {
            stdoutJob = scope.launch(ioDispatcher) {
                readStream(stdout, isStderr = false)
            }
        }

        if (stderr != null) {
            stderrJob = scope.launch(ioDispatcher) {
                readStream(stderr, isStderr = true)
            }
        }
    }

    @Synchronized
    override fun stop() {
        _isStreaming = false
        stdoutJob?.cancel()
        stdoutJob = null
        stderrJob?.cancel()
        stderrJob = null
        try {
            stdoutStream?.close()
        } catch (ignored: Throwable) {}
        stdoutStream = null
        try {
            stderrStream?.close()
        } catch (ignored: Throwable) {}
        stderrStream = null
        try {
            streamerScope?.cancel()
        } catch (ignored: Throwable) {}
        streamerScope = null
    }

    @Synchronized
    override fun clear() {
        buffer.clear()
        _logsFlow.value = emptyList()
    }

    @Synchronized
    override fun append(entry: LogEntry) {
        buffer.add(entry)
        _logsFlow.value = buffer.toList()
    }

    override fun append(message: String, level: LogLevel) {
        append(LogEntry(message = message, level = level))
    }

    @Synchronized
    override fun getLogs(): List<LogEntry> {
        return buffer.toList()
    }

    private suspend fun readStream(stream: InputStream, isStderr: Boolean) {
        val decoder = Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPLACE)
            .onUnmappableCharacter(CodingErrorAction.REPLACE)

        val reader = BufferedReader(InputStreamReader(stream, decoder))

        try {
            while (currentCoroutineContext().isActive) {
                val line = try {
                    reader.readLine()
                } catch (e: IOException) {
                    null
                } ?: break // EOF reached

                val cleanedMessage = stripAnsi(line)
                val level = parseLogLevel(cleanedMessage, isStderr)
                val entry = LogEntry(message = cleanedMessage, level = level)
                append(entry)
            }
        } catch (e: CancellationException) {
            // Normal coroutine cancellation
        } catch (e: IOException) {
            // Stream closed
        } catch (e: Throwable) {
            // Catch-all to prevent unhandled exceptions
        } finally {
            try {
                withContext(NonCancellable + ioDispatcher) {
                    reader.close()
                }
            } catch (ignored: Throwable) {}
        }
    }

    companion object {
        private val ANSI_REGEX = Regex("\u001B\\[[0-9;?]*[ -/]*[@-~]")

        /**
         * Strips ANSI escape sequences from the string.
         */
        fun stripAnsi(text: String): String {
            return ANSI_REGEX.replace(text, "")
        }

        /**
         * Parses the log severity level based on line content heuristics and stream origin.
         *
         * Heuristics:
         * - ERROR: contains [ERROR], ERROR:, FATAL, Exception, Traceback, Error: or unformatted stderr
         * - WARN: contains [WARN], [WARNING], WARNING:, WARN:
         * - DEBUG: contains [DEBUG], DEBUG:
         * - INFO: contains [INFO], INFO: or default for stdout
         */
        fun parseLogLevel(line: String, isStderr: Boolean = false): LogLevel {
            val upper = line.uppercase()
            return when {
                upper.contains("[ERROR]") ||
                upper.contains("ERROR:") ||
                upper.contains("FATAL") ||
                upper.contains("EXCEPTION") ||
                upper.contains("TRACEBACK") ||
                upper.contains("ERROR: ") ||
                upper.contains(" ERROR ") -> LogLevel.ERROR

                upper.contains("[WARN]") ||
                upper.contains("[WARNING]") ||
                upper.contains("WARNING:") ||
                upper.contains("WARN:") ||
                upper.contains(" WARNING ") ||
                upper.contains(" WARN ") -> LogLevel.WARN

                upper.contains("[DEBUG]") ||
                upper.contains("DEBUG:") ||
                upper.contains(" DEBUG ") -> LogLevel.DEBUG

                upper.contains("[INFO]") ||
                upper.contains("INFO:") ||
                upper.contains(" INFO ") -> LogLevel.INFO

                isStderr -> LogLevel.ERROR

                else -> LogLevel.INFO
            }
        }
    }
}
