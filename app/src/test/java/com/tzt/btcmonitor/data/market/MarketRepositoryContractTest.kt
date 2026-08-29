package com.tzt.btcmonitor.data.market

import com.tzt.btcmonitor.domain.market.CandlePageRequest
import com.tzt.btcmonitor.domain.market.InstrumentId
import com.tzt.btcmonitor.domain.market.MarketConnection
import com.tzt.btcmonitor.domain.market.MarketError
import com.tzt.btcmonitor.domain.market.MarketErrorKind
import com.tzt.btcmonitor.domain.market.MarketFreshness
import com.tzt.btcmonitor.domain.market.MarketInstrument
import com.tzt.btcmonitor.domain.market.MarketQuote
import com.tzt.btcmonitor.domain.market.MarketRealtimeState
import com.tzt.btcmonitor.domain.market.MarketResult
import com.tzt.btcmonitor.domain.market.MarketSymbol
import com.tzt.btcmonitor.domain.market.MarketTimeframe
import com.tzt.btcmonitor.model.MarketCandle
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MarketRepositoryContractTest {
    private val btc = MarketInstrument(
        id = InstrumentId("provider:BTC-USDT"),
        symbol = MarketSymbol("BTC-USDT")
    )

    @Test
    fun candlePagesAreExclusiveSortedDeduplicatedAndMoveBackward() = runBlocking {
        val repository = FakeMarketRepository().apply {
            setCandles(
                instrument = btc,
                timeframe = MarketTimeframe.FIVE_MINUTES,
                candles = listOf(
                    candle(4_000L, close = 4.0),
                    candle(2_000L, close = 2.0),
                    candle(3_000L, close = 3.0),
                    candle(2_000L, close = 2.5),
                    candle(1_000L, close = 1.0)
                )
            )
        }

        val first = repository.loadCandles(request(limit = 2)).successValue()
        assertEquals(listOf(3_000L, 4_000L), first.candles.map { it.openTimeMillis })
        assertTrue(first.hasMore)
        assertEquals(3_000L, first.nextBeforeExclusiveMillis)

        val second = repository.loadCandles(
            request(beforeExclusiveMillis = first.nextBeforeExclusiveMillis, limit = 3)
        ).successValue()
        assertEquals(listOf(1_000L, 2_000L), second.candles.map { it.openTimeMillis })
        assertEquals(2.5, second.candles.last().close, 0.0)
        assertFalse(second.hasMore)
        assertNull(second.nextBeforeExclusiveMillis)
        assertTrue(first.candles.map { it.openTimeMillis }.intersect(second.candles.map { it.openTimeMillis }.toSet()).isEmpty())
    }

    @Test
    fun realtimeStateCarriesQuotesConnectionFreshnessAndError() = runBlocking {
        val repository = FakeMarketRepository()
        val quote = MarketQuote(
            instrument = btc,
            price = 100.0,
            open24h = 90.0,
            exchangeTimeMillis = 9_000L,
            receivedTimeMillis = 10_000L
        )
        val error = MarketError(MarketErrorKind.NETWORK, "temporarily offline", retryable = true)

        repository.setQuote(quote)
        repository.setRealtimeInstruments(setOf(btc))
        repository.emitRealtime(
            MarketRealtimeState(
                quotes = mapOf(btc.id to quote),
                connection = MarketConnection.RECONNECTING,
                freshness = mapOf(btc.id to MarketFreshness.STALE),
                error = error
            )
        )

        assertEquals(setOf(btc), repository.realtimeInstruments)
        assertEquals(quote, repository.loadQuote(btc).successValue())
        assertEquals(quote, repository.realtime.value.quotes[btc.id])
        assertEquals(MarketConnection.RECONNECTING, repository.realtime.value.connection)
        assertEquals(MarketFreshness.STALE, repository.realtime.value.freshness[btc.id])
        assertEquals(error, repository.realtime.value.error)
    }

    @Test
    fun failuresAreValuesAndDoNotLoseRetrySemantics() = runBlocking {
        val repository = FakeMarketRepository().apply {
            nextFailure = MarketError(MarketErrorKind.TIMEOUT, "request timed out", retryable = true)
        }

        val result = repository.loadQuote(btc)

        assertTrue(result is MarketResult.Failure)
        result as MarketResult.Failure
        assertEquals(MarketErrorKind.TIMEOUT, result.error.kind)
        assertTrue(result.error.retryable)
    }

    @Test
    fun suspendRequestsPropagateCancellation() = runBlocking {
        val repository = FakeMarketRepository().apply { loadDelayMillis = 60_000L }
        var completed = false
        val job = launch {
            repository.loadCandles(request())
            completed = true
        }
        yield()

        job.cancelAndJoin()

        assertTrue(job.isCancelled)
        assertFalse(completed)
    }

    private fun request(
        beforeExclusiveMillis: Long? = null,
        limit: Int = 80
    ) = CandlePageRequest(
        instrument = btc,
        timeframe = MarketTimeframe.FIVE_MINUTES,
        beforeExclusiveMillis = beforeExclusiveMillis,
        limit = limit
    )

    private fun candle(openTimeMillis: Long, close: Double) = MarketCandle(
        openTimeMillis = openTimeMillis,
        open = close,
        high = close + 1.0,
        low = close - 1.0,
        close = close,
        volume = 10.0,
        confirmed = true
    )

    private fun <T> MarketResult<T>.successValue(): T {
        assertTrue("Expected success but was $this", this is MarketResult.Success)
        return (this as MarketResult.Success).value
    }
}
