package com.tzt.btcmonitor.strategy

import com.tzt.btcmonitor.model.AlertConfig
import com.tzt.btcmonitor.model.AlertDirection
import com.tzt.btcmonitor.model.MarketTick
import com.tzt.btcmonitor.model.StrategyResult

class StrategyEngine(initialConfigs: List<AlertConfig> = emptyList()) {
    private var configs = initialConfigs.distinctBy(AlertConfig::id)
    private val previousConditions = mutableMapOf<String, Boolean>()

    @Synchronized
    fun updateConfigs(newConfigs: List<AlertConfig>) {
        val normalized = newConfigs.distinctBy(AlertConfig::id)
        val oldById = configs.associateBy(AlertConfig::id)
        val changedIds = normalized.filter { oldById[it.id] != it }.mapTo(mutableSetOf(), AlertConfig::id)
        previousConditions.keys.retainAll(normalized.mapTo(mutableSetOf(), AlertConfig::id))
        changedIds.forEach(previousConditions::remove)
        configs = normalized
    }

    @Synchronized
    fun evaluate(tick: MarketTick): List<StrategyResult> = configs.mapNotNull { config ->
        if (!config.enabled || tick.symbol != config.symbol) {
            previousConditions.remove(config.id)
            return@mapNotNull null
        }

        val condition = when (config.direction) {
            AlertDirection.ABOVE_OR_EQUAL -> tick.price >= config.threshold
            AlertDirection.BELOW_OR_EQUAL -> tick.price <= config.threshold
        }
        val triggered = previousConditions[config.id] == false && condition
        previousConditions[config.id] = condition
        val operator = if (config.direction == AlertDirection.ABOVE_OR_EQUAL) "突破" else "跌破"
        StrategyResult(
            alertId = config.id,
            triggered = triggered,
            isConditionMet = condition,
            message = if (triggered) {
                "${config.name}：${config.symbol} 已$operator ${formatPrice(config.threshold)}；当前价格 ${formatPrice(tick.price)}"
            } else null
        )
    }

    @Synchronized
    fun reset() {
        previousConditions.clear()
    }

    private fun formatPrice(value: Double): String =
        if (value % 1.0 == 0.0) "%.0f".format(value) else "%.2f".format(value)
}
