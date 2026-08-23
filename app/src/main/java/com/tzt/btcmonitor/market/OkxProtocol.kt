package com.tzt.btcmonitor.market

import org.json.JSONObject

object OkxProtocol {
    const val MONITOR_REQUEST_ID = "btcmonitor"
    const val PROBE_REQUEST_ID = "btcmonitorprobe"

    const val MONITOR_SUBSCRIBE_MESSAGE =
        "{\"id\":\"btcmonitor\",\"op\":\"subscribe\",\"args\":[{\"channel\":\"tickers\",\"instId\":\"BTC-USDT\"}]}"
    const val PROBE_SUBSCRIBE_MESSAGE =
        "{\"id\":\"btcmonitorprobe\",\"op\":\"subscribe\",\"args\":[{\"channel\":\"tickers\",\"instId\":\"BTC-USDT\"}]}"

    fun errorDetail(json: JSONObject): String? {
        if (json.optString("event") != "error") return null
        return "OKX ${json.optString("code")}: ${json.optString("msg")}".trim()
    }

    fun errorDetail(text: String): String? = runCatching {
        errorDetail(JSONObject(text))
    }.getOrNull()
}
