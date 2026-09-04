package com.hermes.node.engine

import com.hermes.node.data.model.GatewayConfig
import com.hermes.node.data.model.HermesConfig
import com.hermes.node.data.model.ProviderConfig
import com.hermes.node.data.model.RestApiGatewayConfig
import com.hermes.node.viewmodel.LogEntry
import com.hermes.node.viewmodel.LogLevel
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.InputStream
import java.net.ConnectException

@OptIn(ExperimentalCoroutinesApi::class)
class TelegramGatewayManagerTest {

    private val testDispatcher = StandardTestDispatcher()
    private lateinit var manager: TelegramGatewayManager

    @Before
    fun setUp() {
        manager = TelegramGatewayManager(ioDispatcher = testDispatcher)
    }

    @Test
    fun start_withBlankToken_returnsFailure() = runTest(testDispatcher) {
        val result = manager.start("")
        assertTrue(result.isFailure)
        assertFalse(manager.isRunning)
    }

    @Test
    fun stop_resetsState() = runTest(testDispatcher) {
        manager.stop()
        assertFalse(manager.isRunning)
        assertEquals(null, manager.botUsername.value)
    }

    @Test
    fun fetchBotInfo_withInvalidToken_returnsFailure() {
        val result = manager.fetchBotInfo("invalid_token_12345")
        assertTrue(result.isFailure)
    }

    @Test
    fun stop_clearsSessionHistory() = runTest(testDispatcher) {
        manager.addConversationTurn(101L, "Hello", "Hi there")
        assertEquals(2, manager.getConversationHistory(101L).size)

        manager.stop()
        assertTrue(manager.getConversationHistory(101L).isEmpty())
        assertTrue(manager.conversationHistory.isEmpty())
    }

    // --- Multi-turn Conversation Memory & Sliding Window Tests ---

    @Test
    fun multiTurnHistory_accumulatesConversationMessages() = runTest(testDispatcher) {
        val testManager = TestTelegramGatewayManager(testDispatcher)
        val config = HermesConfig(
            gateway = GatewayConfig(restApi = RestApiGatewayConfig(enabled = true, port = 8000))
        )

        testManager.mockDaemonResponse = Result.success("Halo Budi! Senang bertemu.")
        testManager.handleIncomingMessage(
            botToken = "token_123",
            chatId = 101L,
            fromId = "user_budi",
            fromUsername = "budi",
            text = "My name is Budi",
            adminSet = emptySet(),
            hermesConfig = config,
            logStreamer = null,
            botName = "HermesBot"
        )
        advanceUntilIdle()

        // Verify turn 1 saved
        val historyTurn1 = testManager.getConversationHistory(101L)
        assertEquals(2, historyTurn1.size)
        assertEquals("user", historyTurn1[0].role)
        assertEquals("My name is Budi", historyTurn1[0].content)
        assertEquals("assistant", historyTurn1[1].role)
        assertEquals("Halo Budi! Senang bertemu.", historyTurn1[1].content)

        // Turn 2
        testManager.mockDaemonResponse = Result.success("Your name is Budi.")
        testManager.handleIncomingMessage(
            botToken = "token_123",
            chatId = 101L,
            fromId = "user_budi",
            fromUsername = "budi",
            text = "What is my name?",
            adminSet = emptySet(),
            hermesConfig = config,
            logStreamer = null,
            botName = "HermesBot"
        )
        advanceUntilIdle()

        // Verify prior history was included in turn 2 query payload
        val historyPassedToDaemon = testManager.lastHistoryPassed
        assertNotNull(historyPassedToDaemon)
        assertEquals(2, historyPassedToDaemon!!.size)
        assertEquals("My name is Budi", historyPassedToDaemon[0].content)

        // Verify history now has 4 messages (2 turns)
        val historyTurn2 = testManager.getConversationHistory(101L)
        assertEquals(4, historyTurn2.size)
        assertEquals("user", historyTurn2[2].role)
        assertEquals("What is my name?", historyTurn2[2].content)
        assertEquals("assistant", historyTurn2[3].role)
        assertEquals("Your name is Budi.", historyTurn2[3].content)
    }

