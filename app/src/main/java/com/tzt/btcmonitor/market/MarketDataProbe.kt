package com.tzt.btcmonitor.market

import com.tzt.btcmonitor.BuildConfig
import com.tzt.btcmonitor.logging.LogLevel
import com.tzt.btcmonitor.logging.LogManager
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.util.concurrent.TimeUnit

enum class MarketProbeStatus { NOT_TESTED, TESTING, SUCCESS, FAILED }

data class MarketProbeResult(
    val endpoint: MarketEndpoint,
    val status: MarketProbeStatus = MarketProbeStatus.NOT_TESTED,
    val latencyMillis: Long? = null,
    val price: Double? = null,
    val detail: String = "尚未测试"
)

data class MarketProbeUiState(
    val running: Boolean = false,
    val results: List<MarketProbeResult> = MarketEndpoints.all.map(::MarketProbeResult)
)

class MarketDataProbe(private val logs: LogManager) {
    suspend fun run(onResult: (MarketProbeResult) -> Unit) {
        logs.log("MarketProbeStarted", "endpoints=${MarketEndpoints.all.size}")
        MarketEndpoints.all.forEachIndexed { index, endpoint ->
            onResult(MarketProbeResult(endpoint, MarketProbeStatus.TESTING, detail = "正在连接并等待首个行情…"))
            val result = testEndpoint(endpoint)
            onResult(result)
            if (index != MarketEndpoints.all.lastIndex) delay(BETWEEN_ENDPOINTS_MS)
        }
        logs.log("MarketProbeCompleted")
    }

    private suspend fun testEndpoint(endpoint: MarketEndpoint): MarketProbeResult {
        val startedNanos = System.nanoTime()
        val outcome = CompletableDeferred<ProbeOutcome>()
        val client = OkHttpClient.Builder()
            .connectTimeout(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .pingInterval(20, TimeUnit.SECONDS)
            .retryOnConnectionFailure(false)
            .build()
        val request = Request.Builder()
            .url(endpoint.url)
            .header("User-Agent", "BTCMonitor-Android/${BuildConfig.VERSION_NAME}")
            .build()
        var socket: WebSocket? = null

        fun finish(value: ProbeOutcome) {
            outcome.complete(value)
        }

        try {
            logs.log("MarketProbeConnecting", "${endpoint.label} ${endpoint.url}")
            socket = client.newWebSocket(request, object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    if (!webSocket.send(SUBSCRIBE_MESSAGE)) {
                        finish(ProbeOutcome.Failure("订阅发送失败；HTTP ${response.code}"))
                    }
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    runCatching {
                        val json = JSONObject(text)
                        if (json.optString("event") == "error") {
                            error("OKX ${json.optString("code")}: ${json.optString("msg")}")
                        }
                        val data = json.optJSONArray("data") ?: return
                        if (data.length() == 0) return
                        val price = data.getJSONObject(0).optString("last").toDoubleOrNull() ?: return
                        finish(ProbeOutcome.Success(price))
                    }.onFailure {
                        finish(ProbeOutcome.Failure("响应解析失败：${exceptionDetail(it)}"))
                    }
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    finish(ProbeOutcome.Failure("收到行情前连接关闭：code=$code reason=$reason"))
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    val http = response?.let { " HTTP ${it.code}" }.orEmpty()
                    finish(ProbeOutcome.Failure("${exceptionDetail(t)}$http"))
                }
            })

            val value = withTimeoutOrNull(PROBE_TIMEOUT_MS) { outcome.await() }
                ?: ProbeOutcome.Failure("等待首个行情超时（${PROBE_TIMEOUT_MS / 1_000} 秒）")
            val elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedNanos)
            return when (value) {
                is ProbeOutcome.Success -> MarketProbeResult(
                    endpoint = endpoint,
                    status = MarketProbeStatus.SUCCESS,
                    latencyMillis = elapsed,
                    price = value.price,
                    detail = "已完成握手、订阅并收到 BTC-USDT 行情"
                ).also {
                    logs.log("MarketProbeSuccess", "${endpoint.label} price=${value.price} latency=${elapsed}ms")
                }
                is ProbeOutcome.Failure -> MarketProbeResult(
                    endpoint = endpoint,
                    status = MarketProbeStatus.FAILED,
                    latencyMillis = elapsed,
                    detail = value.detail
                ).also {
                    logs.log("MarketProbeFailed", "${endpoint.label} ${value.detail} latency=${elapsed}ms", LogLevel.ERROR)
                }
            }
        } finally {
            socket?.cancel()
            client.dispatcher.executorService.shutdown()
            client.connectionPool.evictAll()
        }
    }

    private sealed interface ProbeOutcome {
        data class Success(val price: Double) : ProbeOutcome
        data class Failure(val detail: String) : ProbeOutcome
    }

    companion object {
        const val SUBSCRIBE_MESSAGE =
            "{\"id\":\"btc-monitor-probe\",\"op\":\"subscribe\",\"args\":[{\"channel\":\"tickers\",\"instId\":\"BTC-USDT\"}]}"
        private const val CONNECT_TIMEOUT_SECONDS = 8L
        private const val PROBE_TIMEOUT_MS = 12_000L
        private const val BETWEEN_ENDPOINTS_MS = 300L

        fun exceptionDetail(error: Throwable): String {
            val chain = generateSequence(error) { it.cause }
                .take(4)
                .map { throwable ->
                    val message = throwable.message?.takeIf(String::isNotBlank)
                    if (message == null) throwable.javaClass.simpleName
                    else "${throwable.javaClass.simpleName}: $message"
                }
                .distinct()
                .toList()
            return chain.joinToString(" <- ").ifBlank { error.javaClass.name }
        }
    }
}
