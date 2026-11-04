package com.example.shortsdetector.ai.agent

import com.example.shortsdetector.ai.AgentInputJson
import com.example.shortsdetector.ai.AiFailureKind
import com.example.shortsdetector.ai.AiGoalRecommender
import com.example.shortsdetector.ai.AiHttpResponse
import com.example.shortsdetector.ai.AiTransport
import com.example.shortsdetector.ai.ClaudeMessagesClient
import com.example.shortsdetector.ai.TextFit
import com.example.shortsdetector.data.InterventionPolicyPreferences
import com.example.shortsdetector.data.goal.Goal
import com.example.shortsdetector.data.goal.GoalSource
import com.example.shortsdetector.data.goal.GoalType
import com.example.shortsdetector.data.profile.AgeBand
import com.example.shortsdetector.data.profile.UserProfile
import com.example.shortsdetector.data.profile.UsualDailyWatch
import com.example.shortsdetector.data.profile.WatchTimeSlot
import com.example.shortsdetector.data.stats.AgentInput
import com.example.shortsdetector.data.stats.AgentInputBuilder
import com.example.shortsdetector.data.stats.UsageStatsCalculator
import com.example.shortsdetector.data.usage.DemoSeedData
import com.example.shortsdetector.data.usage.InMemoryUsageStore
import com.example.shortsdetector.data.usage.InterventionMethod
import com.example.shortsdetector.data.usage.InterventionOutcome
import com.example.shortsdetector.data.usage.InterventionRecord
import com.example.shortsdetector.data.usage.PolicyChangeSource
import com.example.shortsdetector.data.usage.PolicyChangeStatus
import com.example.shortsdetector.data.usage.UsageSchema
import com.example.shortsdetector.intervention.AgentCopyProvider
import com.example.shortsdetector.intervention.InterventionCopy
import com.example.shortsdetector.intervention.InterventionCopyInput
import com.example.shortsdetector.intervention.InterventionCopyProvider
import com.example.shortsdetector.intervention.InterventionPolicy
import com.example.shortsdetector.intervention.LevelStreaks
import com.example.shortsdetector.testutil.FakeSharedPreferences
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

class PolicyAgentTest {

    private val zone = ZoneId.of("Asia/Seoul")
    private val today = LocalDate.of(2026, 11, 5)
    private val now = today.atTime(20, 0).atZone(zone).toInstant().toEpochMilli()

    private val policyPrefs = FakeSharedPreferences()
    private val agentPrefs = FakeSharedPreferences()
    private val store = InMemoryUsageStore()
    private val state = PreferencesAgentStore(policyPrefs, agentPrefs)

    private var calls = 0
    private var sentBody: String? = null
    private var answer: () -> AiHttpResponse = { AiHttpResponse(500, "") }
    private val transport = AiTransport { _, _, body -> calls++; sentBody = body; answer() }

    private fun agent(key: String = "k") =
        PolicyAgent(ClaudeMessagesClient(key, transport), state, store, { now + (++ticks) }) { "id-${++ids}" }

    private var ids = 0
    private var ticks = 0L

    private val base = InterventionPolicy(thresholdMs = 180_000, cooldownMs = 60_000, maxDailyInterventions = 6, level = 2)

    private fun decision(
        decision: String = "CHANGE",
        thresholdSec: Int = 120,
        cooldownSec: Int = 90,
        dailyMax: Int = 5,
        startLevel: Int = 3,
        reason: String = "알림을 많이 넘겨서 일찍 알려요",
        lv1: String = "잠깐, 계속 볼까요?",
        lv2: String = "지금 왜 보고 있어요?"
    ) = JSONObject()
        .put("decision", decision).put("thresholdSec", thresholdSec).put("cooldownSec", cooldownSec)
        .put("dailyMax", dailyMax).put("startLevel", startLevel).put("reason", reason)
        .put("lv1Message", lv1).put("lv2Question", lv2)

    private fun respond(json: JSONObject, stop: String = "end_turn") = respondText(json.toString(), stop)

