package com.hermes.node.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.hermes.node.ui.theme.StatusError
import com.hermes.node.ui.theme.StatusRunning
import com.hermes.node.ui.theme.StatusStarting
import com.hermes.node.ui.theme.StatusStopped
import com.hermes.node.ui.theme.StatusStopping
import com.hermes.node.viewmodel.ServerStatus

/**
 * Reusable server and runtime status pill badge.
 */
@Composable
fun StatusPill(
    status: ServerStatus,
    modifier: Modifier = Modifier,
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
        modifier = modifier.border(1.dp, textColor.copy(alpha = 0.4f), CircleShape)
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
