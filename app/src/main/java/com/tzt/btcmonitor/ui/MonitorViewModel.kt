package com.tzt.btcmonitor.ui

import android.app.Application
import android.content.Intent
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.tzt.btcmonitor.AppContainer
import com.tzt.btcmonitor.BuildConfig
import com.tzt.btcmonitor.market.MarketDataProbe
import com.tzt.btcmonitor.market.MarketProbeUiState
import com.tzt.btcmonitor.model.AlertDirection
import com.tzt.btcmonitor.model.AlertConfig
import com.tzt.btcmonitor.model.CandleTimeframe
import com.tzt.btcmonitor.model.MarketCandle
import com.tzt.btcmonitor.model.WatchAsset
import com.tzt.btcmonitor.service.MarketMonitorService
import com.tzt.btcmonitor.settings.AppSettings
import com.tzt.btcmonitor.update.UpdateUiState
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class CandleChartUiState(
    val symbol: String = "BTC-USDT",
    val timeframe: CandleTimeframe = CandleTimeframe.FIVE_MINUTES,
    val candles: List<MarketCandle> = emptyList(),
    val loading: Boolean = false,
    val error: String? = null,
    val loadedAtMillis: Long? = null
)

class MonitorViewModel(application: Application) : AndroidViewModel(application) {
    private val marketProbe = MarketDataProbe(AppContainer.logs)
    private val mutableMarketProbeState = MutableStateFlow(MarketProbeUiState())
    private var marketProbeJob: Job? = null
    private var candleJob: Job? = null
    private var quoteSnapshotJob: Job? = null
    private val mutableCandleState = MutableStateFlow(CandleChartUiState())