    private fun respondText(text: String, stop: String = "end_turn") {
        val body = JSONObject()
            .put("content", JSONArray().put(JSONObject().put("type", "text").put("text", text)))
            .put("stop_reason", stop).toString()
        answer = { AiHttpResponse(200, body) }
    }

    private fun input(
        consent: Boolean = true,
        interventions: Int = 3,
        policy: InterventionPolicy = base,
        extra: List<InterventionRecord> = emptyList()
    ): AgentInput {
        InterventionPolicyPreferences.write(policyPrefs, policy)
        val list = (0 until interventions).map {
            InterventionRecord("i$it", null, now - 3_600_000L * (it + 1), InterventionMethod.POPUP, 1, 1, level = 1, outcome = InterventionOutcome.IGNORED)
        } + extra
        return AgentInputBuilder.build(
            UserProfile(AgeBand.TWENTIES, aiRecommendationConsent = consent), emptyList(), policy,
            emptyList(), list, store.recentPolicyChanges(10), zone, now
        )
    }

    private fun saved() = InterventionPolicyPreferences.read(policyPrefs)

    // --- 정상 ---

    @Test
    fun `CHANGE saves the policy and records an AGENT change with model and summary`() {
        respond(decision())
        val r = agent().run(input())
        assertEquals(AgentRunStatus.CHANGED, r.status)
        val p = saved()
        assertEquals(120_000, p.thresholdMs)
        assertEquals(90_000, p.cooldownMs)
        assertEquals(5, p.maxDailyInterventions)
        assertEquals(3, p.level)

        val change = store.recentPolicyChanges(1).single()
        assertEquals(PolicyChangeSource.AGENT, change.source)
        assertEquals(PolicyChangeStatus.APPLIED, change.status)
        assertEquals("claude-haiku-5-5", change.modelId)
        assertEquals(base, change.previousPolicy)
        assertEquals(p, change.newPolicy)
        assertEquals("알림을 많이 넘겨서 일찍 알려요", change.reasonText)
        assertTrue(change.inputSummary!!.contains("개입 3건"))
    }

    @Test
    fun `KEEP changes nothing, not even the messages`() {
        respond(decision("KEEP", thresholdSec = 30, dailyMax = 3))
        val r = agent().run(input())
        assertEquals(AgentRunStatus.KEPT, r.status)
        assertEquals(base, saved())
        assertTrue(store.recentPolicyChanges(5).isEmpty())
        assertNull(AgentPreferences.activeMessages(agentPrefs))
    }

    @Test
    fun `CHANGE that clips back to the same policy records nothing`() {
        respond(decision(thresholdSec = 180, cooldownSec = 60, dailyMax = 6, startLevel = 2))
        val r = agent().run(input())
        assertEquals(AgentRunStatus.KEPT, r.status)
        assertTrue(store.recentPolicyChanges(5).isEmpty())
    }

    // --- 범위와 변경 폭 ---

    private fun proposal(thresholdSec: Int = 180, cooldownSec: Int = 60, dailyMax: Int = 6, startLevel: Int = 2) =
        AgentProposal(false, thresholdSec, cooldownSec, dailyMax, startLevel, "r", "a", "b")

    @Test
    fun `threshold moves at most 50 percent in one run`() {
        assertEquals(90_000, AgentPolicyRules.apply(base, proposal(thresholdSec = 30)).thresholdMs)
        assertEquals(270_000, AgentPolicyRules.apply(base, proposal(thresholdSec = 600)).thresholdMs)
    }

    @Test
    fun `level moves one step and daily max three`() {
        val p = base.copy(level = 1, maxDailyInterventions = 10)
        val out = AgentPolicyRules.apply(p, proposal(startLevel = 3, dailyMax = 3))
        assertEquals(2, out.level)
        assertEquals(7, out.maxDailyInterventions)
    }

