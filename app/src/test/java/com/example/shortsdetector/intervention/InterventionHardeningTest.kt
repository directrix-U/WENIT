package com.example.shortsdetector.intervention

import com.example.shortsdetector.data.InterventionPolicyPreferences
import com.example.shortsdetector.data.goal.GoalRules
import com.example.shortsdetector.data.goal.GoalType
import com.example.shortsdetector.data.stats.AgentInputBuilder
import com.example.shortsdetector.data.stats.UsageStatsCalculator
import com.example.shortsdetector.data.usage.InMemoryUsageStore
import com.example.shortsdetector.data.usage.InterventionMethod
import com.example.shortsdetector.data.usage.InterventionOutcome
import com.example.shortsdetector.data.usage.InterventionOutcome.CONTINUED
import com.example.shortsdetector.data.usage.InterventionOutcome.IGNORED
import com.example.shortsdetector.data.usage.InterventionOutcome.STOPPED
import com.example.shortsdetector.data.usage.InterventionRecord
import com.example.shortsdetector.data.usage.PolicyChangeRecord
import com.example.shortsdetector.data.usage.PolicyChangeSource
import com.example.shortsdetector.data.usage.PolicyChangeStatus
import com.example.shortsdetector.data.usage.SessionRecorder
import com.example.shortsdetector.data.usage.UsageClock
import com.example.shortsdetector.data.usage.UsageSchema
import com.example.shortsdetector.detection.DetectionEngine
import com.example.shortsdetector.detection.adapters.youtube.YouTubeShortsAdapter
import com.example.shortsdetector.detection.model.DetectionState
import com.example.shortsdetector.detection.model.EndReason
import com.example.shortsdetector.detection.state.DetectionTuning
import com.example.shortsdetector.testutil.FakeSharedPreferences
import com.example.shortsdetector.testutil.Fixtures
import com.example.shortsdetector.testutil.Fixtures.YT
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

/** 2단계 리스크 처리: Lv.3 시간, 최대 단계, 이탈 신호, 시연 분리, 미답 개입 정리. */
class InterventionHardeningTest {

    // --- Lv.3 시간 흐름 ---

    private class Clock {
        var now = 0L
        val queue = ArrayList<Pair<Long, () -> Unit>>()
        val post: (Long, () -> Unit) -> Unit = { delay, action -> queue += (now + delay) to action }
        fun advanceTo(t: Long) {
            while (true) {
                val next = queue.filter { it.first <= t }.minByOrNull { it.first } ?: break
                queue.remove(next)
                now = next.first
                next.second()
            }
            now = t
        }
    }

    private val clock = Clock()
    private val counts = ArrayList<Int>()
    private var buttons = 0
    private var timedOut = 0
    private val timeline = BreathingTimeline(clock.post, { counts += it }, { buttons++ }, { timedOut++ })

    @Test
    fun `countdown runs ten seconds with no buttons, then shows them`() {
        timeline.start()
        clock.advanceTo(9_999)
        assertEquals((10 downTo 1).toList(), counts)
        assertEquals(0, buttons)
        clock.advanceTo(10_000)
        assertEquals(1, buttons)
        assertEquals(0, timedOut)
    }

    @Test
    fun `no answer closes the overlay 30 seconds after the buttons appear, as IGNORED`() {
        val store = InMemoryUsageStore()
        val prefs = FakeSharedPreferences()
        val time = object : UsageClock {
            override fun epochNowMs() = 1L
            override fun elapsedNowMs() = 0L
        }
        val copy = object : InterventionCopyProvider {
            override fun copyFor(input: InterventionCopyInput) = InterventionCopy("t")
            override fun levelChangeReason(from: Int, to: Int, policy: InterventionPolicy) = "r"
        }
        val coordinator = InterventionCoordinator(store, PreferencesPolicyStore(prefs), copy, time)
        store.insertIntervention(InterventionRecord("i", null, 1, InterventionMethod.POPUP, 1, 1, level = 3))
        coordinator.begin("i", 1, 3)

        val t = BreathingTimeline(clock.post, {}, {}, { coordinator.abandon() })
        t.start()
        clock.advanceTo(39_999)
        assertNull(store.allInterventions().single().outcome)
        clock.advanceTo(40_000)
        assertEquals(IGNORED, store.allInterventions().single().outcome)
    }

