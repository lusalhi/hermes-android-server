package com.hermes.node.ui.components

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hermes.node.ui.theme.LogDebug
import com.hermes.node.ui.theme.LogError
import com.hermes.node.ui.theme.LogInfo
import com.hermes.node.ui.theme.LogWarn
import com.hermes.node.ui.theme.MonospaceCodeStyle
import com.hermes.node.viewmodel.LogEntry
import com.hermes.node.viewmodel.LogLevel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Dedicated monospaced cyber-terminal log entry row component with
 * level-coded color tags, precision timestamps, and text selection support.
 */
@Composable
fun LogLineItem(
    entry: LogEntry,
    modifier: Modifier = Modifier
) {
    val timeFormat = remember { SimpleDateFormat("HH:mm:ss.SSS", Locale.US) }
    val formattedTime = remember(entry.timestamp) { timeFormat.format(Date(entry.timestamp)) }

    val levelColor = when (entry.level) {
        LogLevel.INFO -> LogInfo
        LogLevel.WARN -> LogWarn
        LogLevel.ERROR -> LogError
        LogLevel.DEBUG -> LogDebug
    }

    SelectionContainer {
        Row(
            modifier = modifier.fillMaxWidth(),
            verticalAlignment = Alignment.Top
        ) {
            Text(
                text = formattedTime,
                style = MonospaceCodeStyle.copy(fontSize = 11.sp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                softWrap = false
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = LogExportHelper.formatLevelTag(entry.level),
                style = MonospaceCodeStyle.copy(fontWeight = FontWeight.Bold, fontSize = 11.sp),
                color = levelColor,
                maxLines = 1,
                softWrap = false
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = entry.message,
                style = MonospaceCodeStyle.copy(fontSize = 12.sp),
                color = MaterialTheme.colorScheme.onBackground,
                modifier = Modifier.weight(1f, fill = false)
            )
        }
    }
}
