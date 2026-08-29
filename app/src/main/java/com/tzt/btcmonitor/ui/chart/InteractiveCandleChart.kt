package com.tzt.btcmonitor.ui.chart

import android.graphics.Paint
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.text.DateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs

sealed interface CandleChartRenderState {
    data object Loading : CandleChartRenderState
    data object Empty : CandleChartRenderState
    data class Error(val message: String) : CandleChartRenderState
    data class Ready(val chart: InteractiveCandleChartState) : CandleChartRenderState
}

/**
 * Reusable chart boundary with local interaction state. It intentionally has no ViewModel,
 * Repository, provider, persistence, or notification dependency.
 */
@Composable
fun InteractiveCandleChart(
    state: CandleChartRenderState,
    modifier: Modifier = Modifier,
    onRetry: (() -> Unit)? = null,
    callbacks: InteractiveCandleChartCallbacks = InteractiveCandleChartCallbacks.None
) {
    when (state) {
        CandleChartRenderState.Loading -> ChartMessage(
            title = "正在加载 K 线…",
            modifier = modifier,
            showProgress = true
        )

        CandleChartRenderState.Empty -> ChartMessage(
            title = "暂无 K 线数据",
            detail = "切换周期或稍后刷新。",
            modifier = modifier
        )

        is CandleChartRenderState.Error -> ChartMessage(
            title = "K 线加载失败",
            detail = state.message,
            modifier = modifier,
            onRetry = onRetry
        )

        is CandleChartRenderState.Ready -> {
            if (state.chart.candles.isEmpty()) {
                ChartMessage(
                    title = "暂无 K 线数据",
                    detail = "切换周期或稍后刷新。",
                    modifier = modifier
                )
            } else {
                StatefulReadyCandleChart(state.chart, modifier, callbacks)
            }
        }
    }
}

@Composable
private fun StatefulReadyCandleChart(
    input: InteractiveCandleChartState,
    modifier: Modifier,
    callbacks: InteractiveCandleChartCallbacks
) {
    var chartState by remember { mutableStateOf(input) }
    val latestCallbacks by rememberUpdatedState(callbacks)
    LaunchedEffect(input.candles, input.alertLines, input.history) {
        chartState = chartState.copy(
            candles = input.candles,
            alertLines = input.alertLines,
            history = input.history
        )
    }

    fun dispatch(action: ChartAction) {
        val reduction = InteractiveCandleChartReducer.reduce(chartState, action)
        chartState = reduction.state
        reduction.events.forEach { latestCallbacks.dispatch(it) }
    }

    ReadyCandleChart(
        state = chartState,
        modifier = modifier,
        onAction = ::dispatch
    )
}

@Composable
private fun ChartMessage(
    title: String,
    modifier: Modifier,
    detail: String? = null,
    showProgress: Boolean = false,
    onRetry: (() -> Unit)? = null
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(220.dp)
            .background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.padding(20.dp)
        ) {
            if (showProgress) CircularProgressIndicator()
            Text(title, style = MaterialTheme.typography.titleSmall)
            detail?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            onRetry?.let { retry ->
                OutlinedButton(onClick = retry) { Text("重试") }
            }
        }
    }
}