    @Test
    fun `an answer or closing cancels the timeline at any point`() {
        timeline.start()
        clock.advanceTo(3_000)
        timeline.cancel()
        clock.advanceTo(100_000)
        assertEquals(0, buttons)
        assertEquals(0, timedOut)

        val second = BreathingTimeline(clock.post, {}, { buttons++ }, { timedOut++ })
        second.start()
        clock.advanceTo(115_000)
        second.cancel()
        clock.advanceTo(200_000)
        assertEquals(1, buttons)
        assertEquals(0, timedOut)
    }

    // --- 최대 단계 ---

    private fun next(p: InterventionPolicy, o: InterventionOutcome) = InterventionLevelRules.next(p, LevelStreaks(), o)

    @Test
    fun `max level 2 keeps level 2 when the user continues`() {
        val s = next(InterventionPolicy(level = 2, maxLevel = 2), CONTINUED)
        assertEquals(2, s.policy.level)
        assertFalse(s.levelChanged)
    }

    @Test
    fun `max level 2 still rises from 1 to 2 and falls back down`() {
        assertEquals(2, next(InterventionPolicy(escalateAfterSkips = 1, maxLevel = 2), IGNORED).policy.level)
        assertEquals(1, next(InterventionPolicy(level = 2, deescalateAfterStops = 1, maxLevel = 2), STOPPED).policy.level)
    }

    @Test
    fun `max level is clamped to 2 or 3, default 3, and caps the current level`() {
        assertEquals(3, InterventionPolicy().maxLevel)
        assertEquals(2, InterventionPolicy(maxLevel = 1).clamped().maxLevel)
        assertEquals(3, InterventionPolicy(maxLevel = 9).clamped().maxLevel)
        assertEquals(2, InterventionPolicy(level = 3, maxLevel = 2).clamped().level)
    }

    @Test
    fun `max level survives the codec and old records default to 3`() {
        val p = InterventionPolicy(maxLevel = 2)
        assertEquals(p, InterventionPolicyCodec.decode(InterventionPolicyCodec.encode(p)))
        assertEquals(3, InterventionPolicyCodec.decode("level=1")!!.maxLevel)
    }

    @Test
    fun `demo mode respects max level`() {
        val prefs = FakeSharedPreferences()
        InterventionPolicyPreferences.write(prefs, InterventionPolicy(maxLevel = 2))
        InterventionPolicyPreferences.writeDemoMode(prefs, true)
        InterventionPolicyPreferences.writeLevelState(prefs, 3, LevelStreaks())
        assertEquals(2, InterventionPolicyPreferences.resolve(prefs, emptyList()).policy.level)
    }

    // --- 카운트다운 시간과 이탈 신호 ---

    private val tuning = DetectionTuning()

    private fun engine(cooldownMs: Long = 1_000, maxDaily: Int = Int.MAX_VALUE) = DetectionEngine(
        tuning, listOf(YouTubeShortsAdapter(tuning)), cooldownMs,
        watchThresholdMs = 30_000, maxDailyInterventions = maxDaily, currentDay = { 1L }
    ).also { it.setEnabledPackages(setOf(YT), 0) }

    private fun DetectionEngine.frame(t: Long) =
        onSnapshot(t, Fixtures.youtubeShorts()).also { if (it.notifySession != null) onNotificationPosted(t) }

    private fun DetectionEngine.enter(from: Long) {
        frame(from); frame(from + 250); frame(from + 500)
    }

    private fun DetectionEngine.watch(from: Long, to: Long) = (from..to step 1_000L).map { frame(it) }

    private fun DetectionEngine.leave(from: Long) {
        onRootUnavailable(from); onRootUnavailable(from + 1_000); onRootUnavailable(from + 2_000)
    }

    @Test
    fun `countdown time goes to obscured, not to watch time`() {
        val e = engine()
        e.enter(0)
        e.watch(1_000, 10_000)
        val before = e.sessionProgress()!!

        e.setWatchHold(true)
        e.watch(11_000, 20_000)
        val held = e.sessionProgress()!!
        assertEquals(before.estimatedWatchMs, held.estimatedWatchMs)
        assertEquals(before.obscuredMs + 10_000, held.obscuredMs)

        e.setWatchHold(false)
        e.watch(21_000, 25_000)
        assertEquals(held.estimatedWatchMs + 5_000, e.sessionProgress()!!.estimatedWatchMs)
        assertEquals(held.obscuredMs, e.sessionProgress()!!.obscuredMs)
    }

