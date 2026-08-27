package com.hermes.node.engine

import com.hermes.node.viewmodel.LogEntry
import com.hermes.node.viewmodel.LogLevel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

@OptIn(ExperimentalCoroutinesApi::class)
class LogStreamerTest {

    private val testDispatcher = StandardTestDispatcher()

    @Test
    fun stripAnsi_removesVariousAnsiEscapeSequences() {
        assertEquals(
            "[INFO] Model loaded",
            LogStreamer.stripAnsi("\u001B[32m[INFO]\u001B[0m Model loaded")
        )
        assertEquals(
            "[WARN] High memory usage",
            LogStreamer.stripAnsi("\u001B[1;33m[WARN]\u001B[0m High memory usage")
        )
        assertEquals(
            "[ERROR] Connection failed",
            LogStreamer.stripAnsi("\u001B[31;1m[ERROR]\u001B[0m Connection failed")
        )
        assertEquals(
            "Starting server...",
            LogStreamer.stripAnsi("\u001B[2J\u001B[HStarting server...")
        )
        assertEquals(
            "Plain uncolored text",
            LogStreamer.stripAnsi("Plain uncolored text")
        )
    }

    @Test
    fun parseLogLevel_categorizesSeverityCorrectly() {
        // INFO
        assertEquals(
            LogLevel.INFO,
            LogStreamer.parseLogLevel("2026-08-26 [INFO] Gateway started on port 8000", isStderr = false)
        )
        assertEquals(
            LogLevel.INFO,
            LogStreamer.parseLogLevel("Plain stdout message without tags", isStderr = false)
        )
        assertEquals(
            LogLevel.INFO,
            LogStreamer.parseLogLevel("[INFO] Output on stderr", isStderr = true)
        )

        // WARN
        assertEquals(
            LogLevel.WARN,
            LogStreamer.parseLogLevel("[WARN] High memory usage", isStderr = false)
        )
        assertEquals(
            LogLevel.WARN,
            LogStreamer.parseLogLevel("[WARNING] CPU throttling detected", isStderr = false)
        )
        assertEquals(
            LogLevel.WARN,
            LogStreamer.parseLogLevel("WARNING: Disk space below 10%", isStderr = false)
        )
        assertEquals(
            LogLevel.WARN,
            LogStreamer.parseLogLevel("WARN: Connection slow", isStderr = false)
        )
        assertEquals(
            LogLevel.WARN,
            LogStreamer.parseLogLevel("[WARN] High memory usage on stderr", isStderr = true)
        )

        // ERROR
        assertEquals(
            LogLevel.ERROR,
            LogStreamer.parseLogLevel("[ERROR] Connection failed", isStderr = false)
        )
        assertEquals(
            LogLevel.ERROR,
            LogStreamer.parseLogLevel("ERROR: Port 8000 already in use", isStderr = false)
        )
        assertEquals(
            LogLevel.ERROR,
            LogStreamer.parseLogLevel("FATAL: Out of memory", isStderr = false)
        )
        assertEquals(
            LogLevel.ERROR,
            LogStreamer.parseLogLevel("java.lang.NullPointerException Exception in thread main", isStderr = false)
        )
        assertEquals(
            LogLevel.ERROR,
            LogStreamer.parseLogLevel("Traceback (most recent call last):", isStderr = false)
        )
        assertEquals(
            LogLevel.ERROR,
            LogStreamer.parseLogLevel("Error: Failed to bind socket", isStderr = false)
        )

        // Raw stderr fallback to ERROR
        assertEquals(
            LogLevel.ERROR,
            LogStreamer.parseLogLevel("Random unformatted output on stderr", isStderr = true)
        )
        assertEquals(
            LogLevel.ERROR,
            LogStreamer.parseLogLevel("Traceback (most recent call last):", isStderr = true)
        )

        // DEBUG
        assertEquals(
            LogLevel.DEBUG,
            LogStreamer.parseLogLevel("[DEBUG] Loading tool definitions", isStderr = false)
        )
        assertEquals(
            LogLevel.DEBUG,
            LogStreamer.parseLogLevel("DEBUG: Cache hit for prompt", isStderr = false)
        )
    }