    @Test
    fun `agent range is stricter than the app range`() {
        val p = InterventionPolicy(thresholdMs = 500_000, cooldownMs = 60_000, maxDailyInterventions = 9)
        val out = AgentPolicyRules.apply(p, proposal(thresholdSec = 100_000, cooldownSec = 100_000, dailyMax = 100))
        assertEquals(600_000, out.thresholdMs)
        assertEquals(600_000, out.cooldownMs)
        assertEquals(10, out.maxDailyInterventions)

        val low = AgentPolicyRules.apply(p.copy(maxDailyInterventions = 4), proposal(thresholdSec = 1, cooldownSec = 1, dailyMax = 0))
        assertEquals(30_000, low.cooldownMs)
        assertEquals(3, low.maxDailyInterventions)
    }

    @Test
    fun `agent can never apply under 30 seconds, even from the 10 second test value`() {
        val test = InterventionPolicy(thresholdMs = 10_000)
        for (sec in listOf(-5, 0, 5, 10, 29)) {
            assertEquals(30_000, AgentPolicyRules.apply(test, proposal(thresholdSec = sec)).thresholdMs)
        }
        assertEquals(30_000, AgentPolicyRules.apply(InterventionPolicy(thresholdMs = 40_000), proposal(thresholdSec = 1)).thresholdMs)
    }

    @Test
    fun `start level stays within max level`() {
        val out = AgentPolicyRules.apply(base.copy(level = 2, maxLevel = 2), proposal(startLevel = 3))
        assertEquals(2, out.level)
        assertEquals(1, AgentPolicyRules.apply(base.copy(level = 1), proposal(startLevel = -3)).level)
    }

    @Test
    fun `other policy fields are untouched`() {
        val p = base.copy(escalateAfterSkips = 4, deescalateAfterStops = 5)
        val out = AgentPolicyRules.apply(p, proposal(thresholdSec = 120))
        assertEquals(4, out.escalateAfterSkips)
        assertEquals(5, out.deescalateAfterStops)
    }

    @Test
    fun `level change resets streaks, same level keeps them`() {
        InterventionPolicyPreferences.write(policyPrefs, base)
        InterventionPolicyPreferences.writeLevelState(policyPrefs, 2, LevelStreaks(skips = 1))
        InterventionPolicyPreferences.writeAgentPolicy(policyPrefs, base.copy(thresholdMs = 60_000))
        assertEquals(LevelStreaks(skips = 1), InterventionPolicyPreferences.readLevelStreaks(policyPrefs))
        InterventionPolicyPreferences.writeAgentPolicy(policyPrefs, base.copy(level = 3))
        assertEquals(LevelStreaks(), InterventionPolicyPreferences.readLevelStreaks(policyPrefs))
    }

    // --- 실패 시 정책 유지 ---

    private fun assertUntouched(r: AgentRunResult, kind: AiFailureKind, status: Int? = null) {
        assertEquals(AgentRunStatus.FAILED, r.status)
        assertEquals(kind, r.failure)
        assertEquals(status, r.httpStatus)
        assertEquals(base, saved())
        assertEquals(r.before, r.after)
        assertTrue(store.recentPolicyChanges(5).isEmpty())
        assertNull(AgentPreferences.activeMessages(agentPrefs))
    }

    @Test
    fun `network, timeout and http failures keep the policy`() {
        answer = { throw IOException() }
        assertUntouched(agent().run(input()), AiFailureKind.NETWORK)
        answer = { throw SocketTimeoutException() }
        assertUntouched(agent().run(input()), AiFailureKind.TIMEOUT)
        for (code in listOf(401, 429, 500)) {
            answer = { AiHttpResponse(code, "") }
            assertUntouched(agent().run(input()), AiFailureKind.HTTP_ERROR, code)
        }
    }

    @Test
    fun `refusal and truncation keep the policy`() {
        respond(decision(), "refusal")
        assertUntouched(agent().run(input()), AiFailureKind.REFUSED)
        respond(decision(), "max_tokens")
        assertUntouched(agent().run(input()), AiFailureKind.TRUNCATED)
    }

