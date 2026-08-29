package com.hermes.node.data

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.hermes.node.data.model.DiscordGatewayConfig
import com.hermes.node.data.model.GatewayConfig
import com.hermes.node.data.model.HermesConfig
import com.hermes.node.data.model.ProviderConfig
import com.hermes.node.data.model.RestApiGatewayConfig
import com.hermes.node.data.model.SlackGatewayConfig
import com.hermes.node.data.model.SystemConfig
import com.hermes.node.data.model.TelegramGatewayConfig
import com.hermes.node.data.model.WhatsAppGatewayConfig

interface ConfigRepository {
    fun saveConfig(config: HermesConfig)
    fun getConfig(): HermesConfig
    fun clear()

    fun getProvider(): String
    fun saveProvider(provider: String)
    fun getApiKey(): String
    fun saveApiKey(apiKey: String)
    fun getTelegramToken(): String
    fun saveTelegramToken(token: String)
    fun isTelegramEnabled(): Boolean = getTelegramToken().isNotBlank()
    fun saveTelegramEnabled(enabled: Boolean) {}
    fun getTelegramAdminUserIds(): String = ""
    fun saveTelegramAdminUserIds(adminIds: String) {}

    fun isDiscordEnabled(): Boolean = false
    fun saveDiscordEnabled(enabled: Boolean) {}
    fun getDiscordToken(): String = ""
    fun saveDiscordToken(token: String) {}
    fun getDiscordChannelIds(): String = ""
    fun saveDiscordChannelIds(channelIds: String) {}

    fun isSlackEnabled(): Boolean = false
    fun saveSlackEnabled(enabled: Boolean) {}
    fun getSlackAppToken(): String = ""
    fun saveSlackAppToken(token: String) {}
    fun getSlackBotToken(): String = ""
    fun saveSlackBotToken(token: String) {}

    fun isWhatsAppEnabled(): Boolean = false
    fun saveWhatsAppEnabled(enabled: Boolean) {}
    fun getWhatsAppSessionLink(): String = ""
    fun saveWhatsAppSessionLink(sessionLink: String) {}
    fun getWhatsAppWebhookToken(): String = ""
    fun saveWhatsAppWebhookToken(token: String) {}

    fun isRestApiEnabled(): Boolean = true
    fun saveRestApiEnabled(enabled: Boolean) {}
    fun getRestApiPort(): Int = 8000
    fun saveRestApiPort(port: Int) {}

    fun getCustomModel(): String
    fun saveCustomModel(model: String)
    fun getCustomBaseUrl(): String
    fun saveCustomBaseUrl(baseUrl: String)
    fun isAutoStartEnabled(): Boolean
    fun saveAutoStart(enabled: Boolean)
    fun isPublicTunnelEnabled(): Boolean
    fun savePublicTunnel(enabled: Boolean)
}

