package com.tzt.btcmonitor.market

import org.junit.Assert.assertTrue
import org.junit.Test

class OkxProtocolTest {
    @Test
    fun requestIdsMeetOkxAlphanumericConstraint() {
        val allowed = Regex("^[A-Za-z0-9]{1,32}$")
        assertTrue(allowed.matches(OkxProtocol.MONITOR_REQUEST_ID))
        assertTrue(allowed.matches(OkxProtocol.PROBE_REQUEST_ID))
    }

    @Test
    fun subscriptionMessagesUseTheValidatedIds() {
        assertTrue(
            OkxProtocol.monitorSubscribeMessage(setOf("BTC-USDT", "ETH-USDT")).contains(
                "\"id\":\"${OkxProtocol.MONITOR_REQUEST_ID}\""
            )
        )
        assertTrue(OkxProtocol.monitorSubscribeMessage(setOf("BTC-USDT", "ETH-USDT")).contains("ETH-USDT"))
        assertTrue(
            OkxProtocol.PROBE_SUBSCRIBE_MESSAGE.contains(
                "\"id\":\"${OkxProtocol.PROBE_REQUEST_ID}\""
            )
        )
    }
}