    @Test
    fun start_streamsStdoutAndStderrAsynchronously() = runTest(testDispatcher) {
        val streamer = LogStreamer(capacity = 100, ioDispatcher = testDispatcher)

        val stdoutData = """
            2026-08-26 [INFO] Gateway started on port 8000
            \u001B[32m[INFO]\u001B[0m Model loaded successfully
            [WARN] High memory usage
        """.trimIndent().replace("\\u001B", "\u001B") + "\n"

        val stderrData = """
            Traceback (most recent call last):
            [DEBUG] Subprocess initialized
        """.trimIndent() + "\n"

        val stdoutStream = ByteArrayInputStream(stdoutData.toByteArray(Charsets.UTF_8))
        val stderrStream = ByteArrayInputStream(stderrData.toByteArray(Charsets.UTF_8))

        assertFalse(streamer.isStreaming)
        streamer.start(stdoutStream, stderrStream)
        assertTrue(streamer.isStreaming)

        advanceUntilIdle()

        val logs = streamer.getLogs()
        assertEquals(5, logs.size)

        // Verify stdout entries
        val info1 = logs.find { it.message.contains("Gateway started") }
        val info2 = logs.find { it.message.contains("Model loaded") }
        val warn1 = logs.find { it.message.contains("High memory") }
        val err1 = logs.find { it.message.contains("Traceback") }
        val debug1 = logs.find { it.message.contains("Subprocess initialized") }

        assertTrue(info1 != null && info1.level == LogLevel.INFO)
        assertTrue(info2 != null && info2.level == LogLevel.INFO && !info2.message.contains("\u001B"))
        assertTrue(warn1 != null && warn1.level == LogLevel.WARN)
        assertTrue(err1 != null && err1.level == LogLevel.ERROR)
        assertTrue(debug1 != null && debug1.level == LogLevel.DEBUG)

        // Verify StateFlow emission
        assertEquals(logs, streamer.logsFlow.value)

        streamer.stop()
        assertFalse(streamer.isStreaming)
    }

    @Test
    fun start_withMalformedUtf8_replacesInvalidBytesWithoutThrowing() = runTest(testDispatcher) {
        val streamer = LogStreamer(capacity = 50, ioDispatcher = testDispatcher)

        // Construct invalid UTF-8 byte stream (0xFF, 0xFE invalid bytes) followed by valid UTF-8
        val invalidBytes = byteArrayOf(
            0xFF.toByte(), 0xFE.toByte(),
            '['.code.toByte(), 'I'.code.toByte(), 'N'.code.toByte(), 'F'.code.toByte(), 'O'.code.toByte(), ']'.code.toByte(),
            ' '.code.toByte(), 'R'.code.toByte(), 'e'.code.toByte(), 'a'.code.toByte(), 'd'.code.toByte(), 'y'.code.toByte(),
            '\n'.code.toByte()
        )

        val stream = ByteArrayInputStream(invalidBytes)
        streamer.start(stdout = stream, stderr = null)

        advanceUntilIdle()

        val logs = streamer.getLogs()
        assertEquals(1, logs.size)
        val entry = logs[0]
        assertEquals(LogLevel.INFO, entry.level)
        assertTrue(entry.message.contains("[INFO] Ready"))
        // Replacement char '\uFFFD' present
        assertTrue(entry.message.contains("\uFFFD"))

        streamer.stop()
    }

    @Test
    fun bufferOverflow_strictlyEnforcesCapacityCapInFIFOOrder() = runTest(testDispatcher) {
        val capacity = 2000
        val streamer = LogStreamer(capacity = capacity, ioDispatcher = testDispatcher)

        val sb = StringBuilder()
        for (i in 0 until 2500) {
            sb.append("Log message line $i\n")
        }

        val stream = ByteArrayInputStream(sb.toString().toByteArray(Charsets.UTF_8))
        streamer.start(stdout = stream, stderr = null)

        advanceUntilIdle()

        val logs = streamer.getLogs()
        assertEquals(2000, logs.size)
        assertEquals(2000, streamer.logsFlow.value.size)

        // Oldest 500 lines pruned
        assertEquals("Log message line 500", logs.first().message)
        assertEquals("Log message line 2499", logs.last().message)

        streamer.stop()
    }