    @Test
    fun `malformed output is thrown away whole`() {
        val cases = listOf(
            JSONObject(decision().toString()).apply { remove("cooldownSec") },
            decision("MAYBE"),
            JSONObject(decision().toString()).put("thresholdSec", "120"),
            JSONObject(decision().toString()).put("dailyMax", 4.5),
            JSONObject(decision().toString()).apply { remove("reason") },
            JSONObject().put("goals", JSONArray())
        )
        for (json in cases) {
            respond(json)
            assertUntouched(agent().run(input()), AiFailureKind.BAD_FORMAT)
        }
        respondText("그냥 글입니다")
        assertEquals(AiFailureKind.BAD_FORMAT, agent().run(input()).failure)
    }

    @Test
    fun `no key keeps the policy without a call`() {
        val r = agent(key = " ").run(input())
        assertUntouched(r, AiFailureKind.NO_KEY)
        assertEquals(0, calls)
    }

    // --- 게이트 ---

    @Test
    fun `no consent means no call`() {
        respond(decision())
        val r = agent().run(input(consent = false))
        assertEquals(AgentRunStatus.NO_CONSENT, r.status)
        assertEquals(0, calls)
        assertEquals(base, saved())
    }

    @Test
    fun `fewer than three interventions means no call`() {
        respond(decision())
        val r = agent().run(input(interventions = 2))
        assertEquals(AgentRunStatus.NOT_ENOUGH_DATA, r.status)
        assertEquals(0, calls)
        assertNull(AgentPreferences.lastRunAtMs(agentPrefs))
    }

    private fun AgentPreferences.lastRunAtMs(p: FakeSharedPreferences): Long? =
        if (p.contains("last_run_at")) p.getLong("last_run_at", 0) else null

    @Test
    fun `demo interventions do not count as data`() {
        respond(decision())
        val demo = (0 until 5).map {
            InterventionRecord("d$it", null, now - 1_000L * it, InterventionMethod.POPUP, 1, 1, level = 1, outcome = InterventionOutcome.IGNORED, isDemo = true)
        }
        assertEquals(AgentRunStatus.NOT_ENOUGH_DATA, agent().run(input(interventions = 0, extra = demo)).status)
    }

    @Test
    fun `switching the agent off stops it and returns to fixed copy`() {
        respond(decision())
        agent().run(input())
        assertEquals("잠깐, 계속 볼까요?", AgentPreferences.activeMessages(agentPrefs)!!.lv1Message)

        agentPrefs.values["enabled"] = false
        val policy = saved()
        calls = 0
        val r = agent().run(input(policy = policy))
        assertEquals(AgentRunStatus.DISABLED, r.status)
        assertEquals(0, calls)
        assertNull(AgentPreferences.activeMessages(agentPrefs))
    }

    @Test
    fun `run time is stored when the API was attempted, including failures`() {
        answer = { AiHttpResponse(500, "") }
        agent().run(input())
        assertTrue(agentPrefs.getLong("last_run_at", 0) >= now)
    }

    @Test
    fun `schedule needs 24 hours, consent and the switch`() {
        val day = AgentSchedule.INTERVAL_MS
        assertTrue(AgentSchedule.isDue(now, null, enabled = true, consent = true))
        assertTrue(AgentSchedule.isDue(now, now - day, enabled = true, consent = true))
        assertFalse(AgentSchedule.isDue(now, now - day + 1, enabled = true, consent = true))
        assertFalse(AgentSchedule.isDue(now, null, enabled = true, consent = false))
        assertFalse(AgentSchedule.isDue(now, null, enabled = false, consent = true))
    }

    // --- 되돌리기 ---

    @Test
    fun `revert restores the previous policy, clears messages and records USER`() {
        respond(decision())
        agent().run(input())
        val reverted = agent().revertLatest("되돌림")!!

        assertEquals(base, saved())
        assertNull(AgentPreferences.activeMessages(agentPrefs))
        assertEquals(PolicyChangeSource.USER, reverted.source)
        assertEquals(PolicyChangeStatus.REVERTED_BY_USER, reverted.status)
        assertEquals(base, reverted.newPolicy)
        val agentChange = store.recentPolicyChanges(5).first { it.source == PolicyChangeSource.AGENT }
        assertEquals(PolicyChangeStatus.REVERTED_BY_USER, agentChange.status)
    }

