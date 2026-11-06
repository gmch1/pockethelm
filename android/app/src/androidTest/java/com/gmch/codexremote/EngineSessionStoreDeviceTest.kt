package com.gmch.codexremote

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.gmch.codexremote.data.VaultEngineSessionStore
import com.gmch.codexremote.engines.contract.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class EngineSessionStoreDeviceTest {
    @Test fun encryptedStoreSurvivesRecreationAndSeparatesOriginsAndWorkspace() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val fixture = TestPrefsApp(UUID.randomUUID().toString()).also { it.attach(context) }
        try {
            val store = VaultEngineSessionStore(fixture)
            val origin = EngineOrigin("env", "third", "one")
            val ref = EngineSessionRef(origin, "s", "/a")
            val other = ref.copy(workspacePath = "/b")
            val foreign = ref.copy(origin = origin.copy(instance = "two"))
            val record = PendingOperation(origin, "command", PendingKind.Send, ref, text = "pending", submittedAtMs = 1793896200000L)
            store.saveDraft(ref, "draft"); store.saveDraft(other, "other"); store.savePending(record)
            val recreated = VaultEngineSessionStore(fixture)
            assertEquals("draft", recreated.draft(ref)); assertEquals("other", recreated.draft(other))
            assertEquals("", recreated.draft(foreign)); assertTrue(recreated.pending(foreign.origin).isEmpty())
            assertEquals(listOf(record), recreated.pending(origin))
            recreated.clearPending(foreign.origin, "command")
            assertEquals(listOf(record), recreated.pending(origin))
            recreated.clearPending(origin, "command")
            assertTrue(store.pending(origin).isEmpty()); assertEquals("draft", store.draft(ref))
        } finally { context.deleteSharedPreferences("zcode_load_test_${fixture.namespace}-vault") }
    }
}
