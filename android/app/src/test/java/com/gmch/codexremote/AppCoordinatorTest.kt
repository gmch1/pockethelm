package com.gmch.codexremote

import com.gmch.codexremote.app.AppCoordinator
import com.gmch.codexremote.engines.contract.*
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.*
import org.junit.Test

class AppCoordinatorTest {
    private class Host(id: String, private val events: MutableList<String>) : EngineHost {
        override val descriptor = EngineDescriptor(id, id)
        override val connection = MutableStateFlow(EngineConnection())
        private fun mark(event: String) { events += "${descriptor.id}:$event" }
        override fun enter() = mark("enter")
        override fun leave() = mark("leave")
        override fun background() = mark("background")
        override fun keepConnection() = mark("keep")
        override fun connect() = mark("connect")
        override fun disconnect() = mark("disconnect")
        override fun prepareEnvironmentChange() = mark("invalidate")
        override fun reloadEnvironment() = mark("reload")
        override fun dismissError() = Unit
        override fun dismissChallenge() = Unit
    }
    @Test fun thirdEngineRegistersAndReceivesLifecycleWithoutNativeEngineBranches() {
        val events = mutableListOf<String>(); val saved = mutableListOf<String>()
        val registry = EngineRegistry(listOf(Host("first", events), Host("second", events), Host("third", events)))
        val app = AppCoordinator(registry, "first", saved::add)
        app.start(); app.start()
        assertEquals(listOf("first:keep", "second:keep", "third:keep", "first:enter"), events)
        events.clear(); app.select("third")
        assertEquals(listOf("first:leave", "third:enter"), events); assertEquals(listOf("third"), saved)
        events.clear(); app.stop(); app.stop()
        assertEquals(listOf("first:background", "second:background", "third:background"), events)
        events.clear(); app.select("second")
        assertEquals(listOf("third:leave"), events)
        events.clear(); app.start()
        assertEquals("second:enter", events.last())
    }
    @Test fun unknownAndDuplicateEngineIdsAreRejected() {
        val events = mutableListOf<String>()
        assertThrows(IllegalArgumentException::class.java) { EngineRegistry(listOf(Host("x", events), Host("x", events))) }
        val app = AppCoordinator(EngineRegistry(listOf(Host("known", events))), "removed", {})
        assertEquals("known", app.selected.value)
        assertThrows(IllegalArgumentException::class.java) { app.select("missing") }
        assertTrue(events.isEmpty())
    }
    @Test fun failedPreferenceWriteDoesNotSwitchOrStopTheCurrentEngine() {
        val events = mutableListOf<String>()
        val app = AppCoordinator(EngineRegistry(listOf(Host("a", events), Host("b", events))), "a", { error("disk failure") })
        assertThrows(IllegalStateException::class.java) { app.select("b") }
        assertEquals("a", app.selected.value); assertTrue(events.isEmpty())
    }
    @Test fun environmentChangeInvalidatesAllEnginesAndOnlyReconnectsTheVisibleOne() {
        val events = mutableListOf<String>()
        val app = AppCoordinator(EngineRegistry(listOf(Host("a", events), Host("b", events))), "b", {})
        app.start(); events.clear()
        app.beforeEnvironmentChange(); app.environmentChanged()
        assertEquals(listOf("a:invalidate", "b:invalidate", "a:reload", "b:reload", "b:connect"), events)
        app.stop(); events.clear(); app.environmentChanged()
        assertEquals(listOf("a:reload", "b:reload"), events)
    }
}
