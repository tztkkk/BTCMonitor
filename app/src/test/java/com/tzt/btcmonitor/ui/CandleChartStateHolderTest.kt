package com.tzt.btcmonitor.ui

import com.tzt.btcmonitor.domain.market.CandlePage
import com.tzt.btcmonitor.domain.market.CandlePageRequest
import com.tzt.btcmonitor.domain.market.MarketError
import com.tzt.btcmonitor.domain.market.MarketErrorKind
import com.tzt.btcmonitor.domain.market.MarketInstrument
import com.tzt.btcmonitor.domain.market.MarketQuote
import com.tzt.btcmonitor.domain.market.MarketRealtimeState
import com.tzt.btcmonitor.domain.market.MarketRepository
import com.tzt.btcmonitor.domain.market.MarketResult
import com.tzt.btcmonitor.model.CandleTimeframe
import com.tzt.btcmonitor.model.MarketCandle
import com.tzt.btcmonitor.ui.chart.ChartViewportAnchor
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CandleChartStateHolderTest {
    @Test
    fun initialAndOlderPagesMergeSortedAndUseExclusiveOldestCursor() = runBlocking {
        val repository = ScriptedRepository { request ->
            if (request.beforeExclusiveMillis == null) {
                page(request, listOf(candle(3_000L), candle(4_000L)), hasMore = true)
            } else {
                page(request, listOf(candle(1_000L), candle(2_000L)), hasMore = false)
            }
        }
        val holder = CandleChartStateHolder(repository, this, nowMillis = { 9_000L })

        holder.loadInitial("BTC-USDT", CandleTimeframe.FIVE_MINUTES).join()
        val anchor = ChartViewportAnchor(3_000L, 0.0)
        holder.loadOlder(anchor)?.join()

        assertEquals(listOf(1_000L, 2_000L, 3_000L, 4_000L), holder.state.value.candles.map { it.openTimeMillis })
        assertEquals(listOf(null, 3_000L), repository.requests.map { it.beforeExclusiveMillis })
        assertFalse(holder.state.value.hasMore)
        assertFalse(holder.state.value.loadingOlder)
        assertEquals(9_000L, holder.state.value.loadedAtMillis)
    }

    @Test
    fun onlyOneOlderRequestRunsAtATime() = runBlocking {
        val olderGate = CompletableDeferred<Unit>()
        val repository = ScriptedRepository { request ->
            if (request.beforeExclusiveMillis == null) {
                page(request, listOf(candle(3_000L), candle(4_000L)), hasMore = true)
            } else {
                olderGate.await()
                page(request, listOf(candle(1_000L), candle(2_000L)), hasMore = false)
            }
        }
        val holder = CandleChartStateHolder(repository, this)
        holder.loadInitial().join()

        val first = holder.loadOlder(ChartViewportAnchor(3_000L, 0.0))
        yield()
        val duplicate = holder.loadOlder(ChartViewportAnchor(3_000L, 0.0))

        assertNull(duplicate)
        assertTrue(holder.state.value.loadingOlder)
        assertEquals(2, repository.requests.size)
        olderGate.complete(Unit)
        first?.join()
        Unit
    }

    @Test
    fun olderFailureKeepsCurrentCandlesAndCanRetry() = runBlocking {
        var failOlder = true
        val repository = ScriptedRepository { request ->
            if (request.beforeExclusiveMillis == null) {
                page(request, listOf(candle(3_000L), candle(4_000L)), hasMore = true)
            } else if (failOlder) {
                failOlder = false
                MarketResult.Failure(MarketError(MarketErrorKind.NETWORK, "offline", true))
            } else {
                page(request, listOf(candle(1_000L), candle(2_000L)), hasMore = false)
            }
        }
        val holder = CandleChartStateHolder(repository, this)
        holder.loadInitial().join()

        holder.loadOlder(ChartViewportAnchor(3_000L, 0.0))?.join()
        assertEquals(listOf(3_000L, 4_000L), holder.state.value.candles.map { it.openTimeMillis })
        assertEquals("offline", holder.state.value.olderError)

        holder.loadOlder(ChartViewportAnchor(3_000L, 0.0))?.join()
        assertEquals(listOf(1_000L, 2_000L, 3_000L, 4_000L), holder.state.value.candles.map { it.openTimeMillis })
        assertNull(holder.state.value.olderError)
    }

    @Test
    fun switchingTargetCancelsOldRequestAndCannotPublishItsCandles() = runBlocking {
        var btcCancelled = false
        val repository = ScriptedRepository { request ->
            if (request.instrument.symbol.value == "BTC-USDT") {
                try {
                    awaitCancellation()
                } finally {
                    btcCancelled = true
                }
            }
            page(request, listOf(candle(7_000L)), hasMore = false)
        }
        val holder = CandleChartStateHolder(repository, this)
        holder.loadInitial("BTC-USDT", CandleTimeframe.ONE_MINUTE)
        yield()

        holder.loadInitial("ETH-USDT", CandleTimeframe.ONE_HOUR).join()

        assertTrue(btcCancelled)
        assertEquals("ETH-USDT", holder.state.value.symbol)
        assertEquals(CandleTimeframe.ONE_HOUR, holder.state.value.timeframe)
        assertEquals(listOf(7_000L), holder.state.value.candles.map { it.openTimeMillis })
    }

    private class ScriptedRepository(
        private val response: suspend (CandlePageRequest) -> MarketResult<CandlePage>
    ) : MarketRepository {
        override val realtime: StateFlow<MarketRealtimeState> = MutableStateFlow(MarketRealtimeState())
        val requests = mutableListOf<CandlePageRequest>()

        override suspend fun setRealtimeInstruments(
            instruments: Set<MarketInstrument>
        ): MarketResult<Unit> = MarketResult.Success(Unit)

        override suspend fun loadQuote(instrument: MarketInstrument): MarketResult<MarketQuote> =
            error("Not used")

        override suspend fun loadCandles(request: CandlePageRequest): MarketResult<CandlePage> {
            requests += request
            return response(request)
        }
    }

    private fun page(
        request: CandlePageRequest,
        candles: List<MarketCandle>,
        hasMore: Boolean
    ): MarketResult<CandlePage> = MarketResult.Success(CandlePage.create(request, candles, hasMore))

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