    @Test
    fun `hold ends with the session and does not reach the next one`() {
        val e = engine()
        e.enter(0)
        e.setWatchHold(true)
        e.leave(1_000)
        e.enter(30_000)
        e.watch(31_000, 35_000)
        assertEquals(4_500, e.sessionProgress()!!.estimatedWatchMs)
        assertEquals(0, e.sessionProgress()!!.obscuredMs)
    }

    @Test
    fun `hold without a session does nothing`() {
        val e = engine()
        e.setWatchHold(true)
        e.enter(0)
        e.watch(1_000, 3_000)
        assertEquals(0, e.sessionProgress()!!.obscuredMs)
    }

    @Test
    fun `first exit signal is reported once and session ending timing does not change`() {
        val e = engine()
        e.enter(0)
        e.watch(1_000, 5_000)

        val first = e.onRootUnavailable(6_000)
        assertTrue(first.exitSignalStarted)
        assertEquals(DetectionState.EXIT_PENDING, first.step.current)
        assertNull(first.endedSession)
        assertFalse(first.cancelNotification)

        val second = e.onRootUnavailable(7_000)
        assertFalse(second.exitSignalStarted)
        assertNull(second.endedSession)

        val end = e.onRootUnavailable(8_000)
        assertFalse(end.exitSignalStarted)
        assertEquals(EndReason.EXIT_CONFIRMED, end.endedSession!!.endReason)
    }

    @Test
    fun `foreign window is an exit signal, ordinary frames and exits without a session are not`() {
        val e = engine()
        assertFalse(e.onRootUnavailable(1).exitSignalStarted)
        e.enter(100)
        assertFalse(e.watch(1_000, 3_000).any { it.exitSignalStarted })
        assertTrue(e.onForeignWindow(4_000, "com.other").exitSignalStarted)
    }

    @Test
    fun `a transient exit signal does not end the session`() {
        val e = engine()
        e.enter(0)
        e.watch(1_000, 5_000)
        assertTrue(e.onRootUnavailable(6_000).exitSignalStarted)
        assertEquals(DetectionState.SHORTFORM_ESTIMATED, e.frame(7_000).step.current)
        assertTrue(e.onRootUnavailable(8_000).exitSignalStarted)   // 다시 나가면 다시 알린다
    }

    // --- 시연 전용 카운터 ---

    private fun DetectionEngine.session(from: Long): List<com.example.shortsdetector.detection.EngineOutput> {
        enter(from)
        return watch(from + 1_000, from + 40_000).also { leave(from + 41_000) }
    }

    @Test
    fun `demo uses its own counter and does not spend the real daily limit`() {
        val e = engine(maxDaily = 1)
        e.applyPolicy(30_000, 1_000, 1, demoRunId = 100)
        assertEquals(1, e.session(0).count { it.notifySession != null })
        assertEquals(1, e.session(60_000).count { it.suppressedByDailyLimit })

        e.applyPolicy(30_000, 1_000, 1, demoRunId = null)
        assertEquals(1, e.session(120_000).count { it.notifySession != null })
    }

    @Test
    fun `turning demo on again resets its counter, reapplying the same run does not`() {
        val e = engine(maxDaily = 1)
        e.applyPolicy(30_000, 1_000, 1, demoRunId = 100)
        e.session(0)
        e.applyPolicy(30_000, 1_000, 1, demoRunId = 100)
        assertEquals(1, e.session(60_000).count { it.suppressedByDailyLimit })

        e.applyPolicy(30_000, 1_000, 1, demoRunId = 200)
        assertEquals(1, e.session(120_000).count { it.notifySession != null })
    }

    @Test
    fun `a used real limit does not block demo and restored demo count still applies`() {
        val e = engine(maxDaily = 1)
        e.seedDailyInterventions(1)
        e.applyPolicy(30_000, 1_000, 1, demoRunId = 100)
        assertEquals(1, e.session(0).count { it.notifySession != null })

        val restarted = engine(maxDaily = 1)
        restarted.applyPolicy(30_000, 1_000, 1, demoRunId = 100)
        restarted.seedDailyInterventions(0, 1)
        assertEquals(1, restarted.session(0).count { it.suppressedByDailyLimit })
    }