class EncryptedConfigRepository(
    private val prefs: SharedPreferences
) : ConfigRepository {

    companion object {
        const val PREFS_FILE = "hermes_secure_preferences"
        const val FALLBACK_PREFS_FILE = "hermes_node_prefs_fallback"
        const val KEY_PROVIDER = "key_provider"
        const val KEY_API_KEY = "key_api_key"
        const val KEY_CUSTOM_MODEL = "key_custom_model"
        const val KEY_CUSTOM_BASE_URL = "key_custom_base_url"

        const val KEY_TELEGRAM_ENABLED = "key_telegram_enabled"
        const val KEY_TELEGRAM_TOKEN = "key_telegram_token"
        const val KEY_TELEGRAM_ADMIN_IDS = "key_telegram_admin_ids"

        const val KEY_DISCORD_ENABLED = "key_discord_enabled"
        const val KEY_DISCORD_TOKEN = "key_discord_token"
        const val KEY_DISCORD_CHANNEL_IDS = "key_discord_channel_ids"

        const val KEY_SLACK_ENABLED = "key_slack_enabled"
        const val KEY_SLACK_APP_TOKEN = "key_slack_app_token"
        const val KEY_SLACK_BOT_TOKEN = "key_slack_bot_token"

        const val KEY_WHATSAPP_ENABLED = "key_whatsapp_enabled"
        const val KEY_WHATSAPP_SESSION_LINK = "key_whatsapp_session_link"
        const val KEY_WHATSAPP_WEBHOOK_TOKEN = "key_whatsapp_webhook_token"

        const val KEY_REST_API_ENABLED = "key_rest_api_enabled"
        const val KEY_REST_API_PORT = "key_rest_api_port"

        const val KEY_AUTO_START = "key_auto_start"
        const val KEY_PUBLIC_TUNNEL = "key_public_tunnel"

        fun create(context: Context): ConfigRepository {
            return try {
                val masterKey = MasterKey.Builder(context)
                    .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                    .build()

                val securePrefs = EncryptedSharedPreferences.create(
                    context,
                    PREFS_FILE,
                    masterKey,
                    EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                    EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
                )
                EncryptedConfigRepository(securePrefs)
            } catch (e: Exception) {
                // Fallback to isolated unencrypted preferences if hardware Keystore is unavailable
                try {
                    android.util.Log.w("ConfigRepository", "Keystore initialization failed, falling back to isolated preferences: ${e.message}")
                } catch (_: Throwable) {
                    System.err.println("Keystore initialization failed, falling back to isolated preferences: ${e.message}")
                }
                val fallbackPrefs = context.getSharedPreferences(FALLBACK_PREFS_FILE, Context.MODE_PRIVATE)
                EncryptedConfigRepository(fallbackPrefs)
            }
        }
    }

    override fun saveConfig(config: HermesConfig) {
        prefs.edit()
            .putString(KEY_PROVIDER, config.provider.provider)
            .putString(KEY_API_KEY, config.provider.apiKey)
            .putString(KEY_CUSTOM_MODEL, config.provider.model)
            .putString(KEY_CUSTOM_BASE_URL, config.provider.baseUrl)
            .putBoolean(KEY_TELEGRAM_ENABLED, config.gateway.telegram.enabled)
            .putString(KEY_TELEGRAM_TOKEN, config.gateway.telegram.botToken)
            .putString(KEY_TELEGRAM_ADMIN_IDS, config.gateway.telegram.adminUserIds)
            .putBoolean(KEY_DISCORD_ENABLED, config.gateway.discord.enabled)
            .putString(KEY_DISCORD_TOKEN, config.gateway.discord.botToken)
            .putString(KEY_DISCORD_CHANNEL_IDS, config.gateway.discord.channelIds)
            .putBoolean(KEY_SLACK_ENABLED, config.gateway.slack.enabled)
            .putString(KEY_SLACK_APP_TOKEN, config.gateway.slack.appToken)
            .putString(KEY_SLACK_BOT_TOKEN, config.gateway.slack.botToken)
            .putBoolean(KEY_WHATSAPP_ENABLED, config.gateway.whatsapp.enabled)
            .putString(KEY_WHATSAPP_SESSION_LINK, config.gateway.whatsapp.sessionLink)
            .putString(KEY_WHATSAPP_WEBHOOK_TOKEN, config.gateway.whatsapp.webhookToken)
            .putBoolean(KEY_REST_API_ENABLED, config.gateway.restApi.enabled)
            .putInt(KEY_REST_API_PORT, config.gateway.restApi.port)
            .putBoolean(KEY_AUTO_START, config.system.autoStartOnBoot)
            .putBoolean(KEY_PUBLIC_TUNNEL, config.system.publicTunnelEnabled)
            .apply()
    }

    override fun getConfig(): HermesConfig {
        val telegramToken = prefs.getString(KEY_TELEGRAM_TOKEN, "") ?: ""
        val isTelegramEnabled = if (prefs.contains(KEY_TELEGRAM_ENABLED)) {
            prefs.getBoolean(KEY_TELEGRAM_ENABLED, false)
        } else {
            telegramToken.isNotBlank()
        }

        return HermesConfig(
            provider = ProviderConfig(
                provider = prefs.getString(KEY_PROVIDER, "nous_portal") ?: "nous_portal",
                apiKey = prefs.getString(KEY_API_KEY, "") ?: "",
                model = prefs.getString(KEY_CUSTOM_MODEL, "") ?: "",
                baseUrl = prefs.getString(KEY_CUSTOM_BASE_URL, "") ?: ""
            ),
            gateway = GatewayConfig(
                telegram = TelegramGatewayConfig(
                    enabled = isTelegramEnabled,
                    botToken = telegramToken,
                    adminUserIds = prefs.getString(KEY_TELEGRAM_ADMIN_IDS, "") ?: ""
                ),
                discord = DiscordGatewayConfig(
                    enabled = prefs.getBoolean(KEY_DISCORD_ENABLED, false),
                    botToken = prefs.getString(KEY_DISCORD_TOKEN, "") ?: "",
                    channelIds = prefs.getString(KEY_DISCORD_CHANNEL_IDS, "") ?: ""
                ),
                slack = SlackGatewayConfig(
                    enabled = prefs.getBoolean(KEY_SLACK_ENABLED, false),
                    appToken = prefs.getString(KEY_SLACK_APP_TOKEN, "") ?: "",
                    botToken = prefs.getString(KEY_SLACK_BOT_TOKEN, "") ?: ""
                ),
                whatsapp = WhatsAppGatewayConfig(
                    enabled = prefs.getBoolean(KEY_WHATSAPP_ENABLED, false),
                    sessionLink = prefs.getString(KEY_WHATSAPP_SESSION_LINK, "") ?: "",
                    webhookToken = prefs.getString(KEY_WHATSAPP_WEBHOOK_TOKEN, "") ?: ""
                ),
                restApi = RestApiGatewayConfig(
                    enabled = prefs.getBoolean(KEY_REST_API_ENABLED, true),
                    port = prefs.getInt(KEY_REST_API_PORT, 8000)
                )
            ),
            system = SystemConfig(
                autoStartOnBoot = prefs.getBoolean(KEY_AUTO_START, false),
                publicTunnelEnabled = prefs.getBoolean(KEY_PUBLIC_TUNNEL, false)
            )
        )
    }

    override fun clear() {
        prefs.edit().clear().apply()
    }

    override fun getProvider(): String = prefs.getString(KEY_PROVIDER, "nous_portal") ?: "nous_portal"
    override fun saveProvider(provider: String) {
        prefs.edit().putString(KEY_PROVIDER, provider).apply()
    }

    override fun getApiKey(): String = prefs.getString(KEY_API_KEY, "") ?: ""
    override fun saveApiKey(apiKey: String) {
        prefs.edit().putString(KEY_API_KEY, apiKey).apply()
    }

    override fun getTelegramToken(): String = prefs.getString(KEY_TELEGRAM_TOKEN, "") ?: ""
    override fun saveTelegramToken(token: String) {
        prefs.edit().putString(KEY_TELEGRAM_TOKEN, token).apply()
    }

    override fun isTelegramEnabled(): Boolean {
        return if (prefs.contains(KEY_TELEGRAM_ENABLED)) {
            prefs.getBoolean(KEY_TELEGRAM_ENABLED, false)
        } else {
            getTelegramToken().isNotBlank()
        }
    }
    override fun saveTelegramEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_TELEGRAM_ENABLED, enabled).apply()
    }

    override fun getTelegramAdminUserIds(): String = prefs.getString(KEY_TELEGRAM_ADMIN_IDS, "") ?: ""
    override fun saveTelegramAdminUserIds(adminIds: String) {
        prefs.edit().putString(KEY_TELEGRAM_ADMIN_IDS, adminIds).apply()
    }

    override fun isDiscordEnabled(): Boolean = prefs.getBoolean(KEY_DISCORD_ENABLED, false)
    override fun saveDiscordEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_DISCORD_ENABLED, enabled).apply()
    }

    override fun getDiscordToken(): String = prefs.getString(KEY_DISCORD_TOKEN, "") ?: ""
    override fun saveDiscordToken(token: String) {
        prefs.edit().putString(KEY_DISCORD_TOKEN, token).apply()
    }

    override fun getDiscordChannelIds(): String = prefs.getString(KEY_DISCORD_CHANNEL_IDS, "") ?: ""
    override fun saveDiscordChannelIds(channelIds: String) {
        prefs.edit().putString(KEY_DISCORD_CHANNEL_IDS, channelIds).apply()
    }

    override fun isSlackEnabled(): Boolean = prefs.getBoolean(KEY_SLACK_ENABLED, false)
    override fun saveSlackEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_SLACK_ENABLED, enabled).apply()
    }

    override fun getSlackAppToken(): String = prefs.getString(KEY_SLACK_APP_TOKEN, "") ?: ""
    override fun saveSlackAppToken(token: String) {
        prefs.edit().putString(KEY_SLACK_APP_TOKEN, token).apply()
    }

    override fun getSlackBotToken(): String = prefs.getString(KEY_SLACK_BOT_TOKEN, "") ?: ""
    override fun saveSlackBotToken(token: String) {
        prefs.edit().putString(KEY_SLACK_BOT_TOKEN, token).apply()
    }

    override fun isWhatsAppEnabled(): Boolean = prefs.getBoolean(KEY_WHATSAPP_ENABLED, false)
    override fun saveWhatsAppEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_WHATSAPP_ENABLED, enabled).apply()
    }

    override fun getWhatsAppSessionLink(): String = prefs.getString(KEY_WHATSAPP_SESSION_LINK, "") ?: ""
    override fun saveWhatsAppSessionLink(sessionLink: String) {
        prefs.edit().putString(KEY_WHATSAPP_SESSION_LINK, sessionLink).apply()
    }

    override fun getWhatsAppWebhookToken(): String = prefs.getString(KEY_WHATSAPP_WEBHOOK_TOKEN, "") ?: ""
    override fun saveWhatsAppWebhookToken(token: String) {
        prefs.edit().putString(KEY_WHATSAPP_WEBHOOK_TOKEN, token).apply()
    }

    override fun isRestApiEnabled(): Boolean = prefs.getBoolean(KEY_REST_API_ENABLED, true)
    override fun saveRestApiEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_REST_API_ENABLED, enabled).apply()
    }

    override fun getRestApiPort(): Int = prefs.getInt(KEY_REST_API_PORT, 8000)
    override fun saveRestApiPort(port: Int) {
        prefs.edit().putInt(KEY_REST_API_PORT, port).apply()
    }

    override fun getCustomModel(): String = prefs.getString(KEY_CUSTOM_MODEL, "") ?: ""
    override fun saveCustomModel(model: String) {
        prefs.edit().putString(KEY_CUSTOM_MODEL, model).apply()
    }

    override fun getCustomBaseUrl(): String = prefs.getString(KEY_CUSTOM_BASE_URL, "") ?: ""
    override fun saveCustomBaseUrl(baseUrl: String) {
        prefs.edit().putString(KEY_CUSTOM_BASE_URL, baseUrl).apply()
    }

    override fun isAutoStartEnabled(): Boolean = prefs.getBoolean(KEY_AUTO_START, false)
    override fun saveAutoStart(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_AUTO_START, enabled).apply()
    }

    override fun isPublicTunnelEnabled(): Boolean = prefs.getBoolean(KEY_PUBLIC_TUNNEL, false)
    override fun savePublicTunnel(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_PUBLIC_TUNNEL, enabled).apply()
    }
}

