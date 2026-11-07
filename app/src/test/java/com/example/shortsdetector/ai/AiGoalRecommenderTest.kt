package com.example.shortsdetector.ai

import com.example.shortsdetector.data.goal.GoalRules
import com.example.shortsdetector.data.goal.GoalSource
import com.example.shortsdetector.data.goal.GoalType
import com.example.shortsdetector.data.goal.PresetGoalRecommender
import com.example.shortsdetector.data.profile.AgeBand
import com.example.shortsdetector.data.profile.UserProfile
import com.example.shortsdetector.data.stats.AgentInputBuilder
import com.example.shortsdetector.data.usage.InterventionMethod
import com.example.shortsdetector.data.usage.InterventionRecord
import com.example.shortsdetector.data.usage.SessionRecord
import com.example.shortsdetector.data.usage.SessionStatus
import com.example.shortsdetector.intervention.InterventionPolicy
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.net.SocketTimeoutException
import java.time.LocalDate
import java.time.ZoneId

class AiGoalRecommenderTest {

    private val zone = ZoneId.of("Asia/Seoul")
    private val now = LocalDate.of(2026, 11, 5).atTime(20, 0).atZone(zone).toInstant().toEpochMilli()

    private class FakeTransport(private val answer: () -> AiHttpResponse) : AiTransport {
        var calls = 0
        var url: String? = null
        var headers: Map<String, String> = emptyMap()
        var body: JSONObject? = null

        override fun post(url: String, headers: Map<String, String>, body: String): AiHttpResponse {
            calls++
            this.url = url
            this.headers = headers
            this.body = JSONObject(body)
            return answer()
        }
    }

    private fun input(
        consent: Boolean = true,
        profile: UserProfile? = UserProfile(AgeBand.TWENTIES, aiRecommendationConsent = consent)
    ) = AgentInputBuilder.build(
        profile, emptyList(), InterventionPolicy(), emptyList(), emptyList(), emptyList(), zone, now
    )

    private fun message(text: String, stopReason: String = "end_turn", thinking: Boolean = false): String {
        val content = JSONArray()
        if (thinking) content.put(JSONObject().put("type", "thinking").put("thinking", "").put("signature", "s"))
        content.put(JSONObject().put("type", "text").put("text", text))
        return JSONObject().put("content", content).put("stop_reason", stopReason).toString()
    }

    private fun goal(type: String, value: Int, label: String = "목표", reason: String = "이유") =
        JSONObject().put("type", type).put("value", value).put("label", label).put("reason", reason)

    private fun goals(vararg g: JSONObject) = JSONObject().put("goals", JSONArray(g.toList())).toString()

    private val threeGoals = goals(
        goal("DAILY_TOTAL", 60, "하루 한 시간", "저녁에 많이 본다고 답했어요"),
        goal("PER_SESSION", 15, "한 번에 15분"),
        goal("TIME_WINDOW", 1380, "밤 11시까지")
    )

    private fun ok(body: String) = FakeTransport { AiHttpResponse(200, body) }

    private fun recommend(t: AiTransport, key: String = "test-key", i: com.example.shortsdetector.data.stats.AgentInput = input()) =
        AiGoalRecommender(ClaudeMessagesClient(key, t)) { now }.recommend(i)

    private fun assertFellBack(r: GoalRecommendation, kind: AiFailureKind, status: Int? = null) {
        assertEquals(kind, r.failure)
        assertEquals(status, r.httpStatus)
        assertEquals(3, r.goals.size)
        assertTrue(r.goals.all { it.source == GoalSource.PRESET })
    }

    // --- 정상 ---

    @Test
    fun `valid response gives three AI goals`() {
        val r = recommend(ok(message(threeGoals)))
        assertNull(r.failure)
        assertEquals(listOf(GoalType.DAILY_TOTAL, GoalType.PER_SESSION, GoalType.TIME_WINDOW), r.goals.map { it.type })
        assertTrue(r.goals.all { it.source == GoalSource.AI })
        assertEquals("저녁에 많이 본다고 답했어요", r.goals[0].reason)
    }

    @Test
    fun `text is found by block type when thinking comes first`() {
        assertNull(recommend(ok(message(threeGoals, thinking = true))).failure)
    }

