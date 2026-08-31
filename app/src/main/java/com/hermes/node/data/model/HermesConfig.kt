package com.hermes.node.data.model

data class ProviderConfig(
    val provider: String = "nous_portal",
    val apiKey: String = "",
    val model: String = "",
    val baseUrl: String = ""
)

data class TelegramGatewayConfig(
    val enabled: Boolean = false,
    val botToken: String = "",
    val adminUserIds: String = ""
)

data class DiscordGatewayConfig(
    val enabled: Boolean = false,
    val botToken: String = "",
    val channelIds: String = ""
)

data class SlackGatewayConfig(
    val enabled: Boolean = false,
    val appToken: String = "",
    val botToken: String = ""
)

data class WhatsAppGatewayConfig(
    val enabled: Boolean = false,
    val sessionLink: String = "",
    val webhookToken: String = ""
)

data class RestApiGatewayConfig(
    val enabled: Boolean = true,
    val port: Int = 8000
)

data class GatewayConfig(
    val telegram: TelegramGatewayConfig = TelegramGatewayConfig(),
    val discord: DiscordGatewayConfig = DiscordGatewayConfig(),
    val slack: SlackGatewayConfig = SlackGatewayConfig(),
    val whatsapp: WhatsAppGatewayConfig = WhatsAppGatewayConfig(),
    val restApi: RestApiGatewayConfig = RestApiGatewayConfig()
) {
    val telegramToken: String get() = telegram.botToken
    val isTelegramEnabled: Boolean get() = telegram.enabled

    constructor(telegramToken: String, isTelegramEnabled: Boolean = telegramToken.isNotBlank()) : this(
        telegram = TelegramGatewayConfig(
            enabled = isTelegramEnabled,
            botToken = telegramToken
        )
    )
}

data class SystemConfig(
    val autoStartOnBoot: Boolean = false,
    val publicTunnelEnabled: Boolean = false
)

data class SkillInfo(
    val id: String,
    val name: String,
    val description: String,
    val enabled: Boolean = true,
    val isCore: Boolean = true
)

data class SkillsConfig(
    val webSearch: Boolean = true,
    val fileManager: Boolean = true,
    val bashRunner: Boolean = true,
    val cronScheduler: Boolean = true,
    val customSkills: Map<String, Boolean> = emptyMap()
) {
    fun isSkillEnabled(skillId: String): Boolean = when (skillId) {
        SKILL_WEB_SEARCH -> webSearch
        SKILL_FILE_MANAGER -> fileManager
        SKILL_BASH_RUNNER -> bashRunner
        SKILL_CRON_SCHEDULER -> cronScheduler
        else -> customSkills[skillId] ?: true
    }

    fun withSkillToggled(skillId: String, enabled: Boolean): SkillsConfig = when (skillId) {
        SKILL_WEB_SEARCH -> copy(webSearch = enabled)
        SKILL_FILE_MANAGER -> copy(fileManager = enabled)
        SKILL_BASH_RUNNER -> copy(bashRunner = enabled)
        SKILL_CRON_SCHEDULER -> copy(cronScheduler = enabled)
        else -> copy(customSkills = customSkills + (skillId to enabled))
    }

    fun toInstalledSkills(): List<SkillInfo> {
        val coreList = listOf(
            CORE_SKILL_WEB_SEARCH.copy(enabled = webSearch),
            CORE_SKILL_FILE_MANAGER.copy(enabled = fileManager),
            CORE_SKILL_BASH_RUNNER.copy(enabled = bashRunner),
            CORE_SKILL_CRON_SCHEDULER.copy(enabled = cronScheduler)
        )
        val customList = customSkills
            .filterKeys { it !in CORE_SKILL_IDS }
            .map { (id, enabled) ->
                SkillInfo(
                    id = id,
                    name = formatSkillName(id),
                    description = "Custom runtime capability ($id)",
                    enabled = enabled,
                    isCore = false
                )
            }
        return coreList + customList
    }

    companion object {
        const val SKILL_WEB_SEARCH = "web_search"
        const val SKILL_FILE_MANAGER = "file_manager"
        const val SKILL_BASH_RUNNER = "bash_runner"
        const val SKILL_CRON_SCHEDULER = "cron_scheduler"

        val CORE_SKILL_IDS = setOf(
            SKILL_WEB_SEARCH,
            SKILL_FILE_MANAGER,
            SKILL_BASH_RUNNER,
            SKILL_CRON_SCHEDULER
        )

        private val CORE_SKILL_WEB_SEARCH = SkillInfo(
            id = SKILL_WEB_SEARCH,
            name = "Web Search",
            description = "Enables real-time web querying and information retrieval via search APIs.",
            enabled = true,
            isCore = true
        )
        private val CORE_SKILL_FILE_MANAGER = SkillInfo(
            id = SKILL_FILE_MANAGER,
            name = "File Manager",
            description = "Allows reading, writing, and organizing files within the agent sandbox environment.",
            enabled = true,
            isCore = true
        )
        private val CORE_SKILL_BASH_RUNNER = SkillInfo(
            id = SKILL_BASH_RUNNER,
            name = "Bash Runner",
            description = "Executes Linux shell commands and scripts inside the embedded PRoot userland.",
            enabled = true,
            isCore = true
        )
        private val CORE_SKILL_CRON_SCHEDULER = SkillInfo(
            id = SKILL_CRON_SCHEDULER,
            name = "Cron Scheduler",
            description = "Schedules autonomous background tasks and periodic cron triggers.",
            enabled = true,
            isCore = true
        )

        val DEFAULT_CORE_SKILLS: List<SkillInfo> = listOf(
            CORE_SKILL_WEB_SEARCH,
            CORE_SKILL_FILE_MANAGER,
            CORE_SKILL_BASH_RUNNER,
            CORE_SKILL_CRON_SCHEDULER
        )

        fun formatSkillName(id: String): String {
            return id.split('_', '-', ' ')
                .filter { it.isNotBlank() }
                .joinToString(" ") { word ->
                    word.lowercase().replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
                }
        }
    }
}

data class HermesConfig(
    val provider: ProviderConfig = ProviderConfig(),
    val gateway: GatewayConfig = GatewayConfig(),
    val system: SystemConfig = SystemConfig(),
    val skills: SkillsConfig = SkillsConfig()
)