    @Test
    fun `second revert does nothing and never walks further back`() {
        respond(decision())
        agent().run(input())
        val afterFirst = saved()
        respond(decision(thresholdSec = 90, startLevel = 2))
        agent().run(input(policy = afterFirst))
        assertEquals(afterFirst, agent().revertLatest("r")!!.newPolicy)
        val restored = saved()
        assertNull(agent().revertLatest("r"))
        assertEquals(restored, saved())
    }

    @Test
    fun `nothing to revert`() {
        assertNull(agent().revertLatest("r"))
    }

    // --- 문구 ---

    @Test
    fun `reason and messages are fitted at a space`() {
        respond(
            decision(
                reason = "최근 일주일 동안 알림을 스무 번 받았는데 그중 대부분을 넘겨서 다음에는 조금 더 일찍 알려 드려요",
                lv1 = "지금 보고 있는 영상이 끝나면 잠깐 멈춰 보는 건 어떨까요 정말로요",
                lv2 = "지금 이 영상을 계속 보고 싶은 이유가 무엇인지 한번 떠올려 볼까요"
            )
        )
        agent().run(input())
        val m = AgentPreferences.activeMessages(agentPrefs)!!
        val reason = store.recentPolicyChanges(1).single().reasonText
        for ((text, max) in listOf(reason to 40, m.lv1Message!! to 30, m.lv2Question!! to 30)) {
            assertTrue(text, text.length <= max)
            assertTrue(text, text.endsWith("…"))
            assertFalse(text, text.dropLast(1).endsWith(" "))
        }
    }

    @Test
    fun `banned words fall back to fixed copy and a fixed reason, the policy still applies`() {
        respond(decision(reason = "숏폼 중독이 걱정돼요", lv1 = "도파민을 줄여 봐요", lv2 = "지금 왜 보고 있어요?"))
        assertEquals(AgentRunStatus.CHANGED, agent().run(input()).status)
        assertEquals("최근 반응을 보고 조금 조정했어요", store.recentPolicyChanges(1).single().reasonText)
        val m = AgentPreferences.activeMessages(agentPrefs)!!
        assertNull(m.lv1Message)
        assertEquals("지금 왜 보고 있어요?", m.lv2Question)
    }

    @Test
    fun `blank text is not accepted`() {
        assertNull(AgentText.clean("   ", 30))
        assertEquals("두 줄 문구", AgentText.clean("두 줄\n문구", 30))
    }

    @Test
    fun `copy provider uses agent text, then fixed text, and leaves level 3 alone`() {
        val fixed = object : InterventionCopyProvider {
            override fun copyFor(input: InterventionCopyInput) = InterventionCopy("고정 ${input.level}", "본문")
            override fun levelChangeReason(from: Int, to: Int, policy: InterventionPolicy) = "이유"
        }
        var messages: AgentMessages? = AgentMessages("에이전트 1", "에이전트 2")
        val p = AgentCopyProvider(fixed) { messages }
        fun copy(level: Int) = p.copyFor(InterventionCopyInput(level, 1_000, null))

        assertEquals(InterventionCopy("에이전트 1", "고정 1"), copy(1))
        assertEquals(InterventionCopy("에이전트 2"), copy(2))
        assertEquals("고정 3", copy(3).title)
        assertEquals("이유", p.levelChangeReason(1, 2, InterventionPolicy()))

        messages = AgentMessages(null, null)
        assertEquals("고정 1", copy(1).title)
        messages = null
        assertEquals("고정 2", copy(2).title)
    }

    // --- 입력 ---

    @Test
    fun `sent data has recent stats and no records or package names`() {
        val batch = DemoSeedData.build(DemoSeedData.Scenario.LATE_NIGHT_SKIPPER, today, zone, now)
        InterventionPolicyPreferences.write(policyPrefs, base)
        val i = AgentInputBuilder.build(
            UserProfile(AgeBand.TWENTIES, aiRecommendationConsent = true), emptyList(), base,
            batch.sessions, batch.interventions, emptyList(), zone, now
        )
        respond(decision())
        agent().run(i)
        assertFalse("com.google" in sentBody!!)
        assertFalse("seed-" in sentBody!!)
        val recent = AgentInputJson.toJson(i).getJSONObject("recent")
        assertEquals(7, recent.getJSONArray("dailyWatchMs").length())
        assertTrue(recent.getJSONObject("lv2Reasons").length() > 0)
    }

