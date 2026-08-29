package com.tzt.btcmonitor.ui.chart

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp

/** Debug-only deterministic host for TASK-008 renderer verification. */
class Task008RendererTestHostActivity : ComponentActivity() {
    private var forcedFontScale by mutableFloatStateOf(1f)

    override fun onCreate(savedInstanceState: Bundle?) {
        setShowWhenLocked(true)
        setTurnScreenOn(true)
        super.onCreate(savedInstanceState)
        updateFontScale(intent)
        setContent {
            val deviceDensity = LocalDensity.current
            CompositionLocalProvider(
                LocalDensity provides Density(deviceDensity.density, forcedFontScale)
            ) {
                MaterialTheme(colorScheme = darkColorScheme()) {
                    Surface(modifier = Modifier.fillMaxSize()) {
                        Task008RendererTestHost()
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        updateFontScale(intent)
    }

    private fun updateFontScale(intent: Intent) {
        forcedFontScale = intent.getFloatExtra(FONT_SCALE_EXTRA, 1f).coerceIn(1f, 1.3f)
    }

    companion object {
        const val FONT_SCALE_EXTRA = "task009_font_scale"
    }
}

@Composable
private fun Task008RendererTestHost() {
    val candles = remember {
        List(200) { index ->
            val open = 65_000.0 + index * 6.0 + (index % 9 - 4) * 12.0
            val close = open + if (index % 2 == 0) 28.0 else -22.0
            ChartCandle(
                openTimeMillis = 1_700_000_000_000L + index * 60_000L,
                open = open,
                high = maxOf(open, close) + 18.0,
                low = minOf(open, close) - 16.0,
                close = close,
                volume = 100.0 + index,
                confirmed = true
            )
        }
    }
    InteractiveCandleChart(
        state = CandleChartRenderState.Ready(
            InteractiveCandleChartState(
                candles = candles,
                alertLines = listOf(
                    AlertLine("task008-in-range", "范围内提醒", candles.last().close, true),
                    AlertLine("task008-outside", "图外提醒", candles.last().high + 10_000.0, true),
                    AlertLine("task008-disabled", "停用提醒", candles.last().low, false)
                )
            )
        ),
        modifier = Modifier
            .fillMaxWidth()
            .padding(12.dp)
            .testTag("task008-renderer")
    )
}
