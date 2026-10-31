package com.gmch.codexremote

import org.junit.Assert.*
import org.junit.Test

class ZcodeBrowserTest {
    private fun open(start: String? = "/home/dev/root") =
        ZcodeBrowser.reduce(ZcodeBrowserState(), ZcodeBrowserEvent.Open(start))
    private fun loaded(state: ZcodeBrowserState, path: String = state.requested!!) =
        ZcodeBrowser.reduce(state, ZcodeBrowserEvent.ReadOk(path, ZDirectoryListing(path,
            path.substringBeforeLast('/', ""), true, listOf(ZDirectoryEntry("child", path + "/child"))))).first

    @Test fun failedNavigationKeepsOldListingButRetriesSameRequest() {
        val afterA = loaded(open("/a").first)
        assertEquals("/a", afterA.current)
        // Navigate to /b, which fails: current must stay /a and requested must be /b for retry.
        val (navigating, effect) = ZcodeBrowser.reduce(afterA, ZcodeBrowserEvent.Browse("/b"))
        assertEquals("/b", navigating.requested)
        assertEquals("/b", (effect as ZcodeBrowserEffect.Read).requested)
        val failed = ZcodeBrowser.reduce(navigating, ZcodeBrowserEvent.ReadFail("/b", "无权限")).first
        assertEquals("/a", failed.current)
        assertEquals("/b", failed.requested)
        assertEquals("无权限", failed.readError)
        assertFalse(failed.loading)
        // Retry repeats the exact failed request, and its success lands on /b.
        val (retried, retryEffect) = ZcodeBrowser.reduce(failed, ZcodeBrowserEvent.Browse(failed.requested))
        assertEquals("/b", (retryEffect as ZcodeBrowserEffect.Read).requested)
        val recovered = loaded(retried)
        assertEquals("/b", recovered.current)
        assertNull(recovered.readError)
    }

    @Test fun initialRootFailureRetriesRootNotHome() {
        val (state, effect) = open("/home/dev/root")
        assertEquals("/home/dev/root", state.requested)
        assertEquals("/home/dev/root", (effect as ZcodeBrowserEffect.Read).requested)
        val failed = ZcodeBrowser.reduce(state, ZcodeBrowserEvent.ReadFail("/home/dev/root", "不存在")).first
        assertEquals("/home/dev/root", failed.requested)
        assertEquals("", failed.current)
    }

    @Test fun staleReadResultsAreIgnored() {
        val (loading, _) = open("/a")
        val closed = ZcodeBrowser.reduce(loading, ZcodeBrowserEvent.Close).first
        assertFalse(closed.open)
        // Result of the closed dialog's request must not resurrect state.
        val stale = ZcodeBrowser.reduce(closed, ZcodeBrowserEvent.ReadOk("/a",
            ZDirectoryListing("/a", "/home", true, emptyList()))).first
        assertEquals("", stale.current)
        // Even while open, a result for a superseded request is dropped.
        val superseded = ZcodeBrowser.reduce(loading, ZcodeBrowserEvent.Browse("/b")).first
        val staleInFlight = ZcodeBrowser.reduce(superseded, ZcodeBrowserEvent.ReadOk("/a",
            ZDirectoryListing("/a", "/home", true, emptyList()))).first
        assertTrue(staleInFlight.loading)
        assertEquals("/b", staleInFlight.requested)
    }

    @Test fun hiddenToggleStartsReadAndAppliesNewEntries() {
        val base = loaded(open("/a").first)
        val (hidden, effect) = ZcodeBrowser.reduce(base, ZcodeBrowserEvent.Hidden(true))
        assertTrue(hidden.showHidden)
        assertTrue(hidden.loading)
        assertEquals("/a", hidden.requested)
        assertEquals(ZcodeBrowserEffect.Read("/a"), effect)
        assertNull(hidden.readError)
        // The follow-up read for /a now succeeds with hidden entries included: they must land.
        val listing = ZDirectoryListing("/a", "/home", true,
            base.entries + ZDirectoryEntry(".git", "/a/.git"))
        val applied = ZcodeBrowser.reduce(hidden, ZcodeBrowserEvent.ReadOk("/a", listing)).first
        assertFalse(applied.loading)
        assertTrue(applied.entries.any { it.name == ".git" })
        assertEquals("/a", applied.current)
    }

    @Test fun addingBlocksNavigationAndHiddenButRecoversFromAddFailure() {
        var state = loaded(open("/a").first)
        state = ZcodeBrowser.reduce(state, ZcodeBrowserEvent.AddStart).first
        assertTrue(state.adding)
        val (nav, navEffect) = ZcodeBrowser.reduce(state, ZcodeBrowserEvent.Browse("/b"))
        assertFalse(nav.loading)
        assertEquals("/a", nav.requested)
        assertEquals(ZcodeBrowserEffect.None, navEffect)
        val (hidden, hiddenEffect) = ZcodeBrowser.reduce(state, ZcodeBrowserEvent.Hidden(true))
        assertFalse(hidden.showHidden)
        assertEquals(ZcodeBrowserEffect.None, hiddenEffect)
        val failed = ZcodeBrowser.reduce(state, ZcodeBrowserEvent.AddFail("目录已存在")).first
        assertFalse(failed.adding)
        assertEquals("目录已存在", failed.addError)
        // Retry add succeeds and closes the dialog.
        val retry = ZcodeBrowser.reduce(failed, ZcodeBrowserEvent.AddStart).first
        assertTrue(retry.adding)
        assertFalse(ZcodeBrowser.reduce(retry, ZcodeBrowserEvent.AddOk).first.open)
    }

    @Test fun addEffectEmittedOnlyOnRealTransition() {
        val ready = loaded(open("/a").first)
        assertEquals(ZcodeBrowserEffect.Add, ZcodeBrowser.reduce(ready, ZcodeBrowserEvent.AddStart).second)
        // Duplicate AddStart while already adding must not fire again.
        val adding = loaded(open("/a").first).let { ZcodeBrowser.reduce(it, ZcodeBrowserEvent.AddStart).first }
        assertEquals(ZcodeBrowserEffect.None, ZcodeBrowser.reduce(adding, ZcodeBrowserEvent.AddStart).second)
        // Refused while loading, closed, or after a failed read.
        assertEquals(ZcodeBrowserEffect.None, ZcodeBrowser.reduce(open("/a").first, ZcodeBrowserEvent.AddStart).second)
        val closed = ZcodeBrowser.reduce(loaded(open("/a").first), ZcodeBrowserEvent.Close).first
        assertEquals(ZcodeBrowserEffect.None, ZcodeBrowser.reduce(closed, ZcodeBrowserEvent.AddStart).second)
        val readBroken = ZcodeBrowser.reduce(open("/a").first, ZcodeBrowserEvent.ReadFail("/a", "服务不可用")).first
        assertEquals(ZcodeBrowserEffect.None, ZcodeBrowser.reduce(readBroken, ZcodeBrowserEvent.AddStart).second)
    }

    @Test fun filterAndManualAreRecorded() {
        var state = loaded(open("/a").first)
        state = ZcodeBrowser.reduce(state, ZcodeBrowserEvent.Filter("app")).first
        assertEquals("app", state.filter)
        state = ZcodeBrowser.reduce(state, ZcodeBrowserEvent.Manual("/x/y")).first
        assertEquals("/x/y", state.manual)
    }
}
