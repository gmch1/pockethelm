package com.gmch.codexremote

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ZcodeModelsTest {
    @Test fun modelSelectionKeepsBillingProviderAndReasoning() {
        val first = ZSelection("account:bigmodel-individual-coding-plan", "GLM-5.3", "max")
        val other = first.copy(provider = "account:bigmodel-team-coding-plan")
        assertNotEquals(first, other)
        assertEquals(first, ZSelection.parse(first.json()))
        assertFalse(first.copy(level = "").json().has("options"))
    }
    @Test fun timelineSeparatesMessagesAndCompactTools() {
        val data = JSONObject("""{"items":[
          {"id":"2","kind":"userInput","text":"question","commandId":"pending-id"},
          {"id":"3","kind":"toolCall","text":"Bash\nsleep 1","status":"success"},
          {"id":"4","kind":"assistantText","text":"answer","status":"streaming"}
        ]}""")
        val items = zcodeItems(data)
        assertEquals(listOf("userMessage", "mcpToolCall", "agentMessage"), items.map { it.kind })
        assertEquals("pending-id", items[0].clientId)
        assertEquals("completed", items[1].status)
        assertEquals("running", items[2].status)
    }
    @Test fun missingQuotaIsNotPresentedAsZeroRemaining() {
        assertEquals("服务端未返回额度窗口", zcodeQuota(JSONObject("""{"limits":[]}""")))
        assertTrue(zcodeQuota(JSONObject("""{"limits":[{"type":"CREDIT_LIMIT","unit":3,"number":5,"remaining":547}]}""")).contains("剩余 547"))
        assertFalse(zcodeQuota(JSONObject("""{"limits":[{"type":"UNKNOWN"}]}""")).contains("剩余 0"))
    }
    @Test fun freePlanUsesTokenBalancesAndRemainingRatio() {
        val data = JSONObject("""{"planKind":"start-plan","limits":[{"unit":5000000000,"number":5000000000,
            "remaining":2250000000,"usage":2750000000,"currentValue":2750000000,"percentage":0.45,
            "unitType":"token","period":"one_time","nextResetTime":1793581200000,
            "usageDetails":[{"modelCode":"glm-flash","displayName":"GLM Flash"}]}]}""")
        val text = zcodeQuota(data)
        assertTrue(text.contains("GLM Flash · 一次性额度"))
        assertTrue(text.contains("剩余 2,250,000,000 Tokens · 45%"))
        assertTrue(text.contains("已用 2,750,000,000 Tokens / 总量 5,000,000,000 Tokens"))
        assertTrue(text.contains("到期"))
        assertFalse(text.contains("小时"))
        assertEquals("剩余 45%", zcodeQuotaSummary(data, "GLM-Flash"))
        assertEquals("额度 ▾", zcodeQuotaSummary(data, "other-model"))
    }
    @Test fun paidPercentageMeansUsedAndNumberMeansWindowNotTotal() {
        val data = JSONObject("""{"planKind":"individual-coding-plan","limits":[{"unit":3,"number":5,
            "percentage":30,"currentValue":21,"usage":70}]}""")
        assertEquals("5 小时额度\n剩余 70%\n已用 21", zcodeQuota(data))
        assertEquals("剩余 70%", zcodeQuotaSummary(data, "model"))
    }
    @Test fun unknownQuotaNeverInventsZeroAndRealZeroRemainsVisible() {
        val data = JSONObject("""{"planKind":"start-plan","limits":[{"remaining":null,"currentValue":"bad",
            "percentage":null},{"remaining":0,"currentValue":10,"number":10,"unitType":"token"}]}""")
        val text = zcodeQuota(data).split("\n\n")
        assertTrue(text[0].contains("剩余量暂不可用"))
        assertFalse(text[0].contains("剩余 0"))
        assertTrue(text[1].contains("剩余 0 Tokens · 0%"))
        assertEquals("Start Plan 已到期", zcodeQuota(JSONObject("""{"startPlanExpired":true,"limits":[]}""")))
    }
    @Test fun multipleBucketsRemainSeparateAndDailyQuotaResets() {
        val data = JSONObject("""{"planKind":"start-plan","limits":[
            {"number":3,"remaining":1.5,"period":"daily","nextResetTime":1793581200000,
             "usageDetails":[{"modelCode":"m","displayName":"每日"}]},
            {"number":6,"remaining":4,"usageDetails":[{"modelCode":"m","displayName":"赠送"}]}]}""")
        val text = zcodeQuota(data)
        assertTrue(text.contains("每日 · 每日额度"))
        assertTrue(text.contains("剩余 1.5 · 50%"))
        assertTrue(text.contains("重置"))
        assertTrue(text.contains("赠送\n剩余 4"))
        assertEquals("额度 ▾", zcodeQuotaSummary(data, "m"))
    }
    @Test fun sessionListOrdersByMostRecentActivity() {
        val data = JSONObject("""{"sessions":[
          {"taskId":"a","title":"旧","updatedAt":1700000000},
          {"taskId":"b","title":"毫秒","updatedAt":1800000000000},
          {"taskId":"c","title":"ISO","updatedAt":"2023-11-14T22:13:20Z"},
          {"taskId":"d","title":"无时间"},
          {"taskId":"e","title":"坏值","updatedAt":"not-a-time"}
        ]}""")
        val sessions = data.optJSONArray("sessions").objects().map(ZSession::parse).sortedByDescending { it.updatedAt }
        // Seconds are normalized to millis and ISO strings parse, so mixed formats compare
        // correctly; peers without a usable timestamp keep their relative order at the end.
        assertEquals(listOf("b", "a", "c", "d", "e"), sessions.map { it.id })
        assertEquals(1700000000000L, sessions.first { it.id == "a" }.updatedAt)
        assertEquals(1800000000000L, sessions.first { it.id == "b" }.updatedAt)
        assertEquals(1700000000000L, sessions.first { it.id == "c" }.updatedAt)
        assertEquals(0L, sessions.first { it.id == "e" }.updatedAt)
    }
    @Test fun directoryListingDropsMalformedAndRelativeEntries() {
        val listing = ZDirectoryListing.parse(JSONObject("""{"path":"home/dev","parent":"/home",
          "directories":[
            {"name":"ok","path":"/opt/ok"},
            {"path":"/opt/noname"},
            {"name":"nopath"},
            {"name":7,"path":"/opt/badname"},
            {"name":"badpath","path":7},
            {"name":"relative","path":"dev/relative"},
            {"name":"","path":"/opt/empty"},
            "not-a-dict"
          ]}"""))
        assertEquals("home/dev", listing.path)
        assertEquals(listOf("ok"), listing.directories.map { it.name })
        assertEquals(listOf("/opt/ok"), listing.directories.map { it.path })
    }

    // ---- Interaction answering (task 2026-11-02-interaction-approvals) ----

    @Test fun interactionParsingKeepsIdentityAndUnsupportedFlags() {
        val data = JSONObject("""{"interactions":[
          {"interactionId":"i-1","kind":"userInput","supported":true,"prompt":"选一个","freeText":true,
           "sensitive":true,"toolName":"AskUserQuestion","currentQuestionIndex":1,
           "questions":[{"question":"Q1？","header":"第一题","options":[{"value":"a","label":"A"}]},
                        {"question":"Q2？","header":"第二题","multiSelect":true,"options":[{"value":"b","label":"B"}]}],
           "answerDrafts":{"Q1？":["a"]},
           "autoResolution":{"state":"visibleCountdown","deadlineAt":1793600000000}},
          {"interactionId":"i-2","kind":"workspaceHookReview","supported":false,"unsupportedReason":"unsupported_kind"}
        ]}""")
        val interactions = zcodeInteractions(data)
        assertEquals(2, interactions.size)
        val first = interactions[0]
        assertTrue(first.supported); assertTrue(first.sensitive); assertEquals("AskUserQuestion", first.toolName)
        assertEquals(2, first.questions.size); assertFalse(first.questions[0].multiSelect)
        assertTrue(first.questions[1].multiSelect)
        assertEquals(mapOf("Q1？" to listOf("a")), first.answerDrafts)
        assertEquals(1, first.currentQuestionIndex)
        assertEquals("visibleCountdown", first.autoResolution?.state)
        val second = interactions[1]
        assertFalse(second.supported); assertEquals("unsupported_kind", second.unsupportedReason)
    }

    /** Mirrors the official buildElicitationResponseContent shape exactly. */
    @Test fun interactionContentMatchesOfficialAnswerShape() {
        val q1 = ZQuestion("用哪个数据库？", "数据库", listOf(ZQuestionOption("postgres", "PostgreSQL"), ZQuestionOption("mysql", "MySQL")), multiSelect = false)
        val q2 = ZQuestion("包含哪些模块？", "模块", listOf(ZQuestionOption("api", "API"), ZQuestionOption("web", "Web")), multiSelect = true)
        val drafts = mapOf(
            "用哪个数据库？" to ZFormDraft(listOf("postgres")),
            "包含哪些模块？" to ZFormDraft(listOf("api"), "自定义模块"))
        val content = buildInteractionContent(listOf(q1, q2), drafts)
        // answers joins each question's values with ", " — including the custom answer.
        assertEquals("postgres", content.getJSONObject("answers").string("用哪个数据库？"))
        assertEquals("api, 自定义模块", content.getJSONObject("answers").string("包含哪些模块？"))
        // answer_N keeps multi-select as array, single as plain string.
        assertEquals("postgres", content.getString("answer_0"))
        val second = content.getJSONArray("answer_1")
        assertEquals(listOf("api", "自定义模块"), (0 until second.length()).map { second.getString(it) })
        // Multi-question form has no legacy single "answer" key.
        assertFalse(content.has("answer"))
        assertTrue(interactionFormAnswered(listOf(q1, q2), drafts))
    }

    @Test fun singleQuestionContentAddsLegacyAnswerKeyAndUnansweredOmitted() {
        val q = ZQuestion("继续吗？", "确认", listOf(ZQuestionOption("yes", "继续")), multiSelect = true)
        val content = buildInteractionContent(listOf(q), mapOf("继续吗？" to ZFormDraft(listOf("yes"))))
        assertEquals("yes", content.getJSONObject("answers").string("继续吗？"))
        assertEquals("yes", content.getJSONArray("answer_0").getString(0))
        // Single-question form also carries the legacy "answer" key as an array for multi-select.
        assertEquals("yes", content.getJSONArray("answer").getString(0))
        // An entirely unanswered form produces no answer keys and does not gate submit.
        val empty = buildInteractionContent(listOf(q), emptyMap())
        assertEquals(0, empty.getJSONObject("answers").length())
        assertFalse(empty.has("answer_0")); assertFalse(empty.has("answer"))
        assertFalse(interactionFormAnswered(listOf(q), emptyMap()))
    }
    @Test fun customSingleAnswerWinsAndIndexedDraftsRestore() {
        val q = ZQuestion("数据库", "DB", listOf(ZQuestionOption("pg", "Postgres")), false)
        val result = buildInteractionContent(listOf(q), mapOf(q.question to ZFormDraft(listOf("pg"), "SQLite")))
        assertEquals("SQLite", result.getString("answer_0"))
        assertEquals("SQLite", result.getJSONObject("answers").getString(q.question))
        val interaction = ZInteraction("i", "userInput", true, questions = listOf(q, q.copy(question = "模块", multiSelect = true)),
            answerDrafts = mapOf("answer_0" to listOf("SQLite"), "1" to listOf("pg", "custom")))
        val restored = initialInteractionDrafts(interaction)
        assertEquals(ZFormDraft(customAnswer = "SQLite"), restored[q.question])
        assertEquals(ZFormDraft(listOf("pg"), "custom"), restored["模块"])
    }

    @Test fun countdownAcceptsSecondsAndMillisAndShowsExpiry() {
        val now = 1_800_000_000_000L
        assertEquals("即将自动结束 · 剩余约 2 分钟", interactionCountdown(now + 120_000, now))
        assertEquals(interactionCountdown(now + 120_000, now), interactionCountdown((now + 120_000) / 1000, now))
        assertEquals("自动结束时间已到，等待服务器确认", interactionCountdown(now - 1, now))
    }
    // ---- P1 (2026-11-02 zcode-p1-parity): row identity, turn summary, diff detail ----

    @Test fun zcodeItemsCarryEntityIdWhenPresent() {
        val items = zcodeItems(JSONObject("""{"items":[
            {"id":"1","kind":"userInput","text":"hi","commandId":"c1","entityId":"turn-1"},
            {"id":"2","kind":"assistantText","text":"ok","status":"complete"}]}"""))
        assertEquals("turn-1", items[0].entityId)
        assertEquals("", items[1].entityId)
    }

    @Test fun turnParseKeepsAuthoritativeFieldsAndDegradesTolerantly() {
        val full = ZTurn.parse(JSONObject("""{"rowId":7,"entityId":"turn-7","state":"running",
            "activeMs":112000,"fileChanges":{"additions":318,"deletions":0,"files":2,"state":"active"}}"""))
        assertEquals(ZTurn(7, "turn-7", "running", 112000, 318, 0, 2, false), full)
        assertNull(ZTurn.parse(null))
        assertNull(ZTurn.parse(JSONObject("""{"entityId":"x"}""")))
        // Missing fileChanges keeps identity + state with absent counters.
        val bare = ZTurn.parse(JSONObject("""{"rowId":7,"entityId":"turn-7","state":"failed"}"""))
        assertEquals(ZTurn(7, "turn-7", "failed", 0, -1, -1, -1, false), bare)
        assertFalse(bare!!.hasChanges)
        val reverted = ZTurn.parse(JSONObject("""{"rowId":7,"entityId":"e","state":"completedSuccess",
            "fileChanges":{"additions":5,"deletions":5,"files":1,"state":"reverted"}}"""))
        assertTrue(reverted!!.reverted)
    }

    @Test fun turnSummaryFeedsTheSharedBadgeOnlyWithContentOrDetailTarget() {
        assertNull((null as ZTurn?).summary())
        // No counters and no detail target: nothing to render.
        assertNull(ZTurn(7, "", "running").summary())
        // Counters alone justify the badge; entityId alone justifies a detail-only badge.
        val withChanges = ZTurn(7, "turn-7", "completedSuccess", 4000, 318, 0, 2, reverted = true)
        val summary = withChanges.summary()!!
        assertEquals(318, summary.additions); assertEquals(0, summary.deletions)
        assertEquals(2, summary.files); assertTrue(summary.reverted); assertFalse(summary.running)
        assertTrue(summary.detailAvailable)
        val detailOnly = ZTurn(8, "turn-8", "running", 9000).summary()!!
        assertTrue(detailOnly.detailAvailable)
        assertTrue(detailOnly.running)
    }

    @Test fun formatFileChangesRendersTotalsCountersAndHunks() {
        val text = formatFileChanges(JSONObject("""{"files":2,"additions":7,"deletions":3,"state":"active","items":[
            {"path":"src/a.kt","additions":7,"deletions":3,"writeCount":2,"toolNames":["Edit","Bash"],
             "patches":[{"oldStart":1,"oldLines":1,"newStart":1,"newLines":2,"lines":["- old","+ new"]}]}]}"""))
        assertTrue(text.contains("2 个文件"))
        assertTrue(text.contains("+7"))
        assertTrue(text.contains("−3"))
        assertTrue(text.contains("src/a.kt"))
        assertTrue(text.contains("改写 2 次"))
        assertTrue(text.contains("Edit/Bash"))
        assertTrue(text.contains("@@ -1 +1 @@"))
        assertTrue(text.contains("+ new"))
        assertFalse(text.contains("已撤销"))
    }
}
