package com.tzt.btcmonitor.data.market

import com.tzt.btcmonitor.domain.market.CandlePage
import com.tzt.btcmonitor.domain.market.CandlePageRequest
import com.tzt.btcmonitor.domain.market.InstrumentId
import com.tzt.btcmonitor.domain.market.MarketError
import com.tzt.btcmonitor.domain.market.MarketErrorKind
import com.tzt.btcmonitor.domain.market.MarketInstrument
import com.tzt.btcmonitor.domain.market.MarketQuote
import com.tzt.btcmonitor.domain.market.MarketRealtimeState
import com.tzt.btcmonitor.domain.market.MarketRepository
import com.tzt.btcmonitor.domain.market.MarketResult
import com.tzt.btcmonitor.domain.market.MarketTimeframe
import com.tzt.btcmonitor.model.MarketCandle
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class FakeMarketRepository : MarketRepository {
    private val mutableRealtime = MutableStateFlow(MarketRealtimeState())
    private val quotes = mutableMapOf<InstrumentId, MarketQuote>()
    private val histories = mutableMapOf<HistoryKey, List<MarketCandle>>()

    override val realtime: StateFlow<MarketRealtimeState> = mutableRealtime.asStateFlow()

    var loadDelayMillis: Long = 0L
    var nextFailure: MarketError? = null
    var realtimeInstruments: Set<MarketInstrument> = emptySet()
        private set
    val candleRequests = mutableListOf<CandlePageRequest>()

    fun setQuote(quote: MarketQuote) {
        quotes[quote.instrument.id] = quote
    }

    fun setCandles(
        instrument: MarketInstrument,
        timeframe: MarketTimeframe,
        candles: Iterable<MarketCandle>
    ) {
        histories[HistoryKey(instrument.id, timeframe)] = candles
            .associateBy(MarketCandle::openTimeMillis)
            .values
            .sortedBy(MarketCandle::openTimeMillis)
    }

    fun emitRealtime(state: MarketRealtimeState) {
        mutableRealtime.value = state
    }

    override suspend fun setRealtimeInstruments(
        instruments: Set<MarketInstrument>
    ): MarketResult<Unit> {
        delayIfConfigured()
        failureOrNull()?.let { return it }
        realtimeInstruments = instruments.toSet()
        return MarketResult.Success(Unit)
    }

    override suspend fun loadQuote(instrument: MarketInstrument): MarketResult<MarketQuote> {
        delayIfConfigured()
        failureOrNull()?.let { return it }
        return quotes[instrument.id]
            ?.let { MarketResult.Success(it) }
            ?: MarketResult.Failure(
                MarketError(
                    kind = MarketErrorKind.NOT_FOUND,
                    message = "No quote configured for ${instrument.id.value}",
                    retryable = false
                )
            )
    }

    override suspend fun loadCandles(request: CandlePageRequest): MarketResult<CandlePage> {
        candleRequests += request
        delayIfConfigured()
        failureOrNull()?.let { return it }

        val eligible = histories[HistoryKey(request.instrument.id, request.timeframe)]
            .orEmpty()
            .filter { candle ->
                request.beforeExclusiveMillis?.let { candle.openTimeMillis < it } ?: true
            }
        val selected = eligible.takeLast(request.limit)
        return MarketResult.Success(
            CandlePage.create(
                request = request,
                candles = selected,
                hasMore = eligible.size > selected.size
            )
        )
    }

    private suspend fun delayIfConfigured() {
        if (loadDelayMillis > 0L) delay(loadDelayMillis)
    }

    private fun failureOrNull(): MarketResult<Nothing>? = nextFailure
        ?.also { nextFailure = null }
        ?.let { MarketResult.Failure(it) }

    private data class HistoryKey(
        val instrumentId: InstrumentId,
        val timeframe: MarketTimeframe
    )
}
