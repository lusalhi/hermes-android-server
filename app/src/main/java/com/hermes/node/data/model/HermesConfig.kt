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

data class HermesConfig(
    val provider: ProviderConfig = ProviderConfig(),
    val gateway: GatewayConfig = GatewayConfig(),
    val system: SystemConfig = SystemConfig()
)

