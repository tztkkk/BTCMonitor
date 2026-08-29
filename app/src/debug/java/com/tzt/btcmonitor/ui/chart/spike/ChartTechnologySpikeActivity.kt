package com.tzt.btcmonitor.ui.chart.spike

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tzt.btcmonitor.AppContainer
import com.tzt.btcmonitor.model.CandleTimeframe
import com.tzt.btcmonitor.ui.chart.AlertLine
import com.tzt.btcmonitor.ui.chart.ChartAction
import com.tzt.btcmonitor.ui.chart.ChartCandle
import com.tzt.btcmonitor.ui.chart.ChartOutputEvent
import com.tzt.btcmonitor.ui.chart.ChartPlot
import com.tzt.btcmonitor.ui.chart.ChartPriceRange
import com.tzt.btcmonitor.ui.chart.ChartViewportAnchor
import com.tzt.btcmonitor.ui.chart.CrosshairState
import com.tzt.btcmonitor.ui.chart.InteractiveCandleChartReducer
import com.tzt.btcmonitor.ui.chart.InteractiveCandleChartState
import com.tzt.btcmonitor.ui.chart.candleIndexAtX
import com.tzt.btcmonitor.ui.chart.candleX
import com.tzt.btcmonitor.ui.chart.priceToY
import com.tzt.btcmonitor.ui.chart.panViewport
import com.tzt.btcmonitor.ui.chart.resolveVisibleRange
import com.tzt.btcmonitor.ui.chart.scalePriceRange
import com.tzt.btcmonitor.ui.chart.scaleTimeViewport
import com.tzt.btcmonitor.ui.chart.toChartCandles
import com.tzt.btcmonitor.ui.chart.visiblePriceRange
import com.tzt.btcmonitor.ui.chart.yToPrice
import java.text.DateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Debug-only host for TASK-007. It is not linked from the production application UI. */
class ChartTechnologySpikeActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        setShowWhenLocked(true)
        setTurnScreenOn(true)
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    ChartTechnologySpikeScreen(
                        useTestCandles = intent.getBooleanExtra(TEST_CANDLES_EXTRA, false)
                    )
                }
            }
        }
    }

    private companion object {
        const val TEST_CANDLES_EXTRA = "task007_test_candles"
    }
}

private enum class SpikeLoadState {
    LOADING,
    READY,
    ERROR
}

