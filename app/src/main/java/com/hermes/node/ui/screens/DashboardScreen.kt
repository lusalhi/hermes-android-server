package com.hermes.node.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Sensors
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Thermostat
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hermes.node.ui.theme.DarkBorder
import com.hermes.node.ui.theme.DarkSurface
import com.hermes.node.ui.theme.HermesCyan
import com.hermes.node.ui.theme.HermesCyanDark
import com.hermes.node.ui.theme.HermesCyanLight
import com.hermes.node.ui.theme.StatusError
import com.hermes.node.ui.theme.StatusRunning
import com.hermes.node.ui.theme.StatusStarting
import com.hermes.node.ui.theme.StatusStopped
import com.hermes.node.ui.theme.StatusStopping
import com.hermes.node.viewmodel.ServerStatus
import com.hermes.node.viewmodel.ServerUiState
import java.util.Locale

@Composable
fun DashboardScreen(
    state: ServerUiState,
    onToggleServer: () -> Unit,
    onRetryBootstrap: (() -> Unit)? = null,
    onRepairRuntime: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val scrollState = rememberScrollState()
    val context = LocalContext.current

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // App Header
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(
                    text = "Hermes Node",
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onBackground
                )
                Text(
                    text = "Autonomous Agent Daemon",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            StatusPill(
                status = state.status,
                isBootstrapping = state.isBootstrapping,
                isRepairing = state.isRepairing,
                isRuntimeCorrupted = state.isRuntimeCorrupted
            )
        }

        // Runtime Integrity Card (when corrupted or repairing)
        AnimatedVisibility(visible = state.isRuntimeCorrupted || state.isRepairing) {
            onRepairRuntime?.let { repairAction ->
                RuntimeIntegrityCard(
                    warning = state.integrityWarning,
                    isRepairing = state.isRepairing,
                    onRepairRuntime = repairAction
                )
            }
        }

        // Bootstrap Progress Card
        AnimatedVisibility(visible = state.isBootstrapping) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = DarkSurface),
                shape = RoundedCornerShape(12.dp),
                border = CardDefaults.outlinedCardBorder().copy(brush = androidx.compose.ui.graphics.SolidColor(HermesCyan))
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                strokeWidth = 2.dp,
                                color = HermesCyan
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = if (state.isRepairing) "Repairing ARM64 Linux Userland" else "Extracting ARM64 Linux Userland",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onBackground
                            )
                        }
                        Text(
                            text = "${(state.bootstrapProgress * 100).toInt()}%",
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.Bold,
                            color = HermesCyan
                        )
                    }

                    androidx.compose.material3.LinearProgressIndicator(
                        progress = { state.bootstrapProgress },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(6.dp)
                            .clip(RoundedCornerShape(3.dp)),
                        color = HermesCyan,
                        trackColor = DarkBorder
                    )

                    Text(
                        text = state.bootstrapMessage.ifEmpty { if (state.isRepairing) "Repairing userland..." else "Decompressing userland..." },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        // Bootstrap Required Card (when not complete, not actively running, and not corrupted)
        AnimatedVisibility(visible = !state.isBootstrapComplete && !state.isBootstrapping && !state.isRuntimeCorrupted && onRetryBootstrap != null) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = DarkSurface),
                shape = RoundedCornerShape(12.dp),
                border = CardDefaults.outlinedCardBorder().copy(brush = androidx.compose.ui.graphics.SolidColor(StatusStarting))
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "ARM64 Userland Setup Required",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onBackground
                        )
                        Text(
                            text = "Unpack Python 3.11 & PRoot runtime to enable agent daemon.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    Button(
                        onClick = { onRetryBootstrap?.invoke() },
                        colors = ButtonDefaults.buttonColors(containerColor = HermesCyan)
                    ) {
                        Text("Install", color = DarkSurface, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }

        // Server Status & Main Action Hero Card
        ServerControlCard(
            status = state.status,
            uptimeSeconds = state.uptimeSeconds,
            isBootstrapping = state.isBootstrapping,
            isRepairing = state.isRepairing,
            isRuntimeCorrupted = state.isRuntimeCorrupted,
            isBootstrapComplete = state.isBootstrapComplete,
            onToggleServer = onToggleServer
        )

        // Error message if any
        AnimatedVisibility(visible = state.errorMessage != null) {
            state.errorMessage?.let { error ->
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = StatusError.copy(alpha = 0.15f)
                    ),
                    shape = RoundedCornerShape(12.dp),
                    border = CardDefaults.outlinedCardBorder().copy(brush = androidx.compose.ui.graphics.SolidColor(StatusError))
                ) {
                    Row(
                        modifier = Modifier.padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.ErrorOutline,
                            contentDescription = "Error",
                            tint = StatusError
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(
                            text = error,
                            style = MaterialTheme.typography.bodyMedium,
                            color = StatusError
                        )
                    }
                }
            }
        }

        // Device Telemetry / Metrics Grid
        Text(
            text = "Telemetry & System Stats",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onBackground
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            MetricCard(
                title = "CPU Usage",
                value = String.format(Locale.US, "%.1f%%", state.cpuUsagePercent),
                icon = Icons.Default.Bolt,
                iconTint = HermesCyan,
                modifier = Modifier.weight(1f)
            )
            MetricCard(
                title = "RAM Used",
                value = "${state.memoryUsageMb} MB",
                icon = Icons.Default.Memory,
                iconTint = HermesCyan,
                modifier = Modifier.weight(1f)
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            MetricCard(
                title = "Uptime",
                value = formatUptime(state.uptimeSeconds),
                icon = Icons.Default.Schedule,
                iconTint = StatusRunning,
                modifier = Modifier.weight(1f)
            )
            MetricCard(
                title = "Temperature",
                value = if (state.status == ServerStatus.RUNNING) "32.5 °C" else "28.0 °C",
                icon = Icons.Default.Thermostat,
                iconTint = StatusStarting,
                modifier = Modifier.weight(1f)
            )
        }

        // Active Cloudflare Public Tunnel Card (if enabled)
        if (state.isPublicTunnelEnabled && state.tunnelUrl != null) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = DarkSurface
                ),
                shape = RoundedCornerShape(12.dp),
                border = CardDefaults.outlinedCardBorder().copy(brush = androidx.compose.ui.graphics.SolidColor(DarkBorder))
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Default.Sensors,
                                contentDescription = "Tunnel",
                                tint = HermesCyan,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "Cloudflare Quick Tunnel",
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.onBackground
                            )
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = state.tunnelUrl,
                            style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                            color = HermesCyanLight
                        )
                    }
                    IconButton(
                        onClick = {
                            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            clipboard.setPrimaryClip(ClipData.newPlainText("Tunnel URL", state.tunnelUrl))
                            Toast.makeText(context, "Copied Tunnel URL to clipboard", Toast.LENGTH_SHORT).show()
                        }
                    ) {
                        Icon(
                            imageVector = Icons.Default.ContentCopy,
                            contentDescription = "Copy URL",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }

        // Agent & Gateway Summary Card
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = DarkSurface),
            shape = RoundedCornerShape(12.dp),
            border = CardDefaults.outlinedCardBorder().copy(brush = androidx.compose.ui.graphics.SolidColor(DarkBorder))
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = "Agent Configuration",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onBackground
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "LLM Provider",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = state.selectedProvider.replace("_", " ").uppercase(Locale.US),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                        color = HermesCyan
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Telegram Gateway",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = if (state.telegramToken.isNotBlank()) "Configured" else "Disabled",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                        color = if (state.telegramToken.isNotBlank()) StatusRunning else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Auto-start on Boot",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = if (state.isAutoStartEnabled) "Enabled" else "Disabled",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                        color = if (state.isAutoStartEnabled) StatusRunning else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
fun RuntimeIntegrityCard(
    warning: String?,
    isRepairing: Boolean,
    onRepairRuntime: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = DarkSurface
        ),
        shape = RoundedCornerShape(12.dp),
        border = CardDefaults.outlinedCardBorder().copy(brush = androidx.compose.ui.graphics.SolidColor(StatusError))
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Default.Warning,
                    contentDescription = "Integrity Warning",
                    tint = StatusError,
                    modifier = Modifier.size(24.dp)
                )
                Spacer(modifier = Modifier.width(10.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Runtime Integrity Warning",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = StatusError
                    )
                    Text(
                        text = warning?.takeIf { it.isNotBlank() }
                            ?: "Corrupted or missing userland binaries detected.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Button(
                    onClick = onRepairRuntime,
                    enabled = !isRepairing,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = StatusError,
                        contentColor = Color.White
                    ),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    if (isRepairing) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp,
                            color = Color.White
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Repairing...", fontWeight = FontWeight.Bold)
                    } else {
                        Icon(
                            imageVector = Icons.Default.Build,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Repair Runtime", fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}

@Composable
private fun StatusPill(
    status: ServerStatus,
    isBootstrapping: Boolean = false,
    isRepairing: Boolean = false,
    isRuntimeCorrupted: Boolean = false
) {
    val (bgColor, textColor, label) = when {
        isRepairing -> Triple(StatusStarting.copy(alpha = 0.2f), StatusStarting, "REPAIRING")
        isBootstrapping -> Triple(StatusStarting.copy(alpha = 0.2f), StatusStarting, "INSTALLING")
        isRuntimeCorrupted -> Triple(StatusError.copy(alpha = 0.2f), StatusError, "CORRUPTED")
        else -> when (status) {
            ServerStatus.STOPPED -> Triple(StatusStopped.copy(alpha = 0.2f), StatusStopped, "STOPPED")
            ServerStatus.STARTING -> Triple(StatusStarting.copy(alpha = 0.2f), StatusStarting, "STARTING")
            ServerStatus.RUNNING -> Triple(StatusRunning.copy(alpha = 0.2f), StatusRunning, "ONLINE")
            ServerStatus.STOPPING -> Triple(StatusStopping.copy(alpha = 0.2f), StatusStopping, "STOPPING")
            ServerStatus.ERROR -> Triple(StatusError.copy(alpha = 0.2f), StatusError, "ERROR")
        }
    }

    Surface(
        color = bgColor,
        shape = CircleShape,
        modifier = Modifier.border(1.dp, textColor.copy(alpha = 0.4f), CircleShape)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(textColor)
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = label,
                color = textColor,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

@Composable
private fun ServerControlCard(
    status: ServerStatus,
    uptimeSeconds: Long,
    isBootstrapping: Boolean = false,
    isRepairing: Boolean = false,
    isRuntimeCorrupted: Boolean = false,
    isBootstrapComplete: Boolean = true,
    onToggleServer: () -> Unit
) {
    val isRunning = status == ServerStatus.RUNNING
    val isBusy = status == ServerStatus.STARTING || status == ServerStatus.STOPPING || isBootstrapping || isRepairing

    val buttonColor by animateColorAsState(
        targetValue = when {
            isRepairing || isBootstrapping -> StatusStarting
            isRuntimeCorrupted -> StatusError
            status == ServerStatus.RUNNING -> StatusError
            status == ServerStatus.STOPPED || status == ServerStatus.ERROR -> HermesCyan
            status == ServerStatus.STARTING || status == ServerStatus.STOPPING -> StatusStarting
            else -> HermesCyan
        },
        label = "buttonColor"
    )

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = DarkSurface),
        shape = RoundedCornerShape(16.dp),
        border = CardDefaults.outlinedCardBorder().copy(brush = androidx.compose.ui.graphics.SolidColor(DarkBorder))
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(
                imageVector = Icons.Default.PowerSettingsNew,
                contentDescription = null,
                tint = when {
                    isRepairing || isBootstrapping -> StatusStarting
                    isRuntimeCorrupted -> StatusError
                    status == ServerStatus.RUNNING -> StatusRunning
                    status == ServerStatus.STARTING || status == ServerStatus.STOPPING -> StatusStarting
                    status == ServerStatus.ERROR -> StatusError
                    else -> StatusStopped
                },
                modifier = Modifier.size(48.dp)
            )

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = when {
                    isRepairing -> "Repairing ARM64 Userland..."
                    isBootstrapping -> "Installing ARM64 Userland..."
                    isRuntimeCorrupted -> "Runtime Integrity Corrupted"
                    status == ServerStatus.RUNNING -> "Hermes Daemon Active"
                    status == ServerStatus.STARTING -> "Starting Daemon..."
                    status == ServerStatus.STOPPING -> "Stopping Daemon..."
                    status == ServerStatus.ERROR -> "Daemon Error"
                    else -> "Daemon Inactive"
                },
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onBackground
            )

            Text(
                text = when {
                    isRepairing -> "Restoring clean Python runtime & PRoot environment"
                    isBootstrapping -> "Unpacking Python runtime & PRoot environment"
                    isRuntimeCorrupted -> "Core binaries damaged. Tap Repair Runtime above."
                    isRunning -> "Listening on port 8000"
                    else -> "Tap start to launch the agent runtime"
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(20.dp))

            Button(
                onClick = onToggleServer,
                enabled = !isBusy && isBootstrapComplete && !isRuntimeCorrupted,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = buttonColor,
                    contentColor = if (status == ServerStatus.RUNNING || isRuntimeCorrupted) Color.White else DarkSurface
                )
            ) {
                if (isRepairing) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 2.dp,
                        color = DarkSurface
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "REPAIRING RUNTIME...",
                        fontWeight = FontWeight.Bold,
                        color = DarkSurface
                    )
                } else if (isBootstrapping) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 2.dp,
                        color = DarkSurface
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "EXTRACTING RUNTIME...",
                        fontWeight = FontWeight.Bold,
                        color = DarkSurface
                    )
                } else if (isBusy) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 2.dp,
                        color = Color.White
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = if (status == ServerStatus.STARTING) "STARTING..." else "STOPPING...",
                        fontWeight = FontWeight.Bold
                    )
                } else {
                    Icon(
                        imageVector = if (isRunning) Icons.Default.Stop else Icons.Default.PlayArrow,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = if (isRunning) "STOP SERVER" else "START SERVER",
                        fontWeight = FontWeight.Bold,
                        fontSize = 16.sp
                    )
                }
            }
        }
    }
}

@Composable
private fun MetricCard(
    title: String,
    value: String,
    icon: ImageVector,
    iconTint: Color,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(containerColor = DarkSurface),
        shape = RoundedCornerShape(12.dp),
        border = CardDefaults.outlinedCardBorder().copy(brush = androidx.compose.ui.graphics.SolidColor(DarkBorder))
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = iconTint,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = title,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Text(
                text = value,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onBackground
            )
        }
    }
}

private fun formatUptime(seconds: Long): String {
    val safeSeconds = seconds.coerceAtLeast(0L)
    val hrs = safeSeconds / 3600
    val mins = (safeSeconds % 3600) / 60
    val secs = safeSeconds % 60
    return if (hrs > 0) {
        String.format(Locale.US, "%02d:%02d:%02d", hrs, mins, secs)
    } else {
        String.format(Locale.US, "%02d:%02d", mins, secs)
    }
}