    @Test
    fun multiTurnHistory_slidingWindow_capsAtTenTurnsOrTwentyMessages() {
        val chatId = 202L
        // Add 12 turns (24 messages)
        for (i in 1..12) {
            manager.addConversationTurn(
                chatId = chatId,
                userMessage = "User message $i",
                assistantReply = "Assistant reply $i"
            )
        }

        val history = manager.getConversationHistory(chatId)
        assertEquals(TelegramGatewayManager.MAX_HISTORY_MESSAGES, history.size)
        assertEquals(20, history.size)

        // The oldest 2 turns (User message 1 & 2) should have been pruned
        assertEquals("User message 3", history.first().content)
        assertEquals("user", history.first().role)
        assertEquals("Assistant reply 12", history.last().content)
        assertEquals("assistant", history.last().role)
    }

    @Test
    fun multiTurnHistory_differentChatsMaintainIsolatedHistories() {
        manager.addConversationTurn(101L, "Msg A", "Reply A")
        manager.addConversationTurn(202L, "Msg B", "Reply B")

        val hist1 = manager.getConversationHistory(101L)
        val hist2 = manager.getConversationHistory(202L)

        assertEquals(2, hist1.size)
        assertEquals(2, hist2.size)
        assertEquals("Msg A", hist1[0].content)
        assertEquals("Msg B", hist2[0].content)
    }

    // --- Reset / Clear / New Command Tests ---

    @Test
    fun resetCommand_purgesChatHistoryAndSendsConfirmation() = runTest(testDispatcher) {
        val testManager = TestTelegramGatewayManager(testDispatcher)
        val chatId = 101L
        testManager.addConversationTurn(chatId, "Turn 1", "Reply 1")
        testManager.addConversationTurn(chatId, "Turn 2", "Reply 2")
        assertEquals(4, testManager.getConversationHistory(chatId).size)

        testManager.handleIncomingMessage(
            botToken = "token_123",
            chatId = chatId,
            fromId = "user1",
            fromUsername = "user1",
            text = "/reset",
            adminSet = emptySet(),
            hermesConfig = null,
            logStreamer = null,
            botName = "HermesBot"
        )
        advanceUntilIdle()

        // History must be purged
        assertTrue(testManager.getConversationHistory(chatId).isEmpty())

        // Confirmation message sent
        val lastMsg = testManager.sentMessages.lastOrNull()
        assertNotNull(lastMsg)
        assertEquals(chatId, lastMsg!!.second)
        assertTrue(lastMsg.third.contains("dibersihkan"))
    }

    @Test
    fun clearCommand_idempotentOnEmptyHistory() = runTest(testDispatcher) {
        val testManager = TestTelegramGatewayManager(testDispatcher)
        val chatId = 101L
        assertTrue(testManager.getConversationHistory(chatId).isEmpty())

        testManager.handleIncomingMessage(
            botToken = "token_123",
            chatId = chatId,
            fromId = "user1",
            fromUsername = "user1",
            text = "/clear",
            adminSet = emptySet(),
            hermesConfig = null,
            logStreamer = null,
            botName = "HermesBot"
        )
        advanceUntilIdle()

        assertTrue(testManager.getConversationHistory(chatId).isEmpty())
        val lastMsg = testManager.sentMessages.lastOrNull()
        assertNotNull(lastMsg)
        assertTrue(lastMsg!!.third.contains("dibersihkan"))
    }

    @Test
    fun newCommand_clearsChatHistory() = runTest(testDispatcher) {
        val testManager = TestTelegramGatewayManager(testDispatcher)
        val chatId = 101L
        testManager.addConversationTurn(chatId, "Old", "Reply")
        assertEquals(2, testManager.getConversationHistory(chatId).size)

        testManager.handleIncomingMessage(
            botToken = "token_123",
            chatId = chatId,
            fromId = "user1",
            fromUsername = "user1",
            text = "/new",
            adminSet = emptySet(),
            hermesConfig = null,
            logStreamer = null,
            botName = "HermesBot"
        )
        advanceUntilIdle()

        assertTrue(testManager.getConversationHistory(chatId).isEmpty())
    }

