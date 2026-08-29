package com.tzt.btcmonitor.ui.chart

data class ChartHistoryState(
    val hasMore: Boolean = false,
    val loadingOlder: Boolean = false,
    val error: String? = null
)

data class AlertLineDragState(
    val alertId: String,
    val originalPrice: Double,
    val candidatePrice: Double
)

data class InteractiveCandleChartState(
    val candles: List<ChartCandle> = emptyList(),
    val viewport: ChartViewport = ChartViewport(),
    val crosshair: CrosshairState = CrosshairState.Hidden,
    val alertLines: List<AlertLine> = emptyList(),
    val history: ChartHistoryState = ChartHistoryState(),
    val alertLineDrag: AlertLineDragState? = null
)

sealed interface ChartAction {
    data class ReplaceCandles(
        val candles: List<ChartCandle>,
        val hasMore: Boolean
    ) : ChartAction

    data class PanTo(val anchor: ChartViewportAnchor) : ChartAction
    data class ScaleTime(
        val visibleCandleCount: Int,
        val focalAnchor: ChartViewportAnchor? = null
    ) : ChartAction

    data class ScalePrice(val range: ChartPriceRange) : ChartAction
    data object FollowLatest : ChartAction
    data class ShowCrosshair(val candleOpenTimeMillis: Long, val price: Double) : ChartAction
    data class MoveCrosshair(val candleOpenTimeMillis: Long, val price: Double) : ChartAction
    data object PinCrosshair : ChartAction
    data object HideCrosshair : ChartAction
    data object RequestCreateAlert : ChartAction
    data object RequestLoadOlder : ChartAction

    data class OlderCandlesLoaded(
        val candles: List<ChartCandle>,
        val hasMore: Boolean
    ) : ChartAction

    data class OlderCandlesFailed(val message: String) : ChartAction
    data class BeginAlertLineDrag(val alertId: String) : ChartAction
    data class UpdateAlertLineDrag(val price: Double) : ChartAction
    data object EndAlertLineDrag : ChartAction
    data object CancelAlertLineDrag : ChartAction
}

data class ChartReduction(
    val state: InteractiveCandleChartState,
    val events: List<ChartOutputEvent> = emptyList()
)

object InteractiveCandleChartReducer {
    fun reduce(state: InteractiveCandleChartState, action: ChartAction): ChartReduction = when (action) {
        is ChartAction.ReplaceCandles -> ChartReduction(
            state.copy(
                candles = action.candles.canonicalChartCandles(),
                history = ChartHistoryState(hasMore = action.hasMore)
            )
        )

        is ChartAction.PanTo -> changeViewport(
            state,
            state.viewport.copy(anchor = action.anchor)
        )

        is ChartAction.ScaleTime -> changeViewport(
            state,
            state.viewport.copy(
                visibleCandleCount = action.visibleCandleCount,
                anchor = action.focalAnchor ?: state.viewport.anchor
            )
        )

        is ChartAction.ScalePrice -> changeViewport(
            state,
            state.viewport.copy(priceRange = action.range)
        )

        ChartAction.FollowLatest -> changeViewport(
            state,
            state.viewport.copy(anchor = null, priceRange = null)
        )

        is ChartAction.ShowCrosshair -> updateCrosshair(state, action.candleOpenTimeMillis, action.price)
        is ChartAction.MoveCrosshair -> updateCrosshair(state, action.candleOpenTimeMillis, action.price)
        ChartAction.PinCrosshair -> {
            val visible = state.crosshair as? CrosshairState.Visible
            ChartReduction(state.copy(crosshair = visible?.copy(pinned = true) ?: CrosshairState.Hidden))
        }

        ChartAction.HideCrosshair -> ChartReduction(state.copy(crosshair = CrosshairState.Hidden))
        ChartAction.RequestCreateAlert -> {
            val visible = state.crosshair as? CrosshairState.Visible
            ChartReduction(
                state,
                visible?.let { listOf(ChartOutputEvent.CreateAlert(it.selectedPrice)) }.orEmpty()
            )
        }

        ChartAction.RequestLoadOlder -> requestLoadOlder(state)
        is ChartAction.OlderCandlesLoaded -> ChartReduction(
            state.copy(
                candles = (state.candles + action.candles).canonicalChartCandles(),
                history = ChartHistoryState(hasMore = action.hasMore)
            )
        )

        is ChartAction.OlderCandlesFailed -> ChartReduction(
            state.copy(
                history = state.history.copy(
                    loadingOlder = false,
                    error = action.message.ifBlank { "Unable to load older candles" }
                )
            )
        )

        is ChartAction.BeginAlertLineDrag -> beginAlertLineDrag(state, action.alertId)
        is ChartAction.UpdateAlertLineDrag -> updateAlertLineDrag(state, action.price)
        ChartAction.EndAlertLineDrag -> endAlertLineDrag(state)
        ChartAction.CancelAlertLineDrag -> ChartReduction(state.copy(alertLineDrag = null))
    }

    private fun updateCrosshair(
        state: InteractiveCandleChartState,
        candleOpenTimeMillis: Long,
        price: Double
    ): ChartReduction {
        if (state.candles.none { it.openTimeMillis == candleOpenTimeMillis }) return ChartReduction(state)
        return ChartReduction(
            state.copy(
                crosshair = CrosshairState.Visible(
                    candleOpenTimeMillis = candleOpenTimeMillis,
                    selectedPrice = price,
                    pinned = false
                )
            )
        )
    }

    private fun changeViewport(
        state: InteractiveCandleChartState,
        viewport: ChartViewport
    ) = ChartReduction(
        state.copy(viewport = viewport),
        listOf(ChartOutputEvent.ViewportChanged(viewport))
    )

    private fun requestLoadOlder(state: InteractiveCandleChartState): ChartReduction {
        if (!state.history.hasMore || state.history.loadingOlder || state.candles.isEmpty()) {
            return ChartReduction(state)
        }
        val anchoredViewport = state.viewport.withStableAnchor(state.candles)
        val anchor = requireNotNull(anchoredViewport.anchor)
        return ChartReduction(
            state.copy(
                viewport = anchoredViewport,
                history = state.history.copy(loadingOlder = true, error = null)
            ),
            listOf(ChartOutputEvent.LoadOlder(anchor))
        )
    }

    private fun beginAlertLineDrag(
        state: InteractiveCandleChartState,
        alertId: String
    ): ChartReduction {
        val line = state.alertLines.firstOrNull { it.id == alertId && it.draggable }
            ?: return ChartReduction(state)
        return ChartReduction(
            state.copy(
                alertLineDrag = AlertLineDragState(
                    alertId = line.id,
                    originalPrice = line.price,
                    candidatePrice = line.price
                )
            )
        )
    }

    private fun updateAlertLineDrag(
        state: InteractiveCandleChartState,
        price: Double
    ): ChartReduction {
        val drag = state.alertLineDrag ?: return ChartReduction(state)
        if (!price.isFinite() || price <= 0.0) return ChartReduction(state)
        return ChartReduction(state.copy(alertLineDrag = drag.copy(candidatePrice = price)))
    }

    private fun endAlertLineDrag(state: InteractiveCandleChartState): ChartReduction {
        val drag = state.alertLineDrag ?: return ChartReduction(state)
        val event = if (drag.candidatePrice != drag.originalPrice) {
            listOf(ChartOutputEvent.MoveAlert(drag.alertId, drag.candidatePrice))
        } else {
            emptyList()
        }
        return ChartReduction(state.copy(alertLineDrag = null), event)
    }
}
