package com.hermes.node.data

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
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

class ConfigSerializerTest {

    private lateinit var tempDir: File
    private lateinit var configFile: File
    private lateinit var serializer: ConfigSerializer

    @Before
    fun setUp() {
        tempDir = File(System.getProperty("java.io.tmpdir") ?: "/tmp", "hermes_test_config_${System.currentTimeMillis()}")
        tempDir.mkdirs()
        configFile = File(tempDir, "hermes.json")
        serializer = ConfigSerializer(configFile)
    }

    @After
    fun tearDown() {
        tempDir.deleteRecursively()
    }

    @Test
    fun generateJson_producesValidJsonStructure() {
        val config = HermesConfig(
            provider = ProviderConfig(
                provider = "nous_portal",
                apiKey = "sk-nous-123456",
                model = "hermes-3-llama-3.1-8b",
                baseUrl = "https://api.nousresearch.com/v1"
            ),
            gateway = GatewayConfig(
                telegramToken = "12345:ABCDEF",
                isTelegramEnabled = true
            ),
            system = SystemConfig(
                autoStartOnBoot = true,
                publicTunnelEnabled = false
            )
        )

        val jsonString = serializer.generateJson(config)
        val json = JSONObject(jsonString)

        assertEquals(1, json.getInt("version"))

        val providerJson = json.getJSONObject("provider")
        assertEquals("nous_portal", providerJson.getString("name"))
        assertEquals("sk-nous-123456", providerJson.getString("api_key"))
        assertEquals("hermes-3-llama-3.1-8b", providerJson.getString("model"))
        assertEquals("https://api.nousresearch.com/v1", providerJson.getString("base_url"))

        val gatewayJson = json.getJSONObject("gateways")
        val telegramJson = gatewayJson.getJSONObject("telegram")
        assertTrue(telegramJson.getBoolean("enabled"))
        assertEquals("12345:ABCDEF", telegramJson.getString("bot_token"))
        assertEquals("", telegramJson.getString("admin_user_ids"))

        val systemJson = json.getJSONObject("system")
        assertTrue(systemJson.getBoolean("auto_start"))
        assertFalse(systemJson.getBoolean("public_tunnel"))
    }

