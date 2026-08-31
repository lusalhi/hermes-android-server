package com.hermes.node.service

import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import com.hermes.node.data.ConfigRepository
import com.hermes.node.data.model.HermesConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class BootReceiverTest {

    private lateinit var fakeConfigRepository: FakeConfigRepository
    private var serviceStarterCalls = 0
    private var serviceStarterReturnValue = true
    private lateinit var bootReceiver: TestableBootReceiver
    private lateinit var mockContext: Context

    @Before
    fun setUp() {
        fakeConfigRepository = FakeConfigRepository()
        serviceStarterCalls = 0
        serviceStarterReturnValue = true
        bootReceiver = TestableBootReceiver(
            configRepo = fakeConfigRepository,
            starter = {
                serviceStarterCalls++
                serviceStarterReturnValue
            }
        )
        mockContext = ContextWrapper(null)
    }

    @Test
    fun onReceive_bootCompleted_whenAutoStartEnabled_startsService() {
        fakeConfigRepository.autoStart = true
        bootReceiver.testAction = Intent.ACTION_BOOT_COMPLETED
        val intent = Intent()

        bootReceiver.onReceive(mockContext, intent)

        assertEquals(1, serviceStarterCalls)
    }

    @Test
    fun onReceive_bootCompleted_whenAutoStartDisabled_doesNotStartService() {
        fakeConfigRepository.autoStart = false
        bootReceiver.testAction = Intent.ACTION_BOOT_COMPLETED
        val intent = Intent()

        bootReceiver.onReceive(mockContext, intent)

        assertEquals(0, serviceStarterCalls)
    }

    @Test
    fun onReceive_myPackageReplaced_whenAutoStartEnabled_startsService() {
        fakeConfigRepository.autoStart = true
        bootReceiver.testAction = Intent.ACTION_MY_PACKAGE_REPLACED
        val intent = Intent()

        bootReceiver.onReceive(mockContext, intent)

        assertEquals(1, serviceStarterCalls)
    }

    @Test
    fun onReceive_myPackageReplaced_whenAutoStartDisabled_doesNotStartService() {
        fakeConfigRepository.autoStart = false
        bootReceiver.testAction = Intent.ACTION_MY_PACKAGE_REPLACED
        val intent = Intent()

        bootReceiver.onReceive(mockContext, intent)

        assertEquals(0, serviceStarterCalls)
    }

    @Test
    fun onReceive_quickBootPowerOn_whenAutoStartEnabled_startsService() {
        fakeConfigRepository.autoStart = true
        bootReceiver.testAction = "android.intent.action.QUICKBOOT_POWERON"
        val intent = Intent()

        bootReceiver.onReceive(mockContext, intent)

        assertEquals(1, serviceStarterCalls)
    }

    @Test
    fun onReceive_htcQuickBootPowerOn_whenAutoStartEnabled_startsService() {
        fakeConfigRepository.autoStart = true
        bootReceiver.testAction = "com.htc.intent.action.QUICKBOOT_POWERON"
        val intent = Intent()

        bootReceiver.onReceive(mockContext, intent)

        assertEquals(1, serviceStarterCalls)
    }

    @Test
    fun onReceive_lockedBootCompleted_doesNotStartService() {
        fakeConfigRepository.autoStart = true
        bootReceiver.testAction = "android.intent.action.LOCKED_BOOT_COMPLETED"
        val intent = Intent()

        bootReceiver.onReceive(mockContext, intent)

        assertEquals(0, serviceStarterCalls)
    }

    @Test
    fun onReceive_unsupportedAction_doesNotStartService() {
        fakeConfigRepository.autoStart = true
        bootReceiver.testAction = Intent.ACTION_AIRPLANE_MODE_CHANGED
        val intent = Intent()

        bootReceiver.onReceive(mockContext, intent)

        assertEquals(0, serviceStarterCalls)
    }

    @Test
    fun onReceive_nullContext_doesNotCrash() {
        bootReceiver.testAction = Intent.ACTION_BOOT_COMPLETED
        val intent = Intent()
        bootReceiver.onReceive(null, intent)
        assertEquals(0, serviceStarterCalls)
    }

    @Test
    fun onReceive_nullIntent_doesNotCrash() {
        bootReceiver.testAction = Intent.ACTION_BOOT_COMPLETED
        bootReceiver.onReceive(mockContext, null)
        assertEquals(0, serviceStarterCalls)
    }

    @Test
    fun onReceive_nullAction_doesNotCrash() {
        bootReceiver.testAction = null
        val intent = Intent()
        bootReceiver.onReceive(mockContext, intent)
        assertEquals(0, serviceStarterCalls)
    }

    @Test
    fun onReceive_whenConfigRepositoryThrows_handlesGracefullyWithoutCrashing() {
        val failingReceiver = TestableBootReceiver(
            configRepo = object : FakeConfigRepository() {
                override fun isAutoStartEnabled(): Boolean = throw RuntimeException("Keystore corrupted")
            },
            starter = {
                serviceStarterCalls++
                true
            }
        )
        failingReceiver.testAction = Intent.ACTION_BOOT_COMPLETED
        val intent = Intent()

        // Should catch internally and not crash
        failingReceiver.onReceive(mockContext, intent)
        assertEquals(0, serviceStarterCalls)
    }

    @Test
    fun onReceive_whenServiceStarterThrows_handlesGracefullyWithoutCrashing() {
        fakeConfigRepository.autoStart = true
        val failingReceiver = TestableBootReceiver(
            configRepo = fakeConfigRepository,
            starter = { throw SecurityException("Foreground service not allowed") }
        )
        failingReceiver.testAction = Intent.ACTION_BOOT_COMPLETED
        val intent = Intent()

        // Should catch internally and not crash
        failingReceiver.onReceive(mockContext, intent)
    }

    @Test
    fun onReceive_whenServiceStarterReturnsFalse_handlesCleanly() {
        fakeConfigRepository.autoStart = true
        serviceStarterReturnValue = false
        bootReceiver.testAction = Intent.ACTION_BOOT_COMPLETED
        val intent = Intent()

        bootReceiver.onReceive(mockContext, intent)
        assertEquals(1, serviceStarterCalls)
    }

    private class TestableBootReceiver(
        configRepo: ConfigRepository,
        starter: (Context) -> Boolean
    ) : BootReceiver(
        configRepositoryProvider = { configRepo },
        serviceStarter = starter
    ) {
        var testAction: String? = null

        override fun getActionFromIntent(intent: Intent?): String? {
            return testAction ?: intent?.action
        }
    }

    private open class FakeConfigRepository : ConfigRepository {
        var autoStart: Boolean = false

        override fun saveConfig(config: HermesConfig) {
            autoStart = config.system.autoStartOnBoot
        }

        override fun getConfig(): HermesConfig = HermesConfig(
            system = com.hermes.node.data.model.SystemConfig(autoStartOnBoot = autoStart)
        )

        override fun clear() {
            autoStart = false
        }

        override fun getProvider(): String = "nous_portal"
        override fun saveProvider(provider: String) {}
        override fun getApiKey(): String = ""
        override fun saveApiKey(apiKey: String) {}
        override fun getTelegramToken(): String = ""
        override fun saveTelegramToken(token: String) {}
        override fun isTelegramEnabled(): Boolean = false
        override fun saveTelegramEnabled(enabled: Boolean) {}
        override fun getTelegramAdminUserIds(): String = ""
        override fun saveTelegramAdminUserIds(adminIds: String) {}
        override fun isDiscordEnabled(): Boolean = false
        override fun saveDiscordEnabled(enabled: Boolean) {}
        override fun getDiscordToken(): String = ""
        override fun saveDiscordToken(token: String) {}
        override fun getDiscordChannelIds(): String = ""
        override fun saveDiscordChannelIds(channelIds: String) {}
        override fun isSlackEnabled(): Boolean = false
        override fun saveSlackEnabled(enabled: Boolean) {}
        override fun getSlackAppToken(): String = ""
        override fun saveSlackAppToken(token: String) {}
        override fun getSlackBotToken(): String = ""
        override fun saveSlackBotToken(token: String) {}
        override fun isWhatsAppEnabled(): Boolean = false
        override fun saveWhatsAppEnabled(enabled: Boolean) {}
        override fun getWhatsAppSessionLink(): String = ""
        override fun saveWhatsAppSessionLink(sessionLink: String) {}
        override fun getWhatsAppWebhookToken(): String = ""
        override fun saveWhatsAppWebhookToken(token: String) {}
        override fun isRestApiEnabled(): Boolean = true
        override fun saveRestApiEnabled(enabled: Boolean) {}
        override fun getRestApiPort(): Int = 8000
        override fun saveRestApiPort(port: Int) {}
        override fun getCustomModel(): String = ""
        override fun saveCustomModel(model: String) {}
        override fun getCustomBaseUrl(): String = ""
        override fun saveCustomBaseUrl(baseUrl: String) {}
        override fun isAutoStartEnabled(): Boolean = autoStart
        override fun saveAutoStart(enabled: Boolean) {
            autoStart = enabled
        }
        override fun isPublicTunnelEnabled(): Boolean = false
        override fun savePublicTunnel(enabled: Boolean) {}
    }
}
