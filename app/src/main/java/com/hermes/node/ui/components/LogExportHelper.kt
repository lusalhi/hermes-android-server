package com.hermes.node.ui.components

import com.hermes.node.viewmodel.LogEntry
import com.hermes.node.viewmodel.LogLevel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Pure helper utility for log formatting, severity tag labeling, filtering,
 * and Android clipboard string serialization.
 */
object LogExportHelper {

    private const val TIMESTAMP_PATTERN = "HH:mm:ss.SSS"

    /**
     * Returns a fixed 5-character padded level tag inside brackets.
     * Examples: `[INFO ]`, `[WARN ]`, `[ERROR]`, `[DEBUG]`
     */
    fun formatLevelTag(level: LogLevel): String {
        return "[${level.name.padEnd(5)}]"
    }

    /**
     * Formats epoch millisecond timestamp as `HH:mm:ss.SSS`.
     */
    fun formatTimestamp(timestamp: Long, locale: Locale = Locale.US): String {
        val sdf = SimpleDateFormat(TIMESTAMP_PATTERN, locale)
        return sdf.format(Date(timestamp))
    }

    /**
     * Serializes a single [LogEntry] into standard terminal export format:
     * `[HH:mm:ss.SSS] [LEVEL] Message`
     */
    fun formatLogEntry(entry: LogEntry, locale: Locale = Locale.US): String {
        val timeStr = formatTimestamp(entry.timestamp, locale)
        val tagStr = formatLevelTag(entry.level)
        return "[$timeStr] $tagStr ${entry.message}"
    }

    /**
     * Formats a collection of [LogEntry] items into newline-separated export text.
     */
    fun formatLogsForExport(logs: List<LogEntry>, locale: Locale = Locale.US): String {
        return logs.joinToString("\n") { formatLogEntry(it, locale) }
    }

    /**
     * Filters log entries based on case-insensitive substring search query and optional log level.
     *
     * @param logs List of log entries to filter.
     * @param searchQuery Case-insensitive substring to search within log messages. Blank matches all.
     * @param selectedLevel Desired [LogLevel] filter. Null matches all severity levels.
     */
    fun filterLogs(
        logs: List<LogEntry>,
        searchQuery: String = "",
        selectedLevel: LogLevel? = null
    ): List<LogEntry> {
        val query = searchQuery.trim()
        return logs.filter { entry ->
            val matchesQuery = query.isEmpty() || entry.message.contains(query, ignoreCase = true)
            val matchesLevel = selectedLevel == null || entry.level == selectedLevel
            matchesQuery && matchesLevel
        }
    }

    /**
     * Generates standard user feedback text for clipboard export operations with proper singular/plural grammar.
     */
    fun getCopyFeedbackMessage(count: Int, isFiltered: Boolean): String {
        val noun = if (count == 1) "log" else "logs"
        return if (isFiltered) {
            "Copied $count filtered $noun to clipboard"
        } else {
            "Copied $count $noun to clipboard"
        }
    }
}
