package com.hermes.node.ui

import com.hermes.node.ui.components.LogExportHelper
import com.hermes.node.viewmodel.LogEntry
import com.hermes.node.viewmodel.LogLevel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class LogExportHelperTest {

    @Test
    fun testFormatLevelTag() {
        assertEquals("[INFO ]", LogExportHelper.formatLevelTag(LogLevel.INFO))
        assertEquals("[WARN ]", LogExportHelper.formatLevelTag(LogLevel.WARN))
        assertEquals("[ERROR]", LogExportHelper.formatLevelTag(LogLevel.ERROR))
        assertEquals("[DEBUG]", LogExportHelper.formatLevelTag(LogLevel.DEBUG))
    }

    @Test
    fun testFormatTimestamp() {
        val testTime = 1700000000123L
        val expected = SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(Date(testTime))
        val actual = LogExportHelper.formatTimestamp(testTime, Locale.US)
        assertEquals(expected, actual)
    }

    @Test
    fun testFormatTimestamp_edgeCases() {
        // Zero epoch timestamp
        val expectedZero = SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(Date(0L))
        val actualZero = LogExportHelper.formatTimestamp(0L, Locale.US)
        assertEquals(expectedZero, actualZero)

        // Milliseconds with leading zeros (e.g. 5ms -> .005)
        val timeWithFewMillis = 1700000000005L
        val expectedFewMillis = SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(Date(timeWithFewMillis))
        val actualFewMillis = LogExportHelper.formatTimestamp(timeWithFewMillis, Locale.US)
        assertEquals(expectedFewMillis, actualFewMillis)
        assertTrue(actualFewMillis.endsWith(".005"))
    }

    @Test
    fun testFormatLogEntry() {
        val testTime = 1700000000123L
        val timeStr = SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(Date(testTime))
        val entry = LogEntry(
            timestamp = testTime,
            message = "Hermes Node daemon running on port 8000",
            level = LogLevel.INFO
        )

        val formatted = LogExportHelper.formatLogEntry(entry, Locale.US)
        assertEquals("[$timeStr] [INFO ] Hermes Node daemon running on port 8000", formatted)
    }

    @Test
    fun testFormatLogsForExport() {
        val t1 = 1700000000100L
        val t2 = 1700000000200L
        val time1 = SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(Date(t1))
        val time2 = SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(Date(t2))

        val logs = listOf(
            LogEntry(timestamp = t1, message = "Line 1", level = LogLevel.INFO),
            LogEntry(timestamp = t2, message = "Line 2 warning", level = LogLevel.WARN)
        )

        val result = LogExportHelper.formatLogsForExport(logs, Locale.US)
        val expected = "[$time1] [INFO ] Line 1\n[$time2] [WARN ] Line 2 warning"
        assertEquals(expected, result)
    }

    @Test
    fun testFormatLogsForExport_empty() {
        val result = LogExportHelper.formatLogsForExport(emptyList())
        assertEquals("", result)
    }

    @Test
    fun testFilterLogs_emptyList() {
        val result = LogExportHelper.filterLogs(emptyList(), searchQuery = "search", selectedLevel = LogLevel.ERROR)
        assertTrue(result.isEmpty())
    }

    @Test
    fun testFilterLogs_noFilters() {
        val logs = listOf(
            LogEntry(message = "Log 1", level = LogLevel.INFO),
            LogEntry(message = "Log 2", level = LogLevel.WARN),
            LogEntry(message = "Log 3", level = LogLevel.ERROR)
        )

        val filtered = LogExportHelper.filterLogs(logs, searchQuery = "", selectedLevel = null)
        assertEquals(3, filtered.size)
        assertEquals(logs, filtered)
    }

    @Test
    fun testFilterLogs_byLevel() {
        val logs = listOf(
            LogEntry(message = "Info log", level = LogLevel.INFO),
            LogEntry(message = "Warn log", level = LogLevel.WARN),
            LogEntry(message = "Error log", level = LogLevel.ERROR),
            LogEntry(message = "Debug log", level = LogLevel.DEBUG)
        )

        val infoOnly = LogExportHelper.filterLogs(logs, selectedLevel = LogLevel.INFO)
        assertEquals(1, infoOnly.size)
        assertEquals(LogLevel.INFO, infoOnly.first().level)

        val errorOnly = LogExportHelper.filterLogs(logs, selectedLevel = LogLevel.ERROR)
        assertEquals(1, errorOnly.size)
        assertEquals(LogLevel.ERROR, errorOnly.first().level)
    }

    @Test
    fun testFilterLogs_searchQuery_caseInsensitive() {
        val logs = listOf(
            LogEntry(message = "Telegram Gateway connected", level = LogLevel.INFO),
            LogEntry(message = "Process running on port 8000", level = LogLevel.INFO),
            LogEntry(message = "telegram token invalid", level = LogLevel.ERROR)
        )

        val resultLower = LogExportHelper.filterLogs(logs, searchQuery = "telegram")
        assertEquals(2, resultLower.size)

        val resultUpper = LogExportHelper.filterLogs(logs, searchQuery = "GATEWAY")
        assertEquals(1, resultUpper.size)
        assertEquals("Telegram Gateway connected", resultUpper.first().message)
    }

    @Test
    fun testFilterLogs_searchQuery_withWhitespace() {
        val logs = listOf(
            LogEntry(message = "Telegram Gateway connected", level = LogLevel.INFO),
            LogEntry(message = "Other log", level = LogLevel.WARN)
        )

        val result = LogExportHelper.filterLogs(logs, searchQuery = "  gateway  ")
        assertEquals(1, result.size)
        assertEquals("Telegram Gateway connected", result.first().message)
    }

    @Test
    fun testFilterLogs_combinedSearchAndLevel() {
        val logs = listOf(
            LogEntry(message = "Gateway connected", level = LogLevel.INFO),
            LogEntry(message = "Gateway error occurred", level = LogLevel.ERROR),
            LogEntry(message = "Other error occurred", level = LogLevel.ERROR)
        )

        val result = LogExportHelper.filterLogs(
            logs = logs,
            searchQuery = "gateway",
            selectedLevel = LogLevel.ERROR
        )

        assertEquals(1, result.size)
        assertEquals("Gateway error occurred", result.first().message)
        assertEquals(LogLevel.ERROR, result.first().level)
    }

    @Test
    fun testFilterLogs_noMatches() {
        val logs = listOf(
            LogEntry(message = "Log 1", level = LogLevel.INFO),
            LogEntry(message = "Log 2", level = LogLevel.WARN)
        )

        val result = LogExportHelper.filterLogs(logs, searchQuery = "nonexistent")
        assertTrue(result.isEmpty())
    }

    @Test
    fun testCopyFeedbackMessage_plural() {
        val unFilteredMsg = LogExportHelper.getCopyFeedbackMessage(count = 45, isFiltered = false)
        assertEquals("Copied 45 logs to clipboard", unFilteredMsg)

        val filteredMsg = LogExportHelper.getCopyFeedbackMessage(count = 3, isFiltered = true)
        assertEquals("Copied 3 filtered logs to clipboard", filteredMsg)
    }

    @Test
    fun testCopyFeedbackMessage_singular() {
        val unFilteredMsg = LogExportHelper.getCopyFeedbackMessage(count = 1, isFiltered = false)
        assertEquals("Copied 1 log to clipboard", unFilteredMsg)

        val filteredMsg = LogExportHelper.getCopyFeedbackMessage(count = 1, isFiltered = true)
        assertEquals("Copied 1 filtered log to clipboard", filteredMsg)
    }
}
