package com.tzt.btcmonitor

import android.annotation.SuppressLint
import android.content.Context
import com.tzt.btcmonitor.logging.LogManager
import com.tzt.btcmonitor.logging.DiagnosticsManager
import com.tzt.btcmonitor.market.CandleRepository
import com.tzt.btcmonitor.model.MonitorStateStore
import com.tzt.btcmonitor.notification.NotificationHelper
import com.tzt.btcmonitor.settings.SettingsRepository
import com.tzt.btcmonitor.update.UpdateManager

// Every stored dependency receives applicationContext only and intentionally lives for the process lifetime.
@SuppressLint("StaticFieldLeak")
object AppContainer {
    lateinit var settings: SettingsRepository
        private set
    lateinit var logs: LogManager
        private set
    lateinit var monitorState: MonitorStateStore
        private set
    lateinit var diagnostics: DiagnosticsManager
        private set
    lateinit var candles: CandleRepository
        private set
    lateinit var notifications: NotificationHelper
        private set
    lateinit var updates: UpdateManager
        private set

    @Synchronized
    fun initialize(context: Context) {
        if (::settings.isInitialized) return
        val appContext = context.applicationContext
        logs = LogManager(appContext)
        settings = SettingsRepository(appContext)
        monitorState = MonitorStateStore()
        diagnostics = DiagnosticsManager(appContext, logs) { monitorState.state.value }
        candles = CandleRepository(logs)
        notifications = NotificationHelper(appContext, logs)
        updates = UpdateManager(appContext, logs)
        notifications.createChannels()
    }
}
