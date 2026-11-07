package com.example.shortsdetector.intervention

import com.example.shortsdetector.data.InterventionPolicyPreferences
import com.example.shortsdetector.data.stats.SavedTimeCalculator
import com.example.shortsdetector.data.stats.UsageStatsCalculator
import com.example.shortsdetector.data.usage.InMemoryUsageStore
import com.example.shortsdetector.data.usage.InterventionMethod
import com.example.shortsdetector.data.usage.InterventionOutcome.CONTINUED
import com.example.shortsdetector.data.usage.InterventionOutcome.INTERRUPTED
import com.example.shortsdetector.data.usage.InterventionOutcome.STOPPED
import com.example.shortsdetector.data.usage.InterventionRecord
import com.example.shortsdetector.data.usage.InterventionUserReason
import com.example.shortsdetector.data.usage.SessionRecord
import com.example.shortsdetector.data.usage.SessionRecorder
import com.example.shortsdetector.data.usage.SessionStatus
import com.example.shortsdetector.data.usage.UsageClock
import com.example.shortsdetector.data.usage.UsageSchema
import com.example.shortsdetector.detection.DetectionEngine
import com.example.shortsdetector.detection.adapters.youtube.YouTubeShortsAdapter
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

/** 5단계 선행 정리: 시연 재개입 30초, 세션 is_demo, INTERRUPTED, 시연 준비 초기화. */
class InterventionStep5Test {

    private val prefs = FakeSharedPreferences()
    private val store = InMemoryUsageStore()
    private val clock = object : UsageClock {
        override fun epochNowMs() = 1_000L
        override fun elapsedNowMs() = 0L
    }
    private val copy = object : InterventionCopyProvider {
        override fun copyFor(input: InterventionCopyInput) = InterventionCopy("t")
        override fun levelChangeReason(from: Int, to: Int, policy: InterventionPolicy) = "r"
    }
    private val coordinator = InterventionCoordinator(store, PreferencesPolicyStore(prefs), copy, clock)

    private var seq = 0
    private fun show(level: Int = 1): String {
        val id = "i${++seq}"
        store.insertIntervention(InterventionRecord(id, null, 1, InterventionMethod.POPUP, 1, 1, level = level))
        coordinator.begin(id, 7, level)
        return id
    }

    private fun outcome(id: String) = store.allInterventions().single { it.id == id }.outcome
    private fun streaks() = InterventionPolicyPreferences.readLevelStreaks(prefs)
    private fun level() = InterventionPolicyPreferences.read(prefs).level

    // --- 시연 재개입 ---

    @Test
    fun `demo level 3 more time waits 30 seconds and the saved policy stays`() {
        val saved = InterventionPolicy(thresholdMs = 600_000, level = 2)
        InterventionPolicyPreferences.write(prefs, saved)
        InterventionPolicyPreferences.writeDemoMode(prefs, true)
        InterventionPolicyPreferences.writeLevelState(prefs, 3, LevelStreaks())

        show(3)
        assertEquals(30_000L, coordinator.respond(CONTINUED)!!.reinterveneAfterWatchMs)
        assertEquals(saved, InterventionPolicyPreferences.read(prefs))
    }

    @Test
    fun `outside demo mode level 3 more time is still five minutes`() {
        InterventionPolicyPreferences.write(prefs, InterventionPolicy(level = 3))
        show(3)
        assertEquals(300_000L, coordinator.respond(CONTINUED)!!.reinterveneAfterWatchMs)
    }

    // --- INTERRUPTED ---

    @Test
    fun `interrupted is recorded and leaves level and streaks alone`() {
        InterventionPolicyPreferences.writeLevelState(prefs, 1, LevelStreaks(skips = 1))
        val id = show()
        val r = coordinator.interrupt()!!
        assertEquals(INTERRUPTED, outcome(id))
        assertEquals(7, r.sessionNumber)
        assertEquals(1, r.nextLevel)
        assertEquals(LevelStreaks(skips = 1), streaks())
        assertEquals(1, level())
        assertTrue(store.recentPolicyChanges(10).isEmpty())
    }

    @Test
    fun `interrupted keeps a chosen reason and is written once`() {
        val id = show(2)
        coordinator.chooseReason(InterventionUserReason.TIRED)
        coordinator.interrupt()
        assertNull(coordinator.interrupt())
        assertNull(coordinator.abandon())
        val r = store.allInterventions().single { it.id == id }
        assertEquals(INTERRUPTED, r.outcome)
        assertEquals(InterventionUserReason.TIRED, r.userReason)
    }

    @Test
    fun `level rule ignores interrupted`() {
        val p = InterventionPolicy(escalateAfterSkips = 1)
        val s = InterventionLevelRules.next(p, LevelStreaks(skips = 3, stops = 1), INTERRUPTED)
        assertEquals(1, s.policy.level)
        assertEquals(LevelStreaks(skips = 3, stops = 1), s.streaks)
        assertFalse(s.levelChanged)
    }