    @Test
    fun `resolved policy exposes the run id only while demo mode is on`() {
        val prefs = FakeSharedPreferences()
        assertNull(InterventionPolicyPreferences.resolve(prefs, emptyList()).demoRunId)
        InterventionPolicyPreferences.writeDemoMode(prefs, true, nowMs = 42)
        assertEquals(42L, InterventionPolicyPreferences.resolve(prefs, emptyList()).demoRunId)
        InterventionPolicyPreferences.writeDemoMode(prefs, false)
        assertNull(InterventionPolicyPreferences.resolve(prefs, emptyList()).demoRunId)
    }

    @Test
    fun `clearing records resets both daily counters`() {
        val e = engine(maxDaily = 1)
        e.seedDailyInterventions(1, 1)
        assertEquals(1, e.session(0).count { it.suppressedByDailyLimit })

        e.resetDailyInterventions()
        assertEquals(1, e.session(60_000).count { it.notifySession != null })

        e.resetDailyInterventions()
        e.applyPolicy(30_000, 1_000, 1, demoRunId = 100)
        assertEquals(1, e.session(120_000).count { it.notifySession != null })
    }

    @Test
    fun `records cleared mark changes on every delete and keeps the policy`() {
        val prefs = FakeSharedPreferences()
        InterventionPolicyPreferences.write(prefs, InterventionPolicy(thresholdMs = 60_000))
        InterventionPolicyPreferences.writeRecordsCleared(prefs, 5)
        assertEquals(5L, prefs.getLong(InterventionPolicyPreferences.KEY_RECORDS_CLEARED_AT, 0))
        InterventionPolicyPreferences.writeRecordsCleared(prefs, 6)
        assertEquals(6L, prefs.getLong(InterventionPolicyPreferences.KEY_RECORDS_CLEARED_AT, 0))
        assertEquals(60_000, InterventionPolicyPreferences.read(prefs).thresholdMs)
    }

    // --- 10초 기준 ---

    @Test
    fun `ten seconds is a selectable threshold inside the hard bounds`() {
        assertTrue(10_000L in com.example.shortsdetector.DetectionConfig.WATCH_THRESHOLD_CHOICES_MS)
        assertEquals(10_000, InterventionPolicy(thresholdMs = 10_000).clamped().thresholdMs)
        assertEquals(10_000, InterventionPolicy(thresholdMs = 1).clamped().thresholdMs)
    }

    @Test
    fun `every threshold choice survives clamping unchanged`() {
        for (ms in com.example.shortsdetector.DetectionConfig.WATCH_THRESHOLD_CHOICES_MS) {
            assertEquals(ms, InterventionPolicy(thresholdMs = ms).clamped().thresholdMs)
        }
    }

    @Test
    fun `engine intervenes at ten seconds`() {
        val e = engine()
        e.applyPolicy(10_000, 1_000, 10)
        e.enter(0)
        val first = e.watch(1_000, 15_000).first { it.notifySession != null }
        assertTrue(first.notifyWatchMs!! in 10_000..11_000)
    }

    // --- 시연 기록 분리 ---

    private val zone = ZoneId.of("Asia/Seoul")
    private val day = LocalDate.of(2026, 11, 5)
    private val now = day.atTime(20, 0).atZone(zone).toInstant().toEpochMilli()

    private fun record(id: String, demo: Boolean, at: Long = now - 1_000) = InterventionRecord(
        id, null, at, InterventionMethod.POPUP, 1, 1, level = 1, outcome = STOPPED, isDemo = demo
    )

    private val mixed = listOf(record("real", false), record("demo", true))

    @Test
    fun `today, yesterday and week leave demo interventions out by default`() {
        val goals = listOf(GoalRules.userGoal(GoalType.SELF_STOPS, 1))
        val today = UsageStatsCalculator.today(emptyList(), mixed, goals, zone, now)
        assertEquals(1, today.interventionCount)
        assertEquals(1, today.selfStopCount)

        val week = UsageStatsCalculator.week(emptyList(), mixed, zone, now)
        assertEquals(1, week.levelStopRates.first().delivered)

        val onlyDemo = listOf(record("demo", true, now - 86_400_000))
        val y = UsageStatsCalculator.yesterday(emptyList(), onlyDemo, goals, zone, now)
        assertEquals(0, y.goals.single().currentValue)
    }

