package com.tzt.btcmonitor.logging

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.concurrent.Executors

enum class LogLevel { INFO, WARNING, ERROR }

data class LogEntry(
    val timestampMillis: Long,
    val event: String,
    val detail: String,
    val level: LogLevel = LogLevel.INFO
) {
    fun persistedLine(): String =
        "${Instant.ofEpochMilli(timestampMillis)}\t${level.name}\t$event\t${detail.replace('\n', ' ')}"
}

class LogManager(context: Context) {
    private val file = File(context.filesDir, "logs/events.log")
    private val ioScope = CoroutineScope(
        SupervisorJob() + Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "btc-monitor-log-writer").apply { isDaemon = true }
        }.asCoroutineDispatcher()
    )
    private val lock = Any()
    private val timeFormatter = DateTimeFormatter.ofPattern("MM-dd HH:mm:ss.SSS")
        .withZone(ZoneId.systemDefault())

    private val mutableEntries = MutableStateFlow(loadEntries())
    val entries: StateFlow<List<LogEntry>> = mutableEntries.asStateFlow()

    fun log(event: String, detail: String = "", level: LogLevel = LogLevel.INFO) {
        val entry = LogEntry(System.currentTimeMillis(), event, detail, level)
        val message = if (detail.isBlank()) event else "$event | $detail"
        when (level) {
            LogLevel.INFO -> Log.i(LOGCAT_TAG, message)
            LogLevel.WARNING -> Log.w(LOGCAT_TAG, message)
            LogLevel.ERROR -> Log.e(LOGCAT_TAG, message)
        }
        synchronized(lock) {
            mutableEntries.value = (mutableEntries.value + entry).takeLast(MAX_UI_ENTRIES)
        }
        ioScope.launch { persist(entry) }
    }

    fun formatTime(millis: Long): String = timeFormatter.format(Instant.ofEpochMilli(millis))

    private fun loadEntries(): List<LogEntry> {
        if (!file.exists()) return emptyList()
        return runCatching {
            file.readLines().takeLast(MAX_UI_ENTRIES).mapNotNull { line ->
                val parts = line.split('\t', limit = 4)
                if (parts.size != 4) return@mapNotNull null
                LogEntry(
                    Instant.parse(parts[0]).toEpochMilli(),
                    parts[2],
                    parts[3],
                    runCatching { LogLevel.valueOf(parts[1]) }.getOrDefault(LogLevel.INFO)
                )
            }
        }.getOrDefault(emptyList())
    }

    private fun persist(entry: LogEntry) {
        runCatching {
            file.parentFile?.mkdirs()
            file.appendText(entry.persistedLine() + "\n")
            if (file.length() > MAX_FILE_BYTES) {
                val retained = file.readLines().takeLast(MAX_FILE_LINES)
                file.writeText(retained.joinToString("\n", postfix = "\n"))
            }
        }
    }

    companion object {
        const val LOGCAT_TAG = "BTCMonitor"
        private const val MAX_UI_ENTRIES = 500
        private const val MAX_FILE_LINES = 1_000
        private const val MAX_FILE_BYTES = 512L * 1024L
    }
}
