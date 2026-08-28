package com.tzt.btcmonitor.market

import org.json.JSONObject
import org.json.JSONArray

object OkxProtocol {
    const val MONITOR_REQUEST_ID = "btcmonitor"
    const val PROBE_REQUEST_ID = "btcmonitorprobe"

    const val PROBE_SUBSCRIBE_MESSAGE =
        "{\"id\":\"btcmonitorprobe\",\"op\":\"subscribe\",\"args\":[{\"channel\":\"tickers\",\"instId\":\"BTC-USDT\"}]}"

    fun errorDetail(json: JSONObject): String? {
        if (json.optString("event") != "error") return null
        return "OKX ${json.optString("code")}: ${json.optString("msg")}".trim()
    }

    fun errorDetail(text: String): String? = runCatching {
        errorDetail(JSONObject(text))
    }.getOrNull()

    fun monitorSubscribeMessage(symbols: Set<String>): String = JSONObject().apply {
        put("id", MONITOR_REQUEST_ID)
        put("op", "subscribe")
        put("args", JSONArray().apply {
            symbols.sorted().forEach { symbol ->
                put(JSONObject().apply {
                    put("channel", "tickers")
                    put("instId", symbol)
                })
            }
        })
    }.toString()
}
