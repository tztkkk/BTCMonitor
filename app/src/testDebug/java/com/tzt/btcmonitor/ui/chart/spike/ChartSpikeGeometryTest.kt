package com.tzt.btcmonitor.ui.chart.spike

import com.tzt.btcmonitor.ui.chart.ChartCandle
import com.tzt.btcmonitor.ui.chart.ChartPlot
import com.tzt.btcmonitor.ui.chart.ChartPriceRange
import com.tzt.btcmonitor.ui.chart.ChartViewport
import com.tzt.btcmonitor.ui.chart.candleIndexAtX
import com.tzt.btcmonitor.ui.chart.panViewport
import com.tzt.btcmonitor.ui.chart.priceToY
import com.tzt.btcmonitor.ui.chart.resolveVisibleRange
import com.tzt.btcmonitor.ui.chart.scalePriceRange
import com.tzt.btcmonitor.ui.chart.scaleTimeViewport
import com.tzt.btcmonitor.ui.chart.visiblePriceRange
import com.tzt.btcmonitor.ui.chart.yToPrice
import kotlin.math.abs
import kotlin.math.roundToInt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChartSpikeGeometryTest {
    private val candles = (1L..200L).map { index ->
        candle(index * 60_000L, close = 100.0 + index)
    }
    private val plot = ChartPlot(
        width = 1_000f,
        height = 500f,
        left = 20f,
        right = 80f,
        top = 20f,
        bottom = 30f
    )

    @Test
    fun panMovesTowardOlderCandlesAndKeepsAStableTimeAnchor() {
        val initial = ChartViewport(visibleCandleCount = 60)
        val initialRange = initial.resolveVisibleRange(candles)

        val panned = panViewport(
            viewport = initial,
            candles = candles,
            deltaPixels = plot.plotWidth / 6f,
            plotWidth = plot.plotWidth
        )
        val pannedRange = panned.resolveVisibleRange(candles)

        assertTrue(pannedRange.startInclusive < initialRange.startInclusive)
        assertEquals(
            candles[pannedRange.startInclusive].openTimeMillis,
            panned.anchor?.candleOpenTimeMillis
        )
    }

    @Test
    fun horizontalPinchChangesOnlyVisibleCountAndPreservesFocalCandle() {
        val initial = ChartViewport(visibleCandleCount = 60)
        val initialRange = initial.resolveVisibleRange(candles)
        val focalIndex = initialRange.startInclusive +
            ((initialRange.size - 1) * 0.5).roundToInt()

        val zoomed = scaleTimeViewport(
            viewport = initial,
            candles = candles,
            zoomFactor = 2f,
            focalFraction = 0.5
        )

        assertEquals(30, zoomed.visibleCandleCount)
        assertEquals(candles[focalIndex].openTimeMillis, zoomed.anchor?.candleOpenTimeMillis)
        assertEquals(null, zoomed.priceRange)
    }

    @Test
    fun priceAxisDragScalesAroundTheSameCenter() {
        val initial = ChartPriceRange(80.0, 120.0)

        val zoomedOut = scalePriceRange(initial, verticalDeltaPixels = 100f, plotHeight = 400f)
        val zoomedIn = scalePriceRange(initial, verticalDeltaPixels = -100f, plotHeight = 400f)

        assertEquals(100.0, (zoomedOut.min + zoomedOut.max) / 2.0, 0.000001)
        assertEquals(100.0, (zoomedIn.min + zoomedIn.max) / 2.0, 0.000001)
        assertTrue(zoomedOut.max - zoomedOut.min > initial.max - initial.min)
        assertTrue(zoomedIn.max - zoomedIn.min < initial.max - initial.min)
    }

    @Test
    fun priceCoordinateRoundTripsWithinFloatingPointTolerance() {
        val range = ChartPriceRange(90.0, 110.0)
        val original = 103.25

        val restored = yToPrice(priceToY(original, range, plot), range, plot)

        assertEquals(original, restored, 0.00001)
    }

    @Test
    fun crosshairHitTestIsBoundedToVisibleCandles() {
        val viewport = ChartViewport(visibleCandleCount = 60)
        val visible = viewport.resolveVisibleRange(candles)

        val first = candleIndexAtX(plot.left, candles, viewport, plot)
        val last = candleIndexAtX(plot.plotRight, candles, viewport, plot)

        assertEquals(visible.startInclusive, first)
        assertEquals(visible.endExclusive - 1, last)
        assertEquals(null, candleIndexAtX(plot.left - 1f, candles, viewport, plot))
    }

    @Test
    fun tenThousandCandlesStillResolveToABoundedDrawWindow() {
        val large = (1L..10_000L).map { index ->
            candle(index * 60_000L, close = 100.0 + index / 100.0)
        }
        val viewport = ChartViewport(visibleCandleCount = 300)

        val range = viewport.resolveVisibleRange(large)
        val priceRange = visiblePriceRange(large, viewport)

        assertEquals(300, range.size)
        assertNotNull(priceRange)
        assertTrue(abs(requireNotNull(priceRange).max - requireNotNull(priceRange).min) > 0.0)
    }

    private fun candle(openTimeMillis: Long, close: Double) = ChartCandle(
        openTimeMillis = openTimeMillis,
        open = close - 0.25,
        high = close + 1.0,
        low = close - 1.0,
        close = close,
        volume = 10.0,
        confirmed = true
    )
}