    @Test
    fun `input keeps only the last three policy changes and no demo ones`() {
        respond(decision())
        val a = agent()
        repeat(5) { n ->
            respond(decision(thresholdSec = 100 + n))
            a.run(input())
        }
        val i = AgentInputBuilder.build(
            null, emptyList(), base, emptyList(), emptyList(),
            store.recentPolicyChanges(10).map { it.copy(isDemo = it.id == "id-5") }, zone, now
        )
        assertEquals(3, i.recentPolicyChanges.size)
        assertFalse(i.recentPolicyChanges.any { it.isDemo })
    }

    @Test
    fun `system prompt states the limits and tone`() {
        val p = PolicyAgent.SYSTEM_PROMPT
        for (word in listOf("30~600", "3~10", "±50%", "±3", "±1", "~해요", "높임말", "KEEP")) assertTrue(word, word in p)
    }

    @Test
    fun `schema closes every object and needs all fields`() {
        assertFalse(PolicyAgent.SCHEMA.getBoolean("additionalProperties"))
        assertEquals(PolicyAgent.SCHEMA.getJSONObject("properties").length(), PolicyAgent.SCHEMA.getJSONArray("required").length())
    }

    // --- 시드 ---

    private fun recentOf(s: DemoSeedData.Scenario) = DemoSeedData.build(s, today, zone, now).let {
        UsageStatsCalculator.recent(it.sessions, it.interventions, zone, now)
    }

    @Test
    fun `scenario A looks like late night skipping`() {
        val r = recentOf(DemoSeedData.Scenario.LATE_NIGHT_SKIPPER)
        assertTrue(r.deliveredInterventions >= PolicyAgent.MIN_INTERVENTIONS)
        val night = r.timeSlots.first { it.slot == WatchTimeSlot.LATE_NIGHT }.watchMs
        assertTrue(night > r.timeSlots.sumOf { it.watchMs } / 2)
        val skipped = r.levelOutcomes.sumOf { it.continued + it.ignored }
        val stopped = r.levelOutcomes.sumOf { it.stopped }
        assertTrue(skipped > stopped * 4)
        assertEquals(7, r.days.count { it.watchMs > 0 })
    }

    @Test
    fun `scenario B looks like easy stopping`() {
        val r = recentOf(DemoSeedData.Scenario.EASY_STOPPER)
        assertTrue(r.deliveredInterventions >= PolicyAgent.MIN_INTERVENTIONS)
        assertTrue(r.levelOutcomes.sumOf { it.stopped } > r.levelOutcomes.sumOf { it.continued + it.ignored } * 4)
        assertTrue(r.averageSessionMs < 600_000)
    }

    @Test
    fun `seed records are real to stats, marked as seed, and never demo`() {
        val b = DemoSeedData.build(DemoSeedData.Scenario.EASY_STOPPER, today, zone, now)
        assertTrue(b.sessions.all { it.isSeed && !it.isDemo })
        assertTrue(b.interventions.all { it.isSeed && !it.isDemo })
        assertEquals(b, DemoSeedData.build(DemoSeedData.Scenario.EASY_STOPPER, today, zone, now))
    }

    private fun insert(b: DemoSeedData.Batch) {
        b.sessions.forEach(store::upsertSession)
        b.interventions.forEach(store::insertIntervention)
    }

