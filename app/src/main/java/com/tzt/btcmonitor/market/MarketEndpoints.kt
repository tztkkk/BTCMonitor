package com.tzt.btcmonitor.market

data class MarketEndpoint(
    val id: String,
    val label: String,
    val url: String
)

object MarketEndpoints {
    val all: List<MarketEndpoint> = listOf(
        MarketEndpoint(
            id = "okx-official-8443",
            label = "OKX 官方 8443",
            url = "wss://ws.okx.com:8443/ws/v5/public"
        ),
        MarketEndpoint(
            id = "okx-standard-443",
            label = "OKX 标准 443",
            url = "wss://ws.okx.com/ws/v5/public"
        ),
        MarketEndpoint(
            id = "okx-aws-8443",
            label = "OKX AWS 8443",
            url = "wss://wsaws.okx.com:8443/ws/v5/public"
        )
    )

    fun forAttempt(attempt: Int): MarketEndpoint =
        all[Math.floorMod(attempt, all.size)]
}
