package com.tzt.btcmonitor.ui.chart.spike

import android.os.Build
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
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
class Task010PagingAndroid16Test {
    @get:Rule
    val composeRule = createEmptyComposeRule()

    @Test
    fun leftBoundaryLoadsOneOlderPageAndPreservesViewportAnchor() {
        assertEquals(36, Build.VERSION.SDK_INT)
        launchPagingHost()
        composeRule.waitUntil(timeoutMillis = 10_000L) {
            runCatching {
                composeRule.onAllNodes(hasText("索引 10..69", substring = true))
                    .fetchSemanticsNodes().isNotEmpty()
            }.getOrDefault(false)
        }

        composeRule.onNodeWithTag("interactive-chart-canvas").performTouchInput {
            swipe(
                start = Offset(center.x * 0.55f, center.y * 0.7f),
                end = Offset(center.x * 1.55f, center.y * 0.7f),
                durationMillis = 700L
            )
        }
        composeRule.waitUntil(timeoutMillis = 5_000L) {
            composeRule.onAllNodes(hasText("分页总数 100，请求 1", substring = true))
                .fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithTag("interactive-chart-window")
            .assertTextContains("索引 30..89", substring = true)

        composeRule.onNodeWithTag("interactive-chart-canvas").performTouchInput {
            swipe(
                start = Offset(center.x * 0.55f, center.y * 0.7f),
                end = Offset(center.x * 1.55f, center.y * 0.7f),
                durationMillis = 700L
            )
        }
        composeRule.onNodeWithTag("task010-paging-state")
            .assertTextContains("请求 1", substring = true)
    }

    private fun launchPagingHost() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val packageName = instrumentation.targetContext.packageName
        val component = "$packageName/${Task008RendererTestHostActivity::class.java.name}"
        instrumentation.uiAutomation.executeShellCommand("cmd power wakeup").close()
        val output = instrumentation.uiAutomation
            .executeShellCommand(
                "am start -W -n $component --ez " +
                    "${Task008RendererTestHostActivity.PAGING_EXTRA} true"
            )
            .use { descriptor ->
                FileInputStream(descriptor.fileDescriptor).bufferedReader().use { it.readText() }
            }
        check("Status: ok" in output) { "Unable to launch TASK-010 paging host: $output" }
    }
}
