package com.tzt.btcmonitor.strategy

import com.tzt.btcmonitor.model.AlertConfig
import com.tzt.btcmonitor.model.AlertDirection
import com.tzt.btcmonitor.model.MarketTick
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StrategyEngineTest {
    @Test
    fun aboveAlertTriggersOnlyOnFalseToTrueEdge() {
        val engine = StrategyEngine(AlertConfig(threshold = 120_000.0))

        assertFalse(engine.evaluate(tick(119_900.0)).triggered)
        assertFalse(engine.evaluate(tick(119_950.0)).triggered)
        assertTrue(engine.evaluate(tick(120_010.0)).triggered)
        assertFalse(engine.evaluate(tick(120_100.0)).triggered)
        assertFalse(engine.evaluate(tick(119_900.0)).triggered)
        assertTrue(engine.evaluate(tick(120_010.0)).triggered)
    }

    @Test
    fun belowAlertUsesSameEdgeRule() {
        val engine = StrategyEngine(
            AlertConfig(direction = AlertDirection.BELOW_OR_EQUAL, threshold = 100_000.0)
        )

        assertFalse(engine.evaluate(tick(100_100.0)).triggered)
        assertTrue(engine.evaluate(tick(99_999.0)).triggered)
        assertFalse(engine.evaluate(tick(99_000.0)).triggered)
        assertFalse(engine.evaluate(tick(100_200.0)).triggered)
        assertTrue(engine.evaluate(tick(100_000.0)).triggered)
    }

    @Test
    fun firstTickEstablishesBaselineWithoutStartupNotification() {
        val engine = StrategyEngine(AlertConfig(threshold = 120_000.0))
        assertFalse(engine.evaluate(tick(121_000.0)).triggered)
    }

    private fun tick(price: Double) = MarketTick(
        symbol = "BTC-USDT",
        price = price,
        exchangeTimeMillis = 1L
    )
}
