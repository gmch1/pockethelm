package com.gmch.codexremote

import org.junit.Assert.*
import org.junit.Test

class ResumeSnapshotTest {
    @Test fun presentationRoundTripNeverRestoresLiveExecutionAuthority() {
        val s = SessionInfo("s", "会话", "/work", "内容", "active", 100, lastTurnStatus = "inProgress", activeTurnId = "live-turn")
        val original = ResumeSnapshot("environment", 1, listOf(s), listOf("/work"), s,
            listOf(TimelineItem("i", "agentMessage", "回答")))
        val restored = ResumeSnapshot.parse(original.json())
        assertEquals("s", restored.selected!!.id)
        assertEquals("notLoaded", restored.selected.runtime)
        assertEquals("", restored.selected.activeTurnId)
        assertEquals("", restored.selected.lastTurnStatus)
        assertEquals("回答", restored.items.single().text)
        assertEquals(1, restored.tab)
    }
    @Test fun zcodeIdentityAndBillingChoiceArePreservedButStatusIsNot() {
        val s = ZSession("s", "会话", "/work", "remote:server:example:/work", status = "running", updatedAt = 1_900_000_000_000)
        val choice = ZSelection("provider", "model", "max")
        val restored = ResumeSnapshot.parse(ResumeSnapshot("env", zSessions = listOf(s), zSelected = s,
            choice = choice, guard = "original-billing-guard").json())
        assertEquals(s.identity, restored.zSelected!!.identity)
        assertEquals("", restored.zSelected.status)
        assertEquals(choice, restored.choice)
        assertEquals("original-billing-guard", restored.guard)
    }
    @Test fun longHistoryHasABoundedExplicitPreview() {
        val items = (1..200).map { TimelineItem("$it", "agentMessage", "答".repeat(100_000)) }
        val restored = ResumeSnapshot.parse(ResumeSnapshot("env", items = items).json())
        assertTrue(restored.items.size <= 80)
        assertTrue(restored.items.sumOf { it.text.length } < 241_000)
        assertEquals("200", restored.items.last().id)
        assertTrue(restored.items.last().text.contains("本地预览已截断"))
    }
    @Test fun provisionedEnvironmentAndRootParticipateInIsolation() {
        val p = ConnectionProfile(host = "example.invalid", environmentId = "one", root = "/one")
        assertNotEquals(p.resumeIdentity, p.copy(environmentId = "two").resumeIdentity)
        assertNotEquals(p.resumeIdentity, p.copy(root = "/two").resumeIdentity)
    }
    @Test fun unknownCacheVersionIsRejected() {
        assertThrows(IllegalArgumentException::class.java) { ResumeSnapshot.parse(obj("version" to 99)) }
    }
}