    @Test
    fun resetCommand_withBotMention_clearsHistory() = runTest(testDispatcher) {
        val testManager = TestTelegramGatewayManager(testDispatcher)
        val chatId = 101L
        testManager.addConversationTurn(chatId, "Old", "Reply")

        testManager.handleIncomingMessage(
            botToken = "token_123",
            chatId = chatId,
            fromId = "user1",
            fromUsername = "user1",
            text = "/reset@HermesBot",
            adminSet = emptySet(),
            hermesConfig = null,
            logStreamer = null,
            botName = "HermesBot"
        )
        advanceUntilIdle()

        assertTrue(testManager.getConversationHistory(chatId).isEmpty())
    }

    // --- Typing Indicator Tests ---

    @Test
    fun handleIncomingMessage_dispatchesTypingIndicatorDuringGeneration() = runTest(testDispatcher) {
        val testManager = TestTelegramGatewayManager(testDispatcher)
        val config = HermesConfig(
            gateway = GatewayConfig(restApi = RestApiGatewayConfig(enabled = true, port = 8000))
        )
        testManager.mockDaemonResponse = Result.success("Response")

        testManager.handleIncomingMessage(
            botToken = "token_123",
            chatId = 101L,
            fromId = "user1",
            fromUsername = "user1",
            text = "Hello Hermes",
            adminSet = emptySet(),
            hermesConfig = config,
            logStreamer = null,
            botName = "HermesBot"
        )
        advanceUntilIdle()

        assertTrue(testManager.chatActions.any { it.second == 101L && it.third == "typing" })
    }

    @Test
    fun startTypingIndicator_refreshesPeriodically() = runTest(testDispatcher) {
        val testManager = TestTelegramGatewayManager(testDispatcher)
        val job = testManager.startTypingIndicator(this, "token_123", 101L, intervalMs = 4000L)

        // Initial run
        advanceTimeBy(1)
        assertEquals(1, testManager.chatActions.size)

        // After 4s interval
        advanceTimeBy(4000)
        assertEquals(2, testManager.chatActions.size)

        // After 8s interval
        advanceTimeBy(4000)
        assertEquals(3, testManager.chatActions.size)

        job.cancel()
        advanceTimeBy(4000)
        assertEquals(3, testManager.chatActions.size)
    }

    @Test
    fun sendChatAction_returnsFalseOnNetworkFailureWithoutThrowing() {
        val testManager = object : TelegramGatewayManager(ioDispatcher = testDispatcher) {
            override fun makeHttpRequest(
                urlStr: String,
                method: String,
                body: String?,
                headers: Map<String, String>,
                connectTimeoutMs: Int,
                readTimeoutMs: Int
            ): JSONObject? {
                throw RuntimeException("Simulated network timeout")
            }
        }

        val result = testManager.sendChatAction("bad_token", 101L, "typing")
        assertFalse(result)
    }

    // --- Local Daemon Routing & Fallback Tests ---

    @Test
    fun localDaemon_routesFirstWhenEnabledAndHealthy() = runTest(testDispatcher) {
        val testManager = TestTelegramGatewayManager(testDispatcher)
        val config = HermesConfig(
            provider = ProviderConfig(provider = "openrouter", apiKey = "sk-openrouter"),
            gateway = GatewayConfig(restApi = RestApiGatewayConfig(enabled = true, port = 8000))
        )

        testManager.mockDaemonResponse = Result.success("Local daemon agent answer")
        testManager.mockLlmResponse = Result.success("Cloud LLM answer")

        testManager.handleIncomingMessage(
            botToken = "token_123",
            chatId = 101L,
            fromId = "user1",
            fromUsername = "user1",
            text = "Run automated tool",
            adminSet = emptySet(),
            hermesConfig = config,
            logStreamer = null,
            botName = "HermesBot"
        )
        advanceUntilIdle()

        val lastReply = testManager.sentMessages.last().third
        assertEquals("Local daemon agent answer", lastReply)
        assertEquals(1, testManager.daemonCallCount)
        assertEquals(0, testManager.llmCallCount)
    }

