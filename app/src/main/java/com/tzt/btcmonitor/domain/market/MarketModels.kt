package com.tzt.btcmonitor.domain.market

import com.tzt.btcmonitor.model.MarketCandle

@JvmInline
value class InstrumentId(val value: String) {
    init {
        require(value.isNotBlank()) { "Instrument ID must not be blank" }
    }
}

@JvmInline
value class MarketSymbol(val value: String) {
    init {
        require(value.isNotBlank()) { "Market symbol must not be blank" }
    }
}

data class MarketInstrument(
    val id: InstrumentId,
    val symbol: MarketSymbol
)

enum class MarketTimeframe(val durationMillis: Long) {
    ONE_MINUTE(60_000L),
    FIVE_MINUTES(5 * 60_000L),
    FIFTEEN_MINUTES(15 * 60_000L),
    ONE_HOUR(60 * 60_000L),
    FOUR_HOURS(4 * 60 * 60_000L),
    ONE_DAY(24 * 60 * 60_000L)
}

data class MarketQuote(
    val instrument: MarketInstrument,
    val price: Double,
    val open24h: Double? = null,
    val exchangeTimeMillis: Long? = null,
    val receivedTimeMillis: Long
) {
    init {
        require(price.isFinite() && price > 0.0) { "Quote price must be positive and finite" }
        require(open24h == null || open24h.isFinite()) { "24h open must be finite when present" }
        require(exchangeTimeMillis == null || exchangeTimeMillis > 0L) {
            "Exchange time must be positive when present"
        }
        require(receivedTimeMillis > 0L) { "Received time must be positive" }
    }

    val changePercent24h: Double?
        get() = open24h?.takeIf { it != 0.0 }?.let { (price - it) / it * 100.0 }
}

enum class MarketConnection {
    DISCONNECTED,
    CONNECTING,
    CONNECTED,
    RECONNECTING
}

enum class MarketFreshness {
    UNKNOWN,
    FRESH,
    STALE
}

enum class MarketErrorKind {
    NETWORK,
    TIMEOUT,
    INVALID_REQUEST,
    SOURCE,
    NOT_FOUND,
    UNKNOWN
}

data class MarketError(
    val kind: MarketErrorKind,
    val message: String,
    val retryable: Boolean
) {
    init {
        require(message.isNotBlank()) { "Market error message must not be blank" }
    }
}

sealed interface MarketResult<out T> {
    data class Success<T>(val value: T) : MarketResult<T>
    data class Failure(val error: MarketError) : MarketResult<Nothing>
}

data class MarketRealtimeState(
    val quotes: Map<InstrumentId, MarketQuote> = emptyMap(),
    val connection: MarketConnection = MarketConnection.DISCONNECTED,
    val freshness: Map<InstrumentId, MarketFreshness> = emptyMap(),
    val error: MarketError? = null
) {
    init {
        require(quotes.all { (id, quote) -> id == quote.instrument.id }) {
            "Quote map keys must match quote instrument IDs"
        }
    }
}

data class CandlePageRequest(
    val instrument: MarketInstrument,
    val timeframe: MarketTimeframe,
    val beforeExclusiveMillis: Long? = null,
    val limit: Int = DEFAULT_CANDLE_PAGE_LIMIT
) {
    init {
        require(beforeExclusiveMillis == null || beforeExclusiveMillis > 0L) {
            "Candle cursor must be positive when present"
        }
        require(limit in 1..MAX_CANDLE_PAGE_LIMIT) {
            "Candle page limit must be between 1 and $MAX_CANDLE_PAGE_LIMIT"
        }
    }
}

class CandlePage private constructor(
    val request: CandlePageRequest,
    val candles: List<MarketCandle>,
    val hasMore: Boolean
) {
    val nextBeforeExclusiveMillis: Long? =
        candles.firstOrNull()?.openTimeMillis?.takeIf { hasMore }

    companion object {
        fun create(
            request: CandlePageRequest,
            candles: Iterable<MarketCandle>,
            hasMore: Boolean
        ): CandlePage {
            val canonical = candles
                .associateBy(MarketCandle::openTimeMillis)
                .values
                .sortedBy(MarketCandle::openTimeMillis)
            require(canonical.size <= request.limit) { "Candle page exceeds requested limit" }
            require(
                request.beforeExclusiveMillis == null ||
                    canonical.all { it.openTimeMillis < request.beforeExclusiveMillis }
            ) { "Candle page contains an item at or after its exclusive cursor" }
            require(!hasMore || canonical.isNotEmpty()) { "An empty candle page cannot have more history" }
            return CandlePage(request, canonical, hasMore)
        }
    }
}

const val DEFAULT_CANDLE_PAGE_LIMIT = 80
const val MAX_CANDLE_PAGE_LIMIT = 300
