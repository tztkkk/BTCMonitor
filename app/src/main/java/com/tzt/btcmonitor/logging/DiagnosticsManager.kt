package com.tzt.btcmonitor.logging

import android.Manifest
import android.app.NotificationManager
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.PowerManager
import androidx.core.content.FileProvider
import androidx.core.net.toUri
import com.tzt.btcmonitor.BuildConfig
import com.tzt.btcmonitor.model.MonitorState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

class DiagnosticsManager(
    private val context: Context,
    private val logs: LogManager,
    private val currentMonitorState: () -> MonitorState
) {
    suspend fun createShareIntent(): Intent = withContext(Dispatchers.IO) {
        val directory = File(context.cacheDir, "diagnostics").apply { mkdirs() }
        directory.listFiles()
            ?.filter {
                it.isFile && (
                    it.name.startsWith("Monitor-diagnostics-") ||
                        it.name.startsWith("BTCMonitor-diagnostics-")
                    )
            }
            ?.sortedByDescending { it.lastModified() }
            ?.drop(MAX_RETAINED_EXPORTS - 1)
            ?.forEach { it.delete() }

        val fileName = "Monitor-diagnostics-${FILE_TIME_FORMAT.format(Instant.now())}.txt"
        val reportFile = File(directory, fileName)
        reportFile.writeText(buildReport(logs.entries.value))
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            reportFile
        )
        val sendIntent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, "Monitor diagnostics ${BuildConfig.VERSION_NAME}")
            putExtra(Intent.EXTRA_TEXT, "Monitor 诊断日志。提交前请检查是否包含不希望公开的信息。")
            putExtra(Intent.EXTRA_STREAM, uri)
            clipData = ClipData.newUri(context.contentResolver, fileName, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        Intent.createChooser(sendIntent, "分享 Monitor 日志")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    fun createGitHubIssueIntent(owner: String, repo: String): Intent {
        require(validRepoPart(owner) && validRepoPart(repo)) {
            "请先在设置中填写有效的 GitHub owner 和 repository"
        }
        val recentLogs = logs.entries.value.takeLast(MAX_ISSUE_LOGS)
        val body = buildString {
            appendLine("<!-- 提交前请检查并删除不希望公开的信息。请勿填写 API Key、密码或 Token。 -->")
            appendLine("## 运行状态")
            appendLine()
            appendLine("```text")
            append(buildHeader())
            appendLine("```")
            appendLine()
            appendLine("## 最近日志（最多 $MAX_ISSUE_LOGS 条）")
            appendLine()
            appendLine("```text")
            recentLogs.forEach { appendLine(issueLogLine(it)) }
            appendLine("```")
            appendLine()
            appendLine("## 现象和复现步骤")
            appendLine()
            appendLine("请在这里补充：发生了什么、期望什么、当时是前台/后台/锁屏/Doze，以及使用 WiFi 还是移动网络。")
        }.take(MAX_ISSUE_BODY_CHARS)

        val title = "[Diagnostics] v${BuildConfig.VERSION_NAME} ${Build.MANUFACTURER} ${Build.MODEL}"
        val url = "https://github.com/${owner.trim()}/${repo.trim()}/issues/new".toUri()
            .buildUpon()
            .appendQueryParameter("title", title)
            .appendQueryParameter("body", body)
            .appendQueryParameter("labels", "diagnostic-log")
            .build()
        return Intent(Intent.ACTION_VIEW, url).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    private fun buildReport(entries: List<LogEntry>): String = buildString {
        appendLine("Monitor diagnostic report")
        appendLine("Generated: ${Instant.now()}")
        appendLine("Privacy: review this file before publishing it to a public repository.")
        appendLine()
        append(buildHeader())
        appendLine()
        appendLine("Logs (${entries.size}, oldest to newest)")
        appendLine("----------------------------------------")
        entries.forEach { appendLine(exportLogLine(it)) }
    }

    private fun buildHeader(): String {
        val packageInfo = context.packageManager.getPackageInfo(context.packageName, 0)
        val state = currentMonitorState()
        val powerManager = context.getSystemService(PowerManager::class.java)
        val notificationManager = context.getSystemService(NotificationManager::class.java)
        val notificationPermission = context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        return buildString {
            appendLine("Package: ${context.packageName}")
            appendLine("Version: ${packageInfo.versionName} (${packageInfo.longVersionCode})")
            appendLine("Device: ${Build.MANUFACTURER} ${Build.MODEL}")
            appendLine("Android: ${Build.VERSION.RELEASE} / API ${Build.VERSION.SDK_INT}")
            appendLine("Build fingerprint: ${Build.FINGERPRINT}")
            appendLine("Notification permission: $notificationPermission")
            appendLine("Notifications enabled: ${notificationManager.areNotificationsEnabled()}")
            appendLine("Ignoring battery optimizations: ${powerManager.isIgnoringBatteryOptimizations(context.packageName)}")
            appendLine("Service running: ${state.serviceRunning}")
            appendLine("Service started: ${instantOrDash(state.serviceStartedMillis)}")
            appendLine("Service runtime ms: ${state.serviceStartedMillis?.let { System.currentTimeMillis() - it } ?: "--"}")
            appendLine("Network: ${state.networkType}")
            appendLine("WebSocket: ${state.webSocketStatus}")
            appendLine("Quotes: ${state.quotes.values.joinToString { "${it.symbol}=${it.price}" }.ifBlank { "--" }}")
            appendLine("Last tick: ${instantOrDash(state.lastTickMillis)}")
            appendLine("Last strategy: ${instantOrDash(state.lastStrategyMillis)}")
            appendLine("Last WebSocket connect: ${instantOrDash(state.lastWebSocketConnectMillis)}")
            appendLine("Last WebSocket disconnect: ${instantOrDash(state.lastWebSocketDisconnectMillis)}")
        }
    }

    private fun exportLogLine(entry: LogEntry): String =
        "${Instant.ofEpochMilli(entry.timestampMillis)}\t${entry.level}\t${entry.event}\t${entry.detail}"

    private fun issueLogLine(entry: LogEntry): String {
        val detail = entry.detail.replace('\n', ' ').take(MAX_ISSUE_DETAIL_CHARS)
        return "${Instant.ofEpochMilli(entry.timestampMillis)} ${entry.level} ${entry.event} $detail".trimEnd()
    }

    private fun instantOrDash(value: Long?): String = value?.let { Instant.ofEpochMilli(it).toString() } ?: "--"
    private fun validRepoPart(value: String): Boolean = value.trim().matches(Regex("[A-Za-z0-9_.-]+"))

    companion object {
        private const val MAX_RETAINED_EXPORTS = 3
        private const val MAX_ISSUE_LOGS = 20
        private const val MAX_ISSUE_DETAIL_CHARS = 160
        private const val MAX_ISSUE_BODY_CHARS = 3_500
        private val FILE_TIME_FORMAT = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")
            .withZone(ZoneId.systemDefault())
    }
}
