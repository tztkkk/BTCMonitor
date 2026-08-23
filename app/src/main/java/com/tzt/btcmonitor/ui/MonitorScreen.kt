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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tzt.btcmonitor.logging.LogEntry
import com.tzt.btcmonitor.logging.LogLevel
import com.tzt.btcmonitor.model.AlertDirection
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
    var selectedTab by remember { mutableIntStateOf(0) }
    var message by remember { mutableStateOf("") }

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
                    listOf("监控", "调试日志", "设置").forEachIndexed { index, label ->
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
                        update = update,
                        onStart = { runWithNotificationPermission(viewModel::startMonitoring) },
                        onStop = viewModel::stopMonitoring,
                        onTest = { runWithNotificationPermission(viewModel::sendTestNotification) },
                        onSaveAlert = { enabled, direction, threshold ->
                            viewModel.saveAlert(enabled, direction, threshold) { message = it }
                        },
                        onCheckUpdate = viewModel::checkForUpdates,
                        onDownloadUpdate = viewModel::downloadUpdate,
                        onOpenUnknownSources = openUnknownSourcesSettings
                    )
                    1 -> LogPanel(
                        logs = logs,
                        repositoryConfigured = settings.githubOwner.isNotBlank() && settings.githubRepo.isNotBlank(),
                        onShare = {
                            viewModel.shareDiagnostics(launchExternalIntent) { message = it }
                        },
                        onGitHubIssue = {
                            viewModel.openGitHubDiagnosticsIssue(launchExternalIntent) { message = it }
                        }
                    )
                    else -> SettingsPanel(settings) { owner, repo ->
                        viewModel.saveRepository(owner, repo) { message = it }
                    }
                }
            }
        }
    }
}

@Composable
private fun MonitorPanel(
    monitor: MonitorState,
    settings: AppSettings,
    update: UpdateUiState,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onTest: () -> Unit,
    onSaveAlert: (Boolean, AlertDirection, String) -> Unit,
    onCheckUpdate: () -> Unit,
    onDownloadUpdate: () -> Unit,
    onOpenUnknownSources: () -> Unit
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

        UpdateCard(update, onCheckUpdate, onDownloadUpdate, onOpenUnknownSources)
        Spacer(Modifier.height(24.dp))
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
private fun SettingsPanel(settings: AppSettings, onSave: (String, String) -> Unit) {
    var owner by remember { mutableStateOf(settings.githubOwner) }
    var repo by remember { mutableStateOf(settings.githubRepo) }
    LaunchedEffect(settings.githubOwner, settings.githubRepo) {
        owner = settings.githubOwner
        repo = settings.githubRepo
    }
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        SectionCard("GitHub Release 仓库") {
            Text("填写公开的 Release/Issue 仓库。更新检查和日志 Issue 共用此配置，不要在 APK 中放 GitHub Token。")
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
        SectionCard("固定配置") {
            StatusLine("applicationId", "com.tzt.btcmonitor")
            StatusLine("Android", "16 / API 36 only")
            StatusLine("行情源", "OKX public WebSocket")
            StatusLine("交易能力", "无；不含 API Key 和下单代码")
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
