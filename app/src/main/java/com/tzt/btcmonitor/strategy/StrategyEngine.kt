package com.tzt.btcmonitor.strategy

import com.tzt.btcmonitor.model.AlertConfig
import com.tzt.btcmonitor.model.AlertDirection
import com.tzt.btcmonitor.model.MarketTick
import com.tzt.btcmonitor.model.StrategyResult

class StrategyEngine(initialConfig: AlertConfig = AlertConfig()) {
    private var config = initialConfig
    private var previousCondition: Boolean? = null

    @Synchronized
    fun updateConfig(newConfig: AlertConfig) {
        if (newConfig != config) {
            config = newConfig
            previousCondition = null
        }
    }

    @Synchronized
    fun evaluate(tick: MarketTick): StrategyResult {
        if (!config.enabled || tick.symbol != config.symbol) {
            previousCondition = null
            return StrategyResult(triggered = false, isConditionMet = false)
        }

        val condition = when (config.direction) {
            AlertDirection.ABOVE_OR_EQUAL -> tick.price >= config.threshold
            AlertDirection.BELOW_OR_EQUAL -> tick.price <= config.threshold
        }
        val triggered = previousCondition == false && condition
        previousCondition = condition

        val operator = if (config.direction == AlertDirection.ABOVE_OR_EQUAL) "突破" else "跌破"
        return StrategyResult(
            triggered = triggered,
            isConditionMet = condition,
            message = if (triggered) {
                "${config.symbol} 已$operator ${formatPrice(config.threshold)}；当前价格 ${formatPrice(tick.price)}"
            } else null
        )
    }

    @Synchronized
    fun reset() {
        previousCondition = null
    }

    private fun formatPrice(value: Double): String =
        if (value % 1.0 == 0.0) "%.0f".format(value) else "%.2f".format(value)
}
