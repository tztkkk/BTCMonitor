package com.tzt.btcmonitor.ui.chart

data class InteractiveCandleChartCallbacks(
    val onLoadOlder: (ChartViewportAnchor) -> Unit,
    val onCreateAlert: (Double) -> Unit,
    val onMoveAlert: (alertId: String, price: Double) -> Unit,
    val onViewportChanged: (ChartViewport) -> Unit
) {
    companion object {
        val None = InteractiveCandleChartCallbacks(
            onLoadOlder = {},
            onCreateAlert = {},
            onMoveAlert = { _, _ -> },
            onViewportChanged = {}
        )
    }
}

sealed interface ChartOutputEvent {
    data class LoadOlder(val anchor: ChartViewportAnchor) : ChartOutputEvent
    data class CreateAlert(val price: Double) : ChartOutputEvent
    data class MoveAlert(val alertId: String, val price: Double) : ChartOutputEvent
    data class ViewportChanged(val viewport: ChartViewport) : ChartOutputEvent
}

fun InteractiveCandleChartCallbacks.dispatch(event: ChartOutputEvent) {
    when (event) {
        is ChartOutputEvent.LoadOlder -> onLoadOlder(event.anchor)
        is ChartOutputEvent.CreateAlert -> onCreateAlert(event.price)
        is ChartOutputEvent.MoveAlert -> onMoveAlert(event.alertId, event.price)
        is ChartOutputEvent.ViewportChanged -> onViewportChanged(event.viewport)
    }
}
