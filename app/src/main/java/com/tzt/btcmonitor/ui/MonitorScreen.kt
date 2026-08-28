package com.tzt.btcmonitor.ui

import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.background
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.darkColorScheme
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tzt.btcmonitor.logging.LogEntry
import com.tzt.btcmonitor.logging.LogLevel
import com.tzt.btcmonitor.market.MarketProbeStatus
import com.tzt.btcmonitor.market.MarketProbeUiState
import com.tzt.btcmonitor.model.AlertDirection
import com.tzt.btcmonitor.model.CandleTimeframe
import com.tzt.btcmonitor.model.MarketCandle
import com.tzt.btcmonitor.model.MonitorState
import com.tzt.btcmonitor.model.NetworkType
import com.tzt.btcmonitor.model.WebSocketStatus
import com.tzt.btcmonitor.settings.AppSettings
import com.tzt.btcmonitor.update.UpdatePhase
import com.tzt.btcmonitor.update.UpdateUiState
import kotlinx.coroutines.delay
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val AppColors = darkColorScheme(
    primary = Color(0xFF63B3ED),
    secondary = Color(0xFF68D391),
    background = Color(0xFF0B1220),
    surface = Color(0xFF151E2E),
    error = Color(0xFFF56565)
)

@Composable
@OptIn(ExperimentalMaterial3Api::class)
fun MonitorApp(
    viewModel: MonitorViewModel,
    runWithNotificationPermission: (() -> Unit) -> Unit,
    openUnknownSourcesSettings: () -> Unit,
    launchExternalIntent: (Intent) -> Unit
) {
    val monitor by viewModel.monitorState.collectAsStateWithLifecycle()
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val logs by viewModel.logs.collectAsStateWithLifecycle()
    val update by viewModel.updateState.collectAsStateWithLifecycle()
    val marketProbe by viewModel.marketProbeState.collectAsStateWithLifecycle()
    val candleChart by viewModel.candleState.collectAsStateWithLifecycle()
    var selectedTab by remember { mutableIntStateOf(0) }
    var message by remember { mutableStateOf("") }
    LaunchedEffect(selectedTab) {
        if (selectedTab == 1 && candleChart.candles.isEmpty() && !candleChart.loading) {
            viewModel.loadCandles()
        }
    }

    MaterialTheme(colorScheme = AppColors) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = {
                        Column {
                            Text("BTC Monitor", fontWeight = FontWeight.Bold)
                            Text("v${viewModel.versionName}", style = MaterialTheme.typography.labelSmall)
                        }
                    }
                )
            }
        ) { outerPadding ->
            Column(Modifier.fillMaxSize().padding(outerPadding)) {
                PrimaryTabRow(selectedTabIndex = selectedTab) {
                    listOf("监控", "K线", "日志", "更多").forEachIndexed { index, label ->
                        Tab(
                            selected = selectedTab == index,
                            onClick = { selectedTab = index },
                            text = { Text(label) }
                        )
                    }
                }
                if (message.isNotBlank()) {
                    Text(
                        message,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                        color = MaterialTheme.colorScheme.secondary
                    )
                }
                when (selectedTab) {
                    0 -> MonitorPanel(
                        monitor = monitor,
                        settings = settings,
                        onStart = { runWithNotificationPermission(viewModel::startMonitoring) },
                        onStop = viewModel::stopMonitoring,
                        onTest = { runWithNotificationPermission(viewModel::sendTestNotification) },
                        onSaveAlert = { enabled, direction, threshold ->
                            viewModel.saveAlert(enabled, direction, threshold) { message = it }
                        }
                    )
                    1 -> CandlePanel(
                        state = candleChart,
                        currentPrice = monitor.currentPrice,
                        alertEnabled = settings.alert.enabled,
                        alertDirection = settings.alert.direction,
                        alertPrice = settings.alert.threshold,
                        onTimeframe = viewModel::loadCandles,
                        onRefresh = { viewModel.loadCandles(candleChart.timeframe) }
                    )
                    2 -> LogPanel(
                        logs = logs,
                        repositoryConfigured = settings.githubOwner.isNotBlank() && settings.githubRepo.isNotBlank(),
                        onShare = {
                            viewModel.shareDiagnostics(launchExternalIntent) { message = it }
                        },
                        onGitHubIssue = {
                            viewModel.openGitHubDiagnosticsIssue(launchExternalIntent) { message = it }
                        }
                    )
                    else -> MorePanel(
                        settings = settings,
                        update = update,
                        marketProbe = marketProbe,
                        onTestMarketData = viewModel::testMarketData,
                        onCheckUpdate = viewModel::checkForUpdates,
                        onDownloadUpdate = viewModel::downloadUpdate,
                        onOpenUnknownSources = openUnknownSourcesSettings,
                        onSaveRepository = { owner, repo ->
                            viewModel.saveRepository(owner, repo) { message = it }
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun MonitorPanel(
    monitor: MonitorState,
    settings: AppSettings,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onTest: () -> Unit,
    onSaveAlert: (Boolean, AlertDirection, String) -> Unit
) {
    var enabled by remember { mutableStateOf(settings.alert.enabled) }
    var direction by remember { mutableStateOf(settings.alert.direction) }
    var threshold by remember { mutableStateOf(settings.alert.threshold.toString()) }
    LaunchedEffect(settings.alert) {
        enabled = settings.alert.enabled
        direction = settings.alert.direction
        threshold = settings.alert.threshold.toString()
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        StatusCard(monitor)

        SectionCard("价格提醒") {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("启用策略", modifier = Modifier.weight(1f))
                Switch(checked = enabled, onCheckedChange = { enabled = it })
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (direction == AlertDirection.ABOVE_OR_EQUAL) {
                    Button(onClick = { direction = AlertDirection.ABOVE_OR_EQUAL }) { Text("Price Above") }
                    OutlinedButton(onClick = { direction = AlertDirection.BELOW_OR_EQUAL }) { Text("Price Below") }
                } else {
                    OutlinedButton(onClick = { direction = AlertDirection.ABOVE_OR_EQUAL }) { Text("Price Above") }
                    Button(onClick = { direction = AlertDirection.BELOW_OR_EQUAL }) { Text("Price Below") }
                }
            }
            OutlinedTextField(
                value = threshold,
                onValueChange = { threshold = it },
                label = { Text("提醒价格 USDT") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier.fillMaxWidth()
            )
            Button(
                onClick = { onSaveAlert(enabled, direction, threshold) },
                modifier = Modifier.fillMaxWidth()
            ) { Text("保存提醒设置") }
        }

        SectionCard("监控控制") {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onStart, enabled = !monitor.serviceRunning, modifier = Modifier.weight(1f)) {
                    Text("开始监控")
                }
                OutlinedButton(onClick = onStop, enabled = monitor.serviceRunning, modifier = Modifier.weight(1f)) {
                    Text("停止监控")
                }
            }
            OutlinedButton(onClick = onTest, modifier = Modifier.fillMaxWidth()) { Text("测试通知") }
            Text(
                "测试通知会完全绕过 WebSocket、行情管理器和策略引擎。",
                style = MaterialTheme.typography.bodySmall
            )
        }

        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun CandlePanel(
    state: CandleChartUiState,
    currentPrice: Double?,
    alertEnabled: Boolean,
    alertDirection: AlertDirection,
    alertPrice: Double,
    onTimeframe: (CandleTimeframe) -> Unit,
    onRefresh: () -> Unit
) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        SectionCard("BTC-USDT K 线") {
            Text(
                "OKX 公共行情 · 仅用于监控，不含交易功能",
                style = MaterialTheme.typography.bodySmall
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(5.dp)
            ) {
                CandleTimeframe.entries.forEach { timeframe ->
                    if (timeframe == state.timeframe) {
                        Button(
                            onClick = { onTimeframe(timeframe) },
                            modifier = Modifier.weight(1f),
                            contentPadding = PaddingValues(horizontal = 2.dp)
                        ) { Text(timeframe.label, fontSize = 11.sp) }
                    } else {
                        OutlinedButton(
                            onClick = { onTimeframe(timeframe) },
                            modifier = Modifier.weight(1f),
                            contentPadding = PaddingValues(horizontal = 2.dp)
                        ) { Text(timeframe.label, fontSize = 11.sp) }
                    }
                }
            }

            if (state.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
            state.error?.let {
                Text("K 线加载失败：$it", color = MaterialTheme.colorScheme.error)
                Text("这不会影响 Foreground Service；可检查 VPN/网络后重试。", style = MaterialTheme.typography.bodySmall)
            }
            if (state.candles.isNotEmpty()) {
                val newest = state.candles.last().withLivePrice(currentPrice)
                StatusLine("最新", formatCandleTime(newest.openTimeMillis, state.timeframe))
                StatusLine("O / H", "${priceText(newest.open)} / ${priceText(newest.high)}")
                StatusLine("L / C", "${priceText(newest.low)} / ${priceText(newest.close)}")
                CandlestickChart(
                    candles = state.candles,
                    currentPrice = currentPrice,
                    alertEnabled = alertEnabled,
                    alertDirection = alertDirection,
                    alertPrice = alertPrice,
                    timeframe = state.timeframe
                )
                Text(
                    "绿色上涨，红色下跌；蓝线为当前价，橙色虚线为已保存的提醒价。",
                    style = MaterialTheme.typography.bodySmall
                )
                state.loadedAtMillis?.let { StatusLine("数据加载", timeOrDash(it)) }
            }
            OutlinedButton(onClick = onRefresh, enabled = !state.loading, modifier = Modifier.fillMaxWidth()) {
                Text("刷新 K 线")
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun CandlestickChart(
    candles: List<MarketCandle>,
    currentPrice: Double?,
    alertEnabled: Boolean,
    alertDirection: AlertDirection,
    alertPrice: Double,
    timeframe: CandleTimeframe
) {
    val visible = candles.takeLast(60).mapIndexed { index, candle ->
        if (index == candles.takeLast(60).lastIndex) candle.withLivePrice(currentPrice) else candle
    }
    if (visible.isEmpty()) return

    val candleLow = visible.minOf(MarketCandle::low)
    val candleHigh = visible.maxOf(MarketCandle::high)
    val rawRange = (candleHigh - candleLow).coerceAtLeast(candleHigh * 0.001)
    val minPrice = candleLow - rawRange * 0.08
    val maxPrice = candleHigh + rawRange * 0.08
    val alertInChart = alertEnabled && alertPrice in minPrice..maxPrice
    val upColor = Color(0xFF39D98A)
    val downColor = Color(0xFFFF5C5C)
    val currentColor = Color(0xFF63B3ED)
    val alertColor = Color(0xFFF6AD55)
    val labelColor = MaterialTheme.colorScheme.onSurfaceVariant
    val gridColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.25f)

    Canvas(
        Modifier
            .fillMaxWidth()
            .height(390.dp)
            .background(Color(0xFF0D1524))
    ) {
        val left = 8.dp.toPx()
        val right = 70.dp.toPx()
        val top = 26.dp.toPx()
        val bottom = 28.dp.toPx()
        val plotWidth = size.width - left - right
        val plotHeight = size.height - top - bottom
        val priceRange = maxPrice - minPrice
        fun priceY(price: Double): Float =
            top + ((maxPrice - price) / priceRange * plotHeight).toFloat()

        val labelPaint = android.graphics.Paint().apply {
            color = labelColor.toArgb()
            textSize = 10.sp.toPx()
            isAntiAlias = true
        }
        repeat(5) { index ->
            val ratio = index / 4f
            val y = top + plotHeight * ratio
            drawLine(gridColor, Offset(left, y), Offset(left + plotWidth, y), strokeWidth = 1f)
            val price = maxPrice - priceRange * ratio
            drawContext.canvas.nativeCanvas.drawText(priceText(price), left + plotWidth + 6.dp.toPx(), y + 4.dp.toPx(), labelPaint)
        }

        val slotWidth = plotWidth / visible.size
        val bodyWidth = (slotWidth * 0.62f).coerceAtLeast(2f)
        visible.forEachIndexed { index, candle ->
            val x = left + slotWidth * (index + 0.5f)
            val color = if (candle.close >= candle.open) upColor else downColor
            val highY = priceY(candle.high)
            val lowY = priceY(candle.low)
            val openY = priceY(candle.open)
            val closeY = priceY(candle.close)
            drawLine(color, Offset(x, highY), Offset(x, lowY), strokeWidth = 1.2.dp.toPx())
            val bodyTop = minOf(openY, closeY)
            val bodyHeight = kotlin.math.abs(closeY - openY).coerceAtLeast(1.5.dp.toPx())
            drawRect(color, Offset(x - bodyWidth / 2f, bodyTop), Size(bodyWidth, bodyHeight))
        }

        currentPrice?.takeIf { it in minPrice..maxPrice }?.let { price ->
            val y = priceY(price)
            drawLine(
                currentColor,
                Offset(left, y),
                Offset(left + plotWidth, y),
                strokeWidth = 1.dp.toPx(),
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 4.dp.toPx()))
            )
        }

        if (alertInChart) {
            val y = priceY(alertPrice)
            drawLine(
                alertColor,
                Offset(left, y),
                Offset(left + plotWidth, y),
                strokeWidth = 1.5.dp.toPx(),
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(7.dp.toPx(), 4.dp.toPx()))
            )
            labelPaint.color = alertColor.toArgb()
            labelPaint.textSize = 10.sp.toPx()
            drawContext.canvas.nativeCanvas.drawText("警报 ${priceText(alertPrice)}", left + 4.dp.toPx(), y - 4.dp.toPx(), labelPaint)
        } else if (alertEnabled) {
            val above = alertPrice > maxPrice
            val marker = if (above) "警报↑ ${priceText(alertPrice)}（图外）" else "警报↓ ${priceText(alertPrice)}（图外）"
            labelPaint.color = alertColor.toArgb()
            labelPaint.textSize = 10.sp.toPx()
            drawContext.canvas.nativeCanvas.drawText(
                marker,
                left + 4.dp.toPx(),
                if (above) top - 8.dp.toPx() else top + plotHeight + 18.dp.toPx(),
                labelPaint
            )
        }

        labelPaint.color = labelColor.toArgb()
        labelPaint.textSize = 10.sp.toPx()
        drawContext.canvas.nativeCanvas.drawText(
            formatCandleTime(visible.first().openTimeMillis, timeframe),
            left,
            size.height - 5.dp.toPx(),
            labelPaint
        )
        val endLabel = formatCandleTime(visible.last().openTimeMillis, timeframe)
        labelPaint.textAlign = android.graphics.Paint.Align.RIGHT
        drawContext.canvas.nativeCanvas.drawText(endLabel, left + plotWidth, size.height - 5.dp.toPx(), labelPaint)
    }

    if (alertEnabled) {
        val symbol = if (alertDirection == AlertDirection.ABOVE_OR_EQUAL) "≥" else "≤"
        Text("当前警报：BTC-USDT $symbol ${priceText(alertPrice)} USDT", color = alertColor)
    } else {
        Text("价格警报当前未启用", color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun MarketProbeCard(state: MarketProbeUiState, onRun: () -> Unit) {
    SectionCard("行情获取测试") {
        Text(
            "独立测试每个 WebSocket 端点的连接、订阅和首个 BTC-USDT 行情；不会启动 Service、策略或提醒。",
            style = MaterialTheme.typography.bodySmall
        )
        Button(
            onClick = onRun,
            enabled = !state.running,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(if (state.running) "测试进行中…" else "测试行情获取")
        }
        if (state.running) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())

        state.results.forEach { result ->
            HorizontalDivider()
            Text(result.endpoint.label, fontWeight = FontWeight.SemiBold)
            Text(
                result.endpoint.url,
                style = MaterialTheme.typography.labelSmall,
                fontFamily = FontFamily.Monospace
            )
            StatusLine(
                "结果",
                when (result.status) {
                    MarketProbeStatus.NOT_TESTED -> "尚未测试"
                    MarketProbeStatus.TESTING -> "连接中"
                    MarketProbeStatus.SUCCESS -> "成功"
                    MarketProbeStatus.FAILED -> "失败"
                }
            )
            result.latencyMillis?.let { StatusLine("耗时", "$it ms") }
            result.price?.let { StatusLine("BTC-USDT", "${"%.2f".format(it)} USDT") }
            if (result.status != MarketProbeStatus.NOT_TESTED) {
                Text(
                    result.detail,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (result.status == MarketProbeStatus.FAILED) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    }
                )
            }
        }
    }
}

@Composable
private fun StatusCard(state: MonitorState) {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(state.serviceRunning) {
        while (state.serviceRunning) {
            now = System.currentTimeMillis()
            delay(1_000)
        }
    }
    SectionCard("BTC-USDT") {
        Text(
            state.currentPrice?.let { "$ ${"%.2f".format(it)}" } ?: "--",
            style = MaterialTheme.typography.headlineLarge,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.secondary
        )
        HorizontalDivider()
        StatusLine("WebSocket", when (state.webSocketStatus) {
            WebSocketStatus.CONNECTED -> "已连接"
            WebSocketStatus.CONNECTING -> "正在连接"
            WebSocketStatus.RECONNECTING -> "正在重连"
            WebSocketStatus.DISCONNECTED -> "已断开"
        })
        StatusLine("Foreground Service", if (state.serviceRunning) "运行中" else "已停止")
        StatusLine("网络", when (state.networkType) {
            NetworkType.WIFI -> "WiFi"
            NetworkType.CELLULAR -> "Cellular"
            NetworkType.OTHER -> "Other"
            NetworkType.OFFLINE -> "Offline"
        })
        StatusLine("最后行情", timeOrDash(state.lastTickMillis))
        StatusLine("最后策略计算", timeOrDash(state.lastStrategyMillis))
        StatusLine("最后 WebSocket 连接", timeOrDash(state.lastWebSocketConnectMillis))
        StatusLine("最后 WebSocket 断开", timeOrDash(state.lastWebSocketDisconnectMillis))
        StatusLine("Service Runtime", state.serviceStartedMillis?.let { runtime(now - it) } ?: "--")
    }
}

@Composable
private fun UpdateCard(
    update: UpdateUiState,
    onCheck: () -> Unit,
    onDownload: () -> Unit,
    onOpenUnknownSources: () -> Unit
) {
    SectionCard("版本更新") {
        StatusLine("当前版本", "v${update.currentVersion}")
        update.latestVersion?.let { StatusLine("最新版本", "v$it") }
        if (update.message.isNotBlank()) Text(update.message)
        if (update.releaseNotes.isNotBlank()) {
            Text("更新内容", fontWeight = FontWeight.SemiBold)
            Text(update.releaseNotes, style = MaterialTheme.typography.bodySmall)
        }
        if (update.phase == UpdatePhase.DOWNLOADING) {
            LinearProgressIndicator(
                progress = { update.progressPercent / 100f },
                modifier = Modifier.fillMaxWidth()
            )
            Text("${update.progressPercent}%")
        }
        if (update.phase == UpdatePhase.CHECKING || update.phase == UpdatePhase.VERIFYING) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CircularProgressIndicator()
                Text(if (update.phase == UpdatePhase.CHECKING) "检查中" else "校验中")
            }
        }
        when (update.phase) {
            UpdatePhase.AVAILABLE -> Button(onClick = onDownload, modifier = Modifier.fillMaxWidth()) {
                Text("下载并更新")
            }
            UpdatePhase.PERMISSION_REQUIRED -> Button(
                onClick = onOpenUnknownSources,
                modifier = Modifier.fillMaxWidth()
            ) { Text("允许安装未知应用") }
            else -> OutlinedButton(
                onClick = onCheck,
                enabled = update.phase !in setOf(UpdatePhase.CHECKING, UpdatePhase.DOWNLOADING, UpdatePhase.VERIFYING),
                modifier = Modifier.fillMaxWidth()
            ) { Text("检查更新") }
        }
    }
}

@Composable
private fun LogPanel(
    logs: List<LogEntry>,
    repositoryConfigured: Boolean,
    onShare: () -> Unit,
    onGitHubIssue: () -> Unit
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        item {
            SectionCard("诊断日志") {
                Text(
                    "完整日志通过系统分享；GitHub 按钮会在浏览器中预填最近 20 条日志，必须由你检查并确认提交。",
                    style = MaterialTheme.typography.bodySmall
                )
                Button(onClick = onShare, modifier = Modifier.fillMaxWidth()) {
                    Text("导出 / 分享完整日志")
                }
                OutlinedButton(
                    onClick = onGitHubIssue,
                    enabled = repositoryConfigured,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("在 GitHub 新建日志 Issue")
                }
                if (!repositoryConfigured) {
                    Text("请先在“设置”页填写 GitHub owner 和 repository。", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        if (logs.isEmpty()) {
            item {
                Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                    Text("暂无运行日志")
                }
            }
        }
        items(logs.asReversed()) { entry ->
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                Column(Modifier.fillMaxWidth().padding(10.dp)) {
                    Row {
                        Text(
                            formatLogTime(entry.timestampMillis),
                            modifier = Modifier.weight(1f),
                            fontFamily = FontFamily.Monospace,
                            style = MaterialTheme.typography.labelSmall
                        )
                        Text(
                            entry.level.name,
                            color = when (entry.level) {
                                LogLevel.INFO -> MaterialTheme.colorScheme.secondary
                                LogLevel.WARNING -> Color(0xFFF6AD55)
                                LogLevel.ERROR -> MaterialTheme.colorScheme.error
                            },
                            style = MaterialTheme.typography.labelSmall
                        )
                    }
                    Text(entry.event, fontWeight = FontWeight.Bold)
                    if (entry.detail.isNotBlank()) Text(entry.detail, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

@Composable
private fun MorePanel(
    settings: AppSettings,
    update: UpdateUiState,
    marketProbe: MarketProbeUiState,
    onTestMarketData: () -> Unit,
    onCheckUpdate: () -> Unit,
    onDownloadUpdate: () -> Unit,
    onOpenUnknownSources: () -> Unit,
    onSaveRepository: (String, String) -> Unit
) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        MarketProbeCard(marketProbe, onTestMarketData)
        UpdateCard(update, onCheckUpdate, onDownloadUpdate, onOpenUnknownSources)
        RepositorySettingsCard(settings, onSaveRepository)
        SectionCard("固定配置") {
            StatusLine("applicationId", "com.tzt.btcmonitor")
            StatusLine("Android", "16 / API 36 only")
            StatusLine("行情源", "OKX public API")
            StatusLine("交易能力", "无；不含 API Key 和下单代码")
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun RepositorySettingsCard(settings: AppSettings, onSave: (String, String) -> Unit) {
    var owner by remember { mutableStateOf(settings.githubOwner) }
    var repo by remember { mutableStateOf(settings.githubRepo) }
    LaunchedEffect(settings.githubOwner, settings.githubRepo) {
        owner = settings.githubOwner
        repo = settings.githubRepo
    }
    SectionCard("GitHub Release 仓库") {
        Text("更新检查和日志 Issue 共用此公开仓库；APK 中不保存 GitHub Token。")
        OutlinedTextField(
            value = owner,
            onValueChange = { owner = it },
            label = { Text("GitHub owner") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        OutlinedTextField(
            value = repo,
            onValueChange = { repo = it },
            label = { Text("Release repository") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        Button(onClick = { onSave(owner, repo) }, modifier = Modifier.fillMaxWidth()) {
            Text("保存仓库设置")
        }
    }
}

@Composable
private fun SectionCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            content()
        }
    }
}

@Composable
private fun StatusLine(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(label, modifier = Modifier.weight(1f), color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, fontWeight = FontWeight.Medium)
    }
}

private val clockFormatter = DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault())
private val logFormatter = DateTimeFormatter.ofPattern("MM-dd HH:mm:ss.SSS").withZone(ZoneId.systemDefault())

private fun timeOrDash(millis: Long?): String = millis?.let {
    clockFormatter.format(Instant.ofEpochMilli(it))
} ?: "--"

private fun formatLogTime(millis: Long): String = logFormatter.format(Instant.ofEpochMilli(millis))

private fun runtime(millis: Long): String {
    val duration = Duration.ofMillis(millis.coerceAtLeast(0))
    return "%02d:%02d:%02d".format(duration.toHours(), duration.toMinutesPart(), duration.toSecondsPart())
}

private fun MarketCandle.withLivePrice(currentPrice: Double?): MarketCandle {
    val price = currentPrice?.takeIf(Double::isFinite) ?: return this
    return copy(close = price, high = maxOf(high, price), low = minOf(low, price))
}

private fun priceText(price: Double): String = when {
    price >= 1_000 -> "%.2f".format(price)
    price >= 1 -> "%.4f".format(price)
    else -> "%.6f".format(price)
}

private fun formatCandleTime(millis: Long, timeframe: CandleTimeframe): String {
    val pattern = if (timeframe == CandleTimeframe.ONE_DAY) "MM-dd" else "MM-dd HH:mm"
    return DateTimeFormatter.ofPattern(pattern)
        .withZone(ZoneId.systemDefault())
        .format(Instant.ofEpochMilli(millis))
}
