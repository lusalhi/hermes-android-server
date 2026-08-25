package com.hermes.node.data

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.hermes.node.data.model.GatewayConfig
import com.hermes.node.data.model.HermesConfig
import com.hermes.node.data.model.ProviderConfig
import com.hermes.node.data.model.SystemConfig

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
        const val KEY_TELEGRAM_TOKEN = "key_telegram_token"
        const val KEY_CUSTOM_MODEL = "key_custom_model"
        const val KEY_CUSTOM_BASE_URL = "key_custom_base_url"
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
            .putString(KEY_TELEGRAM_TOKEN, config.gateway.telegramToken)
            .putString(KEY_CUSTOM_MODEL, config.provider.model)
            .putString(KEY_CUSTOM_BASE_URL, config.provider.baseUrl)
            .putBoolean(KEY_AUTO_START, config.system.autoStartOnBoot)
            .putBoolean(KEY_PUBLIC_TUNNEL, config.system.publicTunnelEnabled)
            .apply()
    }

    override fun getConfig(): HermesConfig {
        val telegram = prefs.getString(KEY_TELEGRAM_TOKEN, "") ?: ""
        return HermesConfig(
            provider = ProviderConfig(
                provider = prefs.getString(KEY_PROVIDER, "nous_portal") ?: "nous_portal",
                apiKey = prefs.getString(KEY_API_KEY, "") ?: "",
                model = prefs.getString(KEY_CUSTOM_MODEL, "") ?: "",
                baseUrl = prefs.getString(KEY_CUSTOM_BASE_URL, "") ?: ""
            ),
            gateway = GatewayConfig(
                telegramToken = telegram,
                isTelegramEnabled = telegram.isNotBlank()
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
