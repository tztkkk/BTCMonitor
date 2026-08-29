package com.tzt.btcmonitor.ui.chart

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.roundToInt

class ChartInteractionTest {
    private val candles = candles(200)
    private val plot = ChartPlot(1_000f, 500f, 20f, 80f, 20f, 30f)

    @Test
    fun horizontalPanMovesToOlderCandlesAndStopsAtBothBoundaries() {
        val initial = ChartViewport(visibleCandleCount = 60)
        val older = panViewport(initial, candles, plot.plotWidth / 6f, plot.plotWidth)
        assertTrue(
            older.resolveVisibleRange(candles).startInclusive <
                initial.resolveVisibleRange(candles).startInclusive
        )

        val leftBoundary = panViewport(older, candles, Float.MAX_VALUE, plot.plotWidth)
        assertEquals(0, leftBoundary.resolveVisibleRange(candles).startInclusive)

        val rightBoundary = panViewport(leftBoundary, candles, -Float.MAX_VALUE, plot.plotWidth)
        assertEquals(candles.size, rightBoundary.resolveVisibleRange(candles).endExclusive)
    }

    @Test
    fun timeScalePreservesFocalCandleAndDoesNotMutateSourceCandles() {
        val originalCandles = candles.toList()
        val initial = ChartViewport(visibleCandleCount = 60)
        val initialRange = initial.resolveVisibleRange(candles)
        val focalIndex = initialRange.startInclusive +
            ((initialRange.size - 1) * 0.5).roundToInt()

        val scaled = scaleTimeViewport(initial, candles, zoomFactor = 2f, focalFraction = 0.5)

        assertEquals(30, scaled.visibleCandleCount)
        assertEquals(candles[focalIndex].openTimeMillis, scaled.anchor?.candleOpenTimeMillis)
        assertEquals(originalCandles, candles)
    }

    @Test
    fun timeScaleClampsSupportedCandleCountAndFocalFraction() {
        val minimum = scaleTimeViewport(
            ChartViewport(visibleCandleCount = MIN_VISIBLE_CANDLE_COUNT),
            candles,
            zoomFactor = 100f,
            focalFraction = -2.0
        )
        val maximum = scaleTimeViewport(
            ChartViewport(visibleCandleCount = MAX_VISIBLE_CANDLE_COUNT),
            candles,
            zoomFactor = 0.01f,
            focalFraction = 2.0
        )

        assertEquals(MIN_VISIBLE_CANDLE_COUNT, minimum.visibleCandleCount)
        assertEquals(0.0, minimum.anchor?.positionFraction)
        assertEquals(MAX_VISIBLE_CANDLE_COUNT, maximum.visibleCandleCount)
        assertEquals(1.0, maximum.anchor?.positionFraction)
    }

    @Test
    fun priceScaleKeepsCenterAndDoesNotMutateCandleData() {
        val originalCandles = candles.toList()
        val initial = ChartPriceRange(80.0, 120.0)

        val zoomedOut = scalePriceRange(initial, 100f, 400f)
        val zoomedIn = scalePriceRange(initial, -100f, 400f)

        assertEquals(100.0, (zoomedOut.min + zoomedOut.max) / 2.0, 0.000001)
        assertEquals(100.0, (zoomedIn.min + zoomedIn.max) / 2.0, 0.000001)
        assertTrue(zoomedOut.max - zoomedOut.min > initial.max - initial.min)
        assertTrue(zoomedIn.max - zoomedIn.min < initial.max - initial.min)
        assertEquals(originalCandles, candles)
    }

    @Test
    fun crosshairHitTestUsesNearestVisibleCandleAndNeverLeavesPlot() {
        val viewport = ChartViewport(visibleCandleCount = 60)
        val visible = viewport.resolveVisibleRange(candles)

        assertEquals(
            visible.startInclusive,
            candleIndexAtX(plot.left, candles, viewport, plot)
        )
        assertEquals(
            visible.endExclusive - 1,
            candleIndexAtX(plot.plotRight, candles, viewport, plot)
        )
        assertNull(candleIndexAtX(plot.left - 1f, candles, viewport, plot))
        assertNull(candleIndexAtX(plot.plotRight + 1f, candles, viewport, plot))
    }

    @Test
    fun pinnedCrosshairPersistsUntilExplicitBlankTapAction() {
        val selected = candles[170]
        var state = InteractiveCandleChartState(candles = candles)
        state = InteractiveCandleChartReducer.reduce(
            state,
            ChartAction.ShowCrosshair(selected.openTimeMillis, selected.close)
        ).state
        state = InteractiveCandleChartReducer.reduce(state, ChartAction.PinCrosshair).state

        assertTrue((state.crosshair as CrosshairState.Visible).pinned)

        state = InteractiveCandleChartReducer.reduce(state, ChartAction.HideCrosshair).state
        assertEquals(CrosshairState.Hidden, state.crosshair)
    }

    @Test
    fun returnToLatestRestoresAutomaticPriceRange() {
        val state = InteractiveCandleChartState(
            candles = candles,
            viewport = ChartViewport(
                visibleCandleCount = 30,
                anchor = ChartViewportAnchor(candles[50].openTimeMillis, 0.5),
                priceRange = ChartPriceRange(80.0, 120.0)
            )
        )

        val result = InteractiveCandleChartReducer.reduce(state, ChartAction.FollowLatest)

        assertTrue(result.state.viewport.followsLatest)
        assertNull(result.state.viewport.priceRange)
    }

    @Test
    fun tenThousandCandlesStillRenderOnlyTheBoundedViewport() {
        val large = candles(10_000)
        val plan = requireNotNull(
            buildChartRenderPlan(
                InteractiveCandleChartState(
                    candles = large,
                    viewport = ChartViewport(visibleCandleCount = MAX_VISIBLE_CANDLE_COUNT)
                )
            )
        )

        assertEquals(MAX_VISIBLE_CANDLE_COUNT, plan.visibleRange.size)
    }

    private fun candles(count: Int): List<ChartCandle> = List(count) { index ->
        val close = 100.0 + index / 10.0
        ChartCandle(
            openTimeMillis = 60_000L * (index + 1L),
            open = close - 0.25,
            high = close + 1.0,
            low = close - 1.0,
            close = close,
            volume = 10.0,
            confirmed = true
        )
    }
}
