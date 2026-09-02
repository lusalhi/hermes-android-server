package com.hermes.node.engine

import android.util.Log
import com.hermes.node.data.model.HermesConfig
import com.hermes.node.viewmodel.LogLevel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import kotlin.coroutines.coroutineContext
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import javax.net.ssl.HttpsURLConnection

interface TelegramGatewayManagerInterface {
    val isRunning: Boolean
    val botUsername: StateFlow<String?>
    suspend fun start(
        botToken: String,
        adminUserIds: String = "",
        hermesConfig: HermesConfig? = null,
        logStreamer: LogStreamerInterface? = null
    ): Result<String>
    suspend fun stop()
}

/**
 * Manages real-time connection to the Telegram Bot API via long-polling (getUpdates).
 * Handles incoming Telegram messages, enforces admin ID whitelisting if configured,
 * generates AI responses via configured LLM providers, and sends replies back via sendMessage.
 */
open class TelegramGatewayManager(
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) : TelegramGatewayManagerInterface {

    companion object {
        const val TAG = "TelegramGateway"
        private const val TELEGRAM_API_BASE = "https://api.telegram.org/bot"
        private const val CONNECT_TIMEOUT_MS = 15000
        private const val READ_TIMEOUT_MS = 35000
    }

    private val _botUsername = MutableStateFlow<String?>(null)
    override val botUsername: StateFlow<String?> = _botUsername.asStateFlow()

    private var pollingScope: CoroutineScope? = null
    private var pollingJob: Job? = null

    private var _isRunning = false
    override val isRunning: Boolean
        get() = _isRunning

    override suspend fun start(
        botToken: String,
        adminUserIds: String,
        hermesConfig: HermesConfig?,
        logStreamer: LogStreamerInterface?
    ): Result<String> = withContext(ioDispatcher) {
        if (botToken.isBlank()) {
            return@withContext Result.failure(IllegalArgumentException("Telegram Bot Token cannot be empty."))
        }

        stop()

        try {
            // Verify bot identity
            val meInfo = fetchBotInfo(botToken)
            if (meInfo.isFailure) {
                val err = meInfo.exceptionOrNull() ?: Exception("Failed to authenticate bot token.")
                logStreamer?.append("Telegram Gateway auth failed: ${err.message}", LogLevel.ERROR)
                return@withContext Result.failure(err)
            }

            val username = meInfo.getOrNull() ?: "HermesBot"
            _botUsername.value = username
            _isRunning = true

            logStreamer?.append("Telegram Bot authenticated as @$username", LogLevel.INFO)

            val scope = CoroutineScope(ioDispatcher + SupervisorJob())
            pollingScope = scope

            pollingJob = scope.launch {
                runPollingLoop(botToken, adminUserIds, hermesConfig, logStreamer, username)
            }

            Result.success(username)
        } catch (e: Exception) {
            _isRunning = false
            logStreamer?.append("Telegram Gateway startup error: ${e.message}", LogLevel.ERROR)
            Result.failure(e)
        }
    }

    override suspend fun stop(): Unit = withContext(ioDispatcher) {
        _isRunning = false
        pollingJob?.cancel()
        pollingJob = null
        pollingScope?.cancel()
        pollingScope = null
        _botUsername.value = null
    }

    private suspend fun runPollingLoop(
        botToken: String,
        adminUserIds: String,
        hermesConfig: HermesConfig?,
        logStreamer: LogStreamerInterface?,
        botName: String
    ) {
        var lastUpdateId = 0L
        val adminSet = adminUserIds.split(",", ";", " ")
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .toSet()

        while (coroutineContext.isActive && _isRunning) {
            try {
                val urlString = "$TELEGRAM_API_BASE$botToken/getUpdates?offset=$lastUpdateId&timeout=20"
                val responseJson = makeHttpRequest(urlString, "GET")

                if (responseJson != null && responseJson.optBoolean("ok", false)) {
                    val updates = responseJson.optJSONArray("result") ?: JSONArray()
                    for (i in 0 until updates.length()) {
                        val update = updates.optJSONObject(i) ?: continue
                        val updateId = update.optLong("update_id", 0L)
                        if (updateId >= lastUpdateId) {
                            lastUpdateId = updateId + 1
                        }

                        val message = update.optJSONObject("message") ?: update.optJSONObject("edited_message") ?: continue
                        val chat = message.optJSONObject("chat") ?: continue
                        val chatId = chat.optLong("id", 0L)
                        val text = message.optString("text", "").trim()
                        val from = message.optJSONObject("from")
                        val fromId = from?.optLong("id", 0L)?.toString() ?: ""
                        val rawUsername = from?.optString("username", "") ?: ""
                        val rawFirstName = from?.optString("first_name", "") ?: ""
                        val fromUsername = rawUsername.ifBlank { rawFirstName.ifBlank { "User" } }

                        if (text.isEmpty() || chatId == 0L) continue

                        logStreamer?.append("[Telegram] Received message from @$fromUsername (ID: $fromId): \"$text\"", LogLevel.INFO)

                        // Check admin authorization if specified
                        if (adminSet.isNotEmpty() && fromId !in adminSet && fromUsername !in adminSet) {
                            logStreamer?.append("[Telegram] Unauthorized access attempt from ID $fromId (@$fromUsername)", LogLevel.WARN)
                            sendMessage(
                                botToken = botToken,
                                chatId = chatId,
                                text = "⛔ Akses Ditolak: Akun Anda (ID: $fromId) tidak terdaftar di daftar Admin Hermes Node ini.",
                                logStreamer = logStreamer
                            )
                            continue
                        }

                        // Generate response
                        val replyText = generateBotReply(text, fromUsername, botName, hermesConfig, logStreamer)
                        val sendResult = sendMessage(botToken, chatId, replyText, logStreamer)

                        if (sendResult) {
                            logStreamer?.append("[Telegram] Replied to @$fromUsername (chat ID: $chatId)", LogLevel.INFO)
                        } else {
                            logStreamer?.append("[Telegram] Failed to send reply to chat ID $chatId", LogLevel.WARN)
                        }
                    }
                } else {
                    delay(3000)
                }
            } catch (e: CancellationException) {
                break
            } catch (e: Exception) {
                try {
                    Log.w(TAG, "Exception in Telegram polling loop: ${e.message}")
                } catch (_: Throwable) {}
                delay(4000)
            }
        }
    }

    private suspend fun generateBotReply(
        userMessage: String,
        senderName: String,
        botName: String,
        hermesConfig: HermesConfig?,
        logStreamer: LogStreamerInterface?
    ): String {
        val trimmed = userMessage.trim().lowercase()

        // 1. Conversational & Utility Shortcuts
        if (trimmed == "/ping" || trimmed == "ping") {
            return "🏓 Pong! Hermes Node aktif dan merespons lancar dari server Android ARM64."
        }

        if (trimmed in listOf("/start", "start", "/help", "help", "bantuan")) {
            return buildString {
                append("👋 Halo @$senderName! Selamat datang di Hermes Node (Android Server).\n\n")
                append("Saya adalah asisten AI yang berjalan langsung sebagai server mandiri di smartphone Android Anda.\n\n")
                append("📌 Perintah & Contoh:\n")
                append("• Kirim pertanyaan apa saja untuk dijawab oleh AI\n")
                append("• 'jam berapa' - Cek waktu dan tanggal server HP\n")
                append("• '/ping' - Tes latency koneksi\n")
                append("• '/status' - Status node server\n\n")
                append("💡 Tip: Masukkan API Key di tab Settings aplikasi Hermes jika ingin menggunakan model AI (OpenRouter, Groq, OpenAI, Anthropic, dll).")
            }
        }

        if (trimmed.contains("jam berapa") || trimmed.contains("jam brp") || trimmed.contains("waktu") || trimmed.contains("tanggal") || trimmed == "time") {
            val nowTime = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date())
            val nowDate = java.text.SimpleDateFormat("EEEE, dd MMMM yyyy", java.util.Locale("id", "ID")).format(java.util.Date())
            return "🕒 Waktu server saat ini: $nowTime WIB\n📅 Tanggal: $nowDate\n📱 Host: Android Server Node"
        }

        if (trimmed in listOf("hai", "halo", "hei", "p", "halo hermes", "hai hermes", "assalamualaikum", "pagi", "siang", "malam")) {
            return "👋 Halo @$senderName! Hermes Node Android aktif dan siap membantu. Ada yang bisa saya bantu?"
        }

        if (trimmed.contains("kamu siapa") || trimmed.contains("siapa kamu") || trimmed.contains("who are you")) {
            return "🤖 Saya adalah Hermes, autonomous AI Agent yang di-host langsung sebagai server mandiri di smartphone Android (ARM64) Anda."
        }

        // 2. Query LLM if API Key or Custom Endpoint is configured
        val provider = hermesConfig?.provider
        val hasLlm = provider != null && (
            provider.apiKey.isNotBlank() ||
            provider.baseUrl.isNotBlank() ||
            provider.provider.lowercase() in listOf("custom", "ollama")
        )

        if (hasLlm) {
            logStreamer?.append("[Telegram] Querying LLM (provider: ${provider!!.provider}, model: ${provider.model.ifBlank { "default" }}, url: ${provider.baseUrl.ifBlank { "default" }})...", LogLevel.INFO)
            val llmResult = queryLlmDetailed(userMessage, senderName, provider, logStreamer)
            if (llmResult.isSuccess) {
                val answer = llmResult.getOrNull()
                if (!answer.isNullOrBlank()) {
                    logStreamer?.append("[Telegram] LLM response received (${answer.length} chars)", LogLevel.INFO)
                    return answer
                }
            } else {
                val err = llmResult.exceptionOrNull()?.message ?: "Gagal terhubung ke model AI."
                logStreamer?.append("[Telegram] LLM error: $err", LogLevel.WARN)
                return "⚠️ Gagal mendapatkan respons dari LLM (${provider.provider}):\n$err\n\nSilakan periksa URL Endpoint, Model, dan API Key di tab Settings aplikasi Hermes."
            }
        }

        // 3. Fallback when no LLM API key / custom endpoint is configured
        return buildString {
            append("⚡ Hermes Node (Android Server)\n\n")
            append("Halo @$senderName! Server agen Hermes Anda aktif di background Android.\n\n")
            append("💬 Pesan diterima: \"$userMessage\"\n\n")
            append("📱 Status Sistem:\n")
            append("• Daemon: RUNNING\n")
            append("• Bot: @$botName\n")
            append("• Platform: Android ARM64\n")
            append("• Port REST: ${hermesConfig?.gateway?.restApi?.port ?: 8000}\n")
            append("• Model LLM: Belum diatur (Mode Standby)\n\n")
            append("💡 Buka tab Settings di aplikasi Hermes Node dan masukkan API Key atau Custom LLM Endpoint agar Hermes dapat menjawab seluruh pertanyaan Anda menggunakan model AI.")
        }
    }

    private fun queryLlmDetailed(
        userMessage: String,
        senderName: String,
        provider: com.hermes.node.data.model.ProviderConfig,
        logStreamer: LogStreamerInterface? = null
    ): Result<String> {
        return try {
            val providerName = provider.provider.lowercase()
            val endpoint = when {
                provider.baseUrl.isNotBlank() -> {
                    val raw = provider.baseUrl.trim().trimEnd('/')
                    when {
                        raw.endsWith("/chat/completions") -> raw
                        raw.endsWith("/v1") -> "$raw/chat/completions"
                        else -> "$raw/v1/chat/completions"
                    }
                }
                providerName == "nous_portal" -> "https://api.nousresearch.com/v1/chat/completions"
                providerName == "openrouter" -> "https://openrouter.ai/api/v1/chat/completions"
                providerName == "openai" -> "https://api.openai.com/v1/chat/completions"
                providerName == "anthropic" -> "https://api.anthropic.com/v1/messages"
                providerName == "groq" -> "https://api.groq.com/openai/v1/chat/completions"
                providerName == "ollama" -> "http://127.0.0.1:11434/v1/chat/completions"
                else -> "https://openrouter.ai/api/v1/chat/completions"
            }

            val defaultModel = when (providerName) {
                "groq" -> "llama-3.1-8b-instant"
                "anthropic" -> "claude-3-haiku-20240307"
                "openai" -> "gpt-4o-mini"
                "openrouter" -> "nousresearch/hermes-3-llama-3.1-8b"
                "ollama" -> "llama3.1"
                else -> "hermes-3-llama-3.1-8b"
            }
            val targetModel = provider.model.ifBlank { defaultModel }
            val isAnthropic = providerName == "anthropic"

            logStreamer?.append("[Telegram] Connecting to LLM endpoint: $endpoint (model: $targetModel)", LogLevel.INFO)

            val requestBody = if (isAnthropic) {
                JSONObject().apply {
                    put("model", targetModel)
                    put("max_tokens", 1024)
                    val msgs = JSONArray().apply {
                        put(JSONObject().apply {
                            put("role", "user")
                            put("content", userMessage)
                        })
                    }
                    put("messages", msgs)
                }
            } else {
                JSONObject().apply {
                    put("model", targetModel)
                    val messages = JSONArray().apply {
                        put(JSONObject().apply {
                            put("role", "system")
                            put("content", "You are Hermes, an intelligent autonomous AI assistant running on an Android node server. Respond helpfully and clearly to the user (@$senderName).")
                        })
                        put(JSONObject().apply {
                            put("role", "user")
                            put("content", userMessage)
                        })
                    }
                    put("messages", messages)
                }
            }

            val headers = mutableMapOf("Content-Type" to "application/json")
            if (provider.apiKey.isNotBlank()) {
                if (isAnthropic) {
                    headers["x-api-key"] = provider.apiKey
                    headers["anthropic-version"] = "2023-06-01"
                } else {
                    headers["Authorization"] = "Bearer ${provider.apiKey}"
                    if (providerName == "openrouter") {
                        headers["HTTP-Referer"] = "https://github.com/NousResearch/Hermes-Agent"
                        headers["X-Title"] = "Hermes Android Server"
                    }
                }
            }

            var (code, resp) = makeHttpRequestDetailed(endpoint, "POST", requestBody.toString(), headers)

            // If 404 and custom baseUrl was used, fallback to /api/chat (e.g. Ollama native endpoint)
            if (code == 404 && provider.baseUrl.isNotBlank()) {
                val raw = provider.baseUrl.trim().trimEnd('/')
                val fallbackEndpoint = if (raw.endsWith("/v1")) {
                    "${raw.removeSuffix("/v1")}/api/chat"
                } else {
                    "$raw/api/chat"
                }
                logStreamer?.append("[Telegram] 404 received, trying fallback endpoint: $fallbackEndpoint", LogLevel.INFO)
                val (fbCode, fbResp) = makeHttpRequestDetailed(fallbackEndpoint, "POST", requestBody.toString(), headers)
                if (fbCode in 200..299 && fbResp != null) {
                    code = fbCode
                    resp = fbResp
                }
            }

            if (code in 200..299 && resp != null) {
                val text = when {
                    isAnthropic -> resp.optJSONArray("content")?.optJSONObject(0)?.optString("text")?.trim()
                    resp.has("choices") -> {
                        resp.optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("message")?.optString("content")?.trim()
                    }
                    resp.has("message") -> {
                        resp.optJSONObject("message")?.optString("content")?.trim()
                    }
                    resp.has("response") -> {
                        resp.optString("response")?.trim()
                    }
                    else -> null
                }

                if (!text.isNullOrBlank()) {
                    Result.success(text)
                } else {
                    Result.failure(Exception("Empty text in response from $providerName ($endpoint)."))
                }
            } else {
                val errObj = resp?.optJSONObject("error")
                val errMsg = errObj?.optString("message")
                    ?: resp?.optString("message")
                    ?: resp?.optString("description")
                    ?: "HTTP $code from $endpoint"
                Result.failure(Exception(errMsg))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    open fun fetchBotInfo(botToken: String): Result<String> {
        return try {
            val urlString = "$TELEGRAM_API_BASE$botToken/getMe"
            val json = makeHttpRequest(urlString, "GET")
            if (json != null && json.optBoolean("ok", false)) {
                val resultObj = json.optJSONObject("result")
                val username = resultObj?.optString("username", "") ?: ""
                if (username.isNotEmpty()) {
                    Result.success(username)
                } else {
                    Result.success(resultObj?.optString("first_name", "HermesBot") ?: "HermesBot")
                }
            } else {
                val description = json?.optString("description", "Invalid bot token") ?: "Invalid bot token"
                Result.failure(Exception("Telegram API error: $description"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    open fun sendMessage(
        botToken: String,
        chatId: Long,
        text: String,
        logStreamer: LogStreamerInterface? = null
    ): Boolean {
        return try {
            val urlString = "$TELEGRAM_API_BASE$botToken/sendMessage"
            val payload = JSONObject().apply {
                put("chat_id", chatId)
                put("text", text)
            }
            val resp = makeHttpRequest(urlString, "POST", payload.toString(), mapOf("Content-Type" to "application/json"))
            val ok = resp?.optBoolean("ok", false) == true
            if (!ok) {
                val err = resp?.optString("description") ?: "Unknown error"
                logStreamer?.append("[Telegram] Telegram API send error: $err", LogLevel.WARN)
            }
            ok
        } catch (e: Exception) {
            logStreamer?.append("[Telegram] Failed to execute sendMessage: ${e.message}", LogLevel.WARN)
            false
        }
    }

    private fun makeHttpRequest(
        urlStr: String,
        method: String,
        body: String? = null,
        headers: Map<String, String> = emptyMap()
    ): JSONObject? {
        var conn: HttpURLConnection? = null
        return try {
            val url = URL(urlStr)
            conn = (url.openConnection() as? HttpsURLConnection) ?: (url.openConnection() as HttpURLConnection)
            conn.requestMethod = method
            conn.connectTimeout = CONNECT_TIMEOUT_MS
            conn.readTimeout = READ_TIMEOUT_MS

            headers.forEach { (k, v) -> conn.setRequestProperty(k, v) }

            if (body != null && (method == "POST" || method == "PUT")) {
                conn.doOutput = true
                OutputStreamWriter(conn.outputStream, Charsets.UTF_8).use { writer ->
                    writer.write(body)
                    writer.flush()
                }
            }

            val responseCode = conn.responseCode
            val stream = if (responseCode in 200..299) conn.inputStream else conn.errorStream
            if (stream != null) {
                val responseText = BufferedReader(InputStreamReader(stream, Charsets.UTF_8)).use { it.readText() }
                JSONObject(responseText)
            } else {
                null
            }
        } catch (_: Exception) {
            null
        } finally {
            try { conn?.disconnect() } catch (_: Throwable) {}
        }
    }

    private fun makeHttpRequestDetailed(
        urlStr: String,
        method: String,
        body: String? = null,
        headers: Map<String, String> = emptyMap()
    ): Pair<Int, JSONObject?> {
        var conn: HttpURLConnection? = null
        return try {
            val url = URL(urlStr)
            conn = (url.openConnection() as? HttpsURLConnection) ?: (url.openConnection() as HttpURLConnection)
            conn.requestMethod = method
            conn.connectTimeout = CONNECT_TIMEOUT_MS
            conn.readTimeout = READ_TIMEOUT_MS

            headers.forEach { (k, v) -> conn.setRequestProperty(k, v) }

            if (body != null && (method == "POST" || method == "PUT")) {
                conn.doOutput = true
                OutputStreamWriter(conn.outputStream, Charsets.UTF_8).use { writer ->
                    writer.write(body)
                    writer.flush()
                }
            }

            val responseCode = conn.responseCode
            val stream = if (responseCode in 200..299) conn.inputStream else conn.errorStream
            val responseText = if (stream != null) {
                BufferedReader(InputStreamReader(stream, Charsets.UTF_8)).use { it.readText() }
            } else ""

            val json = try { JSONObject(responseText) } catch (_: Exception) { null }
            Pair(responseCode, json)
        } catch (e: Exception) {
            Pair(-1, null)
        } finally {
            try { conn?.disconnect() } catch (_: Throwable) {}
        }
    }
}
