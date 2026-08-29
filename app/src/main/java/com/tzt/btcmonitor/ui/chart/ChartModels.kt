package com.tzt.btcmonitor.ui.chart

import com.tzt.btcmonitor.model.MarketCandle
import kotlin.math.abs
import kotlin.math.roundToInt

data class ChartCandle(
    val openTimeMillis: Long,
    val open: Double,
    val high: Double,
    val low: Double,
    val close: Double,
    val volume: Double,
    val confirmed: Boolean
) {
    init {
        require(openTimeMillis > 0L) { "Candle time must be positive" }
        require(listOf(open, high, low, close, volume).all(Double::isFinite)) {
            "Candle values must be finite"
        }
        require(high >= maxOf(open, close, low)) { "Candle high is invalid" }
        require(low <= minOf(open, close, high)) { "Candle low is invalid" }
        require(volume >= 0.0) { "Candle volume must not be negative" }
    }
}

fun MarketCandle.toChartCandle() = ChartCandle(
    openTimeMillis = openTimeMillis,
    open = open,
    high = high,
    low = low,
    close = close,
    volume = volume,
    confirmed = confirmed
)

/** Duplicate open times keep the last input value; output is always oldest-to-newest. */
fun Iterable<MarketCandle>.toChartCandles(): List<ChartCandle> =
    map(MarketCandle::toChartCandle).canonicalChartCandles()

fun Iterable<ChartCandle>.canonicalChartCandles(): List<ChartCandle> =
    associateBy(ChartCandle::openTimeMillis).values.sortedBy(ChartCandle::openTimeMillis)

data class ChartPriceRange(
    val min: Double,
    val max: Double
) {
    init {
        require(min.isFinite() && max.isFinite() && min < max) {
            "Chart price range must be finite and increasing"
        }
    }
}

data class ChartViewportAnchor(
    val candleOpenTimeMillis: Long,
    val positionFraction: Double
) {
    init {
        require(candleOpenTimeMillis > 0L) { "Viewport anchor time must be positive" }
        require(positionFraction in 0.0..1.0) { "Viewport anchor position must be within the plot" }
    }
}

data class ChartViewport(
    val visibleCandleCount: Int = DEFAULT_VISIBLE_CANDLE_COUNT,
    val anchor: ChartViewportAnchor? = null,
    val priceRange: ChartPriceRange? = null
) {
    init {
        require(visibleCandleCount in MIN_VISIBLE_CANDLE_COUNT..MAX_VISIBLE_CANDLE_COUNT) {
            "Visible candle count is outside the supported range"
        }
    }

    val followsLatest: Boolean
        get() = anchor == null
}

data class ChartVisibleRange(
    val startInclusive: Int,
    val endExclusive: Int
) {
    init {
        require(startInclusive >= 0 && endExclusive >= startInclusive)
    }

    val size: Int
        get() = endExclusive - startInclusive
}

/** Resolves a time-based anchor without changing it, so prepended history does not shift the view. */
fun ChartViewport.resolveVisibleRange(candles: List<ChartCandle>): ChartVisibleRange {
    if (candles.isEmpty()) return ChartVisibleRange(0, 0)
    val count = visibleCandleCount.coerceAtMost(candles.size)
    val maxStart = candles.size - count
    val start = anchor?.let { stableAnchor ->
        val anchorIndex = candles.indexOfFirst { it.openTimeMillis == stableAnchor.candleOpenTimeMillis }
            .takeIf { it >= 0 }
            ?: candles.indices.minBy { abs(candles[it].openTimeMillis - stableAnchor.candleOpenTimeMillis) }
        val offset = ((count - 1) * stableAnchor.positionFraction).roundToInt()
        (anchorIndex - offset).coerceIn(0, maxStart)
    } ?: maxStart
    return ChartVisibleRange(start, start + count)
}

fun ChartViewport.withStableAnchor(candles: List<ChartCandle>): ChartViewport {
    if (candles.isEmpty()) return this
    val existing = anchor?.takeIf { candidate ->
        candles.any { it.openTimeMillis == candidate.candleOpenTimeMillis }
    }
    if (existing != null) return this
    val range = resolveVisibleRange(candles)
    return copy(
        anchor = ChartViewportAnchor(
            candleOpenTimeMillis = candles[range.startInclusive].openTimeMillis,
            positionFraction = 0.0
        )
    )
}

sealed interface CrosshairState {
    data object Hidden : CrosshairState

    data class Visible(
        val candleOpenTimeMillis: Long,
        val selectedPrice: Double,
        val pinned: Boolean
    ) : CrosshairState {
        init {
            require(candleOpenTimeMillis > 0L)
            require(selectedPrice.isFinite() && selectedPrice > 0.0)
        }
    }
}

data class AlertLine(
    val id: String,
    val label: String,
    val price: Double,
    val enabled: Boolean
) {
    init {
        require(id.isNotBlank())
        require(label.isNotBlank())
        require(price.isFinite() && price > 0.0)
    }

    val draggable: Boolean
        get() = enabled
}

enum class ChartGestureTarget {
    NONE,
    ALERT_LINE,
    CROSSHAIR,
    TIME_PAN,
    TIME_SCALE,
    PRICE_SCALE
}

data class ChartGestureContext(
    val pointerCount: Int,
    val hitAlertLineId: String? = null,
    val longPress: Boolean = false,
    val onPriceAxis: Boolean = false
) {
    init {
        require(pointerCount >= 0)
    }
}

/** Single-pointer priority is alert line, long-press crosshair, then pan; two pointers scale. */
fun resolveGestureTarget(context: ChartGestureContext): ChartGestureTarget = when {
    context.pointerCount == 0 -> ChartGestureTarget.NONE
    context.pointerCount == 1 && context.hitAlertLineId != null -> ChartGestureTarget.ALERT_LINE
    context.pointerCount == 1 && context.longPress -> ChartGestureTarget.CROSSHAIR
    context.pointerCount >= 2 && context.onPriceAxis -> ChartGestureTarget.PRICE_SCALE
    context.pointerCount >= 2 -> ChartGestureTarget.TIME_SCALE
    else -> ChartGestureTarget.TIME_PAN
}

const val DEFAULT_VISIBLE_CANDLE_COUNT = 60
const val MIN_VISIBLE_CANDLE_COUNT = 10
const val MAX_VISIBLE_CANDLE_COUNT = 300
