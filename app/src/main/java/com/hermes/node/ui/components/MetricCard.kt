package com.hermes.node.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Thermostat
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.hermes.node.ui.theme.DarkBorder
import com.hermes.node.ui.theme.DarkSurface
import com.hermes.node.ui.theme.HermesCyan
import com.hermes.node.ui.theme.HermesTheme
import com.hermes.node.ui.theme.StatusError
import com.hermes.node.ui.theme.StatusStarting
import java.util.Locale

/**
 * Reusable telemetry metric card component.
 */
@Composable
fun MetricCard(
    title: String,
    value: String,
    icon: ImageVector,
    iconTint: Color,
    modifier: Modifier = Modifier,
    valueColor: Color = MaterialTheme.colorScheme.onBackground
) {
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(containerColor = DarkSurface),
        shape = RoundedCornerShape(12.dp),
        border = CardDefaults.outlinedCardBorder().copy(brush = SolidColor(DarkBorder))
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
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Text(
                text = value,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = valueColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

/**
 * Returns the status color according to CPU load thresholds:
 * - < 75%: HermesCyan
 * - 75%–90%: StatusStarting (Amber warning)
 * - > 90%: StatusError (Red critical)
 */
fun getCpuColor(cpuPercent: Float): Color {
    return when {
        cpuPercent >= 90.0f -> StatusError
        cpuPercent >= 75.0f -> StatusStarting
        else -> HermesCyan
    }
}

/**
 * Returns the status color according to battery temperature thresholds:
 * - < 38°C: HermesCyan
 * - 38°C–45°C: StatusStarting (Amber warning)
 * - > 45°C: StatusError (Red critical)
 */
fun getThermalColor(temperatureCelsius: Float): Color {
    return when {
        temperatureCelsius >= 45.0f -> StatusError
        temperatureCelsius >= 38.0f -> StatusStarting
        else -> HermesCyan
    }
}

/**
 * Formats battery percentage with optional charging indicator (⚡).
 */
fun formatBattery(batteryPercent: Int, isCharging: Boolean): String {
    return if (isCharging) "$batteryPercent% ⚡" else "$batteryPercent%"
}

/**
 * Formats battery temperature in Celsius (°C).
 * Returns "-- °C" when temperature is uninitialized or unavailable (<= 0°C).
 */
fun formatTemperature(temperatureCelsius: Float): String {
    return if (temperatureCelsius <= 0f) {
        "-- °C"
    } else {
        String.format(Locale.US, "%.1f °C", temperatureCelsius)
    }
}

/**
 * Formats CPU usage percentage.
 */
fun formatCpuUsage(cpuPercent: Float): String {
    return String.format(Locale.US, "%.1f%%", cpuPercent)
}

/**
 * Formats RAM usage in MB (with optional total MB).
 */
fun formatRamUsage(usedMb: Long, totalMb: Long = 0L): String {
    return if (totalMb > 0L) "$usedMb / $totalMb MB" else "$usedMb MB"
}

/**
 * Helper function to format elapsed seconds into HH:mm:ss or mm:ss string.
 */
fun formatUptime(seconds: Long): String {
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

@Preview(name = "MetricCard Normal", showBackground = true)
@Composable
private fun MetricCardNormalPreview() {
    HermesTheme {
        MetricCard(
            title = "CPU Usage",
            value = "12.4%",
            icon = Icons.Default.Bolt,
            iconTint = HermesCyan,
            valueColor = HermesCyan
        )
    }
}

@Preview(name = "MetricCard Warning", showBackground = true)
@Composable
private fun MetricCardWarningPreview() {
    HermesTheme {
        MetricCard(
            title = "Temperature",
            value = "41.2 °C",
            icon = Icons.Default.Thermostat,
            iconTint = StatusStarting,
            valueColor = StatusStarting
        )
    }
}

@Preview(name = "MetricCard Error", showBackground = true)
@Composable
private fun MetricCardErrorPreview() {
    HermesTheme {
        MetricCard(
            title = "Temperature",
            value = "48.5 °C",
            icon = Icons.Default.Thermostat,
            iconTint = StatusError,
            valueColor = StatusError
        )
    }
}

