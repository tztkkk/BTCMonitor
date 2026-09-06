package com.tzt.btcmonitor.strategy

import com.tzt.btcmonitor.domain.alert.AlertCooldown
import com.tzt.btcmonitor.domain.alert.TargetPriceAlert
import com.tzt.btcmonitor.domain.alert.TargetPriceAlertEvaluator
import com.tzt.btcmonitor.domain.alert.TargetPriceTick
import com.tzt.btcmonitor.model.AlertConfig
import com.tzt.btcmonitor.model.MarketTick
import com.tzt.btcmonitor.model.StrategyResult
import java.time.Clock

/** Production adapter; the Domain evaluator owns all crossing and cooldown state. */
class StrategyEngine(
    initialConfigs: List<AlertConfig> = emptyList(),
    clock: Clock = Clock.systemUTC()
) {
    private var configs = initialConfigs.distinctBy(AlertConfig::id)
    private val evaluator = TargetPriceAlertEvaluator(configs.map { it.toTargetAlert() }, clock = clock)

    @Synchronized
    fun updateConfigs(newConfigs: List<AlertConfig>, cooldown: AlertCooldown = AlertCooldown.DEFAULT) {
        configs = newConfigs.distinctBy(AlertConfig::id)
        evaluator.updateAlerts(configs.map { it.toTargetAlert() })
        evaluator.updateCooldown(cooldown)
    }

    @Synchronized
    fun evaluate(tick: MarketTick): List<StrategyResult> {
        if (!tick.price.isFinite() || tick.price <= 0.0 || tick.symbol.isBlank()) return emptyList()
        val byId = configs.associateBy(AlertConfig::id)
        return evaluator.evaluate(TargetPriceTick(tick.symbol, tick.price)).map { result ->
            val config = requireNotNull(byId[result.alertId])
            StrategyResult(
                alertId = result.alertId,
                triggered = result.shouldNotify,
                isConditionMet = result.crossing != null,
                message = if (result.shouldNotify) {
                    "${config.name}：${config.symbol} 已触达/穿越 ${formatPrice(config.threshold)}；当前价格 ${formatPrice(tick.price)}"
                } else null,
                evaluatedAtMillis = result.evaluatedAtMillis
            )
        }
    }

    @Synchronized
    fun reset() = evaluator.reset()

    private fun formatPrice(value: Double): String =
        if (value % 1.0 == 0.0) "%.0f".format(value) else "%.2f".format(value)
}

private fun AlertConfig.toTargetAlert() = TargetPriceAlert(id, name, assetId, symbol, enabled, threshold)