    @Test
    fun localDaemon_whenOffline_seamlesslyFallsBackToCloudLlmAndLogsWarning() = runTest(testDispatcher) {
        val testManager = TestTelegramGatewayManager(testDispatcher)
        val fakeLogStreamer = FakeLogStreamer()
        val config = HermesConfig(
            provider = ProviderConfig(provider = "openrouter", apiKey = "sk-openrouter"),
            gateway = GatewayConfig(restApi = RestApiGatewayConfig(enabled = true, port = 8000))
        )

        testManager.mockDaemonResponse = Result.failure(ConnectException("Connection refused"))
        testManager.mockLlmResponse = Result.success("Cloud LLM fallback answer")

        testManager.handleIncomingMessage(
            botToken = "token_123",
            chatId = 101L,
            fromId = "user1",
            fromUsername = "user1",
            text = "Hello",
            adminSet = emptySet(),
            hermesConfig = config,
            logStreamer = fakeLogStreamer,
            botName = "HermesBot"
        )
        advanceUntilIdle()

        val lastReply = testManager.sentMessages.last().third
        assertEquals("Cloud LLM fallback answer", lastReply)
        assertEquals(1, testManager.daemonCallCount)
        assertEquals(1, testManager.llmCallCount)

        // Verify warning logged in LogStreamer
        val warningLogs = fakeLogStreamer.getLogs().filter { it.level == LogLevel.WARN }
        assertTrue(warningLogs.any { it.message.contains("Local daemon unavailable") || it.message.contains("falling back") })

        // Verify history recorded cloud response
        val history = testManager.getConversationHistory(101L)
        assertEquals(2, history.size)
        assertEquals("Cloud LLM fallback answer", history[1].content)
    }

    @Test
    fun localDaemon_whenDisabled_routesDirectlyToCloudLlm() = runTest(testDispatcher) {
        val testManager = TestTelegramGatewayManager(testDispatcher)
        val config = HermesConfig(
            provider = ProviderConfig(provider = "openrouter", apiKey = "sk-openrouter"),
            gateway = GatewayConfig(restApi = RestApiGatewayConfig(enabled = false, port = 8000))
        )

        testManager.mockDaemonResponse = Result.success("Daemon response")
        testManager.mockLlmResponse = Result.success("Cloud LLM response")

        testManager.handleIncomingMessage(
            botToken = "token_123",
            chatId = 101L,
            fromId = "user1",
            fromUsername = "user1",
            text = "Hello",
            adminSet = emptySet(),
            hermesConfig = config,
            logStreamer = null,
            botName = "HermesBot"
        )
        advanceUntilIdle()

        val lastReply = testManager.sentMessages.last().third
        assertEquals("Cloud LLM response", lastReply)
        assertEquals(0, testManager.daemonCallCount)
        assertEquals(1, testManager.llmCallCount)
    }

    @Test
    fun localDaemon_whenOfflineAndNoLlmConfigured_returnsStandbyMessage() = runTest(testDispatcher) {
        val testManager = TestTelegramGatewayManager(testDispatcher)
        val config = HermesConfig(
            provider = ProviderConfig(provider = "", apiKey = ""),
            gateway = GatewayConfig(restApi = RestApiGatewayConfig(enabled = true, port = 8000))
        )
        testManager.mockDaemonResponse = Result.failure(ConnectException("Connection refused"))

        testManager.handleIncomingMessage(
            botToken = "token_123",
            chatId = 101L,
            fromId = "user1",
            fromUsername = "user1",
            text = "Hello",
            adminSet = emptySet(),
            hermesConfig = config,
            logStreamer = null,
            botName = "HermesBot"
        )
        advanceUntilIdle()

        val lastReply = testManager.sentMessages.last().third
        assertTrue(lastReply.contains("Mode Standby") || lastReply.contains("Hermes Node (Android Server)"))
    }

    // --- Admin Authorization Tests ---

    @Test
    fun handleIncomingMessage_unauthorizedUser_rejectsImmediatelyWithoutTouchingHistoryOrLlm() = runTest(testDispatcher) {
        val testManager = TestTelegramGatewayManager(testDispatcher)
        val config = HermesConfig(
            provider = ProviderConfig(provider = "openrouter", apiKey = "sk-openrouter"),
            gateway = GatewayConfig(restApi = RestApiGatewayConfig(enabled = true, port = 8000))
        )

        val result = testManager.handleIncomingMessage(
            botToken = "token_123",
            chatId = 101L,
            fromId = "stranger_999",
            fromUsername = "intruder",
            text = "My secret name is X",
            adminSet = setOf("admin_user_1", "admin_user_2"),
            hermesConfig = config,
            logStreamer = null,
            botName = "HermesBot"
        )
        advanceUntilIdle()

        assertFalse(result)
        val lastMsg = testManager.sentMessages.last().third
        assertTrue(lastMsg.contains("Akses Ditolak"))
        assertTrue(testManager.getConversationHistory(101L).isEmpty())
        assertEquals(0, testManager.daemonCallCount)
        assertEquals(0, testManager.llmCallCount)
    }