@Composable
private fun ReadyCandleChart(
    state: InteractiveCandleChartState,
    modifier: Modifier,
    onAction: (ChartAction) -> Unit
) {
    val latestState by rememberUpdatedState(state)
    val latestOnAction by rememberUpdatedState(onAction)
    val density = LocalDensity.current
    val plan = remember(state.candles, state.viewport, state.alertLines) {
        requireNotNull(buildChartRenderPlan(state))
    }
    val visibleCandles = remember(state.candles, plan.visibleRange) {
        state.candles.subList(plan.visibleRange.startInclusive, plan.visibleRange.endExclusive)
    }
    val latest = visibleCandles.last()
    val summary = "可见 ${visibleCandles.size} 根 K 线，" +
        "索引 ${plan.visibleRange.startInclusive}..${plan.visibleRange.endExclusive - 1}，" +
        "最新收盘 ${chartPriceText(latest.close)}，提醒线 ${state.alertLines.size} 条，" +
        if (state.viewport.priceRange == null) "自动价格范围" else "手动价格范围"
    val firstTime = remember(visibleCandles.first().openTimeMillis) {
        chartTimeText(visibleCandles.first().openTimeMillis)
    }
    val lastTime = remember(latest.openTimeMillis) { chartTimeText(latest.openTimeMillis) }
    val colors = MaterialTheme.colorScheme
    val upColor = colors.secondary
    val downColor = colors.error
    val alertColor = colors.tertiary
    val disabledAlertColor = colors.onSurfaceVariant.copy(alpha = 0.55f)
    val labelColor = colors.onSurfaceVariant
    val gridColor = colors.outline.copy(alpha = 0.25f)
    val crosshairColor = colors.primary
    val chartHeight = 390.dp
    val axisWidth = 74.dp

    LaunchedEffect(
        plan.visibleRange.startInclusive,
        state.history.hasMore,
        state.history.loadingOlder,
        state.history.error
    ) {
        if (
            plan.visibleRange.startInclusive <= LOAD_OLDER_THRESHOLD_CANDLES &&
            state.history.hasMore &&
            !state.history.loadingOlder &&
            state.history.error == null
        ) {
            latestOnAction(ChartAction.RequestLoadOlder)
        }
    }

    Column(
        modifier = modifier.semantics { contentDescription = summary },
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxWidth()
                .height(chartHeight)
                .testTag("interactive-chart")
                .background(colors.surfaceVariant)
        ) {
            val widthPx = with(density) { maxWidth.toPx() }
            val heightPx = with(density) { chartHeight.toPx() }
            val plot = remember(widthPx, heightPx) {
                ChartPlot(
                    width = widthPx,
                    height = heightPx,
                    left = with(density) { 8.dp.toPx() },
                    right = with(density) { axisWidth.toPx() },
                    top = with(density) { 26.dp.toPx() },
                    bottom = with(density) { 30.dp.toPx() }
                )
            }

            Canvas(
                Modifier
                    .fillMaxSize()
                    .testTag("interactive-chart-canvas")
                    .pointerInput(plot) {
                        var accumulatedPan = 0f
                        detectTransformGestures { centroid, pan, zoom, _ ->
                            val current = latestState
                            when {
                                abs(zoom - 1f) > 0.01f -> {
                                    val changed = scaleTimeViewport(
                                        viewport = current.viewport,
                                        candles = current.candles,
                                        zoomFactor = zoom,
                                        focalFraction =
                                            ((centroid.x - plot.left) / plot.plotWidth).toDouble()
                                    )
                                    if (changed != current.viewport) {
                                        latestOnAction(
                                            ChartAction.ScaleTime(
                                                visibleCandleCount = changed.visibleCandleCount,
                                                focalAnchor = changed.anchor
                                            )
                                        )
                                    }
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
                            val range = visiblePriceRange(
                                current.candles,
                                current.viewport
                            ) ?: return
                            val index = candleIndexAtX(
                                position.x,
                                current.candles,
                                current.viewport,
                                plot
                            ) ?: return
                            val price = yToPrice(position.y, range, plot)
                            latestOnAction(
                                if (moving) {
                                    ChartAction.MoveCrosshair(
                                        current.candles[index].openTimeMillis,
                                        price
                                    )
                                } else {
                                    ChartAction.ShowCrosshair(
                                        current.candles[index].openTimeMillis,
                                        price
                                    )
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
                val labelPaint = Paint().apply {
                    color = labelColor.toArgb()
                    textSize = 10.sp.toPx()
                    isAntiAlias = true
                }

                repeat(5) { index ->
                    val ratio = index / 4f
                    val y = plot.top + plot.plotHeight * ratio
                    drawLine(
                        gridColor,
                        Offset(plot.left, y),
                        Offset(plot.plotRight, y),
                        strokeWidth = 1f
                    )
                    val price = plan.priceRange.max -
                        (plan.priceRange.max - plan.priceRange.min) * ratio
                    drawContext.canvas.nativeCanvas.drawText(
                        chartPriceText(price),
                        plot.plotRight + 6.dp.toPx(),
                        y + 4.dp.toPx(),
                        labelPaint
                    )
                }

                val slotWidth = plot.plotWidth / visibleCandles.size
                val bodyWidth = (slotWidth * 0.62f).coerceIn(2.dp.toPx(), 14.dp.toPx())
                visibleCandles.forEachIndexed { localIndex, candle ->
                    val x = plot.left + slotWidth * (localIndex + 0.5f)
                    val color = if (candle.close >= candle.open) upColor else downColor
                    val highY = priceToY(candle.high, plan.priceRange, plot)
                    val lowY = priceToY(candle.low, plan.priceRange, plot)
                    val openY = priceToY(candle.open, plan.priceRange, plot)
                    val closeY = priceToY(candle.close, plan.priceRange, plot)
                    drawLine(
                        color,
                        Offset(x, highY),
                        Offset(x, lowY),
                        strokeWidth = 1.2.dp.toPx()
                    )
                    drawRect(
                        color = color,
                        topLeft = Offset(x - bodyWidth / 2f, minOf(openY, closeY)),
                        size = Size(bodyWidth, abs(closeY - openY).coerceAtLeast(1.5.dp.toPx()))
                    )
                }

                plan.visibleAlerts.forEach { alert ->
                    val color = if (alert.enabled) alertColor else disabledAlertColor
                    val y = priceToY(alert.price, plan.priceRange, plot)
                    drawLine(
                        color = color,
                        start = Offset(plot.left, y),
                        end = Offset(plot.plotRight, y),
                        strokeWidth = if (alert.enabled) 1.5.dp.toPx() else 1.dp.toPx(),
                        pathEffect = PathEffect.dashPathEffect(
                            floatArrayOf(7.dp.toPx(), 4.dp.toPx())
                        )
                    )
                    labelPaint.color = color.toArgb()
                    drawContext.canvas.nativeCanvas.drawText(
                        "${alert.label} ${chartPriceText(alert.price)}" +
                            if (alert.enabled) "" else "（已停用）",
                        plot.left + 4.dp.toPx(),
                        (y - 4.dp.toPx()).coerceAtLeast(plot.top + 10.dp.toPx()),
                        labelPaint
                    )
                }

                (state.crosshair as? CrosshairState.Visible)?.let { selected ->
                    val candleIndex = state.candles.indexOfFirst {
                        it.openTimeMillis == selected.candleOpenTimeMillis
                    }
                    candleX(candleIndex, state.candles, state.viewport, plot)?.let { x ->
                        val y = priceToY(selected.selectedPrice, plan.priceRange, plot)
                            .coerceIn(plot.top, plot.plotBottom)
                        val dash = PathEffect.dashPathEffect(
                            floatArrayOf(5.dp.toPx(), 5.dp.toPx())
                        )
                        drawLine(
                            crosshairColor,
                            Offset(x, plot.top),
                            Offset(x, plot.plotBottom),
                            1.dp.toPx(),
                            pathEffect = dash
                        )
                        drawLine(
                            crosshairColor,
                            Offset(plot.left, y),
                            Offset(plot.plotRight, y),
                            1.dp.toPx(),
                            pathEffect = dash
                        )
                        drawCircle(
                            crosshairColor,
                            4.dp.toPx(),
                            Offset(x, y),
                            style = Stroke(1.5.dp.toPx())
                        )
                    }
                }

                labelPaint.color = labelColor.toArgb()
                labelPaint.textAlign = Paint.Align.LEFT
                drawContext.canvas.nativeCanvas.drawText(
                    firstTime,
                    plot.left,
                    size.height - 6.dp.toPx(),
                    labelPaint
                )
                labelPaint.textAlign = Paint.Align.RIGHT
                drawContext.canvas.nativeCanvas.drawText(
                    lastTime,
                    plot.plotRight,
                    size.height - 6.dp.toPx(),
                    labelPaint
                )
            }

            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .width(axisWidth)
                    .fillMaxHeight()
                    .testTag("interactive-chart-price-axis")
                    .pointerInput(plot) {
                        var dragRange = visiblePriceRange(
                            latestState.candles,
                            latestState.viewport
                        ) ?: return@pointerInput
                        detectVerticalDragGestures { change, dragAmount ->
                            change.consume()
                            dragRange = scalePriceRange(
                                dragRange,
                                dragAmount,
                                plot.plotHeight
                            )
                            latestOnAction(ChartAction.ScalePrice(dragRange))
                        }
                    }
            )
        }

        Text(
            summary,
            modifier = Modifier.testTag("interactive-chart-window"),
            style = MaterialTheme.typography.bodySmall
        )
        if (state.history.loadingOlder) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.testTag("interactive-chart-loading-older")
            ) {
                CircularProgressIndicator(
                    modifier = Modifier.width(18.dp).height(18.dp),
                    strokeWidth = 2.dp
                )
                Text("正在加载更早 K 线…", style = MaterialTheme.typography.bodySmall)
            }
        }
        state.history.error?.let { error ->
            Column(
                verticalArrangement = Arrangement.spacedBy(4.dp),
                modifier = Modifier.testTag("interactive-chart-older-error")
            ) {
                Text("更早 K 线加载失败：$error", color = colors.error)
                OutlinedButton(
                    onClick = { onAction(ChartAction.RequestLoadOlder) },
                    modifier = Modifier.testTag("interactive-chart-retry-older")
                ) {
                    Text("重试加载更早 K 线")
                }
            }
        }
        CrosshairReadout(state)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedButton(
                onClick = {
                    onAction(ChartAction.FollowLatest)
                    onAction(ChartAction.HideCrosshair)
                },
                enabled = !state.viewport.followsLatest || state.viewport.priceRange != null,
                modifier = Modifier
                    .weight(1f)
                    .testTag("interactive-chart-return-latest")
            ) {
                Text("回到最新")
            }
        }
        Text(
            "单指左右平移，双指缩放时间，拖动右侧价格轴缩放价格；长按查看 OHLC，点击空白关闭。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        state.alertLines.forEach { alert ->
            Text(
                "${alert.label}：${chartPriceText(alert.price)}" +
                    if (alert.enabled) "（已启用）" else "（已停用）",
                style = MaterialTheme.typography.bodySmall,
                color = if (alert.enabled) alertColor else disabledAlertColor
            )
        }
        (plan.alertsAbove.map { "提醒↑ ${it.label} ${chartPriceText(it.price)}（图外）" } +
            plan.alertsBelow.map { "提醒↓ ${it.label} ${chartPriceText(it.price)}（图外）" })
            .forEach { message ->
                Text(message, style = MaterialTheme.typography.bodySmall, color = alertColor)
            }
    }
}

@Composable
private fun CrosshairReadout(state: InteractiveCandleChartState) {
    val selected = state.crosshair as? CrosshairState.Visible
    val candle = selected?.let { crosshair ->
        state.candles.firstOrNull { it.openTimeMillis == crosshair.candleOpenTimeMillis }
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("interactive-chart-crosshair"),
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        if (candle == null) {
            Text(
                "长按图表查看本地时间与 O / H / L / C",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            Text(chartTimeText(candle.openTimeMillis), style = MaterialTheme.typography.bodySmall)
            Text(
                "O ${chartPriceText(candle.open)}  ·  H ${chartPriceText(candle.high)}",
                style = MaterialTheme.typography.bodySmall
            )
            Text(
                "L ${chartPriceText(candle.low)}  ·  C ${chartPriceText(candle.close)}",
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}

private fun chartPriceText(price: Double): String = when {
    price >= 1_000.0 -> String.format(Locale.US, "%,.2f", price)
    price >= 1.0 -> String.format(Locale.US, "%.4f", price)
    else -> String.format(Locale.US, "%.6f", price)
}

private fun chartTimeText(millis: Long): String =
    DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(millis))

private const val LOAD_OLDER_THRESHOLD_CANDLES = 5

@Preview(showBackground = true, widthDp = 420)
@Composable
private fun InteractiveCandleChartPreview() {
    val candles = (0 until 72).map { index ->
        val open = 60_000.0 + index * 18.0
        val close = open + if (index % 3 == 0) -26.0 else 34.0
        ChartCandle(
            openTimeMillis = 1_767_225_600_000L + index * 60_000L,
            open = open,
            high = maxOf(open, close) + 12.0,
            low = minOf(open, close) - 10.0,
            close = close,
            volume = 10.0 + index,
            confirmed = true
        )
    }
    MaterialTheme {
        Surface(Modifier.fillMaxSize()) {
            InteractiveCandleChart(
                state = CandleChartRenderState.Ready(
                    InteractiveCandleChartState(
                        candles = candles,
                        alertLines = listOf(
                            AlertLine("preview-enabled", "突破提醒", 61_260.0, true),
                            AlertLine("preview-disabled", "旧提醒", 59_800.0, false)
                        )
                    )
                ),
                modifier = Modifier.padding(12.dp)
            )
        }
    }
}
