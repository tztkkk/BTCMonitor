package com.tzt.btcmonitor.ui.chart

import com.tzt.btcmonitor.model.MarketCandle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChartContractTest {
    @Test
    fun marketCandleAdapterSortsAndDeduplicatesByOpenTime() {
        val result = listOf(
            marketCandle(3_000L, 3.0),
            marketCandle(1_000L, 1.0),
            marketCandle(2_000L, 2.0),
            marketCandle(2_000L, 2.5)
        ).toChartCandles()

        assertEquals(listOf(1_000L, 2_000L, 3_000L), result.map { it.openTimeMillis })
        assertEquals(2.5, result[1].close, 0.0)
    }

    @Test
    fun stableTimeAnchorKeepsVisibleCandlesAfterHistoryIsPrepended() {
        val current = (3L..5L).map { chartCandle(it * 1_000L) }
        val viewport = ChartViewport(
            visibleCandleCount = 10,
            anchor = ChartViewportAnchor(4_000L, positionFraction = 0.5)
        )
        val before = viewport.resolveVisibleRange(current).timesFrom(current)

        val withHistory = (1L..5L).map { chartCandle(it * 1_000L) }
        val after = viewport.resolveVisibleRange(withHistory).timesFrom(withHistory)

        assertEquals(before, after.takeLast(before.size))
        assertEquals(4_000L, viewport.anchor?.candleOpenTimeMillis)
    }

    @Test
    fun gesturePrioritySeparatesAlertCrosshairPanAndScaling() {
        assertEquals(
            ChartGestureTarget.ALERT_LINE,
            resolveGestureTarget(
                ChartGestureContext(pointerCount = 1, hitAlertLineId = "alert", longPress = true)
            )
        )
        assertEquals(
            ChartGestureTarget.CROSSHAIR,
            resolveGestureTarget(ChartGestureContext(pointerCount = 1, longPress = true))
        )
        assertEquals(
            ChartGestureTarget.TIME_PAN,
            resolveGestureTarget(ChartGestureContext(pointerCount = 1))
        )
        assertEquals(
            ChartGestureTarget.TIME_SCALE,
            resolveGestureTarget(ChartGestureContext(pointerCount = 2))
        )
        assertEquals(
            ChartGestureTarget.PRICE_SCALE,
            resolveGestureTarget(ChartGestureContext(pointerCount = 2, onPriceAxis = true))
        )
    }

    @Test
    fun loadOlderEmitsOnceAndPreservesAnchorWhenDataArrives() {
        val current = (100L..119L).map { chartCandle(it * 1_000L) }
        var state = InteractiveCandleChartState(
            candles = current,
            viewport = ChartViewport(
                visibleCandleCount = 10,
                anchor = ChartViewportAnchor(110_000L, 0.0)
            ),
            history = ChartHistoryState(hasMore = true)
        )
        val originalVisible = state.viewport.resolveVisibleRange(state.candles).timesFrom(state.candles)

        val firstRequest = InteractiveCandleChartReducer.reduce(state, ChartAction.RequestLoadOlder)
        state = firstRequest.state
        assertTrue(state.history.loadingOlder)
        assertEquals(1, firstRequest.events.size)
        assertTrue(firstRequest.events.single() is ChartOutputEvent.LoadOlder)

        val duplicateRequest = InteractiveCandleChartReducer.reduce(state, ChartAction.RequestLoadOlder)
        assertTrue(duplicateRequest.events.isEmpty())

        val older = (90L..101L).map { chartCandle(it * 1_000L) }
        val loaded = InteractiveCandleChartReducer.reduce(
            state,
            ChartAction.OlderCandlesLoaded(older, hasMore = false)
        ).state
        val restoredVisible = loaded.viewport.resolveVisibleRange(loaded.candles).timesFrom(loaded.candles)

        assertEquals(originalVisible, restoredVisible)
        assertEquals(30, loaded.candles.size)
        assertFalse(loaded.history.loadingOlder)
        assertFalse(loaded.history.hasMore)
    }

    @Test
    fun alertLineDragEmitsMoveOnlyOnceWhenGestureEnds() {
        var state = InteractiveCandleChartState(
            alertLines = listOf(AlertLine("alert", "Target", 100.0, enabled = true))
        )

        state = InteractiveCandleChartReducer.reduce(
            state,
            ChartAction.BeginAlertLineDrag("alert")
        ).state
        val moving = InteractiveCandleChartReducer.reduce(
            state,
            ChartAction.UpdateAlertLineDrag(105.0)
        )
        state = moving.state
        assertTrue(moving.events.isEmpty())

        val ended = InteractiveCandleChartReducer.reduce(state, ChartAction.EndAlertLineDrag)
        assertEquals(listOf(ChartOutputEvent.MoveAlert("alert", 105.0)), ended.events)
        assertNull(ended.state.alertLineDrag)

        val repeatedEnd = InteractiveCandleChartReducer.reduce(
            ended.state,
            ChartAction.EndAlertLineDrag
        )
        assertTrue(repeatedEnd.events.isEmpty())
    }

    @Test
    fun disabledAlertLineCannotStartDrag() {
        val state = InteractiveCandleChartState(
            alertLines = listOf(AlertLine("alert", "Disabled", 100.0, enabled = false))
        )

        val result = InteractiveCandleChartReducer.reduce(
            state,
            ChartAction.BeginAlertLineDrag("alert")
        )

        assertNull(result.state.alertLineDrag)
        assertTrue(result.events.isEmpty())
    }

    @Test
    fun crosshairCreateAndViewportChangesAreOutputEvents() {
        val candle = chartCandle(1_000L)
        var state = InteractiveCandleChartState(candles = listOf(candle))
        state = InteractiveCandleChartReducer.reduce(
            state,
            ChartAction.ShowCrosshair(candle.openTimeMillis, 101.0)
        ).state
        state = InteractiveCandleChartReducer.reduce(state, ChartAction.PinCrosshair).state

        val create = InteractiveCandleChartReducer.reduce(state, ChartAction.RequestCreateAlert)
        assertEquals(listOf(ChartOutputEvent.CreateAlert(101.0)), create.events)
        assertTrue((create.state.crosshair as CrosshairState.Visible).pinned)

        val anchor = ChartViewportAnchor(1_000L, 0.5)
        val panned = InteractiveCandleChartReducer.reduce(
            create.state,
            ChartAction.PanTo(anchor)
        )
        assertEquals(
            listOf(ChartOutputEvent.ViewportChanged(panned.state.viewport)),
            panned.events
        )

        val scaledTime = InteractiveCandleChartReducer.reduce(
            panned.state,
            ChartAction.ScaleTime(visibleCandleCount = 20)
        )
        val scaledPrice = InteractiveCandleChartReducer.reduce(
            scaledTime.state,
            ChartAction.ScalePrice(ChartPriceRange(80.0, 120.0))
        )
        assertEquals(20, scaledPrice.state.viewport.visibleCandleCount)
        assertEquals(ChartPriceRange(80.0, 120.0), scaledPrice.state.viewport.priceRange)
        assertEquals(anchor, scaledPrice.state.viewport.anchor)

        val latest = InteractiveCandleChartReducer.reduce(
            scaledPrice.state,
            ChartAction.FollowLatest
        )
        assertTrue(latest.state.viewport.followsLatest)
        assertNull(latest.state.viewport.priceRange)
    }

    private fun ChartVisibleRange.timesFrom(candles: List<ChartCandle>): List<Long> =
        candles.subList(startInclusive, endExclusive).map(ChartCandle::openTimeMillis)

    private fun chartCandle(openTimeMillis: Long, close: Double = 100.0) = ChartCandle(
        openTimeMillis = openTimeMillis,
        open = close,
        high = close + 1.0,
        low = close - 1.0,
        close = close,
        volume = 10.0,
        confirmed = true
    )

    private fun marketCandle(openTimeMillis: Long, close: Double) = MarketCandle(
        openTimeMillis = openTimeMillis,
        open = close,
        high = close + 1.0,
        low = close - 1.0,
        close = close,
        volume = 10.0,
        confirmed = true
    )
}