    // --- Request Body & Payload Construction Tests ---

    @Test
    fun queryLocalDaemon_constructsOpenAiChatCompletionsJsonPayload() {
        var capturedUrl: String? = null
        var capturedMethod: String? = null
        var capturedBody: String? = null
        var capturedTimeout: Int? = null

        val spyManager = object : TelegramGatewayManager(ioDispatcher = testDispatcher) {
            override fun makeHttpRequestDetailed(
                urlStr: String,
                method: String,
                body: String?,
                headers: Map<String, String>,
                connectTimeoutMs: Int,
                readTimeoutMs: Int
            ): Pair<Int, JSONObject?> {
                capturedUrl = urlStr
                capturedMethod = method
                capturedBody = body
                capturedTimeout = connectTimeoutMs
                val resp = JSONObject().apply {
                    put("choices", org.json.JSONArray().apply {
                        put(JSONObject().apply {
                            put("message", JSONObject().apply {
                                put("role", "assistant")
                                put("content", "Agent reply")
                            })
                        })
                    })
                }
                return Pair(200, resp)
            }
        }

        val history = listOf(
            TelegramChatMessage("user", "Previous question"),
            TelegramChatMessage("assistant", "Previous answer")
        )

        val result = spyManager.queryLocalDaemon(
            userMessage = "Current question",
            senderName = "Budi",
            port = 8000,
            model = "hermes",
            history = history
        )

        assertTrue(result.isSuccess)
        assertEquals("Agent reply", result.getOrNull())
        assertEquals("http://127.0.0.1:8000/v1/chat/completions", capturedUrl)
        assertEquals("POST", capturedMethod)
        assertEquals(TelegramGatewayManager.LOCAL_DAEMON_CONNECT_TIMEOUT_MS, capturedTimeout)

        val json = JSONObject(capturedBody!!)
        assertEquals("hermes", json.getString("model"))
        val messages = json.getJSONArray("messages")
        assertEquals(4, messages.length()) // system, user prev, assistant prev, user current
        assertEquals("system", messages.getJSONObject(0).getString("role"))
        assertEquals("user", messages.getJSONObject(1).getString("role"))
        assertEquals("Previous question", messages.getJSONObject(1).getString("content"))
        assertEquals("assistant", messages.getJSONObject(2).getString("role"))
        assertEquals("Previous answer", messages.getJSONObject(2).getString("content"))
        assertEquals("user", messages.getJSONObject(3).getString("role"))
        assertEquals("Current question", messages.getJSONObject(3).getString("content"))
    }