    private val tuning = DetectionTuning()

    private fun engine(cooldownMs: Long, maxDaily: Int = Int.MAX_VALUE) = DetectionEngine(
        tuning, listOf(YouTubeShortsAdapter(tuning)), cooldownMs,
        watchThresholdMs = 10_000, maxDailyInterventions = maxDaily, currentDay = { 1L }
    ).also { it.setEnabledPackages(setOf(YT), 0) }

    private fun DetectionEngine.frame(t: Long) =
        onSnapshot(t, Fixtures.youtubeShorts()).also { if (it.notifySession != null) onNotificationPosted(t) }

    private fun DetectionEngine.enter(from: Long) {
        frame(from); frame(from + 250); frame(from + 500)
    }

    private fun DetectionEngine.watch(from: Long, to: Long) = (from..to step 1_000L).map { frame(it) }

    /** 세션을 열고 첫 개입까지 본 뒤 이탈 신호를 낸다. */
    private fun DetectionEngine.interruptedAt(): Pair<Int, Long> {
        enter(0)
        val first = watch(1_000, 15_000).first { it.notifySession != null }
        return first.notifySession!! to 16_000L
    }

    @Test
    fun `session that continues gets another intervention after the cooldown`() {
        val e = engine(cooldownMs = 20_000)
        val (session, t) = e.interruptedAt()
        val posted = 11_000L
        assertTrue(e.onRootUnavailable(t).exitSignalStarted)
        e.retryIntervention(session)

        val before = e.watch(t + 1_000, posted + 19_000)
        assertEquals(0, before.count { it.notifySession != null })
        assertFalse(before.any { it.suppressedByCooldown })      // 기다리는 동안 기회를 쓰지 않는다

        val after = e.watch(posted + 20_000, posted + 40_000)
        assertEquals(1, after.count { it.notifySession == session })
    }

    @Test
    fun `no retry fires while the exit signal lasts`() {
        val e = engine(cooldownMs = 1_000)
        val (session, t) = e.interruptedAt()
        e.onRootUnavailable(t)
        e.retryIntervention(session)
        assertNull(e.onRootUnavailable(t + 1_000).notifySession)
    }

    @Test
    fun `resumed session retries, a new session does not`() {
        val resumed = engine(cooldownMs = 1_000)
        val (session, t) = resumed.interruptedAt()
        resumed.onRootUnavailable(t)
        resumed.retryIntervention(session)
        resumed.onRootUnavailable(t + 1_000); resumed.onRootUnavailable(t + 2_000)   // 세션 종료
        resumed.frame(t + 3_000); resumed.frame(t + 3_250)
        val back = resumed.frame(t + 3_500)
        assertEquals(session, back.step.resumedSession)
        assertEquals(1, resumed.watch(t + 4_000, t + 6_000).count { it.notifySession == session } + (if (back.notifySession != null) 1 else 0))

        val fresh = engine(cooldownMs = 1_000)
        val (s, t2) = fresh.interruptedAt()
        fresh.onRootUnavailable(t2)
        fresh.retryIntervention(s)
        fresh.onRootUnavailable(t2 + 1_000); fresh.onRootUnavailable(t2 + 2_000)
        fresh.enter(t2 + 30_000)                                                     // 이어 가기 유예 뒤 새 세션
        val outs = fresh.watch(t2 + 31_000, t2 + 38_000)
        assertEquals(0, outs.count { it.notifySession != null })                     // 아직 새 세션 기준 전
    }

    @Test
    fun `retry obeys the daily limit`() {
        val e = engine(cooldownMs = 1_000, maxDaily = 1)
        val (session, t) = e.interruptedAt()
        e.onRootUnavailable(t)
        e.retryIntervention(session)
        val outs = e.watch(t + 1_000, t + 5_000)
        assertEquals(0, outs.count { it.notifySession != null })
        assertEquals(1, outs.count { it.suppressedByDailyLimit })
    }

    @Test
    fun `interrupted interventions stay out of the stop rate`() {
        val zone = ZoneId.of("Asia/Seoul")
        val at = LocalDate.of(2026, 11, 5).atTime(12, 0).atZone(zone).toInstant().toEpochMilli()
        fun rec(id: String, o: com.example.shortsdetector.data.usage.InterventionOutcome) =
            InterventionRecord(id, null, at, InterventionMethod.POPUP, 1, 1, level = 1, outcome = o)
        val rate = UsageStatsCalculator.week(emptyList(), listOf(rec("a", STOPPED), rec("b", INTERRUPTED)), zone, at + 1_000)
            .levelStopRates.first()
        assertEquals(2, rate.delivered)
        assertEquals(1, rate.answered)
        assertEquals(1.0, rate.stopRate!!, 1e-9)
    }

