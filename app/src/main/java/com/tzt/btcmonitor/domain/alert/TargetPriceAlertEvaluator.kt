package com.tzt.btcmonitor.domain.alert

import java.time.Clock

class TargetPriceAlertEvaluator(
    initialAlerts: List<TargetPriceAlert> = emptyList(),
    initialCooldown: AlertCooldown = AlertCooldown.DEFAULT,
    private val clock: Clock = Clock.systemUTC()
) {
    private var alerts = initialAlerts.distinctBy(TargetPriceAlert::id)
    private var cooldown = initialCooldown
    private val states = mutableMapOf<String, AlertState>()

    @Synchronized
    fun updateAlerts(newAlerts: List<TargetPriceAlert>) {
        val normalized = newAlerts.distinctBy(TargetPriceAlert::id)
        val newIds = normalized.mapTo(mutableSetOf(), TargetPriceAlert::id)
        val previousById = alerts.associateBy(TargetPriceAlert::id)
        states.keys.retainAll(newIds)
        normalized.forEach { alert ->
            val previous = previousById[alert.id]
            if (previous != null && previous.evaluationKey() != alert.evaluationKey()) {
                states.remove(alert.id)
            }
        }
        alerts = normalized
    }

    @Synchronized
    fun updateCooldown(newCooldown: AlertCooldown) {
        cooldown = newCooldown
    }

    @Synchronized
    fun evaluate(tick: TargetPriceTick): List<TargetPriceEvaluation> {
        val nowMillis = clock.millis()
        return alerts.mapNotNull { alert ->
            if (!alert.enabled || alert.symbol != tick.symbol) return@mapNotNull null

            val currentSide = PriceSide.from(tick.price, alert.targetPrice)
            val previousState = states[alert.id]
            if (previousState == null) {
                states[alert.id] = AlertState(currentSide, lastNotificationMillis = null)
                return@mapNotNull alert.evaluation(
                    tick = tick,
                    status = TargetPriceEvaluationStatus.BASELINE,
                    crossing = null,
                    nowMillis = nowMillis
                )
            }

            val crossing = crossing(previousState.side, currentSide)
            val cooldownElapsed = previousState.lastNotificationMillis?.let { last ->
                nowMillis - last >= cooldown.durationMillis
            } ?: true
            val status = when {
                crossing == null -> TargetPriceEvaluationStatus.NO_CROSSING
                cooldownElapsed -> TargetPriceEvaluationStatus.TRIGGERED
                else -> TargetPriceEvaluationStatus.COOLDOWN_SUPPRESSED
            }
            states[alert.id] = AlertState(
                side = currentSide,
                lastNotificationMillis = if (status == TargetPriceEvaluationStatus.TRIGGERED) {
                    nowMillis
                } else {
                    previousState.lastNotificationMillis
                }
            )
            alert.evaluation(tick, status, crossing, nowMillis)
        }
    }

    @Synchronized
    fun reset(alertId: String? = null) {
        if (alertId == null) states.clear() else states.remove(alertId)
    }

    private fun crossing(previous: PriceSide, current: PriceSide): TargetPriceCrossing? = when {
        previous == PriceSide.BELOW && current != PriceSide.BELOW -> TargetPriceCrossing.UPWARD
        previous == PriceSide.ABOVE && current != PriceSide.ABOVE -> TargetPriceCrossing.DOWNWARD
        else -> null
    }

    private fun TargetPriceAlert.evaluation(
        tick: TargetPriceTick,
        status: TargetPriceEvaluationStatus,
        crossing: TargetPriceCrossing?,
        nowMillis: Long
    ) = TargetPriceEvaluation(
        alertId = id,
        status = status,
        crossing = crossing,
        currentPrice = tick.price,
        targetPrice = targetPrice,
        evaluatedAtMillis = nowMillis
    )

    private fun TargetPriceAlert.evaluationKey() = EvaluationKey(
        instrumentId = instrumentId,
        symbol = symbol,
        enabled = enabled,
        targetPrice = targetPrice
    )

    private data class EvaluationKey(
        val instrumentId: String,
        val symbol: String,
        val enabled: Boolean,
        val targetPrice: Double
    )

    private data class AlertState(
        val side: PriceSide,
        val lastNotificationMillis: Long?
    )

    private enum class PriceSide {
        BELOW,
        AT,
        ABOVE;

        companion object {
            fun from(price: Double, targetPrice: Double): PriceSide = when {
                price < targetPrice -> BELOW
                price > targetPrice -> ABOVE
                else -> AT
            }
        }
    }
}
