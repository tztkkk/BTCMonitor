package com.tzt.btcmonitor.model

data class MarketTick(
    val symbol: String,
    val price: Double,
    val open24h: Double? = null,
    val exchangeTimeMillis: Long,
    val receivedTimeMillis: Long = System.currentTimeMillis()
)

enum class AlertDirection { ABOVE_OR_EQUAL, BELOW_OR_EQUAL }

data class AlertConfig(
    val id: String = DEFAULT_ALERT_ID,
    val name: String = "BTC 价格提醒",
    val assetId: String = "okx:BTC-USDT",
    val symbol: String = "BTC-USDT",
    val enabled: Boolean = true,
    val direction: AlertDirection = AlertDirection.ABOVE_OR_EQUAL,
    val threshold: Double = 120_000.0
) {
    companion object {
        const val DEFAULT_ALERT_ID = "default-btc-alert"
    }
}

data class StrategyResult(
    val alertId: String,
    val triggered: Boolean,
    val isConditionMet: Boolean,
    val message: String? = null,
    val evaluatedAtMillis: Long = System.currentTimeMillis()
)

enum class WebSocketStatus { DISCONNECTED, CONNECTING, CONNECTED, RECONNECTING }
enum class NetworkType { WIFI, CELLULAR, OTHER, OFFLINE }

data class AssetQuote(
    val symbol: String,
    val price: Double,
    val open24h: Double? = null,
    val receivedTimeMillis: Long = System.currentTimeMillis()
) {
    val changePercent24h: Double?
        get() = open24h?.takeIf { it != 0.0 }?.let { (price - it) / it * 100.0 }
}

data class MonitorState(
    val quotes: Map<String, AssetQuote> = emptyMap(),
    val webSocketStatus: WebSocketStatus = WebSocketStatus.DISCONNECTED,
    val serviceRunning: Boolean = false,
    val networkType: NetworkType = NetworkType.OFFLINE,
    val lastTickMillis: Long? = null,
    val lastStrategyMillis: Long? = null,
    val lastWebSocketConnectMillis: Long? = null,
    val lastWebSocketDisconnectMillis: Long? = null,
    val serviceStartedMillis: Long? = null
)