@Composable
private fun ChartTechnologySpikeScreen(useTestCandles: Boolean) {
    var reloadKey by remember { mutableIntStateOf(0) }
    var loadState by remember { mutableStateOf(SpikeLoadState.LOADING) }
    var loadMessage by remember { mutableStateOf("正在通过现有 CandleRepository 加载 BTC-USDT 真实 K 线…") }
    var lastEvent by remember { mutableStateOf("尚无输出事件") }
    var chartState by remember {
        mutableStateOf(InteractiveCandleChartState())
    }

    fun reduce(action: ChartAction) {
        val reduction = InteractiveCandleChartReducer.reduce(chartState, action)
        chartState = reduction.state
        reduction.events.lastOrNull()?.let { event ->
            lastEvent = when (event) {
                is ChartOutputEvent.LoadOlder -> "onLoadOlder(${event.anchor.candleOpenTimeMillis})"
                is ChartOutputEvent.CreateAlert -> "onCreateAlert(${formatPrice(event.price)})"
                is ChartOutputEvent.MoveAlert ->
                    "onMoveAlert(${event.alertId}, ${formatPrice(event.price)})"
                is ChartOutputEvent.ViewportChanged ->
                    "onViewportChanged(count=${event.viewport.visibleCandleCount})"
            }
        }
    }

    LaunchedEffect(reloadKey) {
        loadState = SpikeLoadState.LOADING
        loadMessage = if (useTestCandles) {
            "正在加载确定性测试 K 线…"
        } else {
            "正在通过现有 CandleRepository 加载 BTC-USDT 真实 K 线…"
        }
        runCatching {
            if (useTestCandles) {
                spikeTestCandles()
            } else {
                withContext(Dispatchers.IO) {
                    AppContainer.candles.loadRecent(
                        symbol = "BTC-USDT",
                        timeframe = CandleTimeframe.ONE_MINUTE,
                        limit = 200
                    )
                }.toChartCandles()
            }
        }.onSuccess { candles ->
            val replaced = InteractiveCandleChartReducer.reduce(
                chartState,
                ChartAction.ReplaceCandles(candles, hasMore = true)
            ).state
            chartState = replaced.copy(
                alertLines = listOf(
                    AlertLine(
                        id = "task-007-spike",
                        label = "可拖动提醒线",
                        price = candles[candles.lastIndex - candles.size.coerceAtMost(20) + 1].close,
                        enabled = true
                    )
                )
            )
            loadState = SpikeLoadState.READY
            loadMessage = if (useTestCandles) {
                "已加载 ${candles.size} 根确定性测试 K 线"
            } else {
                "已加载 ${candles.size} 根真实 BTC-USDT 1m K 线"
            }
        }.onFailure { error ->
            loadState = SpikeLoadState.ERROR
            loadMessage = "真实 K 线加载失败：${error.message ?: error::class.java.simpleName}"
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("TASK-007 · Compose Canvas Spike", style = MaterialTheme.typography.titleLarge)
        Text(
            "仅存在于 debug source set，不替换正式详情页。单指水平平移、双指缩放时间、拖动右侧价格轴缩放价格范围、长按移动十字光标、拖动提醒线。",
            style = MaterialTheme.typography.bodySmall
        )
        Text(loadMessage, style = MaterialTheme.typography.bodyMedium)

        when (loadState) {
            SpikeLoadState.LOADING -> CircularProgressIndicator()
            SpikeLoadState.ERROR -> Button(onClick = { reloadKey++ }) { Text("重试真实 K 线") }
            SpikeLoadState.READY -> {
                CanvasChartSpike(
                    state = chartState,
                    onAction = ::reduce
                )
                val visible = chartState.viewport.resolveVisibleRange(chartState.candles)
                Text(
                    "总数 ${chartState.candles.size} · 可见 ${visible.size} · " +
                        "索引 ${visible.startInclusive}..${(visible.endExclusive - 1).coerceAtLeast(0)} · " +
                        if (chartState.viewport.priceRange == null) {
                            "自动价格范围"
                        } else {
                            "手动价格范围"
                        },
                    modifier = Modifier.testTag("chart-spike-window"),
                    style = MaterialTheme.typography.bodySmall
                )
                CrosshairReadout(chartState)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { reduce(ChartAction.FollowLatest) }) {
                        Text("回到最新")
                    }
                    OutlinedButton(onClick = {
                        val request = InteractiveCandleChartReducer.reduce(
                            chartState,
                            ChartAction.RequestLoadOlder
                        )
                        chartState = request.state
                        request.events.lastOrNull()?.let { lastEvent = "onLoadOlder(anchor preserved)" }
                        chartState = InteractiveCandleChartReducer.reduce(
                            chartState,
                            ChartAction.OlderCandlesLoaded(
                                candles = simulatedOlderCandles(chartState.candles),
                                hasMore = true
                            )
                        ).state
                    }) {
                        Text("模拟前插历史")
                    }
                }
                Text(
                    "最近事件：$lastEvent",
                    modifier = Modifier.testTag("chart-spike-event"),
                    style = MaterialTheme.typography.bodySmall
                )
                Text(
                    "提示：前插按钮只验证 viewport anchor；正式历史网络分页属于 TASK-010。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun CanvasChartSpike(
    state: InteractiveCandleChartState,
    onAction: (ChartAction) -> Unit
) {
    val latestState by rememberUpdatedState(state)
    val latestOnAction by rememberUpdatedState(onAction)
    val density = LocalDensity.current
    val chartHeight = 440.dp
    val axisWidth = 72.dp
    val alertTouchHeight = 48.dp

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxWidth()
            .height(chartHeight)
            .testTag("chart-spike-chart")
            .background(MaterialTheme.colorScheme.surfaceVariant)
    ) {
        val widthPx = with(density) { maxWidth.toPx() }
        val heightPx = with(density) { chartHeight.toPx() }
        val plot = remember(widthPx, heightPx) {
            ChartPlot(
                width = widthPx,
                height = heightPx,
                left = with(density) { 12.dp.toPx() },
                right = with(density) { axisWidth.toPx() },
                top = with(density) { 28.dp.toPx() },
                bottom = with(density) { 30.dp.toPx() }
            )
        }
        val range = visiblePriceRange(state.candles, state.viewport) ?: return@BoxWithConstraints
        val visible = state.viewport.resolveVisibleRange(state.candles)
        val upColor = MaterialTheme.colorScheme.primary
        val downColor = MaterialTheme.colorScheme.error
        val gridColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.35f)
        val labelColor = MaterialTheme.colorScheme.onSurfaceVariant
        val alertColor = MaterialTheme.colorScheme.tertiary
        val crosshairColor = MaterialTheme.colorScheme.secondary

        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(plot) {
                    var accumulatedPan = 0f
                    detectTransformGestures { centroid, pan, zoom, _ ->
                        val current = latestState
                        when {
                            abs(zoom - 1f) > 0.01f -> {
                                latestOnAction(
                                    ChartAction.ScaleTime(
                                        visibleCandleCount = scaleTimeViewport(
                                            viewport = current.viewport,
                                            candles = current.candles,
                                            zoomFactor = zoom,
                                            focalFraction =
                                                ((centroid.x - plot.left) / plot.plotWidth).toDouble()
                                        ).visibleCandleCount,
                                        focalAnchor = scaleTimeViewport(
                                            viewport = current.viewport,
                                            candles = current.candles,
                                            zoomFactor = zoom,
                                            focalFraction =
                                                ((centroid.x - plot.left) / plot.plotWidth).toDouble()
                                        ).anchor
                                    )
                                )
                                accumulatedPan = 0f
                            }
                            abs(pan.x) >= abs(pan.y) -> {
                                accumulatedPan += pan.x
                                val changed = panViewport(
                                    viewport = current.viewport,
                                    candles = current.candles,
                                    deltaPixels = accumulatedPan,
                                    plotWidth = plot.plotWidth
                                )
                                if (changed != current.viewport) {
                                    latestOnAction(ChartAction.PanTo(requireNotNull(changed.anchor)))
                                    accumulatedPan = 0f
                                }
                            }
                        }
                    }
                }
                .pointerInput(plot) {
                    fun updateCrosshair(position: Offset, moving: Boolean) {
                        val current = latestState
                        val currentRange = visiblePriceRange(current.candles, current.viewport) ?: return
                        val index = candleIndexAtX(
                            position.x,
                            current.candles,
                            current.viewport,
                            plot
                        ) ?: return
                        val price = yToPrice(position.y, currentRange, plot)
                        latestOnAction(
                            if (moving) {
                                ChartAction.MoveCrosshair(current.candles[index].openTimeMillis, price)
                            } else {
                                ChartAction.ShowCrosshair(current.candles[index].openTimeMillis, price)
                            }
                        )
                    }
                    detectDragGesturesAfterLongPress(
                        onDragStart = { updateCrosshair(it, moving = false) },
                        onDrag = { change, _ ->
                            change.consume()
                            updateCrosshair(change.position, moving = true)
                        },
                        onDragEnd = { latestOnAction(ChartAction.PinCrosshair) }
                    )
                }
                .pointerInput(Unit) {
                    detectTapGestures(onTap = { latestOnAction(ChartAction.HideCrosshair) })
                }
        ) {
            val labelPaint = android.graphics.Paint().apply {
                color = labelColor.toArgb()
                textSize = 10.sp.toPx()
                isAntiAlias = true
            }
            repeat(5) { index ->
                val fraction = index / 4f
                val y = plot.top + plot.plotHeight * fraction
                drawLine(gridColor, Offset(plot.left, y), Offset(plot.plotRight, y), 1f)
                val price = range.max - (range.max - range.min) * fraction
                drawContext.canvas.nativeCanvas.drawText(
                    formatPrice(price),
                    plot.plotRight + 6.dp.toPx(),
                    y + 4.dp.toPx(),
                    labelPaint
                )
            }

            if (visible.size > 0) {
                val slotWidth = plot.plotWidth / visible.size
                val bodyWidth = (slotWidth * 0.62f).coerceAtLeast(2f)
                for (index in visible.startInclusive until visible.endExclusive) {
                    val candle = state.candles[index]
                    val x = plot.left + slotWidth * (index - visible.startInclusive + 0.5f)
                    val color = if (candle.close >= candle.open) upColor else downColor
                    val highY = priceToY(candle.high, range, plot)
                    val lowY = priceToY(candle.low, range, plot)
                    val openY = priceToY(candle.open, range, plot)
                    val closeY = priceToY(candle.close, range, plot)
                    drawLine(color, Offset(x, highY), Offset(x, lowY), 1.2.dp.toPx())
                    val bodyTop = minOf(openY, closeY)
                    val bodyHeight = abs(closeY - openY).coerceAtLeast(1.5.dp.toPx())
                    drawRect(color, Offset(x - bodyWidth / 2f, bodyTop), Size(bodyWidth, bodyHeight))
                }
            }

            state.alertLines.forEach { line ->
                val displayPrice = state.alertLineDrag
                    ?.takeIf { it.alertId == line.id }
                    ?.candidatePrice
                    ?: line.price
                if (displayPrice in range.min..range.max) {
                    val y = priceToY(displayPrice, range, plot)
                    drawLine(
                        alertColor,
                        Offset(plot.left, y),
                        Offset(plot.plotRight, y),
                        2.dp.toPx(),
                        pathEffect = PathEffect.dashPathEffect(floatArrayOf(8.dp.toPx(), 4.dp.toPx()))
                    )
                }
            }

            val crosshair = state.crosshair as? CrosshairState.Visible
            crosshair?.let { selected ->
                val candleIndex = state.candles.indexOfFirst {
                    it.openTimeMillis == selected.candleOpenTimeMillis
                }
                val x = candleX(candleIndex, state.candles, state.viewport, plot)
                if (x != null) {
                    val y = priceToY(selected.selectedPrice, range, plot)
                        .coerceIn(plot.top, plot.plotBottom)
                    drawLine(
                        crosshairColor,
                        Offset(x, plot.top),
                        Offset(x, plot.plotBottom),
                        1.dp.toPx(),
                        pathEffect = PathEffect.dashPathEffect(floatArrayOf(5.dp.toPx(), 5.dp.toPx()))
                    )
                    drawLine(
                        crosshairColor,
                        Offset(plot.left, y),
                        Offset(plot.plotRight, y),
                        1.dp.toPx(),
                        pathEffect = PathEffect.dashPathEffect(floatArrayOf(5.dp.toPx(), 5.dp.toPx()))
                    )
                    drawCircle(crosshairColor, 4.dp.toPx(), Offset(x, y), style = Stroke(1.5.dp.toPx()))
                }
            }
        }

        Box(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .width(axisWidth)
                .fillMaxHeight()
                .testTag("chart-spike-price-axis")
                .pointerInput(plot) {
                    var dragRange = visiblePriceRange(
                        latestState.candles,
                        latestState.viewport
                    ) ?: return@pointerInput
                    detectVerticalDragGestures { change, dragAmount ->
                        change.consume()
                        dragRange = scalePriceRange(dragRange, dragAmount, plot.plotHeight)
                        latestOnAction(ChartAction.ScalePrice(dragRange))
                    }
                }
        )

        state.alertLines.firstOrNull { it.enabled }?.let { alert ->
            val displayPrice = state.alertLineDrag
                ?.takeIf { it.alertId == alert.id }
                ?.candidatePrice
                ?: alert.price
            if (displayPrice in range.min..range.max) {
                val alertY = priceToY(displayPrice, range, plot)
                val dragState = rememberDraggableState { dragAmount ->
                    val current = latestState
                    val currentRange = visiblePriceRange(
                        current.candles,
                        current.viewport
                    ) ?: return@rememberDraggableState
                    val currentPrice = current.alertLineDrag
                        ?.takeIf { it.alertId == alert.id }
                        ?.candidatePrice
                        ?: current.alertLines.firstOrNull { it.id == alert.id }?.price
                        ?: return@rememberDraggableState
                    val candidateY = priceToY(currentPrice, currentRange, plot) + dragAmount
                    latestOnAction(
                        ChartAction.UpdateAlertLineDrag(
                            yToPrice(candidateY, currentRange, plot)
                        )
                    )
                }
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(alertTouchHeight)
                        .offset {
                            IntOffset(
                                x = 0,
                                y = (alertY - with(density) { alertTouchHeight.toPx() } / 2f)
                                    .roundToInt()
                            )
                        }
                        .testTag("chart-spike-alert-line")
                        .draggable(
                            state = dragState,
                            orientation = Orientation.Vertical,
                            onDragStarted = {
                                latestOnAction(ChartAction.BeginAlertLineDrag(alert.id))
                            },
                            onDragStopped = { latestOnAction(ChartAction.EndAlertLineDrag) }
                        )
                )
            }
        }
    }
}

