package com.tzt.btcmonitor.strategy

import com.tzt.btcmonitor.domain.alert.AlertCooldown
import com.tzt.btcmonitor.model.AlertConfig
import com.tzt.btcmonitor.model.MarketTick
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import org.junit.Assert.*
import org.junit.Test

class TargetPriceProductionTest {
    private class TestClock : Clock() {
        var time = 0L
        override fun getZone(): ZoneId = ZoneOffset.UTC
        override fun withZone(zone: ZoneId): Clock = this
        override fun instant(): Instant = Instant.ofEpochMilli(time)
    }
    private fun tick(price: Double) = MarketTick("BTC-USDT", price, exchangeTimeMillis = 1L)

    @Test fun cooldownTracksSuppressedCrossingAndUsesUpdatedSetting() {
        val clock = TestClock()
        val alerts = listOf(AlertConfig(threshold = 100.0))
        val engine = StrategyEngine(alerts, clock)
        assertFalse(engine.evaluate(tick(99.0)).single().triggered)
        assertTrue(engine.evaluate(tick(101.0)).single().triggered)
        clock.time = 30_000L
        assertFalse(engine.evaluate(tick(99.0)).single().triggered)
        engine.updateConfigs(alerts, AlertCooldown.ONE_MINUTE)
        clock.time = 60_000L
        assertFalse(engine.evaluate(tick(98.0)).single().triggered)
        assertTrue(engine.evaluate(tick(100.0)).single().triggered)
        assertFalse(engine.evaluate(tick(100.0)).single().triggered)
    }

    @Test fun renamePreservesBaselineButPriceChangeAndReenableResetOnlyChangedAlert() {
        val a = AlertConfig(id = "a", threshold = 100.0)
        val b = AlertConfig(id = "b", threshold = 100.0)
        val engine = StrategyEngine(listOf(a, b))
        engine.evaluate(tick(99.0))
        engine.updateConfigs(listOf(a.copy(threshold = 101.0), b.copy(name = "renamed")))
        val results = engine.evaluate(tick(102.0))
        assertFalse(results.single { it.alertId == "a" }.triggered)
        assertTrue(results.single { it.alertId == "b" }.triggered)
        engine.updateConfigs(listOf(a.copy(enabled = false), b))
        engine.updateConfigs(listOf(a, b))
        assertFalse(engine.evaluate(tick(102.0)).single { it.alertId == "a" }.triggered)
        assertTrue(engine.evaluate(tick(99.0)).single { it.alertId == "a" }.triggered)
        assertTrue(engine.evaluate(tick(Double.NaN)).isEmpty())
    }
}
