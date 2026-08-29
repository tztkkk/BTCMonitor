package com.tzt.btcmonitor.market

internal class SymbolDispatchThrottle(
    private val intervalMillis: Long
) {
    private val lastDispatchMillisBySymbol = mutableMapOf<String, Long>()

    @Synchronized
    fun shouldDispatch(symbol: String, nowMillis: Long): Boolean {
        val lastDispatchMillis = lastDispatchMillisBySymbol[symbol]
        if (lastDispatchMillis != null && nowMillis - lastDispatchMillis < intervalMillis) {
            return false
        }
        lastDispatchMillisBySymbol[symbol] = nowMillis
        return true
    }

    @Synchronized
    fun retainSymbols(symbols: Set<String>) {
        lastDispatchMillisBySymbol.keys.retainAll(symbols)
    }

    @Synchronized
    fun clear() {
        lastDispatchMillisBySymbol.clear()
    }
}
