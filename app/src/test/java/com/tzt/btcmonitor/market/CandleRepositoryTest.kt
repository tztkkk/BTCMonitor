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
}