@Composable
private fun CrosshairReadout(state: InteractiveCandleChartState) {
    val crosshair = state.crosshair as? CrosshairState.Visible
    val candle = crosshair?.let { selected ->
        state.candles.firstOrNull { it.openTimeMillis == selected.candleOpenTimeMillis }
    }
    if (candle == null) {
        Text(
            "长按图表查看 Time / O / H / L / C",
            modifier = Modifier.testTag("chart-spike-crosshair"),
            style = MaterialTheme.typography.bodySmall
        )
        return
    }
    val time = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.MEDIUM)
        .format(Date(candle.openTimeMillis))
    Text(
        "$time · O ${formatPrice(candle.open)} · H ${formatPrice(candle.high)} · " +
            "L ${formatPrice(candle.low)} · C ${formatPrice(candle.close)}",
        modifier = Modifier.testTag("chart-spike-crosshair"),
        style = MaterialTheme.typography.bodySmall
    )
}

private fun simulatedOlderCandles(current: List<ChartCandle>): List<ChartCandle> {
    val first = current.firstOrNull() ?: return emptyList()
    val step = current.zipWithNext { left, right -> right.openTimeMillis - left.openTimeMillis }
        .firstOrNull { it > 0L }
        ?: 60_000L
    return (24 downTo 1).map { distance ->
        val drift = first.open * distance * 0.00015
        val open = first.open - drift
        val close = open + first.open * if (distance % 2 == 0) 0.0004 else -0.0003
        ChartCandle(
            openTimeMillis = first.openTimeMillis - step * distance,
            open = open,
            high = maxOf(open, close) + first.open * 0.0005,
            low = minOf(open, close) - first.open * 0.0005,
            close = close,
            volume = first.volume,
            confirmed = true
        )
    }
}

private fun spikeTestCandles(): List<ChartCandle> {
    val firstOpenTime = 1_700_000_000_000L
    return List(200) { index ->
        val open = 65_000.0 + index * 6.0 + (index % 9 - 4) * 12.0
        val close = open + if (index % 2 == 0) 28.0 else -22.0
        ChartCandle(
            openTimeMillis = firstOpenTime + index * 60_000L,
            open = open,
            high = maxOf(open, close) + 18.0,
            low = minOf(open, close) - 16.0,
            close = close,
            volume = 100.0 + index,
            confirmed = true
        )
    }
}

private fun formatPrice(value: Double): String = String.format(Locale.US, "%,.2f", value)