    @Test
    fun queryLlmDetailed_serializesMultiTurnHistoryIntoJsonPayload_forOpenAiAndAnthropic() {
        var capturedUrl = ""
        var capturedBody = ""
        var capturedHeaders = mapOf<String, String>()

        val spyManager = object : TelegramGatewayManager(ioDispatcher = testDispatcher) {
            override fun makeHttpRequestDetailed(
                urlStr: String,
                method: String,
                body: String?,
                headers: Map<String, String>,
                connectTimeoutMs: Int,
                readTimeoutMs: Int
            ): Pair<Int, JSONObject?> {
                capturedUrl = urlStr
                capturedBody = body ?: ""
                capturedHeaders = headers
                val resp = JSONObject().apply {
                    put("choices", org.json.JSONArray().apply {
                        put(JSONObject().apply {
                            put("message", JSONObject().apply {
                                put("role", "assistant")
                                put("content", "OpenAI response")
                            })
                        })
                    })
                    put("content", org.json.JSONArray().apply {
                        put(JSONObject().apply {
                            put("text", "Anthropic response")
                        })
                    })
                }
                return Pair(200, resp)
            }
        }

        val history = listOf(
            TelegramChatMessage("user", "Hello there"),
            TelegramChatMessage("assistant", "General Kenobi")
        )

        // 1. Test OpenAI format
        val openAiConfig = ProviderConfig(provider = "openai", apiKey = "sk-test-key", model = "gpt-4o-mini")
        val openAiRes = spyManager.queryLlmDetailed(
            userMessage = "How are you?",
            senderName = "alice",
            provider = openAiConfig,
            history = history
        )
        assertTrue(openAiRes.isSuccess)
        assertEquals("OpenAI response", openAiRes.getOrNull())
        assertEquals("https://api.openai.com/v1/chat/completions", capturedUrl)
        assertEquals("Bearer sk-test-key", capturedHeaders["Authorization"])

        val openAiJson = JSONObject(capturedBody)
        assertEquals("gpt-4o-mini", openAiJson.getString("model"))
        val openAiMsgs = openAiJson.getJSONArray("messages")
        assertEquals(4, openAiMsgs.length())
        assertEquals("system", openAiMsgs.getJSONObject(0).getString("role"))
        assertEquals("user", openAiMsgs.getJSONObject(1).getString("role"))
        assertEquals("Hello there", openAiMsgs.getJSONObject(1).getString("content"))
        assertEquals("assistant", openAiMsgs.getJSONObject(2).getString("role"))
        assertEquals("General Kenobi", openAiMsgs.getJSONObject(2).getString("content"))
        assertEquals("user", openAiMsgs.getJSONObject(3).getString("role"))
        assertEquals("How are you?", openAiMsgs.getJSONObject(3).getString("content"))

        // 2. Test Anthropic format
        val anthropicConfig = ProviderConfig(provider = "anthropic", apiKey = "ant-key-123", model = "claude-3-haiku-20240307")
        val anthropicRes = spyManager.queryLlmDetailed(
            userMessage = "How are you?",
            senderName = "alice",
            provider = anthropicConfig,
            history = history
        )
        assertTrue(anthropicRes.isSuccess)
        assertEquals("Anthropic response", anthropicRes.getOrNull())
        assertEquals("https://api.anthropic.com/v1/messages", capturedUrl)
        assertEquals("ant-key-123", capturedHeaders["x-api-key"])
        assertEquals("2023-06-01", capturedHeaders["anthropic-version"])

        val anthropicJson = JSONObject(capturedBody)
        assertEquals("claude-3-haiku-20240307", anthropicJson.getString("model"))
        assertTrue(anthropicJson.getString("system").contains("@alice"))
        val anthropicMsgs = anthropicJson.getJSONArray("messages")
        assertEquals(3, anthropicMsgs.length())
        assertEquals("user", anthropicMsgs.getJSONObject(0).getString("role"))
        assertEquals("Hello there", anthropicMsgs.getJSONObject(0).getString("content"))
        assertEquals("assistant", anthropicMsgs.getJSONObject(1).getString("role"))
        assertEquals("General Kenobi", anthropicMsgs.getJSONObject(1).getString("content"))
        assertEquals("user", anthropicMsgs.getJSONObject(2).getString("role"))
        assertEquals("How are you?", anthropicMsgs.getJSONObject(2).getString("content"))
    }

    @Test
    fun sendChatAction_serializesPayloadWithShortTimeout() {
        var capturedUrl = ""
        var capturedBody = ""
        var capturedConnectTimeout = 0
        var capturedReadTimeout = 0

        val spyManager = object : TelegramGatewayManager(ioDispatcher = testDispatcher) {
            override fun makeHttpRequest(
                urlStr: String,
                method: String,
                body: String?,
                headers: Map<String, String>,
                connectTimeoutMs: Int,
                readTimeoutMs: Int
            ): JSONObject? {
                capturedUrl = urlStr
                capturedBody = body ?: ""
                capturedConnectTimeout = connectTimeoutMs
                capturedReadTimeout = readTimeoutMs
                return JSONObject().apply { put("ok", true) }
            }
        }

        val ok = spyManager.sendChatAction("bot_token_abc", 999L, "typing")
        assertTrue(ok)
        assertEquals("https://api.telegram.org/botbot_token_abc/sendChatAction", capturedUrl)
        assertEquals(TelegramGatewayManager.TYPING_TIMEOUT_MS, capturedConnectTimeout)
        assertEquals(TelegramGatewayManager.TYPING_TIMEOUT_MS, capturedReadTimeout)
        assertEquals(3000, capturedConnectTimeout)

        val json = JSONObject(capturedBody)
        assertEquals(999L, json.getLong("chat_id"))
        assertEquals("typing", json.getString("action"))
    }