    @Test
    fun `request uses the agreed endpoint, headers, model, limits and schema`() {
        val t = ok(message(threeGoals))
        recommend(t)
        assertEquals("https://api.anthropic.com/v1/messages", t.url)
        assertEquals("test-key", t.headers["x-api-key"])
        assertEquals("2023-06-01", t.headers["anthropic-version"])
        assertEquals("application/json", t.headers["content-type"])
        val body = t.body!!
        assertEquals("claude-haiku-5-5", body.getString("model"))
        assertEquals(4096, body.getInt("max_tokens"))
        val format = body.getJSONObject("output_config").getJSONObject("format")
        assertEquals("json_schema", format.getString("type"))
        assertSchemaClosed(format.getJSONObject("schema"))
        assertFalse(body.has("temperature"))
    }

    /** 모든 object 에 additionalProperties=false 가 있어야 API 가 받아 준다. */
    private fun assertSchemaClosed(schema: JSONObject) {
        if (schema.optString("type") == "object") {
            assertFalse(schema.getBoolean("additionalProperties"))
            val properties = schema.getJSONObject("properties")
            properties.keys().forEach { assertSchemaClosed(properties.getJSONObject(it)) }
        }
        schema.optJSONObject("items")?.let(::assertSchemaClosed)
    }

    @Test
    fun `schema only uses keywords structured outputs support`() {
        val text = AiGoalRecommender.SCHEMA.toString()
        for (word in listOf("minItems", "maxItems", "maxLength", "minLength", "minimum", "maximum")) {
            assertFalse(word, word in text)
        }
    }

    // --- 검증 ---

    @Test
    fun `out of range values are clipped and the label follows`() {
        val r = recommend(ok(message(goals(goal("DAILY_TOTAL", 9999, "하루 9999분"), goal("PER_SESSION", 0), goal("SELF_STOPS", 99)))))
        assertNull(r.failure)
        assertEquals(240, r.goals[0].value)
        assertEquals("하루 240분 이하로 보기", r.goals[0].label)
        assertEquals(1, r.goals[1].value)
        assertEquals(10, r.goals[2].value)
    }

    @Test
    fun `one-word long label and reason are cut to the limit with an ellipsis`() {
        val r = recommend(ok(message(goals(
            goal("DAILY_TOTAL", 60, "가".repeat(30), "나".repeat(100)),
            goal("PER_SESSION", 15), goal("SELF_STOPS", 1)
        ))))
        assertEquals("가".repeat(24) + "…", r.goals[0].label)
        assertEquals("나".repeat(39) + "…", r.goals[0].reason)
    }

    @Test
    fun `duplicate types keep the first and preset fills the rest`() {
        val r = recommend(ok(message(goals(goal("DAILY_TOTAL", 30), goal("DAILY_TOTAL", 90), goal("PER_SESSION", 15)))))
        assertNull(r.failure)
        assertEquals(3, r.goals.size)
        assertEquals(3, r.goals.map { it.type }.toSet().size)
        assertEquals(30, r.goals[0].value)
        assertEquals(GoalSource.PRESET, r.goals[2].source)
    }

    @Test
    fun `fewer than three goals are filled from preset with their own source`() {
        val r = recommend(ok(message(goals(goal("SELF_STOPS", 2)))))
        assertNull(r.failure)
        assertEquals(listOf(GoalSource.AI, GoalSource.PRESET, GoalSource.PRESET), r.goals.map { it.source })
    }

    @Test
    fun `more than three goals are cut to three`() {
        val r = recommend(ok(message(goals(
            goal("DAILY_TOTAL", 60), goal("PER_SESSION", 15), goal("TIME_WINDOW", 1380), goal("SELF_STOPS", 1)
        ))))
        assertEquals(3, r.goals.size)
        assertTrue(r.goals.all { it.source == GoalSource.AI })
    }

    @Test
    fun `malformed entries and unknown types are skipped`() {
        val broken = JSONObject().put("goals", JSONArray(listOf(
            goal("HOURS_OUTSIDE", 3), JSONObject().put("type", "PER_SESSION"), goal("SELF_STOPS", 2)
        ))).toString()
        val r = recommend(ok(message(broken)))
        assertEquals(GoalType.SELF_STOPS, r.goals[0].type)
        assertEquals(GoalSource.AI, r.goals[0].source)
        assertEquals(GoalSource.PRESET, r.goals[1].source)
    }

    // --- 실패 ---

    @Test
    fun `no usable goals at all counts as bad format`() {
        assertFellBack(recommend(ok(message("""{"goals":[]}"""))), AiFailureKind.BAD_FORMAT)
    }

    @Test
    fun `text that is not json is bad format`() {
        assertFellBack(recommend(ok(message("죄송합니다"))), AiFailureKind.BAD_FORMAT, 200)
    }

    @Test
    fun `response without a text block is bad format`() {
        val body = JSONObject().put("content", JSONArray()).put("stop_reason", "end_turn").toString()
        assertFellBack(recommend(ok(body)), AiFailureKind.BAD_FORMAT)
    }

