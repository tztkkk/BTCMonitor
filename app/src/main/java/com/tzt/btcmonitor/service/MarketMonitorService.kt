package com.tzt.btcmonitor.service

import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import androidx.core.app.ServiceCompat
import com.tzt.btcmonitor.AppContainer
import com.tzt.btcmonitor.logging.LogLevel
import com.tzt.btcmonitor.market.MarketDataManager
import com.tzt.btcmonitor.model.NetworkType
import com.tzt.btcmonitor.model.AssetQuote
import com.tzt.btcmonitor.model.WebSocketStatus
import com.tzt.btcmonitor.network.NetworkMonitor
import com.tzt.btcmonitor.notification.NotificationHelper
import com.tzt.btcmonitor.strategy.StrategyEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class MarketMonitorService : Service() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val strategyEngine = StrategyEngine()
    private lateinit var marketDataManager: MarketDataManager
    private lateinit var networkMonitor: NetworkMonitor
    private var started = false
    private var requestedStop = false
    private var currentNetwork = NetworkType.OFFLINE
    private var lastStatus = WebSocketStatus.DISCONNECTED

    override fun onCreate() {
        super.onCreate()
        AppContainer.initialize(applicationContext)
        marketDataManager = MarketDataManager(
            scope = serviceScope,
            logs = AppContainer.logs,
            onStatus = ::onWebSocketStatus,
            onTick = { tick ->
                AppContainer.monitorState.update {
                    it.copy(
                        quotes = it.quotes + (tick.symbol to AssetQuote(
                            symbol = tick.symbol,
                            price = tick.price,
                            open24h = tick.open24h,
                            receivedTimeMillis = tick.receivedTimeMillis
                        )),
                        lastTickMillis = tick.receivedTimeMillis
                    )
                }
                val results = strategyEngine.evaluate(tick)
                AppContainer.monitorState.update { it.copy(lastStrategyMillis = System.currentTimeMillis()) }
                results.filter { it.triggered && it.message != null }.forEach { result ->
                    val message = requireNotNull(result.message)
                    AppContainer.logs.log("StrategyTriggered", "alertId=${result.alertId} $message")
                    runCatching {
                        AppContainer.notifications.sendTradingAlert(message, tick.price)
                    }.onFailure { error ->
                        AppContainer.logs.log("Exception", "Alert send: ${error.message}", LogLevel.ERROR)
                    }
                }
            }
        )
        networkMonitor = NetworkMonitor(applicationContext, AppContainer.logs) { type ->
            currentNetwork = type
            AppContainer.monitorState.update { it.copy(networkType = type) }
            marketDataManager.onNetworkChanged(type != NetworkType.OFFLINE)
        }
        serviceScope.launch {
            AppContainer.settings.settings.collectLatest { settings ->
                strategyEngine.updateConfigs(settings.alerts)
                val shouldMonitor = !settings.monitoringPaused && settings.alerts.any { it.enabled }
                marketDataManager.updateSymbols(if (shouldMonitor) settings.assets.mapTo(mutableSetOf()) { it.symbol } else emptySet())
                if (started && !shouldMonitor) stopMonitoring()
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        requestedStop = intent?.action == ACTION_STOP
        if (requestedStop) {
            stopMonitoring()
            return START_NOT_STICKY
        }

        promoteToForeground()
        if (!started) startMonitoring()
        return START_STICKY
    }

    private fun promoteToForeground() {
        ServiceCompat.startForeground(
            this,
            NotificationHelper.SERVICE_NOTIFICATION_ID,
            AppContainer.notifications.serviceNotification(),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        )
    }

    private fun startMonitoring() {
        started = true
        val now = System.currentTimeMillis()
        AppContainer.monitorState.update {
            it.copy(serviceRunning = true, serviceStartedMillis = now)
        }
        AppContainer.logs.log("ServiceStarted", "START_STICKY specialUse")
        networkMonitor.start()
        marketDataManager.start(currentNetwork != NetworkType.OFFLINE)
    }

    private fun stopMonitoring() {
        if (started) {
            started = false
            marketDataManager.stop()
            networkMonitor.stop()
            strategyEngine.reset()
            AppContainer.logs.log("ServiceStopped", "User requested")
        }
        AppContainer.monitorState.update {
            it.copy(
                serviceRunning = false,
                serviceStartedMillis = null,
                webSocketStatus = WebSocketStatus.DISCONNECTED
            )
        }
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun onWebSocketStatus(status: WebSocketStatus) {
        val now = System.currentTimeMillis()
        AppContainer.monitorState.update { old ->
            old.copy(
                webSocketStatus = status,
                lastWebSocketConnectMillis = if (status == WebSocketStatus.CONNECTED) now else old.lastWebSocketConnectMillis,
                lastWebSocketDisconnectMillis = if (
                    lastStatus == WebSocketStatus.CONNECTED && status != WebSocketStatus.CONNECTED
                ) now else old.lastWebSocketDisconnectMillis
            )
        }
        lastStatus = status
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        AppContainer.logs.log("onTaskRemoved", "Service continues while allowed by system")
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        if (started) {
            marketDataManager.stop()
            networkMonitor.stop()
        }
        AppContainer.monitorState.update {
            it.copy(serviceRunning = false, serviceStartedMillis = null, webSocketStatus = WebSocketStatus.DISCONNECTED)
        }
        AppContainer.logs.log("ServiceDestroyed", if (requestedStop) "requested" else "system/process")
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val ACTION_START = "com.tzt.btcmonitor.action.START"
        const val ACTION_STOP = "com.tzt.btcmonitor.action.STOP"
    }
}
