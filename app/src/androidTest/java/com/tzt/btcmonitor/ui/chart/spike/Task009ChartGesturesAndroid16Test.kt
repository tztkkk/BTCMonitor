package com.tzt.btcmonitor.ui.chart.spike

import android.os.Build
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.pinch
import androidx.compose.ui.test.swipe
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.tzt.btcmonitor.ui.chart.Task008RendererTestHostActivity
import java.io.FileInputStream
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class Task009ChartGesturesAndroid16Test {
    @get:Rule
    val composeRule = createEmptyComposeRule()

    @Test
    fun productionRendererSupportsPanDualScalePinnedCrosshairAndReturnLatest() {
        assertEquals(36, Build.VERSION.SDK_INT)
        launchRendererHostFromShell()
        composeRule.waitUntil(timeoutMillis = 10_000L) {
            runCatching {
                composeRule.onAllNodes(hasText("索引 140..199", substring = true))
                    .fetchSemanticsNodes().isNotEmpty()
            }.getOrDefault(false)
        }
        composeRule.onNodeWithTag("interactive-chart-canvas").performTouchInput {
            swipe(
                start = Offset(center.x * 0.6f, center.y * 0.7f),
                end = Offset(center.x * 1.5f, center.y * 0.7f),
                durationMillis = 600L
            )
        }
        composeRule.waitUntil(timeoutMillis = 5_000L) {
            composeRule.onAllNodes(hasText("索引 140..199", substring = true))
                .fetchSemanticsNodes().isEmpty()
        }
        composeRule.onNodeWithTag("interactive-chart-return-latest").assertIsEnabled()

        composeRule.onNodeWithTag("interactive-chart-canvas").performTouchInput {
            pinch(
                start0 = Offset(center.x * 0.8f, center.y),
                end0 = Offset(center.x * 0.4f, center.y),
                start1 = Offset(center.x * 1.2f, center.y),
                end1 = Offset(center.x * 1.6f, center.y),
                durationMillis = 600L
            )
        }
        composeRule.waitUntil(timeoutMillis = 5_000L) {
            composeRule.onAllNodes(hasText("可见 60 根 K 线", substring = true))
                .fetchSemanticsNodes().isEmpty()
        }

        composeRule.onNodeWithTag("interactive-chart-price-axis").performTouchInput {
            swipe(
                start = Offset(center.x, center.y * 0.7f),
                end = Offset(center.x, center.y * 1.4f),
                durationMillis = 500L
            )
        }
        composeRule.onNodeWithTag("interactive-chart-window")
            .assertTextContains("手动价格范围", substring = true)

        composeRule.onNodeWithTag("interactive-chart-canvas").performTouchInput {
            down(Offset(center.x, center.y * 0.6f))
            advanceEventTime(700L)
            moveTo(Offset(center.x * 1.3f, center.y * 0.9f), delayMillis = 120L)
            up()
        }
        composeRule.waitUntil(timeoutMillis = 5_000L) {
            composeRule.onAllNodes(hasText("O ", substring = true))
                .fetchSemanticsNodes().isNotEmpty()
        }
        listOf("O ", "H ", "L ", "C ").forEach { label ->
            check(
                composeRule.onAllNodes(hasText(label, substring = true))
                    .fetchSemanticsNodes().isNotEmpty()
            ) { "Missing crosshair value: $label" }
        }

        composeRule.onNodeWithTag("interactive-chart-canvas").performTouchInput {
            down(Offset(center.x * 0.3f, center.y * 0.3f))
            up()
        }
        composeRule.waitUntil(timeoutMillis = 5_000L) {
            composeRule.onAllNodes(hasText("长按图表", substring = true))
                .fetchSemanticsNodes().isNotEmpty()
        }

        composeRule.onNodeWithTag("interactive-chart-return-latest").performClick()
        composeRule.onNodeWithTag("interactive-chart-window")
            .assertTextContains("..199", substring = true)
            .assertTextContains("自动价格范围", substring = true)
    }

    private fun launchRendererHostFromShell() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val packageName = instrumentation.targetContext.packageName
        val component = "$packageName/${Task008RendererTestHostActivity::class.java.name}"
        instrumentation.uiAutomation.executeShellCommand("cmd power wakeup").close()
        val output = instrumentation.uiAutomation
            .executeShellCommand(
                "am start -W -n $component --ef " +
                    "${Task008RendererTestHostActivity.FONT_SCALE_EXTRA} 1.3"
            )
            .use { descriptor ->
                FileInputStream(descriptor.fileDescriptor).bufferedReader().use { it.readText() }
            }
        check("Status: ok" in output) { "Unable to launch TASK-009 renderer host: $output" }
    }
}