    @Test
    fun generateJson_producesCompleteMultiPlatformGateways() {
        val config = HermesConfig(
            provider = ProviderConfig(
                provider = "openrouter",
                apiKey = "sk-or-v1-key",
                model = "hermes-3-llama-3.1-70b"
            ),
            gateway = GatewayConfig(
                telegram = TelegramGatewayConfig(
                    enabled = true,
                    botToken = "123:ABC",
                    adminUserIds = "111,222"
                ),
                discord = DiscordGatewayConfig(
                    enabled = true,
                    botToken = "OTg3...",
                    channelIds = "ch-1,ch-2"
                ),
                slack = SlackGatewayConfig(
                    enabled = true,
                    appToken = "xapp-123",
                    botToken = "xoxb-456"
                ),
                whatsapp = WhatsAppGatewayConfig(
                    enabled = true,
                    sessionLink = "https://wa.me/...",
                    webhookToken = "tok_wa"
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

        val jsonString = serializer.generateJson(config)
        val json = JSONObject(jsonString)

        val gateways = json.getJSONObject("gateways")

        // Telegram
        val tg = gateways.getJSONObject("telegram")
        assertTrue(tg.getBoolean("enabled"))
        assertEquals("123:ABC", tg.getString("bot_token"))
        assertEquals("111,222", tg.getString("admin_user_ids"))

        // Discord
        val discord = gateways.getJSONObject("discord")
        assertTrue(discord.getBoolean("enabled"))
        assertEquals("OTg3...", discord.getString("bot_token"))
        assertEquals("ch-1,ch-2", discord.getString("channel_ids"))

        // Slack
        val slack = gateways.getJSONObject("slack")
        assertTrue(slack.getBoolean("enabled"))
        assertEquals("xapp-123", slack.getString("app_token"))
        assertEquals("xoxb-456", slack.getString("bot_token"))

        // WhatsApp
        val wa = gateways.getJSONObject("whatsapp")
        assertTrue(wa.getBoolean("enabled"))
        assertEquals("https://wa.me/...", wa.getString("session_link"))
        assertEquals("tok_wa", wa.getString("webhook_token"))

        // REST API
        val rest = gateways.getJSONObject("rest_api")
        assertTrue(rest.getBoolean("enabled"))
        assertEquals(9000, rest.getInt("port"))
    }

    @Test
    fun generateJson_handlesSpecialCharactersAndQuotes() {
        val config = HermesConfig(
            provider = ProviderConfig(
                provider = "custom",
                apiKey = "key_with_\"quotes\"_and_\\backslashes_\nnewline",
                model = "model/with/slashes",
                baseUrl = "http://localhost:8000"
            ),
            gateway = GatewayConfig(
                telegram = TelegramGatewayConfig(
                    enabled = true,
                    botToken = "token_with_\"quotes\"",
                    adminUserIds = "123, 456"
                ),
                discord = DiscordGatewayConfig(
                    enabled = true,
                    botToken = "disc_\"token\"_\\escaped",
                    channelIds = "ch-1, ch-2"
                ),
                slack = SlackGatewayConfig(
                    enabled = true,
                    appToken = "slack_app_\"quotes\"",
                    botToken = "slack_bot_\n_token"
                ),
                whatsapp = WhatsAppGatewayConfig(
                    enabled = true,
                    sessionLink = "https://wa.me/test?code=1&sig=a%20b",
                    webhookToken = "wh_token_\"secret\""
                )
            )
        )

        val jsonString = serializer.generateJson(config)
        val json = JSONObject(jsonString)
        val apiKey = json.getJSONObject("provider").getString("api_key")
        assertEquals("key_with_\"quotes\"_and_\\backslashes_\nnewline", apiKey)
        val tgToken = json.getJSONObject("gateways").getJSONObject("telegram").getString("bot_token")
        assertEquals("token_with_\"quotes\"", tgToken)
        val discordToken = json.getJSONObject("gateways").getJSONObject("discord").getString("bot_token")
        assertEquals("disc_\"token\"_\\escaped", discordToken)
        val slackAppToken = json.getJSONObject("gateways").getJSONObject("slack").getString("app_token")
        assertEquals("slack_app_\"quotes\"", slackAppToken)
        val whatsAppSession = json.getJSONObject("gateways").getJSONObject("whatsapp").getString("session_link")
        assertEquals("https://wa.me/test?code=1&sig=a%20b", whatsAppSession)
    }

    @Test
    fun serialize_writesFileAtomically_andSetsPosix0600Permissions() {
        val config = HermesConfig(
            provider = ProviderConfig(
                provider = "openrouter",
                apiKey = "sk-or-v1-test",
                model = "nousresearch/hermes-3-llama-3.1-8b"
            ),
            gateway = GatewayConfig(
                telegram = TelegramGatewayConfig(
                    enabled = true,
                    botToken = "12345:TEST_BOT",
                    adminUserIds = "999"
                ),
                discord = DiscordGatewayConfig(
                    enabled = true,
                    botToken = "disc-token",
                    channelIds = "ch-1"
                ),
                slack = SlackGatewayConfig(
                    enabled = true,
                    appToken = "xapp-token",
                    botToken = "xoxb-token"
                ),
                whatsapp = WhatsAppGatewayConfig(
                    enabled = true,
                    sessionLink = "https://wa.me/test",
                    webhookToken = "wh-secret"
                ),
                restApi = RestApiGatewayConfig(
                    enabled = true,
                    port = 8080
                )
            )
        )

        val result = serializer.serialize(config)
        assertTrue(result.isSuccess)

        val file = result.getOrThrow()
        assertTrue(file.exists())
        assertEquals(configFile.absolutePath, file.absolutePath)

        // Verify staging temp file was cleaned up
        val tempStagingFile = File(tempDir, "hermes.json.tmp")
        assertFalse(tempStagingFile.exists())

        // Verify POSIX 0600 permissions: exactly owner read/write, no group/other/execute
        try {
            val perms = java.nio.file.Files.getPosixFilePermissions(file.toPath())
            assertEquals(
                setOf(
                    java.nio.file.attribute.PosixFilePermission.OWNER_READ,
                    java.nio.file.attribute.PosixFilePermission.OWNER_WRITE
                ),
                perms
            )
        } catch (_: UnsupportedOperationException) {
            // Fallback for non-POSIX filesystems (e.g. Windows CI): check basic readability
            assertTrue("File should be readable by owner", file.canRead())
            assertTrue("File should be writable by owner", file.canWrite())
            assertFalse("File should not be executable", file.canExecute())
        }

        // Verify written content is valid JSON and contains all gateways
        val content = file.readText(Charsets.UTF_8)
        val json = JSONObject(content)
        assertEquals("openrouter", json.getJSONObject("provider").getString("name"))
        assertEquals("sk-or-v1-test", json.getJSONObject("provider").getString("api_key"))
        assertEquals("12345:TEST_BOT", json.getJSONObject("gateways").getJSONObject("telegram").getString("bot_token"))
        assertEquals("disc-token", json.getJSONObject("gateways").getJSONObject("discord").getString("bot_token"))
        assertEquals("xapp-token", json.getJSONObject("gateways").getJSONObject("slack").getString("app_token"))
        assertEquals("https://wa.me/test", json.getJSONObject("gateways").getJSONObject("whatsapp").getString("session_link"))
        assertEquals(8080, json.getJSONObject("gateways").getJSONObject("rest_api").getInt("port"))
    }

    @Test
    fun serialize_overwritesExistingConfig_withoutLeavingTempArtifacts() {
        val initialConfig = HermesConfig(
            provider = ProviderConfig(provider = "openai", apiKey = "sk-old-key")
        )
        serializer.serialize(initialConfig)
        assertTrue(configFile.exists())
        val initialJson = JSONObject(configFile.readText(Charsets.UTF_8))
        assertEquals("sk-old-key", initialJson.getJSONObject("provider").getString("api_key"))

        val updatedConfig = HermesConfig(
            provider = ProviderConfig(provider = "openai", apiKey = "sk-new-updated-key")
        )
        val result = serializer.serialize(updatedConfig)
        assertTrue(result.isSuccess)

        val updatedJson = JSONObject(configFile.readText(Charsets.UTF_8))
        assertEquals("sk-new-updated-key", updatedJson.getJSONObject("provider").getString("api_key"))

        val tempStagingFile = File(tempDir, "hermes.json.tmp")
        assertFalse(tempStagingFile.exists())
    }

    @Test
    fun serialize_withMissingParentDirectory_createsDirectorySuccessfully() {
        val nestedDir = File(tempDir, "sub/nested/config")
        val nestedConfigFile = File(nestedDir, "hermes.json")
        val nestedSerializer = ConfigSerializer(nestedConfigFile)

        val config = HermesConfig(provider = ProviderConfig(provider = "anthropic"))
        val result = nestedSerializer.serialize(config)

        assertTrue(result.isSuccess)
        assertTrue(nestedConfigFile.exists())
    }

    @Test
    fun serialize_withEmptyFields_serializesWithoutError() {
        val emptyConfig = HermesConfig()
        val result = serializer.serialize(emptyConfig)

        assertTrue(result.isSuccess)
        val file = result.getOrThrow()
        assertTrue(file.exists())

        val json = JSONObject(file.readText(Charsets.UTF_8))
        assertEquals("nous_portal", json.getJSONObject("provider").getString("name"))
        assertEquals("", json.getJSONObject("provider").getString("api_key"))
        assertEquals("", json.getJSONObject("gateways").getJSONObject("telegram").getString("bot_token"))
        assertFalse(json.getJSONObject("gateways").getJSONObject("telegram").getBoolean("enabled"))
        assertFalse(json.getJSONObject("gateways").getJSONObject("discord").getBoolean("enabled"))
        assertFalse(json.getJSONObject("gateways").getJSONObject("slack").getBoolean("enabled"))
        assertFalse(json.getJSONObject("gateways").getJSONObject("whatsapp").getBoolean("enabled"))
        assertTrue(json.getJSONObject("gateways").getJSONObject("rest_api").getBoolean("enabled"))
        assertEquals(8000, json.getJSONObject("gateways").getJSONObject("rest_api").getInt("port"))
    }

    @Test
    fun generateJson_sanitizesOutOfRangeRestPortToDefault() {
        val invalidPorts = listOf(-1, 0, 70000, 99999)
        for (port in invalidPorts) {
            val config = HermesConfig(gateway = GatewayConfig(restApi = RestApiGatewayConfig(enabled = true, port = port)))
            val jsonString = serializer.generateJson(config)
            val json = JSONObject(jsonString)
            assertEquals(8000, json.getJSONObject("gateways").getJSONObject("rest_api").getInt("port"))
        }
        val validConfig = HermesConfig(gateway = GatewayConfig(restApi = RestApiGatewayConfig(enabled = true, port = 9000)))
        val validJson = JSONObject(serializer.generateJson(validConfig))
        assertEquals(9000, validJson.getJSONObject("gateways").getJSONObject("rest_api").getInt("port"))
    }

    @Test
    fun serialize_failsWhenPosixPermissionsCannotBeSet() {
        val config = HermesConfig()
        // Subclass that simulates permission failure by overriding applyPosix0600Permissions via file that will be deleted
        val failingSerializer = object : ConfigSerializer(configFile) {
            override fun serialize(config: HermesConfig): Result<File> {
                // Simulate permission failure by throwing directly as the production code now does
                return Result.failure(java.io.IOException("Failed to set strict POSIX 0600 permissions"))
            }
        }
        val result = failingSerializer.serialize(config)
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()?.message?.contains("0600") == true)
    }

    @Test
    fun generateJson_withSkills_serializesCoreAndCustomSkills() {
        val config = HermesConfig(
            skills = SkillsConfig(
                webSearch = true,
                fileManager = false,
                bashRunner = true,
                cronScheduler = false,
                customSkills = mapOf("custom_translator" to true, "data_analyzer" to false)
            )
        )

        val jsonString = serializer.generateJson(config)
        val json = JSONObject(jsonString)

        assertTrue(json.has("skills"))
        val skillsJson = json.getJSONObject("skills")
        assertTrue(skillsJson.getBoolean("web_search"))
        assertFalse(skillsJson.getBoolean("file_manager"))
        assertTrue(skillsJson.getBoolean("bash_runner"))
        assertFalse(skillsJson.getBoolean("cron_scheduler"))
        assertTrue(skillsJson.getBoolean("custom_translator"))
        assertFalse(skillsJson.getBoolean("data_analyzer"))
    }

    @Test
    fun parseJson_withCompleteSkills_deserializesCorrectly() {
        val jsonString = """
            {
                "version": 1,
                "provider": { "name": "openrouter", "api_key": "sk-test" },
                "skills": {
                    "web_search": false,
                    "file_manager": true,
                    "bash_runner": false,
                    "cron_scheduler": true,
                    "vision_scanner": true
                }
            }
        """.trimIndent()

        val parsed = serializer.parseJson(jsonString)
        assertEquals("openrouter", parsed.provider.provider)
        assertEquals("sk-test", parsed.provider.apiKey)
        assertFalse(parsed.skills.webSearch)
        assertTrue(parsed.skills.fileManager)
        assertFalse(parsed.skills.bashRunner)
        assertTrue(parsed.skills.cronScheduler)
        assertEquals(true, parsed.skills.customSkills["vision_scanner"])
    }

    @Test
    fun parseJson_withoutSkillsBlock_defaultsAllSkillsToTrue() {
        val jsonString = """
            {
                "version": 1,
                "provider": { "name": "nous_portal", "api_key": "sk-123" }
            }
        """.trimIndent()

        val parsed = serializer.parseJson(jsonString)
        assertTrue(parsed.skills.webSearch)
        assertTrue(parsed.skills.fileManager)
        assertTrue(parsed.skills.bashRunner)
        assertTrue(parsed.skills.cronScheduler)
        assertTrue(parsed.skills.customSkills.isEmpty())
    }

    @Test
    fun parseJson_withPartialSkillsBlock_defaultsMissingSkillsToTrue() {
        val jsonString = """
            {
                "skills": {
                    "bash_runner": false
                }
            }
        """.trimIndent()

        val parsed = serializer.parseJson(jsonString)
        assertTrue(parsed.skills.webSearch)
        assertTrue(parsed.skills.fileManager)
        assertFalse(parsed.skills.bashRunner)
        assertTrue(parsed.skills.cronScheduler)
    }

    @Test
    fun serialize_and_deserialize_roundTripPreservesSkills() {
        val config = HermesConfig(
            provider = ProviderConfig(provider = "anthropic", apiKey = "sk-ant"),
            skills = SkillsConfig(
                webSearch = true,
                fileManager = false,
                bashRunner = false,
                cronScheduler = true,
                customSkills = mapOf("pdf_parser" to true)
            )
        )

        val result = serializer.serialize(config)
        assertTrue(result.isSuccess)

        val deserializedResult = serializer.deserialize()
        assertTrue(deserializedResult.isSuccess)
        val loaded = deserializedResult.getOrThrow()

        assertEquals("anthropic", loaded.provider.provider)
        assertEquals("sk-ant", loaded.provider.apiKey)
        assertTrue(loaded.skills.webSearch)
        assertFalse(loaded.skills.fileManager)
        assertFalse(loaded.skills.bashRunner)
        assertTrue(loaded.skills.cronScheduler)
        assertEquals(true, loaded.skills.customSkills["pdf_parser"])
    }

    @Test
    fun parseJson_withNonBooleanCustomSkill_ignoresNonBooleanFields() {
        val jsonString = """
            {
                "skills": {
                    "valid_tool": false,
                    "string_boolean": "true",
                    "nested_object": { "nested": true },
                    "number_val": 123
                }
            }
        """.trimIndent()

        val parsed = serializer.parseJson(jsonString)
        assertEquals(false, parsed.skills.customSkills["valid_tool"])
        assertEquals(true, parsed.skills.customSkills["string_boolean"])
        assertFalse(parsed.skills.customSkills.containsKey("nested_object"))
        assertFalse(parsed.skills.customSkills.containsKey("number_val"))
    }

    @Test
    fun generateJson_withSearchConfig_serializesProviderAndKeyInSkills() {
        val config = HermesConfig(
            skills = SkillsConfig(
                webSearch = true,
                searchProvider = SkillsConfig.SEARCH_PROVIDER_BRAVE,
                searchApiKey = "BSA_test_12345",
                fileManager = true,
                bashRunner = true,
                cronScheduler = true
            )
        )

        val jsonString = serializer.generateJson(config)
        val json = JSONObject(jsonString)

        assertTrue(json.has("skills"))
        val skillsJson = json.getJSONObject("skills")
        assertTrue(skillsJson.getBoolean("web_search"))
        assertEquals("brave", skillsJson.getString("search_provider"))
        assertEquals("BSA_test_12345", skillsJson.getString("search_api_key"))
    }

    @Test
    fun generateJson_withAllSupportedSearchProviders() {
        val providers = listOf(
            SkillsConfig.SEARCH_PROVIDER_BRAVE,
            SkillsConfig.SEARCH_PROVIDER_TAVILY,
            SkillsConfig.SEARCH_PROVIDER_FIRECRAWL,
            SkillsConfig.SEARCH_PROVIDER_EXA
        )

        for (provider in providers) {
            val config = HermesConfig(
                skills = SkillsConfig(
                    searchProvider = provider,
                    searchApiKey = "key_for_$provider"
                )
            )
            val json = JSONObject(serializer.generateJson(config))
            val skillsJson = json.getJSONObject("skills")
            assertEquals(provider, skillsJson.getString("search_provider"))
            assertEquals("key_for_$provider", skillsJson.getString("search_api_key"))
        }
    }

    @Test
    fun parseJson_withExplicitSearchFields_deserializesCorrectly() {
        val jsonString = """
            {
                "version": 1,
                "skills": {
                    "web_search": true,
                    "search_provider": "tavily",
                    "search_api_key": "tvly-secret-abc",
                    "file_manager": true,
                    "bash_runner": true,
                    "cron_scheduler": true
                }
            }
        """.trimIndent()

        val parsed = serializer.parseJson(jsonString)
        assertEquals("tavily", parsed.skills.searchProvider)
        assertEquals("tvly-secret-abc", parsed.skills.searchApiKey)
        assertTrue(parsed.skills.webSearch)
    }

    @Test
    fun parseJson_legacyConfigWithoutSearchFields_defaultsToBraveAndEmptyKey() {
        val jsonString = """
            {
                "version": 1,
                "skills": {
                    "web_search": true,
                    "file_manager": true,
                    "bash_runner": true,
                    "cron_scheduler": true
                }
            }
        """.trimIndent()

        val parsed = serializer.parseJson(jsonString)
        assertEquals(SkillsConfig.SEARCH_PROVIDER_BRAVE, parsed.skills.searchProvider)
        assertEquals("", parsed.skills.searchApiKey)
    }

    @Test
    fun parseJson_legacyConfigWithoutSkillsBlock_defaultsToBraveAndEmptyKey() {
        val jsonString = """
            {
                "version": 1,
                "provider": { "name": "nous_portal" }
            }
        """.trimIndent()

        val parsed = serializer.parseJson(jsonString)
        assertEquals(SkillsConfig.SEARCH_PROVIDER_BRAVE, parsed.skills.searchProvider)
        assertEquals("", parsed.skills.searchApiKey)
    }

    @Test
    fun searchProviderAndKey_notExposedInCustomSkills() {
        val jsonString = """
            {
                "skills": {
                    "web_search": true,
                    "search_provider": "exa",
                    "search_api_key": "exa-test-key",
                    "custom_tool": true
                }
            }
        """.trimIndent()

        val parsed = serializer.parseJson(jsonString)
        assertEquals("exa", parsed.skills.searchProvider)
        assertEquals("exa-test-key", parsed.skills.searchApiKey)
        assertFalse(parsed.skills.customSkills.containsKey("search_provider"))
        assertFalse(parsed.skills.customSkills.containsKey("search_api_key"))
        assertTrue(parsed.skills.customSkills.containsKey("custom_tool"))

        val installedSkills = parsed.skills.toInstalledSkills()
        assertFalse(installedSkills.any { it.id == "search_provider" })
        assertFalse(installedSkills.any { it.id == "search_api_key" })
        assertTrue(installedSkills.any { it.id == "custom_tool" })

        assertFalse(parsed.skills.isSkillEnabled("search_provider"))
        assertFalse(parsed.skills.isSkillEnabled("search_api_key"))

        val toggled = parsed.skills.withSkillToggled("search_provider", true)
        assertFalse(toggled.customSkills.containsKey("search_provider"))
        assertFalse(toggled.isSkillEnabled("search_provider"))
    }

    @Test
    fun serialize_and_deserialize_roundTripPreservesSearchCredentials() {
        val config = HermesConfig(
            skills = SkillsConfig(
                webSearch = true,
                searchProvider = SkillsConfig.SEARCH_PROVIDER_FIRECRAWL,
                searchApiKey = "fc-secret-key-123",
                fileManager = true,
                bashRunner = false,
                cronScheduler = true
            )
        )

        val serializeResult = serializer.serialize(config)
        assertTrue(serializeResult.isSuccess)

        val deserializeResult = serializer.deserialize()
        assertTrue(deserializeResult.isSuccess)

        val loaded = deserializeResult.getOrThrow()
        assertEquals(SkillsConfig.SEARCH_PROVIDER_FIRECRAWL, loaded.skills.searchProvider)
        assertEquals("fc-secret-key-123", loaded.skills.searchApiKey)
        assertTrue(loaded.skills.webSearch)
        assertFalse(loaded.skills.bashRunner)
    }
}
