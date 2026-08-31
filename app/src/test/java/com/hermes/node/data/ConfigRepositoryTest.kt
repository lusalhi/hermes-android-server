package com.hermes.node.data

import android.content.SharedPreferences
import com.hermes.node.data.model.DiscordGatewayConfig
import com.hermes.node.data.model.GatewayConfig
import com.hermes.node.data.model.HermesConfig
import com.hermes.node.data.model.ProviderConfig
import com.hermes.node.data.model.RestApiGatewayConfig
import com.hermes.node.data.model.SkillsConfig
import com.hermes.node.data.model.SlackGatewayConfig
import com.hermes.node.data.model.SystemConfig
import com.hermes.node.data.model.TelegramGatewayConfig
import com.hermes.node.data.model.WhatsAppGatewayConfig
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

        // Gateway defaults
        assertEquals("", config.gateway.telegramToken)
        assertFalse(config.gateway.isTelegramEnabled)
        assertFalse(config.gateway.telegram.enabled)
        assertEquals("", config.gateway.telegram.botToken)
        assertEquals("", config.gateway.telegram.adminUserIds)

        assertFalse(config.gateway.discord.enabled)
        assertEquals("", config.gateway.discord.botToken)
        assertEquals("", config.gateway.discord.channelIds)

        assertFalse(config.gateway.slack.enabled)
        assertEquals("", config.gateway.slack.appToken)
        assertEquals("", config.gateway.slack.botToken)

        assertFalse(config.gateway.whatsapp.enabled)
        assertEquals("", config.gateway.whatsapp.sessionLink)
        assertEquals("", config.gateway.whatsapp.webhookToken)

        assertTrue(config.gateway.restApi.enabled)
        assertEquals(8000, config.gateway.restApi.port)

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
        assertTrue(retrieved.gateway.telegram.enabled)
        assertEquals("123456789:ABCdefGhIJKlmNoPQRstuv", retrieved.gateway.telegram.botToken)
        assertTrue(retrieved.system.autoStartOnBoot)
        assertTrue(retrieved.system.publicTunnelEnabled)
    }

    @Test
    fun saveConfig_persistsAllFiveGatewaysCorrectly() {
        val configToSave = HermesConfig(
            provider = ProviderConfig(
                provider = "openai",
                apiKey = "sk-proj-test12345"
            ),
            gateway = GatewayConfig(
                telegram = TelegramGatewayConfig(
                    enabled = true,
                    botToken = "12345:TG-TOKEN",
                    adminUserIds = "111,222,333"
                ),
                discord = DiscordGatewayConfig(
                    enabled = true,
                    botToken = "OTg3NjU0MzIx.DISCORD_TOKEN",
                    channelIds = "ch-100,ch-200"
                ),
                slack = SlackGatewayConfig(
                    enabled = true,
                    appToken = "xapp-1-slack-app",
                    botToken = "xoxb-2-slack-bot"
                ),
                whatsapp = WhatsAppGatewayConfig(
                    enabled = true,
                    sessionLink = "https://wa.me/pair-123",
                    webhookToken = "wa_sec_tok_99"
                ),
                restApi = RestApiGatewayConfig(
                    enabled = true,
                    port = 9000
                )
            ),
            system = SystemConfig(
                autoStartOnBoot = true,
                publicTunnelEnabled = true
            )
        )

        repository.saveConfig(configToSave)

        val retrieved = repository.getConfig()
        assertTrue(retrieved.gateway.telegram.enabled)
        assertEquals("12345:TG-TOKEN", retrieved.gateway.telegram.botToken)
        assertEquals("111,222,333", retrieved.gateway.telegram.adminUserIds)
        assertEquals("12345:TG-TOKEN", retrieved.gateway.telegramToken)
        assertTrue(retrieved.gateway.isTelegramEnabled)

        assertTrue(retrieved.gateway.discord.enabled)
        assertEquals("OTg3NjU0MzIx.DISCORD_TOKEN", retrieved.gateway.discord.botToken)
        assertEquals("ch-100,ch-200", retrieved.gateway.discord.channelIds)

        assertTrue(retrieved.gateway.slack.enabled)
        assertEquals("xapp-1-slack-app", retrieved.gateway.slack.appToken)
        assertEquals("xoxb-2-slack-bot", retrieved.gateway.slack.botToken)

        assertTrue(retrieved.gateway.whatsapp.enabled)
        assertEquals("https://wa.me/pair-123", retrieved.gateway.whatsapp.sessionLink)
        assertEquals("wa_sec_tok_99", retrieved.gateway.whatsapp.webhookToken)

        assertTrue(retrieved.gateway.restApi.enabled)
        assertEquals(9000, retrieved.gateway.restApi.port)
    }

    @Test
    fun individualAccessors_persistAndRetrieve() {
        repository.saveProvider("anthropic")
        assertEquals("anthropic", repository.getProvider())

        repository.saveApiKey("sk-ant-test-key")
        assertEquals("sk-ant-test-key", repository.getApiKey())

        repository.saveTelegramToken("987654:XYZ-TOKEN")
        assertEquals("987654:XYZ-TOKEN", repository.getTelegramToken())

        repository.saveTelegramEnabled(true)
        assertTrue(repository.isTelegramEnabled())

        repository.saveTelegramAdminUserIds("admin-1,admin-2")
        assertEquals("admin-1,admin-2", repository.getTelegramAdminUserIds())

        repository.saveDiscordEnabled(true)
        assertTrue(repository.isDiscordEnabled())

        repository.saveDiscordToken("discord-tok-123")
        assertEquals("discord-tok-123", repository.getDiscordToken())

        repository.saveDiscordChannelIds("chan-99")
        assertEquals("chan-99", repository.getDiscordChannelIds())

        repository.saveSlackEnabled(true)
        assertTrue(repository.isSlackEnabled())

        repository.saveSlackAppToken("xapp-test")
        assertEquals("xapp-test", repository.getSlackAppToken())

        repository.saveSlackBotToken("xoxb-test")
        assertEquals("xoxb-test", repository.getSlackBotToken())

        repository.saveWhatsAppEnabled(true)
        assertTrue(repository.isWhatsAppEnabled())

        repository.saveWhatsAppSessionLink("https://wa.me/link")
        assertEquals("https://wa.me/link", repository.getWhatsAppSessionLink())

        repository.saveWhatsAppWebhookToken("wh-secret-1")
        assertEquals("wh-secret-1", repository.getWhatsAppWebhookToken())

        repository.saveRestApiEnabled(false)
        assertFalse(repository.isRestApiEnabled())

        repository.saveRestApiPort(8080)
        assertEquals(8080, repository.getRestApiPort())

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
    fun backwardCompatibility_legacyTelegramToken_withoutExplicitEnabledKey() {
        // Simulate legacy SharedPreferences that only contained KEY_TELEGRAM_TOKEN
        fakePrefs.edit().putString("key_telegram_token", "legacy:12345").apply()

        val config = repository.getConfig()
        assertTrue(config.gateway.isTelegramEnabled)
        assertTrue(config.gateway.telegram.enabled)
        assertEquals("legacy:12345", config.gateway.telegram.botToken)
        assertEquals("legacy:12345", repository.getTelegramToken())
        assertTrue(repository.isTelegramEnabled())
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
        repository.saveTelegramEnabled(true)
        repository.saveTelegramToken("tg-token-123")
        repository.saveTelegramAdminUserIds("admin-1")
        repository.saveDiscordEnabled(true)
        repository.saveDiscordToken("discord-123")
        repository.saveDiscordChannelIds("ch-99")
        repository.saveSlackEnabled(true)
        repository.saveSlackAppToken("slack-app-123")
        repository.saveSlackBotToken("slack-123")
        repository.saveWhatsAppEnabled(true)
        repository.saveWhatsAppSessionLink("wa-123")
        repository.saveWhatsAppWebhookToken("wa-token-123")
        repository.saveRestApiEnabled(false)
        repository.saveRestApiPort(9999)
        repository.saveAutoStart(true)
        repository.savePublicTunnel(true)

        repository.clear()

        val afterClear = repository.getConfig()
        assertEquals("nous_portal", afterClear.provider.provider)
        assertEquals("", afterClear.provider.apiKey)
        assertEquals("", afterClear.gateway.telegramToken)
        assertFalse(afterClear.gateway.telegram.enabled)
        assertEquals("", afterClear.gateway.telegram.adminUserIds)
        assertFalse(afterClear.gateway.discord.enabled)
        assertEquals("", afterClear.gateway.discord.botToken)
        assertEquals("", afterClear.gateway.discord.channelIds)
        assertFalse(afterClear.gateway.slack.enabled)
        assertEquals("", afterClear.gateway.slack.appToken)
        assertEquals("", afterClear.gateway.slack.botToken)
        assertFalse(afterClear.gateway.whatsapp.enabled)
        assertEquals("", afterClear.gateway.whatsapp.sessionLink)
        assertEquals("", afterClear.gateway.whatsapp.webhookToken)
        assertTrue(afterClear.gateway.restApi.enabled)
        assertEquals(8000, afterClear.gateway.restApi.port)
        assertFalse(afterClear.system.autoStartOnBoot)
        assertFalse(afterClear.system.publicTunnelEnabled)
        assertTrue(afterClear.skills.webSearch)
        assertTrue(afterClear.skills.fileManager)
        assertTrue(afterClear.skills.bashRunner)
        assertTrue(afterClear.skills.cronScheduler)
        assertTrue(afterClear.skills.customSkills.isEmpty())
    }

    @Test
    fun skillsConfig_defaultsToAllEnabled() {
        val skills = repository.getSkillsConfig()
        assertTrue(skills.webSearch)
        assertTrue(skills.fileManager)
        assertTrue(skills.bashRunner)
        assertTrue(skills.cronScheduler)
        assertTrue(skills.customSkills.isEmpty())

        assertTrue(repository.isSkillEnabled(SkillsConfig.SKILL_WEB_SEARCH))
        assertTrue(repository.isSkillEnabled(SkillsConfig.SKILL_FILE_MANAGER))
        assertTrue(repository.isSkillEnabled(SkillsConfig.SKILL_BASH_RUNNER))
        assertTrue(repository.isSkillEnabled(SkillsConfig.SKILL_CRON_SCHEDULER))
        assertTrue(repository.isSkillEnabled("unregistered_skill"))
    }

    @Test
    fun saveSkillsConfig_and_getSkillsConfig_persistsCoreAndCustomSkills() {
        val customMap = mapOf("pdf_reader" to true, "voice_gen" to false)
        val skills = SkillsConfig(
            webSearch = false,
            fileManager = true,
            bashRunner = false,
            cronScheduler = true,
            customSkills = customMap
        )

        repository.saveSkillsConfig(skills)

        val retrieved = repository.getSkillsConfig()
        assertFalse(retrieved.webSearch)
        assertTrue(retrieved.fileManager)
        assertFalse(retrieved.bashRunner)
        assertTrue(retrieved.cronScheduler)
        assertEquals(true, retrieved.customSkills["pdf_reader"])
        assertEquals(false, retrieved.customSkills["voice_gen"])
    }

    @Test
    fun saveSkillEnabled_and_isSkillEnabled_individualAccessors() {
        repository.saveSkillEnabled(SkillsConfig.SKILL_BASH_RUNNER, false)
        assertFalse(repository.isSkillEnabled(SkillsConfig.SKILL_BASH_RUNNER))
        assertTrue(repository.isSkillEnabled(SkillsConfig.SKILL_WEB_SEARCH))

        repository.saveSkillEnabled(SkillsConfig.SKILL_WEB_SEARCH, false)
        assertFalse(repository.isSkillEnabled(SkillsConfig.SKILL_WEB_SEARCH))

        repository.saveSkillEnabled("custom_tool_x", false)
        assertFalse(repository.isSkillEnabled("custom_tool_x"))

        val fullSkills = repository.getSkillsConfig()
        assertFalse(fullSkills.bashRunner)
        assertFalse(fullSkills.webSearch)
        assertTrue(fullSkills.fileManager)
        assertTrue(fullSkills.cronScheduler)
        assertEquals(false, fullSkills.customSkills["custom_tool_x"])
    }

    @Test
    fun saveConfig_persistsSkillsBlock() {
        val config = HermesConfig(
            skills = SkillsConfig(
                webSearch = true,
                fileManager = false,
                bashRunner = true,
                cronScheduler = false
            )
        )
        repository.saveConfig(config)

        val retrieved = repository.getConfig()
        assertTrue(retrieved.skills.webSearch)
        assertFalse(retrieved.skills.fileManager)
        assertTrue(retrieved.skills.bashRunner)
        assertFalse(retrieved.skills.cronScheduler)
    }

    @Test
    fun saveSkillsConfig_removesStaleCustomSkills() {
        val initialSkills = SkillsConfig(
            customSkills = mapOf("old_tool" to true, "kept_tool" to false)
        )
        repository.saveSkillsConfig(initialSkills)
        assertTrue(repository.isSkillEnabled("old_tool"))

        val updatedSkills = SkillsConfig(
            customSkills = mapOf("kept_tool" to true)
        )
        repository.saveSkillsConfig(updatedSkills)

        val retrieved = repository.getSkillsConfig()
        assertFalse(retrieved.customSkills.containsKey("old_tool"))
        assertEquals(true, retrieved.customSkills["kept_tool"])
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
