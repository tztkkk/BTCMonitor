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
import com.tzt.btcmonitor.domain.market.MarketSymbol
import com.tzt.btcmonitor.domain.market.MarketTimeframe
import com.tzt.btcmonitor.market.OkxMarketRestDataSource
import com.tzt.btcmonitor.model.CandleTimeframe
import java.io.IOException
import java.net.SocketTimeoutException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** REST implementation of the TASK-004 boundary; realtime remains owned by the foreground service. */
class OkxRestMarketRepository(
    private val dataSource: OkxMarketRestDataSource
) : MarketRepository {
    private val mutableRealtime = MutableStateFlow(MarketRealtimeState())
    override val realtime: StateFlow<MarketRealtimeState> = mutableRealtime.asStateFlow()

    override suspend fun setRealtimeInstruments(
        instruments: Set<MarketInstrument>
    ): MarketResult<Unit> = MarketResult.Failure(
        MarketError(
            kind = MarketErrorKind.SOURCE,
            message = "实时订阅仍由 MarketMonitorService 管理",
            retryable = false
        )
    )

    override suspend fun loadQuote(instrument: MarketInstrument): MarketResult<MarketQuote> =
        marketResult {
            val quote = dataSource.loadQuote(instrument.symbol.value)
            MarketQuote(
                instrument = instrument,
                price = quote.price,
                open24h = quote.open24h,
                receivedTimeMillis = quote.receivedTimeMillis
            )
        }

    override suspend fun loadCandles(request: CandlePageRequest): MarketResult<CandlePage> =
        marketResult {
            val candles = dataSource.loadCandlePage(
                symbol = request.instrument.symbol.value,
                timeframe = request.timeframe.toLegacyTimeframe(),
                beforeExclusiveMillis = request.beforeExclusiveMillis,
                limit = request.limit
            )
            CandlePage.create(
                request = request,
                candles = candles,
                hasMore = candles.size == request.limit
            )
        }

    private suspend fun <T> marketResult(block: suspend () -> T): MarketResult<T> = try {
        MarketResult.Success(block())
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Throwable) {
        MarketResult.Failure(error.toMarketError())
    }
}

fun okxMarketInstrument(symbol: String): MarketInstrument = MarketInstrument(
    id = InstrumentId("okx:$symbol"),
    symbol = MarketSymbol(symbol)
)

fun CandleTimeframe.toMarketTimeframe(): MarketTimeframe = when (this) {
    CandleTimeframe.ONE_MINUTE -> MarketTimeframe.ONE_MINUTE
    CandleTimeframe.FIVE_MINUTES -> MarketTimeframe.FIVE_MINUTES
    CandleTimeframe.FIFTEEN_MINUTES -> MarketTimeframe.FIFTEEN_MINUTES
    CandleTimeframe.ONE_HOUR -> MarketTimeframe.ONE_HOUR
    CandleTimeframe.FOUR_HOURS -> MarketTimeframe.FOUR_HOURS
    CandleTimeframe.ONE_DAY -> MarketTimeframe.ONE_DAY
}

private fun MarketTimeframe.toLegacyTimeframe(): CandleTimeframe = when (this) {
    MarketTimeframe.ONE_MINUTE -> CandleTimeframe.ONE_MINUTE
    MarketTimeframe.FIVE_MINUTES -> CandleTimeframe.FIVE_MINUTES
    MarketTimeframe.FIFTEEN_MINUTES -> CandleTimeframe.FIFTEEN_MINUTES
    MarketTimeframe.ONE_HOUR -> CandleTimeframe.ONE_HOUR
    MarketTimeframe.FOUR_HOURS -> CandleTimeframe.FOUR_HOURS
    MarketTimeframe.ONE_DAY -> CandleTimeframe.ONE_DAY
}

private fun Throwable.toMarketError(): MarketError = when (this) {
    is SocketTimeoutException -> MarketError(MarketErrorKind.TIMEOUT, messageOrFallback(), true)
    is IllegalArgumentException -> MarketError(MarketErrorKind.INVALID_REQUEST, messageOrFallback(), false)
    is IOException -> MarketError(MarketErrorKind.NETWORK, messageOrFallback(), true)
    else -> MarketError(MarketErrorKind.SOURCE, messageOrFallback(), false)
}

private fun Throwable.messageOrFallback(): String = message?.takeIf(String::isNotBlank)
    ?: "行情请求失败"
