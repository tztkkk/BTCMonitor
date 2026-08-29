package com.tzt.btcmonitor.market

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SymbolDispatchThrottleTest {
    private val throttle = SymbolDispatchThrottle(intervalMillis = 1_000L)

    @Test
    fun differentSymbolsCanDispatchAtTheSameTime() {
        assertTrue(throttle.shouldDispatch("BTC-USDT", nowMillis = 10_000L))
        assertTrue(throttle.shouldDispatch("ETH-USDT", nowMillis = 10_000L))
    }

    @Test
    fun sameSymbolRemainsLimitedByTheInterval() {
        assertTrue(throttle.shouldDispatch("BTC-USDT", nowMillis = 10_000L))
        assertFalse(throttle.shouldDispatch("BTC-USDT", nowMillis = 10_999L))
        assertTrue(throttle.shouldDispatch("BTC-USDT", nowMillis = 11_000L))
    }

    @Test
    fun removedSymbolDoesNotKeepThrottleStateWhenResubscribed() {
        assertTrue(throttle.shouldDispatch("BTC-USDT", nowMillis = 10_000L))

        throttle.retainSymbols(setOf("ETH-USDT"))

        assertTrue(throttle.shouldDispatch("BTC-USDT", nowMillis = 10_001L))
    }

    @Test
    fun clearRemovesAllThrottleState() {
        assertTrue(throttle.shouldDispatch("BTC-USDT", nowMillis = 10_000L))

        throttle.clear()

        assertTrue(throttle.shouldDispatch("BTC-USDT", nowMillis = 10_001L))
    }
}
