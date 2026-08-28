package com.tzt.btcmonitor.model

data class MarketCandle(
    val openTimeMillis: Long,
    val open: Double,
    val high: Double,
    val low: Double,
    val close: Double,
    val volume: Double,
    val confirmed: Boolean
) {
    init {
        require(openTimeMillis > 0)
        require(listOf(open, high, low, close, volume).all(Double::isFinite))
        require(high >= low)
    }
}

enum class CandleTimeframe(val apiValue: String, val label: String) {
    ONE_MINUTE("1m", "1m"),
    FIVE_MINUTES("5m", "5m"),
    FIFTEEN_MINUTES("15m", "15m"),
    ONE_HOUR("1H", "1H"),
    FOUR_HOURS("4H", "4H"),
    ONE_DAY("1D", "1D")
}
