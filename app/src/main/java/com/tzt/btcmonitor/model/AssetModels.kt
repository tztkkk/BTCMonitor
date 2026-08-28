package com.tzt.btcmonitor.model

enum class MarketSource { OKX }

data class WatchAsset(
    val id: String,
    val symbol: String,
    val displayName: String,
    val source: MarketSource = MarketSource.OKX
)

object SupportedAssets {
    val all = listOf(
        WatchAsset("okx:BTC-USDT", "BTC-USDT", "Bitcoin"),
        WatchAsset("okx:ETH-USDT", "ETH-USDT", "Ethereum"),
        WatchAsset("okx:SOL-USDT", "SOL-USDT", "Solana"),
        WatchAsset("okx:DOGE-USDT", "DOGE-USDT", "Dogecoin"),
        WatchAsset("okx:XRP-USDT", "XRP-USDT", "XRP")
    )

    val default: WatchAsset = all.first()
    fun byId(id: String): WatchAsset? = all.firstOrNull { it.id == id }
    fun bySymbol(symbol: String): WatchAsset? = all.firstOrNull { it.symbol == symbol }
}
