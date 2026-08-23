package com.tzt.btcmonitor.market

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MarketEndpointsTest {
    @Test
    fun endpointRotationCyclesThroughEveryConfiguredEndpoint() {
        assertEquals(MarketEndpoints.all[0], MarketEndpoints.forAttempt(0))
        assertEquals(MarketEndpoints.all[1], MarketEndpoints.forAttempt(1))
        assertEquals(MarketEndpoints.all[2], MarketEndpoints.forAttempt(2))
        assertEquals(MarketEndpoints.all[0], MarketEndpoints.forAttempt(3))
    }

    @Test
    fun endpointsAreUniqueSecureWebSocketUrls() {
        assertEquals(MarketEndpoints.all.size, MarketEndpoints.all.map { it.id }.distinct().size)
        assertEquals(MarketEndpoints.all.size, MarketEndpoints.all.map { it.url }.distinct().size)
        assertTrue(MarketEndpoints.all.all { it.url.startsWith("wss://") })
    }
}
