package com.hermes.node.ui.screens

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.AlternateEmail
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.BatteryAlert
import androidx.compose.material.icons.filled.BatteryChargingFull
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Forum
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Lan
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Numbers
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Tag
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.hermes.node.ui.theme.DarkBorder
import com.hermes.node.ui.theme.DarkSurface
import com.hermes.node.ui.theme.HermesCyan
import com.hermes.node.ui.theme.HermesCyanDark
import com.hermes.node.ui.theme.StatusRunning
import com.hermes.node.ui.theme.StatusStarting
import com.hermes.node.viewmodel.ServerUiState

@Composable
fun SettingsScreen(
    state: ServerUiState,
    onUpdateProvider: (String) -> Unit,
    onUpdateApiKey: (String) -> Unit,
    onUpdateTelegramToken: (String) -> Unit,
    onUpdateCustomModel: (String) -> Unit,
    onUpdateCustomBaseUrl: (String) -> Unit,
    onUpdateAutoStart: (Boolean) -> Unit,
    onUpdatePublicTunnel: (Boolean) -> Unit,
    onSaveSettings: () -> Unit,
    onDismissSaveMessage: () -> Unit = {},
    onRequestBatteryExemption: (() -> Unit)? = null,
    oemGuidanceUrl: String = "https://dontkillmyapp.com",
    onUpdateTelegramEnabled: (Boolean) -> Unit = {},
    onUpdateTelegramAdminUserIds: (String) -> Unit = {},
    onUpdateDiscordEnabled: (Boolean) -> Unit = {},
    onUpdateDiscordToken: (String) -> Unit = {},
    onUpdateDiscordChannelIds: (String) -> Unit = {},
    onUpdateSlackEnabled: (Boolean) -> Unit = {},
    onUpdateSlackAppToken: (String) -> Unit = {},
    onUpdateSlackBotToken: (String) -> Unit = {},
    onUpdateWhatsAppEnabled: (Boolean) -> Unit = {},
    onUpdateWhatsAppSessionLink: (String) -> Unit = {},
    onUpdateWhatsAppWebhookToken: (String) -> Unit = {},
    onUpdateRestApiEnabled: (Boolean) -> Unit = {},
    onUpdateRestApiPort: (String) -> Unit = {},
    modifier: Modifier = Modifier
) {
    val scrollState = rememberScrollState()
    val context = LocalContext.current

    var isApiKeyVisible by rememberSaveable { mutableStateOf(false) }
    var isTelegramTokenVisible by rememberSaveable { mutableStateOf(false) }
    var isDiscordTokenVisible by rememberSaveable { mutableStateOf(false) }
    var isSlackAppTokenVisible by rememberSaveable { mutableStateOf(false) }
    var isSlackBotTokenVisible by rememberSaveable { mutableStateOf(false) }
    var isWhatsAppSessionLinkVisible by rememberSaveable { mutableStateOf(false) }
    var isWhatsAppWebhookTokenVisible by rememberSaveable { mutableStateOf(false) }
    var isProviderDropdownOpen by rememberSaveable { mutableStateOf(false) }

    val providers = listOf(
        "nous_portal" to "Nous Portal",
        "openrouter" to "OpenRouter",
        "openai" to "OpenAI",
        "anthropic" to "Anthropic",
        "gemini" to "Google Gemini",
        "groq" to "Groq",
        "ollama" to "Ollama / Custom"
    )

    val currentProviderLabel = providers.firstOrNull { it.first == state.selectedProvider }?.second ?: state.selectedProvider

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(
            text = "Settings",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onBackground
        )

        // Feedback Banner
        AnimatedVisibility(visible = state.configSaveMessage != null) {
            val isSuccess = state.isSettingsSaved
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = if (isSuccess) HermesCyanDark.copy(alpha = 0.25f) else MaterialTheme.colorScheme.errorContainer
                ),
                shape = RoundedCornerShape(12.dp),
                border = CardDefaults.outlinedCardBorder().copy(
                    brush = androidx.compose.ui.graphics.SolidColor(if (isSuccess) HermesCyan else MaterialTheme.colorScheme.error)
                )
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = if (isSuccess) Icons.Default.CheckCircle else Icons.Default.Warning,
                        contentDescription = null,
                        tint = if (isSuccess) HermesCyan else MaterialTheme.colorScheme.error
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Text(
                        text = state.configSaveMessage ?: "",
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (isSuccess) MaterialTheme.colorScheme.onBackground else MaterialTheme.colorScheme.onErrorContainer,
                        modifier = Modifier.weight(1f)
                    )
                    IconButton(onClick = onDismissSaveMessage) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Dismiss message",
                            tint = if (isSuccess) HermesCyan else MaterialTheme.colorScheme.error
                        )
                    }
                }
            }
        }

        // LLM Provider Card
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
                    text = "LLM Provider Configuration",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onBackground
                )

                // Provider Selector
                Column {
                    Text(
                        text = "Provider",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    OutlinedButton(
                        onClick = { isProviderDropdownOpen = true },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = MaterialTheme.colorScheme.onBackground
                        )
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(text = currentProviderLabel)
                            Icon(
                                imageVector = Icons.Default.ArrowDropDown,
                                contentDescription = "Select Provider"
                            )
                        }
                    }

                    DropdownMenu(
                        expanded = isProviderDropdownOpen,
                        onDismissRequest = { isProviderDropdownOpen = false }
                    ) {
                        providers.forEach { (key, label) ->
                            DropdownMenuItem(
                                text = { Text(label) },
                                onClick = {
                                    onUpdateProvider(key)
                                    isProviderDropdownOpen = false
                                }
                            )
                        }
                    }
                }

                // API Key Field
                Column {
                    Text(
                        text = "API Key",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    OutlinedTextField(
                        value = state.apiKey,
                        onValueChange = onUpdateApiKey,
                        placeholder = { Text("Enter API Key") },
                        leadingIcon = {
                            Icon(
                                imageVector = Icons.Default.Key,
                                contentDescription = null,
                                tint = HermesCyan
                            )
                        },
                        trailingIcon = {
                            IconButton(onClick = { isApiKeyVisible = !isApiKeyVisible }) {
                                Icon(
                                    imageVector = if (isApiKeyVisible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                    contentDescription = if (isApiKeyVisible) "Hide Key" else "Show Key",
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        },
                        visualTransformation = if (isApiKeyVisible) VisualTransformation.None else PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedContainerColor = DarkSurface,
                            unfocusedContainerColor = DarkSurface,
                            focusedBorderColor = HermesCyan,
                            unfocusedBorderColor = DarkBorder
                        )
                    )
                }

                // Custom Model / URL (if custom / ollama)
                AnimatedVisibility(visible = state.selectedProvider == "ollama" || state.selectedProvider == "custom") {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Column {
                            Text(
                                text = "Base URL",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            OutlinedTextField(
                                value = state.customBaseUrl,
                                onValueChange = onUpdateCustomBaseUrl,
                                placeholder = { Text("http://192.168.1.100:11434/v1") },
                                leadingIcon = {
                                    Icon(
                                        imageVector = Icons.Default.Link,
                                        contentDescription = null,
                                        tint = HermesCyan
                                    )
                                },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(12.dp),
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedContainerColor = DarkSurface,
                                    unfocusedContainerColor = DarkSurface,
                                    focusedBorderColor = HermesCyan,
                                    unfocusedBorderColor = DarkBorder
                                )
                            )
                        }

                        Column {
                            Text(
                                text = "Model Name",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            OutlinedTextField(
                                value = state.customModel,
                                onValueChange = onUpdateCustomModel,
                                placeholder = { Text("e.g. hermes-3-llama-3.1-8b") },
                                leadingIcon = {
                                    Icon(
                                        imageVector = Icons.Default.Memory,
                                        contentDescription = null,
                                        tint = HermesCyan
                                    )
                                },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(12.dp),
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedContainerColor = DarkSurface,
                                    unfocusedContainerColor = DarkSurface,
                                    focusedBorderColor = HermesCyan,
                                    unfocusedBorderColor = DarkBorder
                                )
                            )
                        }
                    }
                }
            }
        }

        // Messaging Gateways Section Header
        Text(
            text = "Messaging Gateways",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onBackground
        )

        // 1. Telegram Gateway Card
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
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.Send,
                            contentDescription = "Telegram",
                            tint = HermesCyan
                        )
                        Column {
                            Text(
                                text = "Telegram Gateway",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onBackground
                            )
                            Text(
                                text = "Telegram Bot integration with Admin IDs",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    Switch(
                        checked = state.isTelegramEnabled,
                        onCheckedChange = onUpdateTelegramEnabled,
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = Color.White,
                            checkedTrackColor = HermesCyan
                        )
                    )
                }

                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Column {
                        Text(
                            text = "Bot Token",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        OutlinedTextField(
                            value = state.telegramToken,
                            onValueChange = onUpdateTelegramToken,
                            placeholder = { Text("123456789:ABCdefGhIJKlmNoPQRstuv") },
                            leadingIcon = {
                                Icon(
                                    imageVector = Icons.Default.Key,
                                    contentDescription = null,
                                    tint = HermesCyan
                                )
                            },
                            trailingIcon = {
                                IconButton(onClick = { isTelegramTokenVisible = !isTelegramTokenVisible }) {
                                    Icon(
                                        imageVector = if (isTelegramTokenVisible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                        contentDescription = if (isTelegramTokenVisible) "Hide Token" else "Show Token",
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            },
                            visualTransformation = if (isTelegramTokenVisible) VisualTransformation.None else PasswordVisualTransformation(),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                            singleLine = true,
                            isError = state.isTelegramEnabled && state.telegramToken.isBlank(),
                            supportingText = if (state.isTelegramEnabled && state.telegramToken.isBlank()) {
                                { Text("Bot token required when Telegram is enabled", color = MaterialTheme.colorScheme.error) }
                            } else null,
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(12.dp),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedContainerColor = DarkSurface,
                                unfocusedContainerColor = DarkSurface,
                                focusedBorderColor = if (state.isTelegramEnabled && state.telegramToken.isBlank()) MaterialTheme.colorScheme.error else HermesCyan,
                                unfocusedBorderColor = if (state.isTelegramEnabled && state.telegramToken.isBlank()) MaterialTheme.colorScheme.error else DarkBorder
                            )
                        )
                    }

                    Column {
                        Text(
                            text = "Allowed Admin User IDs",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        OutlinedTextField(
                            value = state.telegramAdminUserIds,
                            onValueChange = onUpdateTelegramAdminUserIds,
                            placeholder = { Text("e.g. 11111111, 22222222 (comma-separated)") },
                            leadingIcon = {
                                Icon(
                                    imageVector = Icons.Default.Tag,
                                    contentDescription = null,
                                    tint = HermesCyan
                                )
                            },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(12.dp),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedContainerColor = DarkSurface,
                                unfocusedContainerColor = DarkSurface,
                                focusedBorderColor = HermesCyan,
                                unfocusedBorderColor = DarkBorder
                            )
                        )
                    }
                }
            }
        }

        // 2. Discord Gateway Card
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
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Forum,
                            contentDescription = "Discord",
                            tint = HermesCyan
                        )
                        Column {
                            Text(
                                text = "Discord Gateway",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onBackground
                            )
                            Text(
                                text = "Discord Bot and Channel adapter",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    Switch(
                        checked = state.isDiscordEnabled,
                        onCheckedChange = onUpdateDiscordEnabled,
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = Color.White,
                            checkedTrackColor = HermesCyan
                        )
                    )
                }

                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Column {
                        Text(
                            text = "Bot Token",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        OutlinedTextField(
                            value = state.discordToken,
                            onValueChange = onUpdateDiscordToken,
                            placeholder = { Text("Discord Bot Token (e.g. OTg3...)") },
                            leadingIcon = {
                                Icon(
                                    imageVector = Icons.Default.Key,
                                    contentDescription = null,
                                    tint = HermesCyan
                                )
                            },
                            trailingIcon = {
                                IconButton(onClick = { isDiscordTokenVisible = !isDiscordTokenVisible }) {
                                    Icon(
                                        imageVector = if (isDiscordTokenVisible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                        contentDescription = if (isDiscordTokenVisible) "Hide Token" else "Show Token",
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            },
                            visualTransformation = if (isDiscordTokenVisible) VisualTransformation.None else PasswordVisualTransformation(),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                            singleLine = true,
                            isError = state.isDiscordEnabled && state.discordToken.isBlank(),
                            supportingText = if (state.isDiscordEnabled && state.discordToken.isBlank()) {
                                { Text("Bot token required when Discord is enabled", color = MaterialTheme.colorScheme.error) }
                            } else null,
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(12.dp),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedContainerColor = DarkSurface,
                                unfocusedContainerColor = DarkSurface,
                                focusedBorderColor = if (state.isDiscordEnabled && state.discordToken.isBlank()) MaterialTheme.colorScheme.error else HermesCyan,
                                unfocusedBorderColor = if (state.isDiscordEnabled && state.discordToken.isBlank()) MaterialTheme.colorScheme.error else DarkBorder
                            )
                        )
                    }

                    Column {
                        Text(
                            text = "Allowed Channel IDs",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        OutlinedTextField(
                            value = state.discordChannelIds,
                            onValueChange = onUpdateDiscordChannelIds,
                            placeholder = { Text("e.g. 123456789012345678, 987654321098765432") },
                            leadingIcon = {
                                Icon(
                                    imageVector = Icons.Default.Numbers,
                                    contentDescription = null,
                                    tint = HermesCyan
                                )
                            },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(12.dp),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedContainerColor = DarkSurface,
                                unfocusedContainerColor = DarkSurface,
                                focusedBorderColor = HermesCyan,
                                unfocusedBorderColor = DarkBorder
                            )
                        )
                    }
                }
            }
        }

        // 3. Slack Gateway Card
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
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.AlternateEmail,
                            contentDescription = "Slack",
                            tint = HermesCyan
                        )
                        Column {
                            Text(
                                text = "Slack Gateway",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onBackground
                            )
                            Text(
                                text = "Slack Socket Mode & Bot User Tokens",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    Switch(
                        checked = state.isSlackEnabled,
                        onCheckedChange = onUpdateSlackEnabled,
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = Color.White,
                            checkedTrackColor = HermesCyan
                        )
                    )
                }

                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Column {
                        Text(
                            text = "App-Level Token (Socket Mode)",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        OutlinedTextField(
                            value = state.slackAppToken,
                            onValueChange = onUpdateSlackAppToken,
                            placeholder = { Text("xapp-... (App Token)") },
                            leadingIcon = {
                                Icon(
                                    imageVector = Icons.Default.Key,
                                    contentDescription = null,
                                    tint = HermesCyan
                                )
                            },
                            trailingIcon = {
                                IconButton(onClick = { isSlackAppTokenVisible = !isSlackAppTokenVisible }) {
                                    Icon(
                                        imageVector = if (isSlackAppTokenVisible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                        contentDescription = if (isSlackAppTokenVisible) "Hide Token" else "Show Token",
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            },
                            visualTransformation = if (isSlackAppTokenVisible) VisualTransformation.None else PasswordVisualTransformation(),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                            singleLine = true,
                            isError = state.isSlackEnabled && state.slackAppToken.isBlank(),
                            supportingText = if (state.isSlackEnabled && state.slackAppToken.isBlank()) {
                                { Text("App token required when Slack is enabled", color = MaterialTheme.colorScheme.error) }
                            } else null,
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(12.dp),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedContainerColor = DarkSurface,
                                unfocusedContainerColor = DarkSurface,
                                focusedBorderColor = if (state.isSlackEnabled && state.slackAppToken.isBlank()) MaterialTheme.colorScheme.error else HermesCyan,
                                unfocusedBorderColor = if (state.isSlackEnabled && state.slackAppToken.isBlank()) MaterialTheme.colorScheme.error else DarkBorder
                            )
                        )
                    }

                    Column {
                        Text(
                            text = "Bot User OAuth Token",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        OutlinedTextField(
                            value = state.slackBotToken,
                            onValueChange = onUpdateSlackBotToken,
                            placeholder = { Text("xoxb-... (Bot Token)") },
                            leadingIcon = {
                                Icon(
                                    imageVector = Icons.Default.Key,
                                    contentDescription = null,
                                    tint = HermesCyan
                                )
                            },
                            trailingIcon = {
                                IconButton(onClick = { isSlackBotTokenVisible = !isSlackBotTokenVisible }) {
                                    Icon(
                                        imageVector = if (isSlackBotTokenVisible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                        contentDescription = if (isSlackBotTokenVisible) "Hide Token" else "Show Token",
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            },
                            visualTransformation = if (isSlackBotTokenVisible) VisualTransformation.None else PasswordVisualTransformation(),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                            singleLine = true,
                            isError = state.isSlackEnabled && state.slackBotToken.isBlank(),
                            supportingText = if (state.isSlackEnabled && state.slackBotToken.isBlank()) {
                                { Text("Bot token required when Slack is enabled", color = MaterialTheme.colorScheme.error) }
                            } else null,
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(12.dp),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedContainerColor = DarkSurface,
                                unfocusedContainerColor = DarkSurface,
                                focusedBorderColor = if (state.isSlackEnabled && state.slackBotToken.isBlank()) MaterialTheme.colorScheme.error else HermesCyan,
                                unfocusedBorderColor = if (state.isSlackEnabled && state.slackBotToken.isBlank()) MaterialTheme.colorScheme.error else DarkBorder
                            )
                        )
                    }
                }
            }
        }

        // 4. WhatsApp Gateway Card
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
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.PhoneAndroid,
                            contentDescription = "WhatsApp",
                            tint = HermesCyan
                        )
                        Column {
                            Text(
                                text = "WhatsApp Gateway",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onBackground
                            )
                            Text(
                                text = "Session Link & Webhook Token",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    Switch(
                        checked = state.isWhatsAppEnabled,
                        onCheckedChange = onUpdateWhatsAppEnabled,
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = Color.White,
                            checkedTrackColor = HermesCyan
                        )
                    )
                }

                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Column {
                        Text(
                            text = "Session Link / Pairing Code",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        OutlinedTextField(
                            value = state.whatsAppSessionLink,
                            onValueChange = onUpdateWhatsAppSessionLink,
                            placeholder = { Text("https://wa.me/... or pairing code") },
                            leadingIcon = {
                                Icon(
                                    imageVector = Icons.Default.Link,
                                    contentDescription = null,
                                    tint = HermesCyan
                                )
                            },
                            trailingIcon = {
                                IconButton(onClick = { isWhatsAppSessionLinkVisible = !isWhatsAppSessionLinkVisible }) {
                                    Icon(
                                        imageVector = if (isWhatsAppSessionLinkVisible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                        contentDescription = if (isWhatsAppSessionLinkVisible) "Hide Session Link" else "Show Session Link",
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            },
                            visualTransformation = if (isWhatsAppSessionLinkVisible) VisualTransformation.None else PasswordVisualTransformation(),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                            singleLine = true,
                            isError = state.isWhatsAppEnabled && state.whatsAppSessionLink.isBlank(),
                            supportingText = if (state.isWhatsAppEnabled && state.whatsAppSessionLink.isBlank()) {
                                { Text("Session link required when WhatsApp is enabled", color = MaterialTheme.colorScheme.error) }
                            } else null,
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(12.dp),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedContainerColor = DarkSurface,
                                unfocusedContainerColor = DarkSurface,
                                focusedBorderColor = if (state.isWhatsAppEnabled && state.whatsAppSessionLink.isBlank()) MaterialTheme.colorScheme.error else HermesCyan,
                                unfocusedBorderColor = if (state.isWhatsAppEnabled && state.whatsAppSessionLink.isBlank()) MaterialTheme.colorScheme.error else DarkBorder
                            )
                        )
                    }

                    Column {
                        Text(
                            text = "Webhook Secret Token",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        OutlinedTextField(
                            value = state.whatsAppWebhookToken,
                            onValueChange = onUpdateWhatsAppWebhookToken,
                            placeholder = { Text("Webhook verification token") },
                            leadingIcon = {
                                Icon(
                                    imageVector = Icons.Default.Key,
                                    contentDescription = null,
                                    tint = HermesCyan
                                )
                            },
                            trailingIcon = {
                                IconButton(onClick = { isWhatsAppWebhookTokenVisible = !isWhatsAppWebhookTokenVisible }) {
                                    Icon(
                                        imageVector = if (isWhatsAppWebhookTokenVisible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                        contentDescription = if (isWhatsAppWebhookTokenVisible) "Hide Token" else "Show Token",
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            },
                            visualTransformation = if (isWhatsAppWebhookTokenVisible) VisualTransformation.None else PasswordVisualTransformation(),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                            singleLine = true,
                            isError = state.isWhatsAppEnabled && state.whatsAppWebhookToken.isBlank(),
                            supportingText = if (state.isWhatsAppEnabled && state.whatsAppWebhookToken.isBlank()) {
                                { Text("Webhook token required when WhatsApp is enabled", color = MaterialTheme.colorScheme.error) }
                            } else null,
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(12.dp),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedContainerColor = DarkSurface,
                                unfocusedContainerColor = DarkSurface,
                                focusedBorderColor = if (state.isWhatsAppEnabled && state.whatsAppWebhookToken.isBlank()) MaterialTheme.colorScheme.error else HermesCyan,
                                unfocusedBorderColor = if (state.isWhatsAppEnabled && state.whatsAppWebhookToken.isBlank()) MaterialTheme.colorScheme.error else DarkBorder
                            )
                        )
                    }
                }
            }
        }

        // 5. REST API & Local Web Server Card
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
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Lan,
                            contentDescription = "REST API",
                            tint = HermesCyan
                        )
                        Column {
                            Text(
                                text = "REST API & Local Server",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onBackground
                            )
                            Text(
                                text = "Local HTTP webhook and API endpoint server",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    Switch(
                        checked = state.isRestApiEnabled,
                        onCheckedChange = onUpdateRestApiEnabled,
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = Color.White,
                            checkedTrackColor = HermesCyan
                        )
                    )
                }

                Column {
                    val portInt = state.restApiPort.trim().toIntOrNull()
                    val isPortValid = state.restApiPort.isBlank() || (portInt != null && portInt in 1..65535)

                    Text(
                        text = "Port (Default: 8000)",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    OutlinedTextField(
                        value = state.restApiPort,
                        onValueChange = onUpdateRestApiPort,
                        placeholder = { Text("8000") },
                        leadingIcon = {
                            Icon(
                                imageVector = Icons.Default.Numbers,
                                contentDescription = null,
                                tint = HermesCyan
                            )
                        },
                        isError = !isPortValid,
                        supportingText = if (!isPortValid) {
                            { Text("Valid port range: 1–65535", color = MaterialTheme.colorScheme.error) }
                        } else null,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedContainerColor = DarkSurface,
                            unfocusedContainerColor = DarkSurface,
                            focusedBorderColor = if (isPortValid) HermesCyan else MaterialTheme.colorScheme.error,
                            unfocusedBorderColor = if (isPortValid) DarkBorder else MaterialTheme.colorScheme.error
                        )
                    )
                }
            }
        }

        // System & Daemon Settings Card
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
                    text = "Daemon & Remote Access",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onBackground
                )

                // Auto-start switch
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Auto-start on Boot",
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.onBackground
                        )
                        Text(
                            text = "Start daemon automatically when phone restarts",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = state.isAutoStartEnabled,
                        onCheckedChange = onUpdateAutoStart,
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = Color.White,
                            checkedTrackColor = HermesCyan
                        )
                    )
                }

                Spacer(modifier = Modifier.height(4.dp))

                // Battery Optimization Exemption Card
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = DarkBorder.copy(alpha = 0.35f)
                    ),
                    shape = RoundedCornerShape(10.dp),
                    border = CardDefaults.outlinedCardBorder().copy(
                        brush = androidx.compose.ui.graphics.SolidColor(
                            if (state.isBatteryOptimizationIgnored) DarkBorder else StatusStarting.copy(alpha = 0.5f)
                        )
                    )
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.weight(1f)
                            ) {
                                Icon(
                                    imageVector = if (state.isBatteryOptimizationIgnored) Icons.Default.BatteryChargingFull else Icons.Default.BatteryAlert,
                                    contentDescription = "Battery Optimization",
                                    tint = if (state.isBatteryOptimizationIgnored) StatusRunning else StatusStarting,
                                    modifier = Modifier.size(20.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Column {
                                    Text(
                                        text = "Battery Optimization",
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.SemiBold,
                                        color = MaterialTheme.colorScheme.onBackground
                                    )
                                    Text(
                                        text = if (state.isBatteryOptimizationIgnored) {
                                            "Doze mode exemption active (Unrestricted background execution)"
                                        } else {
                                            "Optimized (May be killed by Android in background)"
                                        },
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                            Spacer(modifier = Modifier.width(8.dp))
                            Surface(
                                color = if (state.isBatteryOptimizationIgnored) StatusRunning.copy(alpha = 0.2f) else StatusStarting.copy(alpha = 0.2f),
                                shape = CircleShape,
                                modifier = Modifier.border(
                                    1.dp,
                                    if (state.isBatteryOptimizationIgnored) StatusRunning.copy(alpha = 0.5f) else StatusStarting.copy(alpha = 0.5f),
                                    CircleShape
                                )
                            ) {
                                Text(
                                    text = if (state.isBatteryOptimizationIgnored) "UNRESTRICTED" else "OPTIMIZED",
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = if (state.isBatteryOptimizationIgnored) StatusRunning else StatusStarting,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                )
                            }
                        }

                        // Actions for exemption & guidance
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            if (!state.isBatteryOptimizationIgnored && onRequestBatteryExemption != null) {
                                Button(
                                    onClick = onRequestBatteryExemption,
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = HermesCyan,
                                        contentColor = DarkSurface
                                    ),
                                    shape = RoundedCornerShape(8.dp),
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Text(
                                        text = "Request Exemption",
                                        fontWeight = FontWeight.Bold,
                                        style = MaterialTheme.typography.labelMedium
                                    )
                                }
                            }

                            OutlinedButton(
                                onClick = {
                                    try {
                                        val browserIntent = Intent(Intent.ACTION_VIEW, Uri.parse(oemGuidanceUrl)).apply {
                                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                        }
                                        context.startActivity(browserIntent)
                                    } catch (e: Exception) {
                                        Toast.makeText(context, "Unable to open OEM guide: ${e.message}", Toast.LENGTH_SHORT).show()
                                    }
                                },
                                shape = RoundedCornerShape(8.dp),
                                modifier = if (!state.isBatteryOptimizationIgnored && onRequestBatteryExemption != null) Modifier.weight(1f) else Modifier.fillMaxWidth()
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.Center
                                ) {
                                    Text(
                                        text = "OEM Guidance",
                                        style = MaterialTheme.typography.labelMedium
                                    )
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Icon(
                                        imageVector = Icons.AutoMirrored.Filled.OpenInNew,
                                        contentDescription = "Open OEM Guide",
                                        modifier = Modifier.size(14.dp)
                                    )
                                }
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(4.dp))

                // Public Tunnel switch
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Cloudflare Public Tunnel",
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.onBackground
                        )
                        Text(
                            text = "Expose dashboard/webhook securely via TryCloudflare",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = state.isPublicTunnelEnabled,
                        onCheckedChange = onUpdatePublicTunnel,
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = Color.White,
                            checkedTrackColor = HermesCyan
                        )
                    )
                }
            }
        }

        // Save Confirmation Button
        Button(
            onClick = onSaveSettings,
            enabled = !state.isSavingSettings,
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp),
            shape = RoundedCornerShape(12.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = HermesCyan,
                contentColor = DarkSurface,
                disabledContainerColor = HermesCyanDark.copy(alpha = 0.5f),
                disabledContentColor = DarkSurface.copy(alpha = 0.7f)
            )
        ) {
            if (state.isSavingSettings) {
                CircularProgressIndicator(
                    modifier = Modifier.size(20.dp),
                    color = DarkSurface,
                    strokeWidth = 2.dp
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "Saving...",
                    fontWeight = FontWeight.Bold
                )
            } else {
                Icon(imageVector = Icons.Default.Check, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "Save Settings",
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}
