package com.tzt.btcmonitor.ui

import android.app.Application
import android.content.Intent
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.tzt.btcmonitor.AppContainer
import com.tzt.btcmonitor.BuildConfig
import com.tzt.btcmonitor.model.AlertDirection
import com.tzt.btcmonitor.service.MarketMonitorService
import com.tzt.btcmonitor.settings.AppSettings
import com.tzt.btcmonitor.update.UpdateUiState
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class MonitorViewModel(application: Application) : AndroidViewModel(application) {
    val monitorState = AppContainer.monitorState.state
    val logs = AppContainer.logs.entries
    val updateState: StateFlow<UpdateUiState> = AppContainer.updates.state
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
                currentPrice = monitorState.value.currentPrice ?: 120_125.3,
                test = true
            )
        }
    }

    fun saveAlert(enabled: Boolean, direction: AlertDirection, thresholdText: String, onResult: (String) -> Unit) {
        val threshold = thresholdText.toDoubleOrNull()
        if (threshold == null || threshold <= 0.0) {
            onResult("请输入有效的正数价格")
            return
        }
        viewModelScope.launch {
            runCatching { AppContainer.settings.saveAlert(enabled, direction, threshold) }
                .onSuccess { onResult("提醒设置已保存") }
                .onFailure { onResult("保存失败：${it.message}") }
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