    @Test
    fun nonResetSentencesStartingWithNewOrClear_treatedAsQueriesNotReset() = runTest(testDispatcher) {
        val testManager = TestTelegramGatewayManager(testDispatcher)
        val chatId = 505L
        testManager.addConversationTurn(chatId, "Initial question", "Initial answer")
        assertEquals(2, testManager.getConversationHistory(chatId).size)

        testManager.mockDaemonResponse = Result.success("Here is info on the new feature.")
        val config = HermesConfig(
            gateway = GatewayConfig(restApi = RestApiGatewayConfig(enabled = true, port = 8000))
        )

        testManager.handleIncomingMessage(
            botToken = "token_123",
            chatId = chatId,
            fromId = "user5",
            fromUsername = "user5",
            text = "new feature request for android",
            adminSet = emptySet(),
            hermesConfig = config,
            logStreamer = null,
            botName = "HermesBot"
        )
        advanceUntilIdle()

        // History must NOT have been cleared, it must have appended the turn
        val history = testManager.getConversationHistory(chatId)
        assertEquals(4, history.size)
        assertEquals("Initial question", history[0].content)
        assertEquals("new feature request for android", history[2].content)
        assertEquals("Here is info on the new feature.", history[3].content)

        // Clear explanation sentence test
        testManager.mockDaemonResponse = Result.success("Here is a clear explanation.")
        testManager.handleIncomingMessage(
            botToken = "token_123",
            chatId = chatId,
            fromId = "user5",
            fromUsername = "user5",
            text = "clear explanation please",
            adminSet = emptySet(),
            hermesConfig = config,
            logStreamer = null,
            botName = "HermesBot"
        )
        advanceUntilIdle()

        val historyAfterClearSentence = testManager.getConversationHistory(chatId)
        assertEquals(6, historyAfterClearSentence.size)
        assertEquals("clear explanation please", historyAfterClearSentence[4].content)
    }

    @Test
    fun sendMessageFailure_doesNotLeavePhantomTurnInHistory() = runTest(testDispatcher) {
        val failSendManager = TestTelegramGatewayManager(testDispatcher)
        failSendManager.shouldFailSendMessage = true

        val chatId = 707L
        failSendManager.addConversationTurn(chatId, "Prior user", "Prior assistant")
        assertEquals(2, failSendManager.getConversationHistory(chatId).size)

        failSendManager.mockDaemonResponse = Result.success("This message will fail to send.")
        val config = HermesConfig(
            gateway = GatewayConfig(restApi = RestApiGatewayConfig(enabled = true, port = 8000))
        )

        val success = failSendManager.handleIncomingMessage(
            botToken = "token_123",
            chatId = chatId,
            fromId = "user7",
            fromUsername = "user7",
            text = "Tell me something",
            adminSet = emptySet(),
            hermesConfig = config,
            logStreamer = null,
            botName = "HermesBot"
        )
        advanceUntilIdle()

        assertFalse(success)
        // History should still only have the 2 prior messages, NO phantom turn!
        val history = failSendManager.getConversationHistory(chatId)
        assertEquals(2, history.size)
        assertEquals("Prior user", history[0].content)
        assertEquals("Prior assistant", history[1].content)
    }

    @Test
    fun sendMessage_chunksLongMessagesExceeding4096Chars() {
        val sentChunks = mutableListOf<String>()
        val spyManager = object : TelegramGatewayManager(ioDispatcher = testDispatcher) {
            override fun sendSingleMessage(
                botToken: String,
                chatId: Long,
                text: String,
                logStreamer: LogStreamerInterface?
            ): Boolean {
                sentChunks.add(text)
                return true
            }
        }

        val part1 = "A".repeat(4000)
        val part2 = "B".repeat(1000)
        val fullText = "$part1\n$part2" // 5001 chars total

        val ok = spyManager.sendMessage("token_123", 1001L, fullText)
        assertTrue(ok)
        assertEquals(2, sentChunks.size)
        assertEquals(part1, sentChunks[0])
        assertEquals(part2, sentChunks[1])
        assertTrue(sentChunks[0].length <= 4096)
        assertTrue(sentChunks[1].length <= 4096)
    }

