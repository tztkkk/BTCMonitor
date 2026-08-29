package com.tzt.btcmonitor.data.market

import com.tzt.btcmonitor.domain.market.CandlePageRequest
import com.tzt.btcmonitor.domain.market.MarketErrorKind
import com.tzt.btcmonitor.domain.market.MarketResult
import com.tzt.btcmonitor.domain.market.MarketTimeframe
import com.tzt.btcmonitor.market.OkxMarketRestDataSource
import com.tzt.btcmonitor.model.AssetQuote
import com.tzt.btcmonitor.model.CandleTimeframe
import com.tzt.btcmonitor.model.MarketCandle
import java.net.SocketTimeoutException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OkxRestMarketRepositoryTest {
    @Test
    fun mapsExclusiveCursorAndReturnsCanonicalPage() = runBlocking {
        val source = FakeRestDataSource(
            candles = listOf(candle(2_000L), candle(1_000L), candle(2_000L))
        )
        val repository = OkxRestMarketRepository(source)
        val request = CandlePageRequest(
            instrument = okxMarketInstrument("BTC-USDT"),
            timeframe = MarketTimeframe.FIVE_MINUTES,
            beforeExclusiveMillis = 3_000L,
            limit = 3
        )

        val result = repository.loadCandles(request) as MarketResult.Success

        assertEquals("BTC-USDT", source.lastSymbol)
        assertEquals(CandleTimeframe.FIVE_MINUTES, source.lastTimeframe)
        assertEquals(3_000L, source.lastBeforeExclusiveMillis)
        assertEquals(listOf(1_000L, 2_000L), result.value.candles.map { it.openTimeMillis })
        assertTrue(result.value.hasMore)
        assertEquals(1_000L, result.value.nextBeforeExclusiveMillis)
    }

    @Test
    fun mapsTimeoutButPropagatesCancellation() = runBlocking {
        val timeoutRepository = OkxRestMarketRepository(
            FakeRestDataSource(error = SocketTimeoutException("timeout"))
        )
        val request = CandlePageRequest(
            instrument = okxMarketInstrument("BTC-USDT"),
            timeframe = MarketTimeframe.ONE_MINUTE
        )

        val timeout = timeoutRepository.loadCandles(request) as MarketResult.Failure
        assertEquals(MarketErrorKind.TIMEOUT, timeout.error.kind)
        assertTrue(timeout.error.retryable)

        val cancellingRepository = OkxRestMarketRepository(
            FakeRestDataSource(error = CancellationException("cancelled"))
        )
        var propagated = false
        try {
            cancellingRepository.loadCandles(request)
        } catch (_: CancellationException) {
            propagated = true
        }
        assertTrue(propagated)
    }

    @Test
    fun realtimeSubscriptionIsExplicitlyNotOwnedByRestAdapter() = runBlocking {
        val repository = OkxRestMarketRepository(FakeRestDataSource())

        val result = repository.setRealtimeInstruments(setOf(okxMarketInstrument("BTC-USDT")))

        assertTrue(result is MarketResult.Failure)
        result as MarketResult.Failure
        assertEquals(MarketErrorKind.SOURCE, result.error.kind)
        assertFalse(result.error.retryable)
    }

    private class FakeRestDataSource(
        private val candles: List<MarketCandle> = emptyList(),
        private val error: Throwable? = null
    ) : OkxMarketRestDataSource {
        var lastSymbol: String? = null
        var lastTimeframe: CandleTimeframe? = null
        var lastBeforeExclusiveMillis: Long? = null

        override suspend fun loadCandlePage(
            symbol: String,
            timeframe: CandleTimeframe,
            beforeExclusiveMillis: Long?,
            limit: Int
        ): List<MarketCandle> {
            error?.let { throw it }
            lastSymbol = symbol
            lastTimeframe = timeframe
            lastBeforeExclusiveMillis = beforeExclusiveMillis
            return candles
        }

        override suspend fun loadQuote(symbol: String): AssetQuote {
            error?.let { throw it }
            return AssetQuote(symbol, 100.0, receivedTimeMillis = 1_000L)
        }
    }

    private fun candle(time: Long) = MarketCandle(
        openTimeMillis = time,
        open = 100.0,
        high = 101.0,
        low = 99.0,
        close = 100.5,
        volume = 1.0,
        confirmed = true
    )
}
