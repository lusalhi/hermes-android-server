package com.hermes.node.data.model

data class ProviderConfig(
    val provider: String = "nous_portal",
    val apiKey: String = "",
    val model: String = "",
    val baseUrl: String = ""
)

data class GatewayConfig(
    val telegramToken: String = "",
    val isTelegramEnabled: Boolean = telegramToken.isNotBlank()
)

data class SystemConfig(
    val autoStartOnBoot: Boolean = false,
    val publicTunnelEnabled: Boolean = false
)

data class HermesConfig(
    val provider: ProviderConfig = ProviderConfig(),
    val gateway: GatewayConfig = GatewayConfig(),
    val system: SystemConfig = SystemConfig()
)
