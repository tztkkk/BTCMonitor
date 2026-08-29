package com.tzt.btcmonitor.domain.alert

import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TargetPriceAlertEvaluatorTest {
    @Test
    fun upwardJumpAcrossTargetTriggers() {
        val evaluator = evaluator(alert(targetPrice = 100.0))

        assertStatus(TargetPriceEvaluationStatus.BASELINE, evaluator.evaluate(tick(90.0)).single())
        val result = evaluator.evaluate(tick(110.0)).single()

        assertStatus(TargetPriceEvaluationStatus.TRIGGERED, result)
        assertEquals(TargetPriceCrossing.UPWARD, result.crossing)
        assertTrue(result.shouldNotify)
    }

    @Test
    fun downwardJumpAcrossTargetTriggers() {
        val evaluator = evaluator(alert(targetPrice = 100.0))

        evaluator.evaluate(tick(110.0))
        val result = evaluator.evaluate(tick(90.0)).single()

        assertStatus(TargetPriceEvaluationStatus.TRIGGERED, result)
        assertEquals(TargetPriceCrossing.DOWNWARD, result.crossing)
    }

    @Test
    fun exactTargetContactTriggersFromEitherSideButNotAsFirstTick() {
        val upward = evaluator(alert(id = "up", targetPrice = 100.0))
        assertStatus(TargetPriceEvaluationStatus.BASELINE, upward.evaluate(tick(90.0)).single())
        val upwardContact = upward.evaluate(tick(100.0)).single()
        assertStatus(TargetPriceEvaluationStatus.TRIGGERED, upwardContact)
        assertEquals(TargetPriceCrossing.UPWARD, upwardContact.crossing)

        val downward = evaluator(alert(id = "down", targetPrice = 100.0))
        assertStatus(TargetPriceEvaluationStatus.BASELINE, downward.evaluate(tick(110.0)).single())
        val downwardContact = downward.evaluate(tick(100.0)).single()
        assertStatus(TargetPriceEvaluationStatus.TRIGGERED, downwardContact)
        assertEquals(TargetPriceCrossing.DOWNWARD, downwardContact.crossing)

        val firstAtTarget = evaluator(alert(id = "baseline", targetPrice = 100.0))
        val baseline = firstAtTarget.evaluate(tick(100.0)).single()
        assertStatus(TargetPriceEvaluationStatus.BASELINE, baseline)
        assertNull(baseline.crossing)
        assertFalse(baseline.shouldNotify)
    }

    @Test
    fun cooldownSuppressesCrossingButContinuesTrackingSide() {
        val clock = MutableClock(1_000_000L)
        val evaluator = evaluator(alert(targetPrice = 100.0), clock = clock)
        evaluator.evaluate(tick(90.0))
        assertStatus(TargetPriceEvaluationStatus.TRIGGERED, evaluator.evaluate(tick(110.0)).single())

        clock.advanceMinutes(1)
        val suppressed = evaluator.evaluate(tick(90.0)).single()
        assertStatus(TargetPriceEvaluationStatus.COOLDOWN_SUPPRESSED, suppressed)
        assertEquals(TargetPriceCrossing.DOWNWARD, suppressed.crossing)
        assertFalse(suppressed.shouldNotify)

        clock.advanceMinutes(5)
        assertStatus(TargetPriceEvaluationStatus.NO_CROSSING, evaluator.evaluate(tick(90.0)).single())
        assertStatus(TargetPriceEvaluationStatus.TRIGGERED, evaluator.evaluate(tick(110.0)).single())
    }

    @Test
    fun allAllowedCooldownsExistAndDefaultIsFiveMinutes() {
        assertEquals(AlertCooldown.FIVE_MINUTES, AlertCooldown.DEFAULT)
        assertEquals(listOf(1, 5, 15, 30, 60), AlertCooldown.entries.map { it.minutes })
        AlertCooldown.entries.forEach { cooldown ->
            assertEquals(cooldown, AlertCooldown.fromMinutes(cooldown.minutes))
        }
        assertNull(AlertCooldown.fromMinutes(2))
    }

    @Test
    fun updatedGlobalCooldownAppliesToFollowingCrossings() {
        val clock = MutableClock(1_000_000L)
        val evaluator = TargetPriceAlertEvaluator(
            initialAlerts = listOf(alert(targetPrice = 100.0)),
            initialCooldown = AlertCooldown.FIFTEEN_MINUTES,
            clock = clock
        )
        evaluator.evaluate(tick(90.0))
        evaluator.evaluate(tick(110.0))
        clock.advanceMinutes(2)

        evaluator.updateCooldown(AlertCooldown.ONE_MINUTE)

        assertStatus(TargetPriceEvaluationStatus.TRIGGERED, evaluator.evaluate(tick(90.0)).single())
    }

    @Test
    fun changingTargetOrReenablingRequiresANewBaseline() {
        val original = alert(targetPrice = 100.0)
        val evaluator = evaluator(original)
        evaluator.evaluate(tick(90.0))
        evaluator.evaluate(tick(110.0))

        evaluator.updateAlerts(listOf(original.copy(targetPrice = 120.0)))
        assertStatus(TargetPriceEvaluationStatus.BASELINE, evaluator.evaluate(tick(110.0)).single())
        assertStatus(TargetPriceEvaluationStatus.TRIGGERED, evaluator.evaluate(tick(130.0)).single())

        evaluator.updateAlerts(listOf(original.copy(targetPrice = 120.0, enabled = false)))
        assertTrue(evaluator.evaluate(tick(110.0)).isEmpty())
        evaluator.updateAlerts(listOf(original.copy(targetPrice = 120.0, enabled = true)))
        assertStatus(TargetPriceEvaluationStatus.BASELINE, evaluator.evaluate(tick(130.0)).single())
    }

    @Test
    fun differentAlertsAndSymbolsKeepIndependentState() {
        val btc = alert(id = "btc", symbol = "BTC-USDT", targetPrice = 100.0)
        val eth = alert(id = "eth", symbol = "ETH-USDT", targetPrice = 10.0)
        val evaluator = evaluator(btc, eth)

        assertStatus(TargetPriceEvaluationStatus.BASELINE, evaluator.evaluate(tick(90.0)).single())
        assertStatus(
            TargetPriceEvaluationStatus.BASELINE,
            evaluator.evaluate(TargetPriceTick("ETH-USDT", 9.0)).single()
        )
        assertEquals("btc", evaluator.evaluate(tick(110.0)).single().alertId)
        assertEquals(
            "eth",
            evaluator.evaluate(TargetPriceTick("ETH-USDT", 11.0)).single().alertId
        )
    }

    @Test
    fun resetOnlyAffectsRequestedAlert() {
        val first = alert(id = "first", targetPrice = 100.0)
        val second = alert(id = "second", targetPrice = 200.0)
        val evaluator = evaluator(first, second)
        evaluator.evaluate(tick(90.0))
        evaluator.reset("first")

        val results = evaluator.evaluate(tick(210.0)).associateBy(TargetPriceEvaluation::alertId)

        assertStatus(TargetPriceEvaluationStatus.BASELINE, requireNotNull(results["first"]))
        assertStatus(TargetPriceEvaluationStatus.TRIGGERED, requireNotNull(results["second"]))
    }

    private fun evaluator(
        vararg alerts: TargetPriceAlert,
        clock: Clock = Clock.fixed(Instant.ofEpochMilli(1_000_000L), ZoneOffset.UTC)
    ) = TargetPriceAlertEvaluator(alerts.toList(), clock = clock)

    private fun alert(
        id: String = "alert",
        symbol: String = "BTC-USDT",
        targetPrice: Double
    ) = TargetPriceAlert(
        id = id,
        name = id,
        instrumentId = "provider:$symbol",
        symbol = symbol,
        enabled = true,
        targetPrice = targetPrice
    )

    private fun tick(price: Double) = TargetPriceTick("BTC-USDT", price)

    private fun assertStatus(
        expected: TargetPriceEvaluationStatus,
        actual: TargetPriceEvaluation
    ) = assertEquals(expected, actual.status)

    private class MutableClock(
        private var currentMillis: Long,
        private val currentZone: ZoneId = ZoneOffset.UTC
    ) : Clock() {
        override fun getZone(): ZoneId = currentZone

        override fun withZone(zone: ZoneId): Clock = MutableClock(currentMillis, zone)

        override fun instant(): Instant = Instant.ofEpochMilli(currentMillis)

        fun advanceMinutes(minutes: Long) {
            currentMillis += minutes * 60_000L
        }
    }
}
