package com.hermes.node.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BatteryAlert
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Sensors
import androidx.compose.material.icons.filled.Thermostat
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.hermes.node.ui.components.MetricCard
import com.hermes.node.ui.components.RuntimeIntegrityCard
import com.hermes.node.ui.components.ServerControlCard
import com.hermes.node.ui.components.StatusPill
import com.hermes.node.ui.components.formatUptime
import com.hermes.node.ui.theme.DarkBorder
import com.hermes.node.ui.theme.DarkSurface
import com.hermes.node.ui.theme.HermesCyan
import com.hermes.node.ui.theme.HermesCyanLight
import com.hermes.node.ui.theme.StatusError
import com.hermes.node.ui.theme.StatusRunning
import com.hermes.node.ui.theme.StatusStarting
import com.hermes.node.viewmodel.ServerStatus
import com.hermes.node.viewmodel.ServerUiState
import java.util.Locale

@Composable
fun DashboardScreen(
    state: ServerUiState,
    onToggleServer: () -> Unit,
    onRetryBootstrap: (() -> Unit)? = null,
    onRepairRuntime: (() -> Unit)? = null,
    onRequestBatteryExemption: (() -> Unit)? = null,
    onDismissBatteryPrompt: (() -> Unit)? = null,
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

        // Battery Optimization Warning Banner (dismissible)
        AnimatedVisibility(visible = state.showBatteryOptimizationPrompt && !state.isBatteryOptimizationIgnored) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = DarkSurface),
                shape = RoundedCornerShape(12.dp),
                border = CardDefaults.outlinedCardBorder().copy(brush = androidx.compose.ui.graphics.SolidColor(StatusStarting))
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.BatteryAlert,
                            contentDescription = "Battery Optimization Warning",
                            tint = StatusStarting,
                            modifier = Modifier.size(24.dp)
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Battery Optimization Active",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold,
                                color = StatusStarting
                            )
                            Text(
                                text = "Android Doze may terminate the daemon in background. Exemption is recommended for 24/7 uptime.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        if (onDismissBatteryPrompt != null) {
                            IconButton(
                                onClick = onDismissBatteryPrompt,
                                modifier = Modifier.size(40.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Close,
                                    contentDescription = "Dismiss Banner",
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }
                    }

                    if (onRequestBatteryExemption != null) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.End
                        ) {
                            Button(
                                onClick = onRequestBatteryExemption,
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = StatusStarting,
                                    contentColor = DarkSurface
                                ),
                                shape = RoundedCornerShape(8.dp)
                            ) {
                                Text(
                                    text = "Request Exemption",
                                    fontWeight = FontWeight.Bold,
                                    style = MaterialTheme.typography.labelMedium
                                )
                            }
                        }
                    }
                }
            }
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
