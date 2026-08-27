package com.hermes.node.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.VerticalAlignBottom
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.hermes.node.ui.components.LogExportHelper
import com.hermes.node.ui.components.LogLineItem
import com.hermes.node.ui.theme.DarkBorder
import com.hermes.node.ui.theme.DarkSurface
import com.hermes.node.ui.theme.HermesCyan
import com.hermes.node.ui.theme.LogDebug
import com.hermes.node.ui.theme.LogError
import com.hermes.node.ui.theme.LogInfo
import com.hermes.node.ui.theme.LogWarn
import com.hermes.node.ui.theme.MonospaceCodeStyle
import com.hermes.node.ui.theme.TerminalBackground
import com.hermes.node.viewmodel.LogEntry
import com.hermes.node.viewmodel.LogLevel

@Composable
fun LogsScreen(
    logs: List<LogEntry>,
    onClearLogs: () -> Unit,
    modifier: Modifier = Modifier
) {
    var searchQuery by remember { mutableStateOf("") }
    var selectedLevel by remember { mutableStateOf<LogLevel?>(null) }
    var isAutoScroll by remember { mutableStateOf(true) }
    val listState = rememberLazyListState()
    val context = LocalContext.current
    val focusManager = LocalFocusManager.current

    val filteredLogs = remember(logs, searchQuery, selectedLevel) {
        LogExportHelper.filterLogs(
            logs = logs,
            searchQuery = searchQuery,
            selectedLevel = selectedLevel
        )
    }

    LaunchedEffect(filteredLogs.lastOrNull()?.timestamp ?: 0L, filteredLogs.size, isAutoScroll) {
        if (isAutoScroll && filteredLogs.isNotEmpty()) {
            listState.scrollToItem(filteredLogs.size - 1)
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Top Header and Controls
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(
                    text = "Console Stream",
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onBackground
                )
                Text(
                    text = "${filteredLogs.size} logs recorded",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                // Auto-scroll toggle
                IconButton(
                    onClick = { isAutoScroll = !isAutoScroll }
                ) {
                    Icon(
                        imageVector = Icons.Default.VerticalAlignBottom,
                        contentDescription = if (isAutoScroll) "Disable auto-scroll" else "Enable auto-scroll",
                        tint = if (isAutoScroll) HermesCyan else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                // Copy logs (filtered if filter active, all if no filter)
                IconButton(
                    onClick = {
                        if (filteredLogs.isNotEmpty()) {
                            val text = LogExportHelper.formatLogsForExport(filteredLogs)
                            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            clipboard.setPrimaryClip(ClipData.newPlainText("Hermes Logs", text))
                            val isFiltered = searchQuery.isNotBlank() || selectedLevel != null
                            val toastMsg = LogExportHelper.getCopyFeedbackMessage(filteredLogs.size, isFiltered)
                            Toast.makeText(context, toastMsg, Toast.LENGTH_SHORT).show()
                        } else {
                            Toast.makeText(context, "No logs to copy", Toast.LENGTH_SHORT).show()
                        }
                    }
                ) {
                    Icon(
                        imageVector = Icons.Default.ContentCopy,
                        contentDescription = "Copy Logs",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                // Clear logs
                IconButton(
                    onClick = onClearLogs
                ) {
                    Icon(
                        imageVector = Icons.Default.Delete,
                        contentDescription = "Clear Logs",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        // Search text field
        OutlinedTextField(
            value = searchQuery,
            onValueChange = { searchQuery = it },
            placeholder = { Text("Filter logs...", style = MaterialTheme.typography.bodyMedium) },
            leadingIcon = {
                Icon(
                    imageVector = Icons.Default.Search,
                    contentDescription = "Search",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            },
            trailingIcon = {
                if (searchQuery.isNotEmpty()) {
                    IconButton(onClick = { searchQuery = "" }) {
                        Icon(
                            imageVector = Icons.Default.Clear,
                            contentDescription = "Clear Search",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { focusManager.clearFocus() }),
            colors = OutlinedTextFieldDefaults.colors(
                focusedContainerColor = DarkSurface,
                unfocusedContainerColor = DarkSurface,
                focusedBorderColor = HermesCyan,
                unfocusedBorderColor = DarkBorder
            )
        )

        // Filter chips (All, INFO, WARN, ERROR, DEBUG) with horizontal scrolling
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            FilterChip(
                selected = selectedLevel == null,
                onClick = { selectedLevel = null },
                label = { Text("ALL") },
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = HermesCyan,
                    selectedLabelColor = Color.Black
                )
            )
            LogLevel.entries.forEach { level ->
                FilterChip(
                    selected = selectedLevel == level,
                    onClick = {
                        selectedLevel = if (selectedLevel == level) null else level
                    },
                    label = { Text(level.name) },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = when (level) {
                            LogLevel.INFO -> LogInfo
                            LogLevel.WARN -> LogWarn
                            LogLevel.ERROR -> LogError
                            LogLevel.DEBUG -> LogDebug
                        },
                        selectedLabelColor = Color.Black
                    )
                )
            }
        }

        // Terminal Console Area
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            colors = CardDefaults.cardColors(containerColor = TerminalBackground),
            shape = RoundedCornerShape(12.dp),
            border = CardDefaults.outlinedCardBorder().copy(brush = SolidColor(DarkBorder))
        ) {
            if (filteredLogs.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = if (logs.isEmpty()) "No logs captured yet." else "No matching logs found.",
                        style = MonospaceCodeStyle,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    items(
                        items = filteredLogs,
                        key = { item -> "${item.timestamp}_${item.level}_${item.message.hashCode()}" }
                    ) { entry ->
                        LogLineItem(entry = entry)
                    }
                }
            }
        }
    }
}