    @Test
    fun `demo interventions can be included on request`() {
        assertEquals(2, UsageStatsCalculator.today(emptyList(), mixed, emptyList(), zone, now, includeDemo = true).interventionCount)
        assertEquals(2, UsageStatsCalculator.week(emptyList(), mixed, zone, now, includeDemo = true).levelStopRates.first().delivered)
    }

    @Test
    fun `agent input leaves out demo interventions and demo policy changes`() {
        fun change(id: String, demo: Boolean) = PolicyChangeRecord(
            id, now, PolicyChangeSource.RULE, null, InterventionPolicy(), "r", null, PolicyChangeStatus.APPLIED, isDemo = demo
        )
        val input = AgentInputBuilder.build(
            null, emptyList(), InterventionPolicy(), emptyList(), mixed,
            listOf(change("real", false), change("demo", true)), zone, now
        )
        assertEquals(1, input.today.interventionCount)
        assertEquals(listOf("real"), input.recentPolicyChanges.map { it.id })
    }

    @Test
    fun `coordinator marks a demo level change and recorder marks a demo intervention`() {
        val store = InMemoryUsageStore()
        val prefs = FakeSharedPreferences()
        InterventionPolicyPreferences.writeDemoMode(prefs, true)
        val time = object : UsageClock {
            override fun epochNowMs() = 1L
            override fun elapsedNowMs() = 0L
        }
        val copy = object : InterventionCopyProvider {
            override fun copyFor(input: InterventionCopyInput) = InterventionCopy("t")
            override fun levelChangeReason(from: Int, to: Int, policy: InterventionPolicy) = "r"
        }
        val recorder = SessionRecorder(store, time)
        val id = recorder.onIntervention(null, InterventionMethod.POPUP, 1, 1, level = 1, isDemo = true)
        val coordinator = InterventionCoordinator(store, PreferencesPolicyStore(prefs), copy, time)
        coordinator.begin(id, 1, 1)
        coordinator.respond(CONTINUED)

        assertTrue(store.allInterventions().single().isDemo)
        assertTrue(store.recentPolicyChanges(1).single().isDemo)
    }

    @Test
    fun `migration 2 to 3 only adds columns and marks known demo changes`() {
        assertEquals(3, UsageSchema.VERSION)
        val sql = UsageSchema.MIGRATE_2_TO_3.joinToString("\n").uppercase()
        assertFalse("DROP" in sql || "DELETE" in sql || "RENAME" in sql)
        assertEquals(1, UsageSchema.MIGRATE_2_TO_3.count { it.startsWith("UPDATE") })
    }

    // --- 답 없이 남은 개입 ---

    @Test
    fun `service start closes unanswered shown interventions only`() {
        val store = InMemoryUsageStore()
        fun add(id: String, method: InterventionMethod, level: Int?, outcome: InterventionOutcome? = null) =
            store.insertIntervention(InterventionRecord(id, null, 1, method, 1, 1, level = level, outcome = outcome))
        add("popup", InterventionMethod.POPUP, 1)
        add("notification", InterventionMethod.NOTIFICATION, 2)
        add("answered", InterventionMethod.POPUP, 1, STOPPED)
        add("cooldown", InterventionMethod.SUPPRESSED_BY_COOLDOWN, 1)
        add("limit", InterventionMethod.SUPPRESSED_BY_DAILY_LIMIT, 1)
        add("failed", InterventionMethod.FAILED, 1)
        add("v1", InterventionMethod.POPUP, null)

        val time = object : UsageClock {
            override fun epochNowMs() = 1_000_000L
            override fun elapsedNowMs() = 0L
        }
        SessionRecorder(store, time).onServiceStarted(10_000_000)

        val byId = store.allInterventions().associate { it.id to it.outcome }
        assertEquals(IGNORED, byId["popup"])
        assertEquals(IGNORED, byId["notification"])
        assertEquals(STOPPED, byId["answered"])
        assertNull(byId["cooldown"])
        assertNull(byId["limit"])
        assertNull(byId["failed"])
        assertNull(byId["v1"])
    }
}