    @Test
    fun clear_resetsBufferAndEmitsEmptyList() = runTest(testDispatcher) {
        val streamer = LogStreamer(capacity = 10, ioDispatcher = testDispatcher)
        streamer.append("Message 1", LogLevel.INFO)
        streamer.append("Message 2", LogLevel.WARN)

        assertEquals(2, streamer.getLogs().size)
        assertEquals(2, streamer.logsFlow.value.size)

        streamer.clear()

        assertEquals(0, streamer.getLogs().size)
        assertEquals(0, streamer.logsFlow.value.size)
        assertTrue(streamer.logsFlow.value.isEmpty())
    }

    @Test
    fun append_directlyAddsLogEntryAndEmitsToFlow() {
        val streamer = LogStreamer(capacity = 10, ioDispatcher = Dispatchers.Unconfined)
        val entry = LogEntry(message = "Direct append", level = LogLevel.DEBUG)

        streamer.append(entry)

        assertEquals(1, streamer.getLogs().size)
        assertEquals(entry, streamer.getLogs().first())
        assertEquals(1, streamer.logsFlow.value.size)
        assertEquals(entry, streamer.logsFlow.value.first())

        streamer.append("Second append", LogLevel.ERROR)
        assertEquals(2, streamer.getLogs().size)
        assertEquals("Second append", streamer.getLogs()[1].message)
        assertEquals(LogLevel.ERROR, streamer.getLogs()[1].level)
    }

    @Test
    fun stop_cancelsStreamingCoroutines() = runTest {
        val streamer = LogStreamer(capacity = 10, ioDispatcher = Dispatchers.Default)
        val pipedOut = PipedOutputStream()
        val pipedIn = PipedInputStream(pipedOut)

        streamer.start(stdout = pipedIn, stderr = null)
        assertTrue(streamer.isStreaming)

        pipedOut.write("Line 1\n".toByteArray(Charsets.UTF_8))
        pipedOut.flush()

        var retries = 0
        while (streamer.getLogs().isEmpty() && retries++ < 50) {
            kotlinx.coroutines.delay(20)
        }

        assertEquals(1, streamer.getLogs().size)

        streamer.stop()
        assertFalse(streamer.isStreaming)

        try {
            pipedOut.close()
            pipedIn.close()
        } catch (ignored: Throwable) {}
    }

    @Test
    fun concurrentAppends_threadSafety() {
        val capacity = 500
        val streamer = LogStreamer(capacity = capacity, ioDispatcher = Dispatchers.Default)
        val threadCount = 6
        val entriesPerThread = 500
        val executor = Executors.newFixedThreadPool(threadCount + 2)
        val latch = CountDownLatch(threadCount)
        val exceptionCount = AtomicInteger(0)

        for (t in 0 until threadCount) {
            val threadId = t
            executor.submit {
                try {
                    for (i in 0 until entriesPerThread) {
                        streamer.append("T$threadId - line $i", LogLevel.INFO)
                    }
                } catch (e: Exception) {
                    exceptionCount.incrementAndGet()
                } finally {
                    latch.countDown()
                }
            }
        }

        // Concurrent readers on logsFlow and getLogs()
        for (r in 0 until 2) {
            executor.submit {
                while (latch.count > 0) {
                    try {
                        val flowVal = streamer.logsFlow.value
                        val logsVal = streamer.getLogs()
                        assertTrue(flowVal.size <= capacity)
                        assertTrue(logsVal.size <= capacity)
                        Thread.sleep(1)
                    } catch (e: Exception) {
                        exceptionCount.incrementAndGet()
                    }
                }
            }
        }

        assertTrue(latch.await(10, TimeUnit.SECONDS))
        executor.shutdown()
        assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS))

        assertEquals(0, exceptionCount.get())
        assertEquals(capacity, streamer.getLogs().size)
        assertEquals(capacity, streamer.logsFlow.value.size)
    }
}
