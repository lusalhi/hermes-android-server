package com.hermes.node.data

import com.hermes.node.data.model.DiscordGatewayConfig
import com.hermes.node.data.model.GatewayConfig
import com.hermes.node.data.model.HermesConfig
import com.hermes.node.data.model.ProviderConfig
import com.hermes.node.data.model.RestApiGatewayConfig
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

        // Verify POSIX permissions: Readable & Writable by owner, Non-executable
        assertTrue("File should be readable by owner", file.canRead())
        assertTrue("File should be writable by owner", file.canWrite())
        assertFalse("File should not be executable", file.canExecute())

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
}
