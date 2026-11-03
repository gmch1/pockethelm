package com.gmch.codexremote

import android.os.SystemClock
import android.view.MotionEvent
import androidx.activity.compose.setContent
import androidx.compose.runtime.mutableStateOf
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ListPullRefreshTest {
    @Test fun pullRefreshWorksForBothEnginesAndEmptyListsWithoutDuplicateRequests() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val busy = mutableStateOf(false)
        val connected = mutableStateOf(true)
        val engine = mutableStateOf("zcode")
        val sessions = mutableStateOf(emptyList<WorkspaceSession>())
        var calls = 0
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity -> activity.setContent {
                PocketHelmTheme {
                    WorkspaceListPage(engine.value, {}, sessions.value, connected.value, busy.value,
                        false, false, {}, {}, {}, { calls++; busy.value = true }, null, null, {}, { _, _ -> }, {})
                }
            } }
            fun swipe() {
                instrumentation.waitForIdleSync()
                val bounds = android.graphics.Rect()
                val deadline = SystemClock.uptimeMillis() + 10000
                var root = instrumentation.uiAutomation.rootInActiveWindow
                while (root == null && SystemClock.uptimeMillis() < deadline) {
                    SystemClock.sleep(100)
                    root = instrumentation.uiAutomation.rootInActiveWindow
                }
                assertNotNull("Activity accessibility tree is ready", root)
                root!!.getBoundsInScreen(bounds)
                val down = SystemClock.uptimeMillis()
                val x = bounds.exactCenterX()
                val start = bounds.top + bounds.height() * 0.35f
                val end = bounds.top + bounds.height() * 0.80f
                for (i in 0..30) {
                    val action = when(i) { 0 -> MotionEvent.ACTION_DOWN; 30 -> MotionEvent.ACTION_UP; else -> MotionEvent.ACTION_MOVE }
                    val event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, x, start + (end-start)*i/30f, 0)
                    try { instrumentation.uiAutomation.injectInputEvent(event, true) } finally { event.recycle() }
                    SystemClock.sleep(20)
                }
                SystemClock.sleep(600)
                instrumentation.waitForIdleSync()
            }
            swipe()
            assertEquals("Empty ZCode list can refresh", 1, calls)
            swipe()
            assertEquals("Busy refresh does not queue another request", 1, calls)
            instrumentation.runOnMainSync {
                busy.value = false; engine.value = "codex"
                sessions.value = listOf(WorkspaceSession("one", "测试会话", "/test", "test", "", 0))
            }
            SystemClock.sleep(400)
            swipe()
            assertEquals("Codex uses the same gesture", 2, calls)
            instrumentation.runOnMainSync { busy.value = false; connected.value = false }
            SystemClock.sleep(400)
            swipe()
            assertEquals("Offline gesture cannot submit requests", 2, calls)
        }
    }
}
