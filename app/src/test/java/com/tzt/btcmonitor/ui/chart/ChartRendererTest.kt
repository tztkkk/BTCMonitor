package com.tzt.btcmonitor.ui.chart

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ChartRendererTest {
    @Test
    fun renderPlanAppliesTimeAnchoredViewport() {
        val candles = candles(count = 100, firstTime = 60_000L)
        val state = InteractiveCandleChartState(
            candles = candles,
            viewport = ChartViewport(
                visibleCandleCount = 20,
                anchor = ChartViewportAnchor(candles[30].openTimeMillis, 0.5)
            )
        )

        val plan = requireNotNull(buildChartRenderPlan(state))

        assertEquals(20, plan.visibleRange.size)
        assertTrue(30 in plan.visibleRange.startInclusive until plan.visibleRange.endExclusive)
    }

    @Test
    fun renderPlanRebuildsLatestViewportForEachTimeframeInput() {
        val oneMinuteCandles = candles(count = 80, firstTime = 60_000L, interval = 60_000L)
        val oneHourCandles = candles(count = 24, firstTime = 3_600_000L, interval = 3_600_000L)

        val oneMinutePlan = requireNotNull(
            buildChartRenderPlan(InteractiveCandleChartState(candles = oneMinuteCandles))
        )
        val oneHourPlan = requireNotNull(
            buildChartRenderPlan(InteractiveCandleChartState(candles = oneHourCandles))
        )

        assertEquals(20, oneMinutePlan.visibleRange.startInclusive)
        assertEquals(80, oneMinutePlan.visibleRange.endExclusive)
        assertEquals(0, oneHourPlan.visibleRange.startInclusive)
        assertEquals(24, oneHourPlan.visibleRange.endExclusive)
    }

    @Test
    fun renderPlanKeepsAlertsAcrossViewportChangesAndClassifiesOutOfRangeLines() {
        val alerts = listOf(
            AlertLine("below", "下方", 80.0, true),
            AlertLine("inside", "范围内", 110.0, true),
            AlertLine("disabled", "已停用", 112.0, false),
            AlertLine("above", "上方", 150.0, true)
        )
        val state = InteractiveCandleChartState(
            candles = candles(count = 20, firstTime = 60_000L),
            viewport = ChartViewport(
                visibleCandleCount = 20,
                priceRange = ChartPriceRange(100.0, 120.0)
            ),
            alertLines = alerts
        )

        val plan = requireNotNull(buildChartRenderPlan(state))

        assertEquals(listOf("inside", "disabled"), plan.visibleAlerts.map(AlertLine::id))
        assertEquals(listOf("above"), plan.alertsAbove.map(AlertLine::id))
        assertEquals(listOf("below"), plan.alertsBelow.map(AlertLine::id))
        assertEquals(alerts, state.alertLines)
    }

    @Test
    fun renderPlanUsesExplicitPriceViewportWithoutMutatingIt() {
        val range = ChartPriceRange(90.0, 140.0)
        val state = InteractiveCandleChartState(
            candles = candles(count = 20, firstTime = 60_000L),
            viewport = ChartViewport(visibleCandleCount = 20, priceRange = range)
        )

        val plan = requireNotNull(buildChartRenderPlan(state))

        assertSame(range, plan.priceRange)
        assertEquals(range, state.viewport.priceRange)
    }

    @Test
    fun coordinateConversionRoundTripsAndEmptyInputHasNoPlan() {
        val plot = ChartPlot(1_000f, 500f, 20f, 80f, 20f, 30f)
        val range = ChartPriceRange(90.0, 140.0)
        val y = priceToY(117.25, range, plot)

        assertEquals(117.25, yToPrice(y, range, plot), 0.0001)
        assertNull(buildChartRenderPlan(InteractiveCandleChartState()))
    }

    private fun candles(
        count: Int,
        firstTime: Long,
        interval: Long = 60_000L
    ): List<ChartCandle> = (0 until count).map { index ->
        val open = 100.0 + index
        ChartCandle(
            openTimeMillis = firstTime + index * interval,
            open = open,
            high = open + 2.0,
            low = open - 2.0,
            close = open + 1.0,
            volume = 10.0,
            confirmed = true
        )
    }
}