    @Test
    fun `reset clears seed records, seed delete leaves real ones`() {
        val b = DemoSeedData.build(DemoSeedData.Scenario.LATE_NIGHT_SKIPPER, today, zone, now)
        insert(b)
        store.insertIntervention(InterventionRecord("real", null, now, InterventionMethod.POPUP, 1, 1, level = 1))
        store.deleteSeedRecords()
        assertEquals(listOf("real"), store.allInterventions().map { it.id })
        assertTrue(store.allSessions().isEmpty())

        insert(b)
        store.deleteAll()
        assertTrue(store.allInterventions().isEmpty())
        assertTrue(store.allSessions().isEmpty())
    }

    @Test
    fun `reset of agent state clears messages and run time but keeps the switch`() {
        agentPrefs.values["enabled"] = false
        AgentPreferences.writeMessages(agentPrefs, AgentMessages("a", "b"))
        AgentPreferences.writeLastRun(agentPrefs, 5)
        AgentPreferences.clearRunState(agentPrefs)
        assertFalse(agentPrefs.contains("lv1_message"))
        assertFalse(agentPrefs.contains("last_run_at"))
        assertFalse(AgentPreferences.isEnabled(agentPrefs))
    }

    @Test
    fun `migration 4 to 5 only adds columns`() {
        assertEquals(5, UsageSchema.VERSION)
        assertTrue(UsageSchema.MIGRATE_4_TO_5.all { it.startsWith("ALTER TABLE") && "ADD COLUMN is_seed" in it })
    }

    // --- 목표 보완 ---

    private fun aiGoal(type: GoalType, value: Int) = Goal(type, value, "라벨", "이유", GoalSource.AI)

    @Test
    fun `daily goal at or above usual becomes 80 percent in five minute steps`() {
        val u = UsualDailyWatch.HOUR_1_TO_2                 // 90분
        assertEquals(70, AiGoalRecommender.belowUsual(aiGoal(GoalType.DAILY_TOTAL, 90), u).value)
        assertEquals(70, AiGoalRecommender.belowUsual(aiGoal(GoalType.DAILY_TOTAL, 120), u).value)
        assertEquals("하루 70분 이하로 보기", AiGoalRecommender.belowUsual(aiGoal(GoalType.DAILY_TOTAL, 120), u).label)
        assertEquals(15, AiGoalRecommender.belowUsual(aiGoal(GoalType.DAILY_TOTAL, 30), UsualDailyWatch.UNDER_30_MIN).value)
        assertEquals(170, AiGoalRecommender.belowUsual(aiGoal(GoalType.DAILY_TOTAL, 240), UsualDailyWatch.OVER_3_HOURS).value)
    }

    @Test
    fun `goals below usual, other types and missing usual are left alone`() {
        val u = UsualDailyWatch.HOUR_1_TO_2
        assertEquals(89, AiGoalRecommender.belowUsual(aiGoal(GoalType.DAILY_TOTAL, 89), u).value)
        assertEquals("라벨", AiGoalRecommender.belowUsual(aiGoal(GoalType.DAILY_TOTAL, 89), u).label)
        assertEquals(200, AiGoalRecommender.belowUsual(aiGoal(GoalType.DAILY_TOTAL, 200), null).value)
        assertEquals(50, AiGoalRecommender.belowUsual(aiGoal(GoalType.PER_SESSION, 50), u).value)
    }

    @Test
    fun `text is cut at the last space and stays within the limit`() {
        assertEquals("짧은 문구", TextFit.fit("  짧은 문구 ", 40))
        assertEquals("하나 둘 셋…", TextFit.fit("하나 둘 셋 넷다섯여섯일곱", 8))
        val long = "평소보다 조금만 줄여 보면 밤에 더 편안하게 쉴 수 있을 것 같아서 이렇게 추천해요"
        val out = TextFit.fit(long, 40)
        assertTrue(out.length <= 40)
        assertTrue(out.endsWith("…"))
        assertTrue(long.startsWith(out.dropLast(1)))
        assertTrue(long[out.length - 1] == ' ')
    }

    @Test
    fun `text at the exact limit is unchanged and punctuation does not dangle`() {
        assertEquals("가".repeat(25), TextFit.fit("가".repeat(25), 25))
        assertEquals("하나, 둘…", TextFit.fit("하나, 둘, 셋넷다섯여섯", 8))
    }
}
