package com.tzt.btcmonitor.ui.chart.spike

import android.os.Build
import androidx.compose.ui.test.assertContentDescriptionContains
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.tzt.btcmonitor.ui.chart.Task008RendererTestHostActivity
import java.io.FileInputStream
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class Task008RendererAndroid16Test {
    @get:Rule
    val composeRule = createEmptyComposeRule()

    @Test
    fun reusableRendererDrawsCandlesAndAlertStatesWithoutCrash() {
        assertEquals(36, Build.VERSION.SDK_INT)
        launchRendererHostFromShell()
        composeRule.waitUntil(timeoutMillis = 10_000L) {
            composeRule.onAllNodes(hasText("可见 60 根 K 线", substring = true))
                .fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithTag("task008-renderer")
            .assertExists()
            .assertContentDescriptionContains("可见 60 根 K 线", substring = true)
            .assertContentDescriptionContains("提醒线 3 条", substring = true)
        composeRule.onAllNodes(hasText("范围内提醒", substring = true))[0].assertExists()
        composeRule.onAllNodes(hasText("停用提醒", substring = true))[0].assertExists()
        composeRule.onAllNodes(hasText("图外提醒", substring = true))[0].assertExists()
    }

    private fun launchRendererHostFromShell() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val packageName = instrumentation.targetContext.packageName
        val component = "$packageName/${Task008RendererTestHostActivity::class.java.name}"
        val output = instrumentation.uiAutomation
            .executeShellCommand("am start -W -n $component")
            .use { descriptor ->
                FileInputStream(descriptor.fileDescriptor).bufferedReader().use { it.readText() }
            }
        check("Status: ok" in output) { "Unable to launch TASK-008 renderer host: $output" }
    }
}
