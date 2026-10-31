package com.gmch.codexremote

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ModelOptionsTest {
    private val model = ModelOption("fixture", "Fixture", "", listOf("low", "high"), "low", listOf(SpeedOption("priority", "Fast", "")))
    @Test fun standardSpeedIsExplicitAndUnsupportedSettingsAreRejected() {
        val params = ModelSettings("fixture", "high", "default").request("thread", listOf(model))
        assertEquals("default", params.getString("serviceTier"))
        assertEquals(setOf("threadId", "model", "effort", "serviceTier"), params.keys().asSequence().toSet())
        for (bad in listOf(ModelSettings("unknown", "high", "default"), ModelSettings("fixture", "ultra", "default"), ModelSettings("fixture", "high", "ultrafast")))
            assertTrue(runCatching { bad.request("thread", listOf(model)) }.isFailure)
    }
    @Test fun resumeAndNotificationHaveTheSameEffectiveSettings() {
        val resumed = ModelSettings.parse(obj("model" to "fixture", "reasoningEffort" to "high", "serviceTier" to "priority"))
        val notified = ModelSettings.parse(obj("model" to "fixture", "effort" to "high", "serviceTier" to "priority"))
        assertEquals(resumed, notified)
        assertEquals("default", ModelSettings.parse(obj("model" to "fixture", "serviceTier" to JSONObject.NULL)).tier)
        assertFalse(ModelSettings.metadata(obj("model" to "fixture")).confirmed)
    }
    @Test fun modelCapabilitiesComeFromTheCatalogIncludingUnknownFutureTiers() {
        val parsed = ModelOption.parse(JSONObject("""{"model":"new-model","displayName":"New","defaultReasoningEffort":"high","supportedReasoningEfforts":[{"reasoningEffort":"high"}],"serviceTiers":[{"id":"future-speed","name":"Future","description":"Server description"}]}"""))
        assertEquals(listOf("high"), parsed.efforts)
        assertEquals("future-speed", ModelSettings("new-model", "high", "future-speed").request("t", listOf(parsed)).getString("serviceTier"))
        assertTrue(ModelOption.parse(obj("model" to "basic")).speeds.isEmpty())
    }
    @Test fun primaryWindowCanBeWeeklyAndDoesNotInventFiveHourQuota() {
        val q = AccountQuota.parse(JSONObject("""{"rateLimits":{"limitId":"codex","primary":{"usedPercent":45,"windowDurationMins":10080,"resetsAt":2000},"secondary":null}}"""), 1234)
        assertEquals(55.0, q.remaining!!, 0.0)
        assertEquals("周额度", q.buckets.single().windows.single().label)
        assertEquals(1, q.buckets.single().windows.size)
        assertEquals(1234L, q.updatedAt)
    }
    @Test fun missingAndMalformedUsageDoNotAppearAsZeroOrUnlimited() {
        assertNull(AccountQuota.parse(obj()).remaining)
        assertNull(QuotaWindow.parse(obj("windowDurationMins" to 300)))
        assertNull(QuotaWindow.parse(obj("usedPercent" to "unknown")))
        assertEquals(0.0, QuotaWindow.parse(obj("usedPercent" to 125))!!.remaining, 0.0)
        assertNull(QuotaWindow.parse(obj("usedPercent" to 25))!!.resetsAt)
    }
    @Test fun multipleBucketsAndCreditsRemainSeparate() {
        val q = AccountQuota.parse(JSONObject("""{"rateLimitsByLimitId":{"reserve":{"primary":{"usedPercent":99},"rateLimitReachedType":"rate_limit_reached"},"codex":{"primary":{"usedPercent":20},"secondary":{"usedPercent":40},"credits":{"balance":"123.4500","unlimited":false}}}}"""))
        assertEquals(60.0, q.remaining!!, 0.0)
        assertFalse(q.blocked)
        assertEquals("123.4500", q.buckets.first { it.id == "codex" }.credits)
        assertTrue(q.buckets.first { it.id == "reserve" }.blocked)
    }
    @Test fun blockedAccountDoesNotRecoverFromPercentagesOrAnElapsedReset() {
        val q = AccountQuota.parse(JSONObject("""{"ordinaryUsageAllowed":false,"rateLimits":{"primary":{"usedPercent":0,"resetsAt":1}}}"""))
        assertTrue(q.blocked)
        assertEquals(100.0, q.remaining!!, 0.0)
    }
}
