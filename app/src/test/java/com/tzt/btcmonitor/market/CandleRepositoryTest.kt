package com.tzt.btcmonitor.market

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CandleRepositoryTest {
    @Test
    fun parsesAndSortsOkxCandlesOldestFirst() {
        val body = """
            {"code":"0","msg":"","data":[
              ["2000","20","23","19","22","12","0","0","0"],
              ["1000","10","13","9","12","8","0","0","1"]
            ]}
        """.trimIndent()

        val result = OkxCandleParser.parse(body)

        assertEquals(listOf(1000L, 2000L), result.map { it.openTimeMillis })
        assertEquals(12.0, result.first().close, 0.0)
        assertTrue(result.first().confirmed)
        assertFalse(result.last().confirmed)
    }

    @Test
    fun parsesTickerSnapshotWithChangeBaseline() {
        val quote = OkxTickerParser.parse(
            """{"code":"0","msg":"","data":[{"instId":"ETH-USDT","last":"4200.5","open24h":"4000","ts":"2000"}]}"""
        )

        assertEquals("ETH-USDT", quote.symbol)
        assertEquals(4200.5, quote.price, 0.0)
        assertEquals(5.0125, quote.changePercent24h ?: 0.0, 0.0001)
    }
}