    @Test
    fun chunkMessage_splitsAtNewlinesSpacesOrHardLimit() {
        // Under limit
        val small = "Hello world"
        val single = manager.chunkMessage(small, maxChunkSize = 50)
        assertEquals(listOf("Hello world"), single)

        // Split on newline
        val textWithNewline = "First line\nSecond line"
        val chunksOnNewline = manager.chunkMessage(textWithNewline, maxChunkSize = 15)
        assertEquals(listOf("First line", "Second line"), chunksOnNewline)

        // Split on space
        val textWithSpace = "One two three four"
        val chunksOnSpace = manager.chunkMessage(textWithSpace, maxChunkSize = 10)
        assertEquals(listOf("One two", "three four"), chunksOnSpace)

        // Hard boundary when no space or newline
        val solidText = "ABCDEFGHIJKLMNOPQRSTUVWXYZ"
        val hardChunks = manager.chunkMessage(solidText, maxChunkSize = 10)
        assertEquals(listOf("ABCDEFGHIJ", "KLMNOPQRST", "UVWXYZ"), hardChunks)
    }

    // --- Test Helpers ---

    private class TestTelegramGatewayManager(
        ioDispatcher: CoroutineDispatcher
    ) : TelegramGatewayManager(ioDispatcher) {
        val sentMessages = mutableListOf<Triple<String, Long, String>>() // botToken, chatId, text
        val chatActions = mutableListOf<Triple<String, Long, String>>() // botToken, chatId, action
        var mockDaemonResponse: Result<String>? = null
        var mockLlmResponse: Result<String>? = null
        var daemonCallCount = 0
        var llmCallCount = 0
        var lastHistoryPassed: List<TelegramChatMessage>? = null
        var shouldFailSendMessage: Boolean = false

        override fun sendMessage(
            botToken: String,
            chatId: Long,
            text: String,
            logStreamer: LogStreamerInterface?
        ): Boolean {
            if (shouldFailSendMessage) return false
            sentMessages.add(Triple(botToken, chatId, text))
            return true
        }

        override fun sendChatAction(
            botToken: String,
            chatId: Long,
            action: String,
            logStreamer: LogStreamerInterface?
        ): Boolean {
            chatActions.add(Triple(botToken, chatId, action))
            return true
        }

        override fun queryLocalDaemon(
            userMessage: String,
            senderName: String,
            port: Int,
            model: String,
            history: List<TelegramChatMessage>,
            apiKey: String,
            logStreamer: LogStreamerInterface?
        ): Result<String> {
            daemonCallCount++
            lastHistoryPassed = history
            return mockDaemonResponse ?: Result.failure(Exception("Daemon mock unset"))
        }

        override fun queryLlmDetailed(
            userMessage: String,
            senderName: String,
            provider: ProviderConfig,
            logStreamer: LogStreamerInterface?,
            history: List<TelegramChatMessage>
        ): Result<String> {
            llmCallCount++
            lastHistoryPassed = history
            return mockLlmResponse ?: Result.failure(Exception("LLM mock unset"))
        }
    }

    private class FakeLogStreamer : LogStreamerInterface {
        private val _logs = mutableListOf<LogEntry>()
        private val _logsFlow = MutableStateFlow<List<LogEntry>>(emptyList())
        override val logsFlow: StateFlow<List<LogEntry>> = _logsFlow
        override val capacity: Int = 2000
        override var isStreaming: Boolean = false

        override fun start(stdout: InputStream?, stderr: InputStream?) {}
        override fun stop() {}
        override fun clear() { _logs.clear() }
        override fun append(entry: LogEntry) { _logs.add(entry) }
        override fun append(message: String, level: LogLevel) {
            _logs.add(LogEntry(timestamp = System.currentTimeMillis(), message = message, level = level))
        }
        override fun getLogs(): List<LogEntry> = _logs.toList()
    }
}

