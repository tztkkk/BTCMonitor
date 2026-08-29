package com.tzt.btcmonitor.ui.chart.spike

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.pinch
import androidx.compose.ui.test.swipe
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.FileInputStream
import kotlin.math.abs
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class ChartTechnologySpikeAndroid16Test {
    @get:Rule
    val composeRule = createEmptyComposeRule()

    @Test
    fun coreGesturesRunOnAndroid16() {
        launchSpikeFromShell()
        composeRule.waitUntil(timeoutMillis = 30_000L) {
            runCatching {
                composeRule.onAllNodes(
                    hasText("已加载 200 根确定性测试 K 线", substring = true)
                ).fetchSemanticsNodes().isNotEmpty()
            }.getOrDefault(false)
        }
        composeRule.onNodeWithTag("chart-spike-window")
            .assertTextContains("可见 60", substring = true)
            .assertTextContains("自动价格范围", substring = true)

        composeRule.onNodeWithTag("chart-spike-chart").performTouchInput {
            swipe(
                start = Offset(center.x * 0.45f, center.y * 0.35f),
                end = Offset(center.x * 1.45f, center.y * 0.35f),
                durationMillis = 600L
            )
        }
        composeRule.waitUntil(timeoutMillis = 5_000L) {
            composeRule.onAllNodes(
                hasTestTag("chart-spike-window") and
                    hasText("索引 140..199", substring = true)
            ).fetchSemanticsNodes().isEmpty()
        }

        composeRule.onNodeWithTag("chart-spike-chart").performTouchInput {
            pinch(
                start0 = Offset(center.x * 0.8f, center.y),
                end0 = Offset(center.x * 0.35f, center.y),
                start1 = Offset(center.x * 1.2f, center.y),
                end1 = Offset(center.x * 1.65f, center.y),
                durationMillis = 600L
            )
        }
        composeRule.waitUntil(timeoutMillis = 5_000L) {
            composeRule.onAllNodes(
                hasTestTag("chart-spike-window") and hasText("可见 60", substring = true)
            ).fetchSemanticsNodes().isEmpty()
        }

        composeRule.onNodeWithTag("chart-spike-price-axis").performTouchInput {
            swipe(
                start = Offset(center.x, center.y * 0.7f),
                end = Offset(center.x, center.y * 1.25f),
                durationMillis = 500L
            )
        }
        composeRule.onNodeWithTag("chart-spike-window")
            .assertTextContains("手动价格范围", substring = true)

        val chartBounds = composeRule.onNodeWithTag("chart-spike-chart")
            .fetchSemanticsNode().boundsInRoot
        val alertBounds = composeRule.onNodeWithTag("chart-spike-alert-line")
            .fetchSemanticsNode().boundsInRoot
        val crosshairY = listOf(0.2f, 0.5f, 0.8f)
            .map { chartBounds.height * it }
            .maxBy { localY -> abs(chartBounds.top + localY - alertBounds.center.y) }
        composeRule.onNodeWithTag("chart-spike-chart").performTouchInput {
            down(Offset(center.x, crosshairY))
            advanceEventTime(700L)
            moveTo(Offset(center.x * 1.1f, crosshairY + 20f), delayMillis = 100L)
            up()
        }
        composeRule.waitUntil(timeoutMillis = 5_000L) {
            composeRule.onAllNodes(
                hasTestTag("chart-spike-crosshair") and
                    hasText("长按图表", substring = true)
            ).fetchSemanticsNodes().isEmpty()
        }
        composeRule.onNodeWithTag("chart-spike-crosshair")
            .assertTextContains("O ", substring = true)
            .assertTextContains("H ", substring = true)
            .assertTextContains("L ", substring = true)
            .assertTextContains("C ", substring = true)

        composeRule.onNodeWithTag("chart-spike-alert-line").performTouchInput {
            down(center)
            advanceEventTime(100L)
            moveTo(Offset(center.x, center.y - 240f), delayMillis = 500L)
            up()
        }
        composeRule.waitUntil(timeoutMillis = 5_000L) {
            composeRule.onAllNodes(
                hasTestTag("chart-spike-event") and hasText("onMoveAlert", substring = true)
            ).fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithTag("chart-spike-event")
            .assertTextContains("onMoveAlert", substring = true)

        composeRule.onNodeWithText("模拟前插历史").performClick()
        composeRule.onNodeWithTag("chart-spike-window")
            .assertTextContains("总数 224", substring = true)
    }

    private fun launchSpikeFromShell() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val packageName = instrumentation.targetContext.packageName
        val component = "$packageName/${ChartTechnologySpikeActivity::class.java.name}"
        val output = instrumentation.uiAutomation
            .executeShellCommand("am start -W -n $component --ez task007_test_candles true")
            .use { descriptor ->
                FileInputStream(descriptor.fileDescriptor).bufferedReader().use { it.readText() }
            }
        check("Status: ok" in output) { "Unable to launch debug spike: $output" }
    }
}
