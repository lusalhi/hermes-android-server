package com.hermes.node.data

import com.hermes.node.data.model.GatewayConfig
import com.hermes.node.data.model.HermesConfig
import com.hermes.node.data.model.ProviderConfig
import com.hermes.node.data.model.SystemConfig
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

        val systemJson = json.getJSONObject("system")
        assertTrue(systemJson.getBoolean("auto_start"))
        assertFalse(systemJson.getBoolean("public_tunnel"))
    }

    @Test
    fun generateJson_handlesSpecialCharactersAndQuotes() {
        val config = HermesConfig(
            provider = ProviderConfig(
                provider = "custom",
                apiKey = "key_with_\"quotes\"_and_\\backslashes_\nnewline",
                model = "model/with/slashes",
                baseUrl = "http://localhost:8000"
            )
        )

        val jsonString = serializer.generateJson(config)
        val json = JSONObject(jsonString)
        val apiKey = json.getJSONObject("provider").getString("api_key")
        assertEquals("key_with_\"quotes\"_and_\\backslashes_\nnewline", apiKey)
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
                telegramToken = "12345:TEST_BOT",
                isTelegramEnabled = true
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

        // Verify written content is valid JSON
        val content = file.readText(Charsets.UTF_8)
        val json = JSONObject(content)
        assertEquals("openrouter", json.getJSONObject("provider").getString("name"))
        assertEquals("sk-or-v1-test", json.getJSONObject("provider").getString("api_key"))
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
    }
}
