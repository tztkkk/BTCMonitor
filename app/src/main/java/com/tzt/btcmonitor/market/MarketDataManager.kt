package com.tzt.btcmonitor.market

import com.tzt.btcmonitor.logging.LogLevel
import com.tzt.btcmonitor.logging.LogManager
import com.tzt.btcmonitor.model.MarketTick
import com.tzt.btcmonitor.model.WebSocketStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class MarketDataManager(
    private val scope: CoroutineScope,
    private val logs: LogManager,
    private val onStatus: (WebSocketStatus) -> Unit,
    private val onTick: (MarketTick) -> Unit
) {
    private val client = OkHttpClient.Builder()
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .pingInterval(20, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    private var socket: WebSocket? = null
    private var reconnectJob: Job? = null
    private var heartbeatJob: Job? = null
    private var reconnectAttempt = 0
    private var generation = 0L
    private var running = false
    private var networkAvailable = false
    private var connected = false
    private var lastTickLogMillis = 0L
    private val dispatchThrottle = SymbolDispatchThrottle(TICK_DISPATCH_INTERVAL_MS)
    private var lastMessageMillis = 0L
    private var pingSentMillis: Long? = null
    private var symbols: Set<String> = emptySet()

    fun start(initialNetworkAvailable: Boolean) {
        running = true
        networkAvailable = initialNetworkAvailable
        if (networkAvailable && symbols.isNotEmpty()) connect() else onStatus(WebSocketStatus.DISCONNECTED)
    }

    fun updateSymbols(newSymbols: Set<String>) {
        scope.launch {
            val normalized = newSymbols.filterTo(sortedSetOf()) { it.isNotBlank() }
            if (normalized == symbols) return@launch
            symbols = normalized
            dispatchThrottle.retainSymbols(symbols)
            logs.log("MarketSubscriptionsChanged", "symbols=${symbols.joinToString()}")
            if (!running) return@launch
            reconnectAttempt = 0
            invalidateSocket()
            if (symbols.isEmpty() || !networkAvailable) {
                onStatus(WebSocketStatus.DISCONNECTED)
            } else {
                connect()
            }
        }
    }

    fun onNetworkChanged(available: Boolean) {
        scope.launch {
            networkAvailable = available
            if (!running) return@launch
            if (!available) {
                reconnectJob?.cancel()
                invalidateSocket()
                onStatus(WebSocketStatus.DISCONNECTED)
            } else {
                reconnectAttempt = 0
                invalidateSocket()
                connect()
            }
        }
    }

    fun stop() {
        running = false
        networkAvailable = false
        reconnectJob?.cancel()
        reconnectJob = null
        heartbeatJob?.cancel()
        heartbeatJob = null
        generation++
        socket?.close(1000, "Monitor stopped")
        socket = null
        connected = false
        dispatchThrottle.clear()
        onStatus(WebSocketStatus.DISCONNECTED)
        client.dispatcher.executorService.shutdown()
        client.connectionPool.evictAll()
    }

    private fun connect() {
        if (!running || !networkAvailable || socket != null || symbols.isEmpty()) return
        val myGeneration = ++generation
        val reconnecting = reconnectAttempt > 0
        val endpoint = MarketEndpoints.forAttempt(reconnectAttempt)
        onStatus(if (reconnecting) WebSocketStatus.RECONNECTING else WebSocketStatus.CONNECTING)
        logs.log(
            "WebSocketConnecting",
            "${endpoint.label} symbols=${symbols.joinToString()} attempt=${reconnectAttempt + 1} url=${endpoint.url}"
        )

        val request = Request.Builder()
            .url(endpoint.url)
            .header("User-Agent", "BTCMonitor-Android/${com.tzt.btcmonitor.BuildConfig.VERSION_NAME}")
            .build()

        socket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                scope.launch {
                    if (myGeneration != generation || !running) return@launch
                    connected = true
                    lastMessageMillis = System.currentTimeMillis()
                    pingSentMillis = null
                    val wasReconnect = reconnectAttempt > 0
                    reconnectAttempt = 0
                    onStatus(WebSocketStatus.CONNECTED)
                    logs.log(
                        if (wasReconnect) "ReconnectSuccess" else "WebSocketConnected",
                        "${endpoint.label} HTTP ${response.code} ${response.message}"
                    )
                    val subscribeMessage = OkxProtocol.monitorSubscribeMessage(symbols)
                    if (!webSocket.send(subscribeMessage)) {
                        logs.log("WebSocketError", "OKX subscription send failed", LogLevel.ERROR)
                        webSocket.cancel()
                        return@launch
                    }
                    startHeartbeat(myGeneration)
                }
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                scope.launch {
                    if (myGeneration != generation || !running) return@launch
                    lastMessageMillis = System.currentTimeMillis()
                    pingSentMillis = null
                    if (text == "pong") return@launch
                    OkxProtocol.errorDetail(text)?.let { detail ->
                        logs.log("WebSocketError", "${endpoint.label} subscription rejected: $detail", LogLevel.ERROR)
                        webSocket.cancel()
                        return@launch
                    }
                    val now = System.currentTimeMillis()
                    parseTick(text)?.let { tick ->
                        if (!dispatchThrottle.shouldDispatch(tick.symbol, now)) return@let
                        if (now - lastTickLogMillis >= TICK_LOG_INTERVAL_MS) {
                            lastTickLogMillis = now
                            logs.log("LastTick", "price=${tick.price}")
                        }
                        onTick(tick)
                    }
                }
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(code, reason)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                scope.launch {
                    if (myGeneration != generation) return@launch
                    heartbeatJob?.cancel()
                    heartbeatJob = null
                    socket = null
                    connected = false
                    logs.log("WebSocketDisconnected", "${endpoint.label} code=$code reason=$reason")
                    onStatus(WebSocketStatus.DISCONNECTED)
                    scheduleReconnect()
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                scope.launch {
                    if (myGeneration != generation) return@launch
                    heartbeatJob?.cancel()
                    heartbeatJob = null
                    socket = null
                    connected = false
                    val http = response?.let { " HTTP ${it.code}" }.orEmpty()
                    logs.log(
                        "WebSocketError",
                        "${endpoint.label} ${MarketDataProbe.exceptionDetail(t)}$http",
                        LogLevel.ERROR
                    )
                    onStatus(WebSocketStatus.DISCONNECTED)
                    scheduleReconnect()
                }
            }
        })
    }

    private fun scheduleReconnect() {
        if (!running || !networkAvailable || reconnectJob?.isActive == true) return
        val delaySeconds = RECONNECT_DELAYS_SECONDS[reconnectAttempt.coerceAtMost(RECONNECT_DELAYS_SECONDS.lastIndex)]
        reconnectAttempt++
        logs.log("ReconnectScheduled", "in ${delaySeconds}s attempt=$reconnectAttempt")
        onStatus(WebSocketStatus.RECONNECTING)
        reconnectJob = scope.launch {
            delay(delaySeconds * 1_000L)
            reconnectJob = null
            connect()
        }
    }

    private fun invalidateSocket() {
        reconnectJob?.cancel()
        reconnectJob = null
        heartbeatJob?.cancel()
        heartbeatJob = null
        generation++
        socket?.cancel()
        socket = null
        connected = false
    }

    private fun parseTick(text: String): MarketTick? = runCatching {
        val json = JSONObject(text)
        val data = json.optJSONArray("data") ?: return@runCatching null
        if (data.length() == 0) return@runCatching null
        val ticker = data.getJSONObject(0)
        MarketTick(
            symbol = json.optJSONObject("arg")?.optString("instId").orEmpty()
                .ifBlank { ticker.optString("instId") }
                .ifBlank { return@runCatching null },
            price = ticker.getString("last").toDouble(),
            open24h = ticker.optString("open24h").toDoubleOrNull(),
            exchangeTimeMillis = ticker.optString("ts").toLongOrNull() ?: System.currentTimeMillis()
        )
    }.onFailure {
        logs.log("Exception", "Tick parse: ${it.message}", LogLevel.ERROR)
    }.getOrNull()

    private fun startHeartbeat(myGeneration: Long) {
        heartbeatJob?.cancel()
        heartbeatJob = scope.launch {
            while (isActive && running && myGeneration == generation) {
                delay(HEARTBEAT_CHECK_INTERVAL_MS)
                val now = System.currentTimeMillis()
                val outstandingPing = pingSentMillis
                if (outstandingPing != null && now - outstandingPing >= PONG_TIMEOUT_MS) {
                    logs.log("WebSocketError", "OKX pong timeout", LogLevel.ERROR)
                    invalidateSocket()
                    onStatus(WebSocketStatus.DISCONNECTED)
                    scheduleReconnect()
                    break
                }
                if (now - lastMessageMillis >= IDLE_BEFORE_PING_MS && socket?.send("ping") == true) {
                    pingSentMillis = now
                }
            }
        }
    }

    companion object {
        private const val TICK_DISPATCH_INTERVAL_MS = 1_000L
        private const val TICK_LOG_INTERVAL_MS = 60_000L
        private const val HEARTBEAT_CHECK_INTERVAL_MS = 10_000L
        private const val IDLE_BEFORE_PING_MS = 20_000L
        private const val PONG_TIMEOUT_MS = 15_000L
        private val RECONNECT_DELAYS_SECONDS = longArrayOf(1, 2, 5, 10, 30)
    }
}
