package com.gmch.codexremote

import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Real Compose rendering and accessibility actions on the device, with synthetic history. */
@RunWith(AndroidJUnit4::class)
class ReadingUiDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private fun nodes(): List<AccessibilityNodeInfo> {
        val found = mutableListOf<AccessibilityNodeInfo>()
        fun walk(node: AccessibilityNodeInfo?) {
            if (node == null) return
            found.add(node)
            for (i in 0 until node.childCount) walk(node.getChild(i))
        }
        walk(instrumentation.uiAutomation.rootInActiveWindow)
        return found
    }
    private fun matching(text: String) = nodes().filter { it.isVisibleToUser && it.text?.toString()?.contains(text) == true }
    private fun await(predicate: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 10_000
        while (System.currentTimeMillis() < deadline) {
            if (predicate()) return
            Thread.sleep(100)
        }
        assertTrue("Expected UI state was not reached", predicate())
    }
    private fun click(text: String) {
        var node: AccessibilityNodeInfo? = matching(text).first()
        while (node != null && !node.isClickable) node = node.parent
        assertNotNull("No clickable node for $text", node)
        assertTrue(node!!.performAction(AccessibilityNodeInfo.ACTION_CLICK))
    }

    @Test fun processDetailsHiddenUntilOpenedAndFailuresRemainVisible() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            val process = listOf(TimelineItem("r", "reasoning", "测试思考摘要"),
                TimelineItem("c", "commandExecution", "$ printf fixture\nfixture output", "completed"),
                TimelineItem("f", "fileChange", "fixture.patch", "failed"))
            scenario.onActivity { activity -> activity.setContent {
                MaterialTheme {
                    var details by remember { mutableStateOf("") }
                    Column(Modifier.fillMaxSize().safeDrawingPadding()) {
                        TimelineCard(TimelineItem("u", "userMessage", "用户需求保留")) { _, _ -> }
                        ActivitySummary(process) { _, text -> details = text }
                        TimelineCard(TimelineItem("a", "agentMessage", "正文结论保留")) { _, _ -> }
                        if (details.isNotEmpty()) Text("详情：$details")
                    }
                }
            } }
            await { matching("正文结论保留").isNotEmpty() }
            assertTrue(matching("有失败").isNotEmpty())
            assertTrue(matching("printf fixture").isEmpty())
            click("工作过程")
            // A short landscape sheet may show only the first (reasoning) entry.
            // Exercise scrolling instead of assuming every tool fits in the first viewport.
            await { matching("printf fixture").isNotEmpty() || nodes().any { it.isScrollable } }
            if (matching("printf fixture").isEmpty()) {
                assertTrue(nodes().first { it.isScrollable }.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD))
            }
            await { matching("printf fixture").isNotEmpty() }
            click("printf fixture")
            await { matching("详情：").isNotEmpty() }
            assertTrue(matching("fixture output").isNotEmpty())
        }
    }

    @Test fun foldedTimelineOpensAtTailAndStickyQuestionJumpsBack() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            val history = listOf(TimelineItem("u", "userMessage", "定位用户问题")) +
                (1..30).map { TimelineItem("t$it", "mcpToolCall", "hidden command $it", "completed") } +
                TimelineItem("a", "agentMessage", (1..40).joinToString("\n") { "这是第 $it 行回答内容" })
            scenario.onActivity { activity -> activity.setContent {
                MaterialTheme { ZcodeTimeline(ZcodeState(items = history), Modifier.fillMaxSize().safeDrawingPadding(), {}, {}) { _, _ -> } }
            } }
            await { matching("定位用户问题").size == 1 && matching("工作过程").isEmpty() }
            click("定位用户问题")
            await { matching("定位用户问题").size >= 2 && matching("工作过程").isNotEmpty() }
            assertTrue(matching("30 项操作").isNotEmpty())
            click("回到最新")
            await { matching("定位用户问题").size == 1 && matching("工作过程").isEmpty() }
        }
    }
}
