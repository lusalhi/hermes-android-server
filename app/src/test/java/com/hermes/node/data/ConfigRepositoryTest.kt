package com.hermes.node.data

import android.content.SharedPreferences
import com.hermes.node.data.model.GatewayConfig
import com.hermes.node.data.model.HermesConfig
import com.hermes.node.data.model.ProviderConfig
import com.hermes.node.data.model.SystemConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ConfigRepositoryTest {

    private lateinit var fakePrefs: FakeSharedPreferences
    private lateinit var repository: ConfigRepository

    @Before
    fun setUp() {
        fakePrefs = FakeSharedPreferences()
        repository = EncryptedConfigRepository(fakePrefs)
    }

    @Test
    fun defaultValues_whenEmpty() {
        val config = repository.getConfig()
        assertEquals("nous_portal", config.provider.provider)
        assertEquals("", config.provider.apiKey)
        assertEquals("", config.provider.model)
        assertEquals("", config.provider.baseUrl)
        assertEquals("", config.gateway.telegramToken)
        assertFalse(config.gateway.isTelegramEnabled)
        assertFalse(config.system.autoStartOnBoot)
        assertFalse(config.system.publicTunnelEnabled)
    }

    @Test
    fun saveConfig_persistsAllValuesCorrectly() {
        val configToSave = HermesConfig(
            provider = ProviderConfig(
                provider = "openrouter",
                apiKey = "sk-or-v1-abcdef123456",
                model = "nousresearch/hermes-3-llama-3.1-405b",
                baseUrl = "https://openrouter.ai/api/v1"
            ),
            gateway = GatewayConfig(
                telegramToken = "123456789:ABCdefGhIJKlmNoPQRstuv",
                isTelegramEnabled = true
            ),
            system = SystemConfig(
                autoStartOnBoot = true,
                publicTunnelEnabled = true
            )
        )

        repository.saveConfig(configToSave)

        val retrieved = repository.getConfig()
        assertEquals("openrouter", retrieved.provider.provider)
        assertEquals("sk-or-v1-abcdef123456", retrieved.provider.apiKey)
        assertEquals("nousresearch/hermes-3-llama-3.1-405b", retrieved.provider.model)
        assertEquals("https://openrouter.ai/api/v1", retrieved.provider.baseUrl)
        assertEquals("123456789:ABCdefGhIJKlmNoPQRstuv", retrieved.gateway.telegramToken)
        assertTrue(retrieved.gateway.isTelegramEnabled)
        assertTrue(retrieved.system.autoStartOnBoot)
        assertTrue(retrieved.system.publicTunnelEnabled)
    }

    @Test
    fun individualAccessors_persistAndRetrieve() {
        repository.saveProvider("anthropic")
        assertEquals("anthropic", repository.getProvider())

        repository.saveApiKey("sk-ant-test-key")
        assertEquals("sk-ant-test-key", repository.getApiKey())

        repository.saveTelegramToken("987654:XYZ-TOKEN")
        assertEquals("987654:XYZ-TOKEN", repository.getTelegramToken())

        repository.saveCustomModel("claude-3-5-sonnet-20241022")
        assertEquals("claude-3-5-sonnet-20241022", repository.getCustomModel())

        repository.saveCustomBaseUrl("https://api.anthropic.com")
        assertEquals("https://api.anthropic.com", repository.getCustomBaseUrl())

        repository.saveAutoStart(true)
        assertTrue(repository.isAutoStartEnabled())

        repository.savePublicTunnel(true)
        assertTrue(repository.isPublicTunnelEnabled())
    }

    @Test
    fun missingOrBlankSecrets_handledGracefully() {
        val blankConfig = HermesConfig(
            provider = ProviderConfig(
                provider = "openai",
                apiKey = "",
                model = "",
                baseUrl = ""
            ),
            gateway = GatewayConfig(
                telegramToken = "",
                isTelegramEnabled = false
            ),
            system = SystemConfig(
                autoStartOnBoot = false,
                publicTunnelEnabled = false
            )
        )

        repository.saveConfig(blankConfig)

        val retrieved = repository.getConfig()
        assertEquals("openai", retrieved.provider.provider)
        assertEquals("", retrieved.provider.apiKey)
        assertEquals("", retrieved.gateway.telegramToken)
        assertFalse(retrieved.gateway.isTelegramEnabled)
    }

    @Test
    fun clear_removesAllPersistedData() {
        repository.saveProvider("groq")
        repository.saveApiKey("gsk-12345")
        repository.saveTelegramToken("tg-token-123")
        repository.saveAutoStart(true)

        repository.clear()

        val afterClear = repository.getConfig()
        assertEquals("nous_portal", afterClear.provider.provider)
        assertEquals("", afterClear.provider.apiKey)
        assertEquals("", afterClear.gateway.telegramToken)
        assertFalse(afterClear.system.autoStartOnBoot)
    }
}

class FakeSharedPreferences : SharedPreferences {
    private val values = mutableMapOf<String, Any>()

    override fun getAll(): MutableMap<String, *> = HashMap(values)

    override fun getString(key: String?, defValue: String?): String? {
        return (values[key] as? String) ?: defValue
    }

    override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? {
        @Suppress("UNCHECKED_CAST")
        return (values[key] as? MutableSet<String>) ?: defValues
    }

    override fun getInt(key: String?, defValue: Int): Int {
        return (values[key] as? Int) ?: defValue
    }

    override fun getLong(key: String?, defValue: Long): Long {
        return (values[key] as? Long) ?: defValue
    }

    override fun getFloat(key: String?, defValue: Float): Float {
        return (values[key] as? Float) ?: defValue
    }

    override fun getBoolean(key: String?, defValue: Boolean): Boolean {
        return (values[key] as? Boolean) ?: defValue
    }

    override fun contains(key: String?): Boolean = values.containsKey(key)

    override fun edit(): SharedPreferences.Editor = FakeEditor(this)

    override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {}

    override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {}

    class FakeEditor(private val prefs: FakeSharedPreferences) : SharedPreferences.Editor {
        private val uncommitted = mutableMapOf<String, Any?>()
        private var clearRequested = false

        override fun putString(key: String?, value: String?): SharedPreferences.Editor {
            if (key != null) uncommitted[key] = value
            return this
        }

        override fun putStringSet(key: String?, values: MutableSet<String>?): SharedPreferences.Editor {
            if (key != null) uncommitted[key] = values
            return this
        }

        override fun putInt(key: String?, value: Int): SharedPreferences.Editor {
            if (key != null) uncommitted[key] = value
            return this
        }

        override fun putLong(key: String?, value: Long): SharedPreferences.Editor {
            if (key != null) uncommitted[key] = value
            return this
        }

        override fun putFloat(key: String?, value: Float): SharedPreferences.Editor {
            if (key != null) uncommitted[key] = value
            return this
        }

        override fun putBoolean(key: String?, value: Boolean): SharedPreferences.Editor {
            if (key != null) uncommitted[key] = value
            return this
        }

        override fun remove(key: String?): SharedPreferences.Editor {
            if (key != null) uncommitted[key] = null
            return this
        }

        override fun clear(): SharedPreferences.Editor {
            clearRequested = true
            return this
        }

        override fun commit(): Boolean {
            apply()
            return true
        }

        override fun apply() {
            if (clearRequested) {
                prefs.values.clear()
            }
            uncommitted.forEach { (k, v) ->
                if (v == null) {
                    prefs.values.remove(k)
                } else {
                    prefs.values[k] = v
                }
            }
        }
    }
}
