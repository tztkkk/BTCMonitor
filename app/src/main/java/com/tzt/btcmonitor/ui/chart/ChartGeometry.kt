package com.tzt.btcmonitor.ui.chart

import kotlin.math.exp
import kotlin.math.roundToInt

internal data class ChartPlot(
    val width: Float,
    val height: Float,
    val left: Float,
    val right: Float,
    val top: Float,
    val bottom: Float
) {
    init {
        require(width > left + right)
        require(height > top + bottom)
    }

    val plotWidth: Float = width - left - right
    val plotHeight: Float = height - top - bottom
    val plotRight: Float = left + plotWidth
    val plotBottom: Float = top + plotHeight
}

internal fun visiblePriceRange(
    candles: List<ChartCandle>,
    viewport: ChartViewport
): ChartPriceRange? {
    viewport.priceRange?.let { return it }
    val visible = viewport.resolveVisibleRange(candles)
    if (visible.size == 0) return null
    val window = candles.subList(visible.startInclusive, visible.endExclusive)
    val low = window.minOf(ChartCandle::low)
    val high = window.maxOf(ChartCandle::high)
    val rawRange = (high - low).coerceAtLeast(high.coerceAtLeast(1.0) * 0.001)
    return ChartPriceRange(low - rawRange * 0.08, high + rawRange * 0.08)
}

internal fun priceToY(price: Double, range: ChartPriceRange, plot: ChartPlot): Float =
    plot.top + ((range.max - price) / (range.max - range.min) * plot.plotHeight).toFloat()

internal fun yToPrice(y: Float, range: ChartPriceRange, plot: ChartPlot): Double =
    range.max - ((y - plot.top) / plot.plotHeight).coerceIn(0f, 1f) * (range.max - range.min)

internal fun candleIndexAtX(
    x: Float,
    candles: List<ChartCandle>,
    viewport: ChartViewport,
    plot: ChartPlot
): Int? {
    val visible = viewport.resolveVisibleRange(candles)
    if (visible.size == 0 || x !in plot.left..plot.plotRight) return null
    val slot = plot.plotWidth / visible.size
    val localIndex = ((x - plot.left) / slot).toInt().coerceIn(0, visible.size - 1)
    return visible.startInclusive + localIndex
}

internal fun candleX(
    candleIndex: Int,
    candles: List<ChartCandle>,
    viewport: ChartViewport,
    plot: ChartPlot
): Float? {
    val visible = viewport.resolveVisibleRange(candles)
    if (candleIndex !in visible.startInclusive until visible.endExclusive || visible.size == 0) {
        return null
    }
    val slot = plot.plotWidth / visible.size
    return plot.left + slot * (candleIndex - visible.startInclusive + 0.5f)
}

internal fun panViewport(
    viewport: ChartViewport,
    candles: List<ChartCandle>,
    deltaPixels: Float,
    plotWidth: Float
): ChartViewport {
    val visible = viewport.resolveVisibleRange(candles)
    if (visible.size == 0 || plotWidth <= 0f) return viewport
    val candleShift = deltaPixels / (plotWidth / visible.size)
    if (candleShift.roundToInt() == 0) return viewport
    val maxStart = (candles.size - visible.size).coerceAtLeast(0)
    val newStart = (visible.startInclusive - candleShift).roundToInt().coerceIn(0, maxStart)
    return viewport.copy(
        anchor = ChartViewportAnchor(
            candleOpenTimeMillis = candles[newStart].openTimeMillis,
            positionFraction = 0.0
        ),
        priceRange = null
    )
}

internal fun scaleTimeViewport(
    viewport: ChartViewport,
    candles: List<ChartCandle>,
    zoomFactor: Float,
    focalFraction: Double
): ChartViewport {
    if (candles.isEmpty() || !zoomFactor.isFinite() || zoomFactor <= 0f) return viewport
    val oldRange = viewport.resolveVisibleRange(candles)
    if (oldRange.size == 0) return viewport
    val fraction = focalFraction.coerceIn(0.0, 1.0)
    val focalOffset = ((oldRange.size - 1) * fraction).roundToInt()
    val focalIndex = (oldRange.startInclusive + focalOffset).coerceIn(candles.indices)
    val newCount = (viewport.visibleCandleCount / zoomFactor).roundToInt()
        .coerceIn(MIN_VISIBLE_CANDLE_COUNT, MAX_VISIBLE_CANDLE_COUNT)
    return viewport.copy(
        visibleCandleCount = newCount,
        anchor = ChartViewportAnchor(candles[focalIndex].openTimeMillis, fraction),
        priceRange = null
    )
}

internal fun scalePriceRange(
    current: ChartPriceRange,
    verticalDeltaPixels: Float,
    plotHeight: Float
): ChartPriceRange {
    if (!verticalDeltaPixels.isFinite() || plotHeight <= 0f) return current
    val center = (current.min + current.max) / 2.0
    val scale = exp((verticalDeltaPixels / plotHeight).coerceIn(-1f, 1f).toDouble())
    val halfRange = (current.max - current.min) / 2.0 * scale
    return ChartPriceRange(center - halfRange, center + halfRange)
}

internal data class ChartRenderPlan(
    val visibleRange: ChartVisibleRange,
    val priceRange: ChartPriceRange,
    val visibleAlerts: List<AlertLine>,
    val alertsAbove: List<AlertLine>,
    val alertsBelow: List<AlertLine>
)

internal fun buildChartRenderPlan(state: InteractiveCandleChartState): ChartRenderPlan? {
    val visibleRange = state.viewport.resolveVisibleRange(state.candles)
    val priceRange = visiblePriceRange(state.candles, state.viewport) ?: return null
    return ChartRenderPlan(
        visibleRange = visibleRange,
        priceRange = priceRange,
        visibleAlerts = state.alertLines.filter { it.price in priceRange.min..priceRange.max },
        alertsAbove = state.alertLines.filter { it.price > priceRange.max },
        alertsBelow = state.alertLines.filter { it.price < priceRange.min }
    )
}
