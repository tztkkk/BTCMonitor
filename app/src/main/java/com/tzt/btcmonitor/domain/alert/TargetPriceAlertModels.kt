package com.tzt.btcmonitor.domain.alert

data class TargetPriceAlert(
    val id: String,
    val name: String,
    val instrumentId: String,
    val symbol: String,
    val enabled: Boolean,
    val targetPrice: Double
) {
    init {
        require(id.isNotBlank()) { "Alert ID must not be blank" }
        require(name.isNotBlank()) { "Alert name must not be blank" }
        require(instrumentId.isNotBlank()) { "Instrument ID must not be blank" }
        require(symbol.isNotBlank()) { "Alert symbol must not be blank" }
        require(targetPrice.isFinite() && targetPrice > 0.0) {
            "Target price must be positive and finite"
        }
    }
}

data class TargetPriceTick(
    val symbol: String,
    val price: Double
) {
    init {
        require(symbol.isNotBlank()) { "Tick symbol must not be blank" }
        require(price.isFinite() && price > 0.0) { "Tick price must be positive and finite" }
    }
}

enum class AlertCooldown(val minutes: Int) {
    ONE_MINUTE(1),
    FIVE_MINUTES(5),
    FIFTEEN_MINUTES(15),
    THIRTY_MINUTES(30),
    SIXTY_MINUTES(60);

    val durationMillis: Long = minutes * 60_000L

    companion object {
        val DEFAULT: AlertCooldown = FIVE_MINUTES

        fun fromMinutes(minutes: Int): AlertCooldown? = entries.firstOrNull { it.minutes == minutes }
    }
}

enum class TargetPriceCrossing {
    UPWARD,
    DOWNWARD
}

enum class TargetPriceEvaluationStatus {
    BASELINE,
    NO_CROSSING,
    TRIGGERED,
    COOLDOWN_SUPPRESSED
}

data class TargetPriceEvaluation(
    val alertId: String,
    val status: TargetPriceEvaluationStatus,
    val crossing: TargetPriceCrossing?,
    val currentPrice: Double,
    val targetPrice: Double,
    val evaluatedAtMillis: Long
) {
    val shouldNotify: Boolean
        get() = status == TargetPriceEvaluationStatus.TRIGGERED
}
