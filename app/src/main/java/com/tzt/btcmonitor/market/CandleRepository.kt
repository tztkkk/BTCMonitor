package com.tzt.btcmonitor.market

import com.tzt.btcmonitor.BuildConfig
import com.tzt.btcmonitor.logging.LogLevel
import com.tzt.btcmonitor.logging.LogManager
import com.tzt.btcmonitor.model.CandleTimeframe
import com.tzt.btcmonitor.model.MarketCandle
import com.tzt.btcmonitor.model.AssetQuote
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class CandleRepository(private val logs: LogManager) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .callTimeout(20, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    suspend fun loadRecent(
        symbol: String,
        timeframe: CandleTimeframe,
        limit: Int = DEFAULT_LIMIT
    ): List<MarketCandle> {
        require(limit in 1..300)
        require(symbol.matches(Regex("[A-Z0-9-]{3,30}"))) { "无效的行情标的" }
        val request = Request.Builder()
            .url("$CANDLES_URL?instId=$symbol&bar=${timeframe.apiValue}&limit=$limit")
            .header("User-Agent", "BTCMonitor-Android/${BuildConfig.VERSION_NAME}")
            .get()
            .build()

        logs.log("CandlesLoading", "$symbol ${timeframe.apiValue} limit=$limit")
        return try {
            val body = client.newCall(request).awaitBody()
            OkxCandleParser.parse(body).also {
                require(it.isNotEmpty()) { "OKX 返回空 K 线列表" }
                logs.log("CandlesLoaded", "$symbol ${timeframe.apiValue} count=${it.size}")
            }
        } catch (error: Throwable) {
            logs.log(
                "CandlesError",
                "$symbol ${timeframe.apiValue}: ${MarketDataProbe.exceptionDetail(error)}",
                LogLevel.ERROR
            )
            throw error
        }
    }

    suspend fun loadQuote(symbol: String): AssetQuote {
        require(symbol.matches(Regex("[A-Z0-9-]{3,30}"))) { "无效的行情标的" }
        val request = Request.Builder()
            .url("$TICKER_URL?instId=$symbol")
            .header("User-Agent", "BTCMonitor-Android/${BuildConfig.VERSION_NAME}")
            .get()
            .build()
        return try {
            OkxTickerParser.parse(client.newCall(request).awaitBody())
        } catch (error: Throwable) {
            logs.log("QuoteSnapshotError", "$symbol: ${MarketDataProbe.exceptionDetail(error)}", LogLevel.ERROR)
            throw error
        }
    }

    private suspend fun Call.awaitBody(): String = suspendCancellableCoroutine { continuation ->
        continuation.invokeOnCancellation { cancel() }
        enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (continuation.isActive) continuation.resumeWithException(e)
            }

            override fun onResponse(call: Call, response: Response) {
                response.use {
                    if (!it.isSuccessful) {
                        if (continuation.isActive) {
                            continuation.resumeWithException(IOException("HTTP ${it.code} ${it.message}"))
                        }
                        return
                    }
                    val body = it.body.string()
                    if (continuation.isActive) continuation.resume(body)
                }
            }
        })
    }

    companion object {
        private const val CANDLES_URL = "https://www.okx.com/api/v5/market/candles"
        private const val TICKER_URL = "https://www.okx.com/api/v5/market/ticker"
        private const val DEFAULT_LIMIT = 80
    }
}

internal object OkxTickerParser {
    fun parse(body: String): AssetQuote {
        val root = JSONObject(body)
        check(root.optString("code") == "0") { "OKX ${root.optString("code")}: ${root.optString("msg")}" }
        val ticker = root.getJSONArray("data").getJSONObject(0)
        return AssetQuote(
            symbol = ticker.getString("instId"),
            price = ticker.getString("last").toDouble(),
            open24h = ticker.optString("open24h").toDoubleOrNull(),
            receivedTimeMillis = ticker.optString("ts").toLongOrNull() ?: System.currentTimeMillis()
        )
    }
}

internal object OkxCandleParser {
    fun parse(body: String): List<MarketCandle> {
        val root = JSONObject(body)
        val code = root.optString("code")
        check(code == "0") {
            "OKX $code: ${root.optString("msg").ifBlank { "K 线请求失败" }}"
        }
        val data = root.optJSONArray("data") ?: error("OKX 响应缺少 data")
        return buildList {
            for (index in 0 until data.length()) {
                val row = data.getJSONArray(index)
                require(row.length() >= 9) { "K 线字段数量不足" }
                add(
                    MarketCandle(
                        openTimeMillis = row.getString(0).toLong(),
                        open = row.getString(1).toDouble(),
                        high = row.getString(2).toDouble(),
                        low = row.getString(3).toDouble(),
                        close = row.getString(4).toDouble(),
                        volume = row.getString(5).toDouble(),
                        confirmed = row.getString(8) == "1"
                    )
                )
            }
        }.sortedBy(MarketCandle::openTimeMillis)
    }
}
