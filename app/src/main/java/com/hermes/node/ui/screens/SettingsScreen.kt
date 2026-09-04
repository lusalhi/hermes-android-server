package com.hermes.node.ui.screens

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Forum
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Lan
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Numbers
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.Tag
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.TravelExplore
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.hermes.node.data.model.SkillsConfig
import com.hermes.node.ui.theme.DarkBorder
import com.hermes.node.ui.theme.DarkSurface
import com.hermes.node.ui.theme.HermesCyan
import com.hermes.node.ui.theme.HermesCyanDark
import com.hermes.node.ui.theme.StatusRunning
import com.hermes.node.ui.theme.StatusStarting
import com.hermes.node.viewmodel.PackageManagerStatus
import com.hermes.node.viewmodel.ServerStatus
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
    onToggleSkill: (String, Boolean) -> Unit = { _, _ -> },
    onUpdateSearchProvider: (String) -> Unit = {},
    onUpdateSearchApiKey: (String) -> Unit = {},
    onToggleSearchApiKeyVisibility: () -> Unit = {},
    onRefreshStorageUsage: () -> Unit = {},
    onExportMemoryToDownloads: () -> Unit = {},
    onShareMemoryBackup: () -> Unit = {},
    onShowClearMemoryDialog: () -> Unit = {},
    onDismissClearMemoryDialog: () -> Unit = {},
    onConfirmClearMemory: () -> Unit = {},
    onDismissMemoryActionMessage: () -> Unit = {},
    onUpdateSharedStorageEnabled: (Boolean) -> Unit = {},
    onInstallPackageManager: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val scrollState = rememberScrollState()
    val context = LocalContext.current

    var isApiKeyVisible by rememberSaveable { mutableStateOf(false) }
    var isSearchApiKeyVisible by rememberSaveable { mutableStateOf(false) }
    var isSearchProviderDropdownOpen by rememberSaveable { mutableStateOf(false) }
    var isTelegramTokenVisible by rememberSaveable { mutableStateOf(false) }
    var isDiscordTokenVisible by rememberSaveable { mutableStateOf(false) }
    var isSlackAppTokenVisible by rememberSaveable { mutableStateOf(false) }
    var isSlackBotTokenVisible by rememberSaveable { mutableStateOf(false) }
    var isWhatsAppSessionLinkVisible by rememberSaveable { mutableStateOf(false) }
    var isWhatsAppWebhookTokenVisible by rememberSaveable { mutableStateOf(false) }
    var isProviderDropdownOpen by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        onRefreshStorageUsage()
    }

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

        // Memory Action Feedback Banner
        AnimatedVisibility(visible = state.memoryActionMessage != null) {
            val isSuccess = state.isMemoryActionSuccess
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
                        text = state.memoryActionMessage ?: "",
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (isSuccess) MaterialTheme.colorScheme.onBackground else MaterialTheme.colorScheme.onErrorContainer,
                        modifier = Modifier.weight(1f)
                    )
                    IconButton(onClick = onDismissMemoryActionMessage) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Dismiss memory message",
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

        // Agent Skills & Capabilities Section Header
        Text(
            text = "Agent Skills & Capabilities",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onBackground
        )

        // Agent Skills & Capabilities Card
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = DarkSurface),
            shape = RoundedCornerShape(12.dp),
            border = CardDefaults.outlinedCardBorder().copy(brush = androidx.compose.ui.graphics.SolidColor(DarkBorder))
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Extension,
                        contentDescription = "Skills",
                        tint = HermesCyan
                    )
                    Column {
                        Text(
                            text = "Installed Capabilities",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onBackground
                        )
                        Text(
                            text = "Autonomous tool execution whitelist for Hermes Agent",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                if (state.installedSkills.isEmpty()) {
                    Text(
                        text = "No skills installed or detected.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    state.installedSkills.forEach { skill ->
                        val icon = when (skill.id) {
                            SkillsConfig.SKILL_WEB_SEARCH -> Icons.Default.TravelExplore
                            SkillsConfig.SKILL_FILE_MANAGER -> Icons.Default.FolderOpen
                            SkillsConfig.SKILL_BASH_RUNNER -> Icons.Default.Terminal
                            SkillsConfig.SKILL_CRON_SCHEDULER -> Icons.Default.Schedule
                            else -> Icons.Default.Extension
                        }

                        Column(
                            modifier = Modifier.fillMaxWidth(),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(
                                    modifier = Modifier.weight(1f),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(40.dp)
                                            .background(
                                                color = if (skill.enabled) HermesCyan.copy(alpha = 0.15f) else DarkBorder.copy(alpha = 0.3f),
                                                shape = RoundedCornerShape(8.dp)
                                            )
                                            .border(
                                                width = 1.dp,
                                                color = if (skill.enabled) HermesCyan.copy(alpha = 0.5f) else DarkBorder,
                                                shape = RoundedCornerShape(8.dp)
                                            ),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Icon(
                                            imageVector = icon,
                                            contentDescription = skill.name,
                                            tint = if (skill.enabled) HermesCyan else MaterialTheme.colorScheme.onSurfaceVariant,
                                            modifier = Modifier.size(22.dp)
                                        )
                                    }
                                    Column(modifier = Modifier.weight(1f)) {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                                        ) {
                                            Text(
                                                text = skill.name,
                                                style = MaterialTheme.typography.bodyLarge,
                                                fontWeight = FontWeight.SemiBold,
                                                color = MaterialTheme.colorScheme.onBackground
                                            )
                                            if (skill.isCore) {
                                                Surface(
                                                    color = DarkBorder.copy(alpha = 0.5f),
                                                    shape = RoundedCornerShape(4.dp)
                                                ) {
                                                    Text(
                                                        text = "CORE",
                                                        style = MaterialTheme.typography.labelSmall,
                                                        color = HermesCyan,
                                                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
                                                    )
                                                }
                                            }
                                        }
                                        Spacer(modifier = Modifier.height(2.dp))
                                        Text(
                                            text = skill.description,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                                Spacer(modifier = Modifier.width(8.dp))
                                Switch(
                                    checked = skill.enabled,
                                    onCheckedChange = { enabled ->
                                        onToggleSkill(skill.id, enabled)
                                    },
                                    modifier = Modifier.semantics {
                                        contentDescription = "Toggle ${skill.name} capability"
                                    },
                                    colors = SwitchDefaults.colors(
                                        checkedThumbColor = Color.White,
                                        checkedTrackColor = HermesCyan
                                    )
                                )
                            }

                            if (skill.id == SkillsConfig.SKILL_WEB_SEARCH) {
                                AnimatedVisibility(visible = skill.enabled) {
                                    Column(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(start = 52.dp, top = 4.dp, bottom = 8.dp),
                                        verticalArrangement = Arrangement.spacedBy(10.dp)
                                    ) {
                                        val searchProviders = listOf(
                                            SkillsConfig.SEARCH_PROVIDER_BRAVE to "Brave Search (Default)",
                                            SkillsConfig.SEARCH_PROVIDER_TAVILY to "Tavily",
                                            SkillsConfig.SEARCH_PROVIDER_FIRECRAWL to "Firecrawl",
                                            SkillsConfig.SEARCH_PROVIDER_EXA to "Exa"
                                        )
                                        val activeProvider = state.skillsConfig.searchProvider.lowercase().trim().ifBlank { SkillsConfig.SEARCH_PROVIDER_BRAVE }
                                        val currentSearchLabel = searchProviders.firstOrNull { it.first == activeProvider }?.second
                                            ?: SkillsConfig.formatSearchProviderLabel(activeProvider)

                                        // Provider Selector Dropdown
                                        Column {
                                            Text(
                                                text = "Search Provider",
                                                style = MaterialTheme.typography.labelMedium,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                            Spacer(modifier = Modifier.height(4.dp))
                                            OutlinedButton(
                                                onClick = { isSearchProviderDropdownOpen = true },
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
                                                    Text(text = currentSearchLabel)
                                                    Icon(
                                                        imageVector = Icons.Default.ArrowDropDown,
                                                        contentDescription = "Select Search Provider"
                                                    )
                                                }
                                            }

                                            DropdownMenu(
                                                expanded = isSearchProviderDropdownOpen,
                                                onDismissRequest = { isSearchProviderDropdownOpen = false }
                                            ) {
                                                searchProviders.forEach { (key, label) ->
                                                    DropdownMenuItem(
                                                        text = { Text(label) },
                                                        onClick = {
                                                            onUpdateSearchProvider(key)
                                                            isSearchProviderDropdownOpen = false
                                                        }
                                                    )
                                                }
                                            }
                                        }

                                        // Search API Key Field
                                        Column {
                                            Text(
                                                text = "Search API Key",
                                                style = MaterialTheme.typography.labelMedium,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                            Spacer(modifier = Modifier.height(4.dp))
                                            OutlinedTextField(
                                                value = state.skillsConfig.searchApiKey,
                                                onValueChange = onUpdateSearchApiKey,
                                                placeholder = { Text("Enter ${SkillsConfig.formatSearchProviderLabel(activeProvider)} API Key") },
                                                leadingIcon = {
                                                    Icon(
                                                        imageVector = Icons.Default.Key,
                                                        contentDescription = null,
                                                        tint = HermesCyan
                                                    )
                                                },
                                                trailingIcon = {
                                                    IconButton(onClick = {
                                                        isSearchApiKeyVisible = !isSearchApiKeyVisible
                                                        onToggleSearchApiKeyVisibility()
                                                    }) {
                                                        Icon(
                                                            imageVector = if (isSearchApiKeyVisible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                                            contentDescription = if (isSearchApiKeyVisible) "Hide Search Key" else "Show Search Key",
                                                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                                                        )
                                                    }
                                                },
                                                visualTransformation = if (isSearchApiKeyVisible) VisualTransformation.None else PasswordVisualTransformation(),
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

                                        // Helper info warning
                                        if (state.skillsConfig.searchApiKey.isBlank()) {
                                            Row(
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                                            ) {
                                                Icon(
                                                    imageVector = Icons.Default.Warning,
                                                    contentDescription = null,
                                                    tint = MaterialTheme.colorScheme.error,
                                                    modifier = Modifier.size(16.dp)
                                                )
                                                Text(
                                                    text = "An API key is required to perform live web searches. Without a key, web search queries will be skipped.",
                                                    style = MaterialTheme.typography.bodySmall,
                                                    color = MaterialTheme.colorScheme.error
                                                )
                                            }
                                        } else {
                                            Text(
                                                text = "Key is encrypted with Keystore and exported to daemon environment.",
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        // Extended Linux Toolchain & Package Manager Card
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = DarkSurface),
            shape = RoundedCornerShape(12.dp),
            border = CardDefaults.outlinedCardBorder().copy(brush = androidx.compose.ui.graphics.SolidColor(DarkBorder))
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(40.dp)
                            .background(
                                color = if (state.packageManagerStatus == PackageManagerStatus.READY)
                                    HermesCyan.copy(alpha = 0.15f)
                                else DarkBorder.copy(alpha = 0.3f),
                                shape = RoundedCornerShape(8.dp)
                            )
                            .border(
                                width = 1.dp,
                                color = if (state.packageManagerStatus == PackageManagerStatus.READY)
                                    HermesCyan.copy(alpha = 0.5f)
                                else DarkBorder,
                                shape = RoundedCornerShape(8.dp)
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Terminal,
                            contentDescription = "Package Manager",
                            tint = if (state.packageManagerStatus == PackageManagerStatus.READY)
                                HermesCyan
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(22.dp)
                        )
                    }
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Extended Linux Toolchain & Package Manager",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onBackground
                        )
                        Text(
                            text = "Alpine apk package manager for installing native ARM64 Linux packages",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                // Status Row
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Status",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = when (state.packageManagerStatus) {
                            PackageManagerStatus.READY -> StatusRunning.copy(alpha = 0.15f)
                            PackageManagerStatus.DOWNLOADING, PackageManagerStatus.EXTRACTING -> StatusStarting.copy(alpha = 0.15f)
                            PackageManagerStatus.ERROR -> MaterialTheme.colorScheme.errorContainer
                            PackageManagerStatus.NOT_INSTALLED -> DarkBorder.copy(alpha = 0.5f)
                        }
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(8.dp)
                                    .background(
                                        color = when (state.packageManagerStatus) {
                                            PackageManagerStatus.READY -> StatusRunning
                                            PackageManagerStatus.DOWNLOADING, PackageManagerStatus.EXTRACTING -> StatusStarting
                                            PackageManagerStatus.ERROR -> MaterialTheme.colorScheme.error
                                            PackageManagerStatus.NOT_INSTALLED -> MaterialTheme.colorScheme.onSurfaceVariant
                                        },
                                        shape = CircleShape
                                    )
                            )
                            Text(
                                text = when (state.packageManagerStatus) {
                                    PackageManagerStatus.READY -> "Ready"
                                    PackageManagerStatus.DOWNLOADING -> "Downloading"
                                    PackageManagerStatus.EXTRACTING -> "Extracting"
                                    PackageManagerStatus.ERROR -> "Error"
                                    PackageManagerStatus.NOT_INSTALLED -> "Not Installed"
                                },
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = when (state.packageManagerStatus) {
                                    PackageManagerStatus.READY -> StatusRunning
                                    PackageManagerStatus.DOWNLOADING, PackageManagerStatus.EXTRACTING -> StatusStarting
                                    PackageManagerStatus.ERROR -> MaterialTheme.colorScheme.error
                                    PackageManagerStatus.NOT_INSTALLED -> MaterialTheme.colorScheme.onSurfaceVariant
                                }
                            )
                        }
                    }
                }

                // Installed tools summary
                if (state.installedToolsSummary.isNotBlank()) {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            text = "Installed Tools",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = state.installedToolsSummary,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onBackground
                        )
                    }
                }

                // Progress indicator during download / extraction
                if (state.packageManagerStatus == PackageManagerStatus.DOWNLOADING ||
                    state.packageManagerStatus == PackageManagerStatus.EXTRACTING
                ) {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        LinearProgressIndicator(
                            progress = { state.packageManagerProgress },
                            modifier = Modifier.fillMaxWidth(),
                            color = HermesCyan,
                            trackColor = DarkBorder
                        )
                        state.packageManagerMessage?.let { msg ->
                            Text(
                                text = msg,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }

                // Error message
                if (state.packageManagerStatus == PackageManagerStatus.ERROR && !state.packageManagerMessage.isNullOrBlank()) {
                    Text(
                        text = state.packageManagerMessage,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }

                // Action button
                val isBusy = state.packageManagerStatus == PackageManagerStatus.DOWNLOADING ||
                        state.packageManagerStatus == PackageManagerStatus.EXTRACTING
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    if (state.packageManagerStatus == PackageManagerStatus.READY) {
                        OutlinedButton(
                            onClick = onInstallPackageManager,
                            enabled = !isBusy,
                            colors = ButtonDefaults.outlinedButtonColors(
                                contentColor = HermesCyan
                            ),
                            border = androidx.compose.foundation.BorderStroke(1.dp, HermesCyan)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Download,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Reinstall / Update")
                        }
                    } else {
                        Button(
                            onClick = onInstallPackageManager,
                            enabled = !isBusy,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = HermesCyan,
                                contentColor = DarkSurface
                            )
                        ) {
                            if (isBusy) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(16.dp),
                                    strokeWidth = 2.dp,
                                    color = DarkSurface
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    if (state.packageManagerStatus == PackageManagerStatus.DOWNLOADING)
                                        "Downloading..."
                                    else
                                        "Extracting..."
                                )
                            } else {
                                Icon(
                                    imageVector = Icons.Default.Download,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("Install Package Manager")
                            }
                        }
                    }
                }
            }
        }

        // Device Shared Storage Card
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = DarkSurface),
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
                Row(
                    modifier = Modifier.weight(1f),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(40.dp)
                            .background(
                                color = if (state.skillsConfig.sharedStorageEnabled) HermesCyan.copy(alpha = 0.15f) else DarkBorder.copy(alpha = 0.3f),
                                shape = RoundedCornerShape(8.dp)
                            )
                            .border(
                                width = 1.dp,
                                color = if (state.skillsConfig.sharedStorageEnabled) HermesCyan.copy(alpha = 0.5f) else DarkBorder,
                                shape = RoundedCornerShape(8.dp)
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.FolderOpen,
                            contentDescription = "Device Shared Storage",
                            tint = if (state.skillsConfig.sharedStorageEnabled) HermesCyan else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(22.dp)
                        )
                    }
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Device Shared Storage",
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onBackground
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = "Bind-mount device shared storage (/shared, /sdcard/Download) into PRoot sandbox",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                Spacer(modifier = Modifier.width(8.dp))
                Switch(
                    checked = state.skillsConfig.sharedStorageEnabled,
                    onCheckedChange = { enabled ->
                        onUpdateSharedStorageEnabled(enabled)
                    },
                    modifier = Modifier.semantics {
                        contentDescription = "Toggle Device Shared Storage"
                    },
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = Color.White,
                        checkedTrackColor = HermesCyan
                    )
                )
            }
        }

        // Episodic Memory & Storage Section Header
        Text(
            text = "Episodic Memory & Storage",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onBackground
        )

        // API 28 (Android 9) requires a runtime WRITE_EXTERNAL_STORAGE grant to write to the
        // public Downloads directory, so request it before dispatching the export action.
        var isStoragePermissionDenied by remember { mutableStateOf(false) }
        val storagePermissionLauncher = rememberLauncherForActivityResult(
            ActivityResultContracts.RequestPermission()
        ) { granted ->
            if (granted) {
                isStoragePermissionDenied = false
                onExportMemoryToDownloads()
            } else {
                isStoragePermissionDenied = true
            }
        }
        val onExportBackupClicked = {
            isStoragePermissionDenied = false
            val requiresRuntimePermission = Build.VERSION.SDK_INT <= Build.VERSION_CODES.P
            if (requiresRuntimePermission) {
                val permission = Manifest.permission.WRITE_EXTERNAL_STORAGE
                val alreadyGranted = ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
                if (alreadyGranted) {
                    onExportMemoryToDownloads()
                } else {
                    storagePermissionLauncher.launch(permission)
                }
            } else {
                onExportMemoryToDownloads()
            }
        }

        // Episodic Memory & Storage Card
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = DarkSurface),
            shape = RoundedCornerShape(12.dp),
            border = CardDefaults.outlinedCardBorder().copy(brush = androidx.compose.ui.graphics.SolidColor(DarkBorder))
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // Storage Usage Header Row
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        modifier = Modifier.weight(1f),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(40.dp)
                                .background(
                                    color = HermesCyan.copy(alpha = 0.15f),
                                    shape = RoundedCornerShape(8.dp)
                                )
                                .border(
                                    width = 1.dp,
                                    color = HermesCyan.copy(alpha = 0.5f),
                                    shape = RoundedCornerShape(8.dp)
                                ),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.Storage,
                                contentDescription = "Storage",
                                tint = HermesCyan,
                                modifier = Modifier.size(22.dp)
                            )
                        }
                        Column {
                            Text(
                                text = "Episodic Memory",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onBackground
                            )
                            Text(
                                text = "SQLite checkpoints & conversation history",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Surface(
                        color = DarkBorder.copy(alpha = 0.5f),
                        shape = CircleShape,
                        modifier = Modifier.border(1.dp, DarkBorder, CircleShape)
                    ) {
                        Text(
                            text = state.storageSizeFormatted,
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = HermesCyan,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
                        )
                    }
                }

                // Backup consistency notice while the daemon is actively writing memory
                if (state.status == ServerStatus.RUNNING) {
                    Text(
                        text = "⚠ Server is running — backups may be less consistent. Stop the server for a consistent snapshot.",
                        style = MaterialTheme.typography.bodySmall,
                        color = StatusStarting
                    )
                }

                // Action Buttons: Export to Downloads & Share Backup
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = onExportBackupClicked,
                        enabled = !state.isExportingMemory && !state.isResettingMemory,
                        shape = RoundedCornerShape(8.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = DarkBorder.copy(alpha = 0.6f),
                            contentColor = MaterialTheme.colorScheme.onBackground
                        ),
                        modifier = Modifier.weight(1f)
                    ) {
                        if (state.isExportingMemory) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                color = HermesCyan,
                                strokeWidth = 2.dp
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "Exporting...",
                                style = MaterialTheme.typography.labelMedium
                            )
                        } else {
                            Icon(
                                imageVector = Icons.Default.Download,
                                contentDescription = "Export Backup",
                                tint = HermesCyan,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "Export Backup",
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }

                    OutlinedButton(
                        onClick = onShareMemoryBackup,
                        enabled = !state.isExportingMemory && !state.isResettingMemory,
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Share,
                            contentDescription = "Share Backup",
                            tint = HermesCyan,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "Share Backup",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }

                if (isStoragePermissionDenied) {
                    Text(
                        text = "Storage permission is required on Android 9 to export backups to public Downloads.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }

                // Clear Memory Button (Destructive action)
                val isClearEnabled = state.storageSizeBytes > 0L && !state.isResettingMemory && !state.isExportingMemory
                Button(
                    onClick = onShowClearMemoryDialog,
                    enabled = isClearEnabled,
                    shape = RoundedCornerShape(8.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error.copy(alpha = 0.2f),
                        contentColor = MaterialTheme.colorScheme.error,
                        disabledContainerColor = DarkBorder.copy(alpha = 0.2f),
                        disabledContentColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                    ),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    if (state.isResettingMemory) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            color = MaterialTheme.colorScheme.error,
                            strokeWidth = 2.dp
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Wiping Memory...",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold
                        )
                    } else {
                        Icon(
                            imageVector = Icons.Default.DeleteForever,
                            contentDescription = "Clear Memory",
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "Clear Memory",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold
                        )
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

        // Clear Memory Confirmation Dialog
        if (state.showClearMemoryDialog) {
            AlertDialog(
                onDismissRequest = onDismissClearMemoryDialog,
                icon = {
                    Icon(
                        imageVector = Icons.Default.Warning,
                        contentDescription = "Warning",
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(32.dp)
                    )
                },
                title = {
                    Text(
                        text = "Wipe Episodic Memory?",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onBackground
                    )
                },
                text = {
                    val isRunning = state.status == ServerStatus.RUNNING
                    val warningPrefix = if (isRunning) {
                        "⚠️ Server is currently RUNNING. Active agent memory will be wiped and the running daemon will re-initialize a clean database state.\n\n"
                    } else ""
                    Text(
                        text = "${warningPrefix}This will permanently delete all episodic SQLite conversation databases and agent checkpoints. This action cannot be undone.\n\nYour LLM provider API keys, gateway tokens, skill settings, and Linux environment will remain intact.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                },
                confirmButton = {
                    Button(
                        onClick = onConfirmClearMemory,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.error,
                            contentColor = MaterialTheme.colorScheme.onError
                        ),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text(
                            text = "Wipe Memory",
                            fontWeight = FontWeight.Bold
                        )
                    }
                },
                dismissButton = {
                    OutlinedButton(
                        onClick = onDismissClearMemoryDialog,
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text(text = "Cancel")
                    }
                },
                containerColor = DarkSurface,
                shape = RoundedCornerShape(16.dp)
            )
        }
    }
}
