package com.tzt.btcmonitor.ui

import com.tzt.btcmonitor.data.market.okxMarketInstrument
import com.tzt.btcmonitor.data.market.toMarketTimeframe
import com.tzt.btcmonitor.domain.market.CandlePageRequest
import com.tzt.btcmonitor.domain.market.MarketRepository
import com.tzt.btcmonitor.domain.market.MarketResult
import com.tzt.btcmonitor.model.CandleTimeframe
import com.tzt.btcmonitor.model.MarketCandle
import com.tzt.btcmonitor.ui.chart.ChartViewportAnchor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class CandleChartUiState(
    val symbol: String = "BTC-USDT",
    val timeframe: CandleTimeframe = CandleTimeframe.FIVE_MINUTES,
    val candles: List<MarketCandle> = emptyList(),
    val loading: Boolean = false,
    val error: String? = null,
    val hasMore: Boolean = false,
    val loadingOlder: Boolean = false,
    val olderError: String? = null,
    val loadedAtMillis: Long? = null
)

class CandleChartStateHolder(
    private val repository: MarketRepository,
    private val scope: CoroutineScope,
    private val nowMillis: () -> Long = System::currentTimeMillis
) {
    private val mutableState = MutableStateFlow(CandleChartUiState())
    private var requestJob: Job? = null

    val state: StateFlow<CandleChartUiState> = mutableState.asStateFlow()

    fun loadInitial(
        symbol: String = mutableState.value.symbol,
        timeframe: CandleTimeframe = mutableState.value.timeframe
    ): Job {
        requestJob?.cancel()
        val current = mutableState.value
        val sameTarget = current.symbol == symbol && current.timeframe == timeframe
        mutableState.value = current.copy(
            symbol = symbol,
            timeframe = timeframe,
            candles = if (sameTarget) current.candles else emptyList(),
            loading = true,
            error = null,
            hasMore = if (sameTarget) current.hasMore else false,
            loadingOlder = false,
            olderError = null
        )
        return scope.launch {
            val request = request(symbol, timeframe)
            when (val result = repository.loadCandles(request)) {
                is MarketResult.Success -> updateIfCurrent(symbol, timeframe) { state ->
                    state.copy(
                        candles = result.value.candles,
                        loading = false,
                        error = null,
                        hasMore = result.value.hasMore,
                        loadedAtMillis = nowMillis()
                    )
                }

                is MarketResult.Failure -> updateIfCurrent(symbol, timeframe) { state ->
                    state.copy(loading = false, error = result.error.message)
                }
            }
        }.also { requestJob = it }
    }

    fun loadOlder(anchor: ChartViewportAnchor? = null): Job? {
        val current = mutableState.value
        if (
            current.loading || current.loadingOlder || !current.hasMore || current.candles.isEmpty() ||
            (anchor != null && current.candles.none { it.openTimeMillis == anchor.candleOpenTimeMillis })
        ) {
            return null
        }
        val symbol = current.symbol
        val timeframe = current.timeframe
        val beforeExclusiveMillis = current.candles.first().openTimeMillis
        mutableState.value = current.copy(loadingOlder = true, olderError = null)
        return scope.launch {
            val request = request(symbol, timeframe, beforeExclusiveMillis)
            when (val result = repository.loadCandles(request)) {
                is MarketResult.Success -> updateIfCurrent(symbol, timeframe) { state ->
                    state.copy(
                        candles = (result.value.candles + state.candles).canonicalCandles(),
                        hasMore = result.value.hasMore,
                        loadingOlder = false,
                        olderError = null,
                        loadedAtMillis = nowMillis()
                    )
                }

                is MarketResult.Failure -> updateIfCurrent(symbol, timeframe) { state ->
                    state.copy(
                        loadingOlder = false,
                        olderError = result.error.message
                    )
                }
            }
        }.also { requestJob = it }
    }

    private fun request(
        symbol: String,
        timeframe: CandleTimeframe,
        beforeExclusiveMillis: Long? = null
    ) = CandlePageRequest(
        instrument = okxMarketInstrument(symbol),
        timeframe = timeframe.toMarketTimeframe(),
        beforeExclusiveMillis = beforeExclusiveMillis
    )

    private inline fun updateIfCurrent(
        symbol: String,
        timeframe: CandleTimeframe,
        update: (CandleChartUiState) -> CandleChartUiState
    ) {
        val current = mutableState.value
        if (current.symbol == symbol && current.timeframe == timeframe) {
            mutableState.value = update(current)
        }
    }
}

private fun Iterable<MarketCandle>.canonicalCandles(): List<MarketCandle> =
    associateBy(MarketCandle::openTimeMillis).values.sortedBy(MarketCandle::openTimeMillis)
