package com.tzt.btcmonitor.domain.market

import kotlinx.coroutines.flow.StateFlow

/**
 * Provider-neutral market data boundary.
 *
 * Implementations must keep [realtime] as the latest observable state and must not create one
 * upstream connection per collector. Setting realtime instruments replaces the repository's
 * desired subscription set; the production owner remains responsible for repository lifecycle.
 *
 * Candle pages are ordered oldest-to-newest and contain at most one candle for each open time.
 * [CandlePageRequest.beforeExclusiveMillis] excludes that timestamp from the returned page.
 * Suspend function cancellation must propagate and must not be converted to [MarketResult.Failure].
 */
interface MarketRepository {
    val realtime: StateFlow<MarketRealtimeState>

    suspend fun setRealtimeInstruments(
        instruments: Set<MarketInstrument>
    ): MarketResult<Unit>

    suspend fun loadQuote(instrument: MarketInstrument): MarketResult<MarketQuote>

    suspend fun loadCandles(request: CandlePageRequest): MarketResult<CandlePage>
}
