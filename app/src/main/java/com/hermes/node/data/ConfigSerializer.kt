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
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

open class ConfigSerializer(
    targetFileOrDir: File
) {
    val configFile: File = when {
        targetFileOrDir.isDirectory || targetFileOrDir.name == "files" -> File(targetFileOrDir, CONFIG_FILE_NAME)
        targetFileOrDir.name.endsWith(".json") -> targetFileOrDir
        else -> File(targetFileOrDir, CONFIG_FILE_NAME)
    }

    constructor(baseDir: File, fileName: String) : this(File(baseDir, fileName))

    companion object {
        const val CONFIG_FILE_NAME = "hermes.json"

        fun applyPosix0600Permissions(file: File): Boolean {
            if (!file.exists()) return false
            try {
                // Ensure owner read and write
                file.setReadable(true, true)
                file.setWritable(true, true)
                file.setExecutable(false, false)

                // Try to restrict other/world access
                file.setReadable(false, false)
                file.setWritable(false, false)

                // Re-grant owner read/write
                file.setReadable(true, true)
                file.setWritable(true, true)
            } catch (_: Throwable) {}
            return file.canRead() && file.canWrite()
        }
    }

    open fun generateJson(config: HermesConfig): String {
        val root = JSONObject()
        root.put("version", 1)

        val providerObj = JSONObject()
        providerObj.put("name", config.provider.provider)
        providerObj.put("api_key", config.provider.apiKey)
        providerObj.put("model", config.provider.model)
        providerObj.put("base_url", config.provider.baseUrl)
        root.put("provider", providerObj)

        val gatewaysObj = JSONObject()

        val telegramObj = JSONObject()
        telegramObj.put("enabled", config.gateway.telegram.enabled)
        telegramObj.put("bot_token", config.gateway.telegram.botToken)
        telegramObj.put("admin_user_ids", config.gateway.telegram.adminUserIds)
        gatewaysObj.put("telegram", telegramObj)

        val discordObj = JSONObject()
        discordObj.put("enabled", config.gateway.discord.enabled)
        discordObj.put("bot_token", config.gateway.discord.botToken)
        discordObj.put("channel_ids", config.gateway.discord.channelIds)
        gatewaysObj.put("discord", discordObj)

        val slackObj = JSONObject()
        slackObj.put("enabled", config.gateway.slack.enabled)
        slackObj.put("app_token", config.gateway.slack.appToken)
        slackObj.put("bot_token", config.gateway.slack.botToken)
        gatewaysObj.put("slack", slackObj)

        val whatsAppObj = JSONObject()
        whatsAppObj.put("enabled", config.gateway.whatsapp.enabled)
        whatsAppObj.put("session_link", config.gateway.whatsapp.sessionLink)
        whatsAppObj.put("webhook_token", config.gateway.whatsapp.webhookToken)
        gatewaysObj.put("whatsapp", whatsAppObj)

        val restApiObj = JSONObject()
        restApiObj.put("enabled", config.gateway.restApi.enabled)
        val sanitizedPort = config.gateway.restApi.port.takeIf { it in 1..65535 } ?: 8000
        restApiObj.put("port", sanitizedPort)
        gatewaysObj.put("rest_api", restApiObj)

        root.put("gateways", gatewaysObj)

        val systemObj = JSONObject()
        systemObj.put("auto_start", config.system.autoStartOnBoot)
        systemObj.put("public_tunnel", config.system.publicTunnelEnabled)
        root.put("system", systemObj)

        val skillsObj = JSONObject()
        skillsObj.put(SkillsConfig.SKILL_WEB_SEARCH, config.skills.webSearch)
        skillsObj.put(SkillsConfig.SKILL_FILE_MANAGER, config.skills.fileManager)
        skillsObj.put(SkillsConfig.SKILL_BASH_RUNNER, config.skills.bashRunner)
        skillsObj.put(SkillsConfig.SKILL_CRON_SCHEDULER, config.skills.cronScheduler)
        for ((customId, customEnabled) in config.skills.customSkills) {
            if (customId !in SkillsConfig.CORE_SKILL_IDS) {
                skillsObj.put(customId, customEnabled)
            }
        }
        root.put("skills", skillsObj)

        return root.toString(2)
    }

    open fun parseJson(jsonString: String): HermesConfig {
        if (jsonString.isBlank()) return HermesConfig()
        val root = JSONObject(jsonString)

        val providerConfig = if (root.has("provider")) {
            val pObj = root.optJSONObject("provider")
            if (pObj != null) {
                ProviderConfig(
                    provider = pObj.optString("name", "nous_portal"),
                    apiKey = pObj.optString("api_key", ""),
                    model = pObj.optString("model", ""),
                    baseUrl = pObj.optString("base_url", "")
                )
            } else ProviderConfig()
        } else ProviderConfig()

        val gatewayConfig = if (root.has("gateways")) {
            val gObj = root.optJSONObject("gateways")
            if (gObj != null) {
                val tg = gObj.optJSONObject("telegram")
                val disc = gObj.optJSONObject("discord")
                val sl = gObj.optJSONObject("slack")
                val wa = gObj.optJSONObject("whatsapp")
                val rest = gObj.optJSONObject("rest_api")

                GatewayConfig(
                    telegram = TelegramGatewayConfig(
                        enabled = tg?.optBoolean("enabled", false) ?: false,
                        botToken = tg?.optString("bot_token", "") ?: "",
                        adminUserIds = tg?.optString("admin_user_ids", "") ?: ""
                    ),
                    discord = DiscordGatewayConfig(
                        enabled = disc?.optBoolean("enabled", false) ?: false,
                        botToken = disc?.optString("bot_token", "") ?: "",
                        channelIds = disc?.optString("channel_ids", "") ?: ""
                    ),
                    slack = SlackGatewayConfig(
                        enabled = sl?.optBoolean("enabled", false) ?: false,
                        appToken = sl?.optString("app_token", "") ?: "",
                        botToken = sl?.optString("bot_token", "") ?: ""
                    ),
                    whatsapp = WhatsAppGatewayConfig(
                        enabled = wa?.optBoolean("enabled", false) ?: false,
                        sessionLink = wa?.optString("session_link", "") ?: "",
                        webhookToken = wa?.optString("webhook_token", "") ?: ""
                    ),
                    restApi = RestApiGatewayConfig(
                        enabled = rest?.optBoolean("enabled", true) ?: true,
                        port = rest?.optInt("port", 8000)?.takeIf { it in 1..65535 } ?: 8000
                    )
                )
            } else GatewayConfig()
        } else GatewayConfig()

        val systemConfig = if (root.has("system")) {
            val sObj = root.optJSONObject("system")
            if (sObj != null) {
                SystemConfig(
                    autoStartOnBoot = sObj.optBoolean("auto_start", false),
                    publicTunnelEnabled = sObj.optBoolean("public_tunnel", false)
                )
            } else SystemConfig()
        } else SystemConfig()

        val skillsConfig = if (root.has("skills")) {
            val sObj = root.optJSONObject("skills")
            if (sObj != null) {
                val customMap = mutableMapOf<String, Boolean>()
                val keys = sObj.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    if (key !in SkillsConfig.CORE_SKILL_IDS) {
                        val rawVal = sObj.opt(key)
                        if (rawVal is Boolean) {
                            customMap[key] = rawVal
                        } else if (rawVal is String && (rawVal.equals("true", ignoreCase = true) || rawVal.equals("false", ignoreCase = true))) {
                            customMap[key] = rawVal.toBoolean()
                        }
                    }
                }
                SkillsConfig(
                    webSearch = sObj.optBoolean(SkillsConfig.SKILL_WEB_SEARCH, true),
                    fileManager = sObj.optBoolean(SkillsConfig.SKILL_FILE_MANAGER, true),
                    bashRunner = sObj.optBoolean(SkillsConfig.SKILL_BASH_RUNNER, true),
                    cronScheduler = sObj.optBoolean(SkillsConfig.SKILL_CRON_SCHEDULER, true),
                    customSkills = customMap
                )
            } else SkillsConfig()
        } else {
            SkillsConfig()
        }

        return HermesConfig(
            provider = providerConfig,
            gateway = gatewayConfig,
            system = systemConfig,
            skills = skillsConfig
        )
    }

    open fun deserialize(file: File = configFile): Result<HermesConfig> = runCatching {
        if (!file.exists()) {
            return@runCatching HermesConfig()
        }
        val content = file.readText(Charsets.UTF_8)
        parseJson(content)
    }

    open fun serialize(config: HermesConfig): Result<File> = runCatching {
        val parentDir = configFile.parentFile ?: File(".")
        if (!parentDir.exists()) {
            if (!parentDir.mkdirs() && !parentDir.exists()) {
                throw IOException("Failed to create configuration directory: ${parentDir.absolutePath}")
            }
        }

        val tempFile = File(parentDir, "${configFile.name}.tmp")
        if (tempFile.exists()) {
            tempFile.delete()
        }

        try {
            val jsonContent = generateJson(config)

            FileOutputStream(tempFile).use { fos ->
                fos.write(jsonContent.toByteArray(Charsets.UTF_8))
                fos.flush()
                try {
                    fos.fd.sync()
                } catch (_: Exception) {
                    // Sync may not be supported on all virtual/in-memory filesystems
                }
            }

            // Apply POSIX 0600 permissions to temp staging file
            applyPosix0600Permissions(tempFile)

            // Atomically replace destination
            val moved = if (tempFile.renameTo(configFile)) {
                true
            } else {
                try {
                    Files.move(tempFile.toPath(), configFile.toPath(), StandardCopyOption.REPLACE_EXISTING)
                    true
                } catch (_: Exception) {
                    try {
                        tempFile.inputStream().use { input ->
                            FileOutputStream(configFile).use { output ->
                                input.copyTo(output)
                                output.flush()
                                try { output.fd.sync() } catch (_: Throwable) {}
                            }
                        }
                        tempFile.delete()
                        true
                    } catch (_: Exception) {
                        false
                    }
                }
            }

            if (!moved) {
                throw IOException("Failed to write configuration file to ${configFile.absolutePath}")
            }

            // Ensure POSIX 0600 permissions on destination file
            applyPosix0600Permissions(configFile)

            configFile
        } catch (e: Exception) {
            if (tempFile.exists()) {
                tempFile.delete()
            }
            throw e
        }
    }
}