    val monitorState = AppContainer.monitorState.state
    val logs = AppContainer.logs.entries
    val updateState: StateFlow<UpdateUiState> = AppContainer.updates.state
    val marketProbeState: StateFlow<MarketProbeUiState> = mutableMarketProbeState.asStateFlow()
    val candleState: StateFlow<CandleChartUiState> = mutableCandleState.asStateFlow()
    val settings: StateFlow<AppSettings> = AppContainer.settings.settings.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        AppSettings()
    )

    val versionName: String = BuildConfig.VERSION_NAME

    init {
        viewModelScope.launch {
            val value = AppContainer.settings.settings.first()
            if (value.githubOwner.isNotBlank() && value.githubRepo.isNotBlank()) {
                AppContainer.updates.checkForUpdates(value.githubOwner, value.githubRepo, silent = true)
            }
        }
    }

    fun startMonitoring() {
        val context = getApplication<Application>()
        val intent = Intent(context, MarketMonitorService::class.java).apply {
            action = MarketMonitorService.ACTION_START
        }
        runCatching { context.startForegroundService(intent) }
            .onFailure { AppContainer.logs.log("Exception", "Start service: ${it.message}") }
    }

    fun stopMonitoring() {
        val context = getApplication<Application>()
        val intent = Intent(context, MarketMonitorService::class.java).apply {
            action = MarketMonitorService.ACTION_STOP
        }
        runCatching { context.startService(intent) }
            .onFailure { AppContainer.logs.log("Exception", "Stop service: ${it.message}") }
    }

    fun sendTestNotification() {
        runCatching {
            AppContainer.notifications.sendTradingAlert(
                message = "BTC-USDT 测试通知通道正常",
                currentPrice = monitorState.value.quotes.values.firstOrNull()?.price ?: 120_125.3,
                test = true
            )
        }
    }

    fun testMarketData() {
        if (marketProbeJob?.isActive == true) return
        marketProbeJob = viewModelScope.launch {
            mutableMarketProbeState.value = MarketProbeUiState(running = true)
            try {
                marketProbe.run { result ->
                    val current = mutableMarketProbeState.value
                    mutableMarketProbeState.value = current.copy(
                        results = current.results.map { existing ->
                            if (existing.endpoint.id == result.endpoint.id) result else existing
                        }
                    )
                }
            } finally {
                mutableMarketProbeState.value = mutableMarketProbeState.value.copy(running = false)
            }
        }
    }

    fun loadCandles(
        symbol: String = mutableCandleState.value.symbol,
        timeframe: CandleTimeframe = mutableCandleState.value.timeframe
    ) {
        candleJob?.cancel()
        candleJob = viewModelScope.launch {
            mutableCandleState.value = mutableCandleState.value.copy(
                timeframe = timeframe,
                symbol = symbol,
                loading = true,
                error = null
            )
            runCatching { AppContainer.candles.loadRecent(symbol, timeframe) }
                .onSuccess { candles ->
                    mutableCandleState.value = CandleChartUiState(
                        timeframe = timeframe,
                        symbol = symbol,
                        candles = candles,
                        loadedAtMillis = System.currentTimeMillis()
                    )
                }
                .onFailure { error ->
                    mutableCandleState.value = mutableCandleState.value.copy(
                        loading = false,
                        error = MarketDataProbe.exceptionDetail(error)
                    )
                }
        }
    }

    fun addAlert(asset: WatchAsset, name: String, enabled: Boolean, direction: AlertDirection, thresholdText: String, onResult: (String) -> Unit) {
        val threshold = thresholdText.toDoubleOrNull()
        if (threshold == null || threshold <= 0.0) {
            onResult("请输入有效的正数价格")
            return
        }
        viewModelScope.launch {
            runCatching { AppContainer.settings.addAlert(asset, name, enabled, direction, threshold) }
                .onSuccess { onResult("提醒已添加") }
                .onFailure { onResult("保存失败：${it.message}") }
        }
    }

    fun updateAlert(alert: AlertConfig, name: String, enabled: Boolean, direction: AlertDirection, thresholdText: String, onResult: (String) -> Unit) {
        val threshold = thresholdText.toDoubleOrNull()
        if (threshold == null || threshold <= 0.0) {
            onResult("请输入有效的正数价格")
            return
        }
        viewModelScope.launch {
            runCatching { AppContainer.settings.updateAlert(alert.id, name, enabled, direction, threshold) }
                .onSuccess { onResult("提醒已更新") }
                .onFailure { onResult("保存失败：${it.message}") }
        }
    }

    fun setAlertEnabled(alert: AlertConfig, enabled: Boolean, onResult: (String) -> Unit) {
        viewModelScope.launch {
            runCatching { AppContainer.settings.setAlertEnabled(alert.id, enabled) }
                .onFailure { onResult("修改失败：${it.message}") }
        }
    }

    fun deleteAlert(alert: AlertConfig, onResult: (String) -> Unit) {
        viewModelScope.launch {
            runCatching { AppContainer.settings.deleteAlert(alert.id) }
                .onSuccess { onResult("提醒已删除") }
                .onFailure { onResult("删除失败：${it.message}") }
        }
    }

    fun addAsset(asset: WatchAsset, onResult: (String) -> Unit) {
        viewModelScope.launch {
            runCatching { AppContainer.settings.addAsset(asset) }
                .onSuccess { onResult("已添加 ${asset.symbol}") }
                .onFailure { onResult("添加失败：${it.message}") }
        }
    }

    fun removeAsset(asset: WatchAsset, onResult: (String) -> Unit) {
        viewModelScope.launch {
            runCatching { AppContainer.settings.removeAsset(asset.id) }
                .onSuccess { onResult("已移除 ${asset.symbol}") }
                .onFailure { onResult("移除失败：${it.message}") }
        }
    }

    fun setMonitoringPaused(paused: Boolean) {
        viewModelScope.launch { AppContainer.settings.setMonitoringPaused(paused) }
    }

    fun refreshQuotes(assets: List<WatchAsset>) {
        quoteSnapshotJob?.cancel()
        quoteSnapshotJob = viewModelScope.launch {
            val quotes = assets.map { asset ->
                async { runCatching { AppContainer.candles.loadQuote(asset.symbol) }.getOrNull() }
            }.awaitAll().filterNotNull()
            if (quotes.isNotEmpty()) {
                AppContainer.monitorState.update { state ->
                    state.copy(quotes = state.quotes + quotes.associateBy { it.symbol })
                }
            }
        }
    }

    fun saveRepository(owner: String, repo: String, onResult: (String) -> Unit) {
        viewModelScope.launch {
            AppContainer.settings.saveGitHubRepository(owner, repo)
            onResult("GitHub Release 仓库已保存")
        }
    }

    fun shareDiagnostics(onReady: (Intent) -> Unit, onResult: (String) -> Unit) {
        viewModelScope.launch {
            runCatching { AppContainer.diagnostics.createShareIntent() }
                .onSuccess(onReady)
                .onFailure { onResult("生成诊断日志失败：${it.message}") }
        }
    }

    fun openGitHubDiagnosticsIssue(onReady: (Intent) -> Unit, onResult: (String) -> Unit) {
        runCatching {
            val value = settings.value
            AppContainer.diagnostics.createGitHubIssueIntent(value.githubOwner, value.githubRepo)
        }.onSuccess(onReady)
            .onFailure { onResult(it.message ?: "无法打开 GitHub Issue") }
    }

    fun checkForUpdates() {
        viewModelScope.launch {
            val value = settings.value
            AppContainer.updates.checkForUpdates(value.githubOwner, value.githubRepo)
        }
    }

    fun downloadUpdate() {
        viewModelScope.launch { AppContainer.updates.downloadAndPrepareInstall() }
    }

    fun unknownSourcesIntent(): Intent = AppContainer.updates.unknownSourcesSettingsIntent()
    fun resumeUpdateInstall() = AppContainer.updates.resumeInstallAfterPermission()
}
