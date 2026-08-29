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
        val engine = StrategyEngine(listOf(AlertConfig(threshold = 120_000.0)))

        assertFalse(engine.evaluate(tick(119_900.0)).single().triggered)
        assertFalse(engine.evaluate(tick(119_950.0)).single().triggered)
        assertTrue(engine.evaluate(tick(120_010.0)).single().triggered)
        assertFalse(engine.evaluate(tick(120_100.0)).single().triggered)
        assertFalse(engine.evaluate(tick(119_900.0)).single().triggered)
        assertTrue(engine.evaluate(tick(120_010.0)).single().triggered)
    }

    @Test
    fun belowAlertUsesSameEdgeRule() {
        val engine = StrategyEngine(listOf(
            AlertConfig(direction = AlertDirection.BELOW_OR_EQUAL, threshold = 100_000.0)
        ))

        assertFalse(engine.evaluate(tick(100_100.0)).single().triggered)
        assertTrue(engine.evaluate(tick(99_999.0)).single().triggered)
        assertFalse(engine.evaluate(tick(99_000.0)).single().triggered)
        assertFalse(engine.evaluate(tick(100_200.0)).single().triggered)
        assertTrue(engine.evaluate(tick(100_000.0)).single().triggered)
    }

    @Test
    fun firstTickEstablishesBaselineWithoutStartupNotification() {
        val engine = StrategyEngine(listOf(AlertConfig(threshold = 120_000.0)))
        assertFalse(engine.evaluate(tick(121_000.0)).single().triggered)
    }

    @Test
    fun alertsKeepIndependentEdgeState() {
        val engine = StrategyEngine(
            listOf(
                AlertConfig(id = "high", name = "高位", threshold = 120_000.0),
                AlertConfig(
                    id = "low",
                    name = "低位",
                    direction = AlertDirection.BELOW_OR_EQUAL,
                    threshold = 100_000.0
                )
            )
        )

        assertFalse(engine.evaluate(tick(110_000.0)).any { it.triggered })
        assertTrue(engine.evaluate(tick(121_000.0)).single { it.alertId == "high" }.triggered)
        val lowTickResults = engine.evaluate(tick(99_000.0))
        assertFalse(lowTickResults.single { it.alertId == "high" }.triggered)
        assertTrue(lowTickResults.single { it.alertId == "low" }.triggered)
    }

    @Test
    fun alertsForDifferentSymbolsKeepIndependentEdgeState() {
        val engine = StrategyEngine(
            listOf(
                AlertConfig(
                    id = "btc-high",
                    name = "BTC 高位",
                    symbol = "BTC-USDT",
                    assetId = "okx:BTC-USDT",
                    threshold = 100.0
                ),
                AlertConfig(
                    id = "eth-high",
                    name = "ETH 高位",
                    symbol = "ETH-USDT",
                    assetId = "okx:ETH-USDT",
                    threshold = 10.0
                )
            )
        )

        assertFalse(engine.evaluate(tick("BTC-USDT", 99.0)).any { it.triggered })
        assertFalse(engine.evaluate(tick("ETH-USDT", 9.0)).any { it.triggered })
        assertTrue(engine.evaluate(tick("BTC-USDT", 101.0)).single().triggered)
        assertTrue(engine.evaluate(tick("ETH-USDT", 11.0)).single().triggered)
    }

    private fun tick(price: Double) = MarketTick(
        symbol = "BTC-USDT",
        price = price,
        exchangeTimeMillis = 1L
    )

    private fun tick(symbol: String, price: Double) = MarketTick(
        symbol = symbol,
        price = price,
        exchangeTimeMillis = 1L
    )
}
