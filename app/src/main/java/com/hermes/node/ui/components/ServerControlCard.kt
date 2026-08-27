package com.hermes.node.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hermes.node.ui.theme.DarkBorder
import com.hermes.node.ui.theme.DarkSurface
import com.hermes.node.ui.theme.HermesCyan
import com.hermes.node.ui.theme.StatusError
import com.hermes.node.ui.theme.StatusRunning
import com.hermes.node.ui.theme.StatusStarting
import com.hermes.node.ui.theme.StatusStopped
import com.hermes.node.viewmodel.ServerStatus

/**
 * Master daemon Start/Stop hero card component.
 */
@Composable
fun ServerControlCard(
    status: ServerStatus,
    uptimeSeconds: Long,
    modifier: Modifier = Modifier,
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
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = DarkSurface),
        shape = RoundedCornerShape(16.dp),
        border = CardDefaults.outlinedCardBorder().copy(brush = SolidColor(DarkBorder))
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