    @Test
    fun `broken envelope is bad format`() {
        assertFellBack(recommend(ok("<html>")), AiFailureKind.BAD_FORMAT, 200)
    }

    @Test
    fun `refusal is reported even when text is present`() {
        assertFellBack(recommend(ok(message(threeGoals, "refusal"))), AiFailureKind.REFUSED)
    }

    @Test
    fun `max tokens is a failure even when the text happens to parse`() {
        assertFellBack(recommend(ok(message(threeGoals, "max_tokens"))), AiFailureKind.TRUNCATED)
    }

    @Test
    fun `timeout and network errors are told apart`() {
        assertFellBack(recommend(FakeTransport { throw SocketTimeoutException() }), AiFailureKind.TIMEOUT)
        assertFellBack(recommend(FakeTransport { throw IOException() }), AiFailureKind.NETWORK)
    }

    @Test
    fun `http errors keep their status`() {
        for (status in listOf(401, 429, 500)) {
            assertFellBack(recommend(FakeTransport { AiHttpResponse(status, "") }), AiFailureKind.HTTP_ERROR, status)
        }
    }

    @Test
    fun `no consent means no network call`() {
        val t = ok(message(threeGoals))
        assertFellBack(recommend(t, i = input(consent = false)), AiFailureKind.NO_CONSENT)
        assertEquals(0, t.calls)
    }

    @Test
    fun `incomplete survey means no consent and no network call`() {
        val t = ok(message(threeGoals))
        assertFellBack(recommend(t, i = input(profile = null)), AiFailureKind.NO_CONSENT)
        assertEquals(0, t.calls)
    }

    @Test
    fun `blank key means no network call`() {
        val t = ok(message(threeGoals))
        assertFellBack(recommend(t, key = "  "), AiFailureKind.NO_KEY)
        assertEquals(0, t.calls)
    }

    @Test
    fun `fallback equals the preset for that survey`() {
        val i = input()
        val r = recommend(FakeTransport { AiHttpResponse(500, "") }, i = i)
        assertEquals(PresetGoalRecommender.recommend(i.profile), r.goals)
    }

    @Test
    fun `elapsed time comes from the injected clock`() {
        var t = 1_000L
        val r = AiGoalRecommender(ClaudeMessagesClient("k", FakeTransport { t += 250; AiHttpResponse(500, "") })) { t }
            .recommend(input())
        assertEquals(250, r.elapsedMs)
    }

    // --- 보내는 데이터 ---

    @Test
    fun `sent data has only the allowed fields and no records, ids or package names`() {
        val session = SessionRecord(
            "secret-session", "com.google.android.youtube", "yt-2", now - 600_000, now - 300_000,
            SessionStatus.CLOSED, 300_000, 0, 0, 0, "HIGH", null, null, 0
        )
        val demo = session.copy(id = "demo", startedAtMs = now - 200_000, endedAtMs = now - 100_000, isDemo = true)
        val intervention = InterventionRecord("secret-i", "secret-session", now - 400_000, InterventionMethod.POPUP, 1, 1, level = 1)
        val i = AgentInputBuilder.build(
            UserProfile(AgeBand.TWENTIES, aiRecommendationConsent = true),
            listOf(GoalRules.userGoal(GoalType.DAILY_TOTAL, 30)), InterventionPolicy(),
            listOf(session, demo), listOf(intervention, intervention.copy(id = "d", isDemo = true)), emptyList(), zone, now
        )
        val t = ok(message(threeGoals))
        recommend(t, i = i)
        val sent = t.body.toString()
        assertFalse("com.google" in sent)
        assertFalse("secret" in sent)
        assertFalse("yt-2" in sent)
        assertFalse("Consent" in sent)

        val json = AgentInputJson.toJson(i)
        assertEquals(
            setOf("survey", "currentGoals", "policy", "goalProgress", "recent", "behavior", "recentPolicyChanges", "today", "week"),
            json.keys().asSequence().toSet()
        )
        assertEquals(300_000, json.getJSONObject("today").getLong("estimatedWatchMs"))  // 시연 세션 제외
        assertEquals(1, json.getJSONObject("today").getInt("interventionCount"))        // 시연 개입 제외
    }

    @Test
    fun `system prompt states the rules the schema cannot`() {
        val p = AiGoalRecommender.SYSTEM_PROMPT
        assertTrue("1380" in p && "25자" in p && "40자" in p && "정확히 3개" in p)
        for (type in GoalType.entries) assertTrue(type.name in p)
    }
}