    // --- 세션 is_demo ---

    private class Clock : UsageClock {
        var elapsed = 0L
        override fun epochNowMs() = 1_800_000_000_000L + elapsed
        override fun elapsedNowMs() = elapsed
    }

    @Test
    fun `sessions started in demo mode are marked and keep the mark to the end`() {
        var demo = true
        val c = Clock()
        val recorder = SessionRecorder(store, c, isDemoMode = { demo })
        val e = DetectionEngine(tuning, listOf(YouTubeShortsAdapter(tuning)), 1_000, watchThresholdMs = 100_000)
            .also { it.setEnabledPackages(setOf(YT), 0) }
        fun f(t: Long) {
            c.elapsed = t
            recorder.onOutput(e.onSnapshot(t, Fixtures.youtubeShorts()), e.sessionProgress())
        }
        fun gone(t: Long) {
            c.elapsed = t
            recorder.onOutput(e.onRootUnavailable(t), e.sessionProgress())
        }
        f(0); f(250); f(500)
        assertTrue(store.allSessions().single().isDemo)
        demo = false                                     // 세션 도중 꺼도 시작 시점 값을 유지한다
        gone(1_000); gone(2_000); gone(3_000)
        assertEquals(SessionStatus.CLOSED, store.allSessions().single().status)
        assertTrue(store.allSessions().single().isDemo)

        f(30_000); f(30_250); f(30_500)
        assertEquals(listOf(true, false), store.allSessions().map { it.isDemo })
    }

    private val zone = ZoneId.of("Asia/Seoul")
    private val day = LocalDate.of(2026, 11, 5)
    private val noon = day.atTime(12, 0).atZone(zone).toInstant().toEpochMilli()

    private fun session(id: String, watch: Long, demo: Boolean, start: Long = noon) = SessionRecord(
        id, "pkg", "yt-2", start, start + watch, SessionStatus.CLOSED, watch, 0, 0, 0, "HIGH", null, null, 0, isDemo = demo
    )

    @Test
    fun `demo sessions are left out of today, week and first week baseline by default`() {
        val list = listOf(session("real", 60_000, false), session("demo", 600_000, true))
        val now = noon + 3_600_000
        val today = UsageStatsCalculator.today(list, emptyList(), emptyList(), zone, now)
        assertEquals(60_000, today.estimatedWatchMs)
        assertEquals(1, today.sessionCount)
        assertEquals(60_000, today.longestSessionMs)
        assertEquals(60_000, UsageStatsCalculator.week(list, emptyList(), zone, now).totalWatchMs)
        assertEquals(660_000, UsageStatsCalculator.today(list, emptyList(), emptyList(), zone, now, includeDemo = true).estimatedWatchMs)

        assertEquals(70_000L / 7, SavedTimeCalculator.firstWeekAverageMs(
            listOf(session("r", 70_000, false), session("d", 700_000, true)), zone, day.plusDays(7)
        ))
    }

    @Test
    fun `migration 3 to 4 only adds the column`() {
        assertEquals(6, UsageSchema.VERSION)
        assertEquals(listOf("ALTER TABLE sessions ADD COLUMN is_demo INTEGER NOT NULL DEFAULT 0"), UsageSchema.MIGRATE_3_TO_4)
    }

    // --- 시연 준비 초기화 ---

    @Test
    fun `reset returns the whole policy and streaks to the demo start values`() {
        val saved = InterventionPolicy(thresholdMs = 10_000, level = 3, cooldownMs = 60_000, maxDailyInterventions = 7, maxLevel = 3)
        InterventionPolicyPreferences.write(prefs, saved)
        InterventionPolicyPreferences.writeLevelState(prefs, 3, LevelStreaks(skips = 1, stops = 1))

        assertEquals(saved, InterventionPolicyPreferences.resetForDemo(prefs, 55))

        val start = InterventionPolicyPreferences.read(prefs)
        assertEquals(InterventionPolicy(), start)
        assertEquals(180_000, start.thresholdMs)
        assertEquals(1, start.level)
        assertEquals(10, start.maxDailyInterventions)
        assertEquals(3, start.maxLevel)
        assertEquals(LevelStreaks(), streaks())
        assertEquals(55L, prefs.getLong(InterventionPolicyPreferences.KEY_RECORDS_CLEARED_AT, 0))
    }

    @Test
    fun `reset also clears demo level and keeps demo mode on`() {
        InterventionPolicyPreferences.writeDemoMode(prefs, true)
        InterventionPolicyPreferences.writeLevelState(prefs, 3, LevelStreaks(stops = 1))

        InterventionPolicyPreferences.resetForDemo(prefs, 55)

        val r = InterventionPolicyPreferences.resolve(prefs, emptyList())
        assertTrue(r.demoMode)
        assertEquals(1, r.policy.level)
        assertEquals(LevelStreaks(), streaks())
    }
}
