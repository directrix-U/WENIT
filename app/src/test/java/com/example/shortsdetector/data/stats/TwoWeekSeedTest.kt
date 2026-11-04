package com.example.shortsdetector.data.stats

import com.example.shortsdetector.data.InterventionPolicyPreferences
import com.example.shortsdetector.data.usage.DemoSeedData
import com.example.shortsdetector.data.usage.DemoSeedData.Scenario
import com.example.shortsdetector.data.usage.InMemoryUsageStore
import com.example.shortsdetector.data.usage.SessionRecord
import com.example.shortsdetector.data.usage.SessionStatus
import com.example.shortsdetector.intervention.DemoMode
import com.example.shortsdetector.intervention.InterventionPolicy
import com.example.shortsdetector.testutil.FakeSharedPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZoneId

class TwoWeekSeedTest {

    private val zone = ZoneId.of("Asia/Seoul")
    private val minute = 60_000L

    private fun at(d: LocalDate, h: Int) = d.atTime(h, 0).atZone(zone).toInstant().toEpochMilli()

    /** 2026-11-02 월요일부터 일주일. 시연 요일이 언제여도 성립해야 한다. */
    private val everyWeekday = (0L..6L).map { LocalDate.of(2026, 11, 2).plusDays(it) }

    private fun week(s: Scenario, today: LocalDate): WeekStats {
        val now = at(today, 20)
        val b = DemoSeedData.build(s, today, zone, now)
        return UsageStatsCalculator.week(b.sessions, b.interventions, zone, now)
    }

    @Test
    fun `seed covers fourteen days before today`() {
        val today = LocalDate.of(2026, 11, 5)
        for (s in Scenario.entries) {
            val b = DemoSeedData.build(s, today, zone, at(today, 20))
            val dates = b.sessions.map { com.example.shortsdetector.data.usage.DailyUsageCalculator.dateOf(it.startedAtMs, zone) }.toSet()
            for (back in 1L..14L) assertTrue("$s -$back", today.minusDays(back) in dates)
        }
    }

    @Test
    fun `A goes up and B goes down on every weekday, in a believable range`() {
        for (today in everyWeekday) {
            val a = week(Scenario.LATE_NIGHT_SKIPPER, today)
            val b = week(Scenario.EASY_STOPPER, today)
            assertTrue("A ${today.dayOfWeek}", a.canCompare)
            assertTrue("B ${today.dayOfWeek}", b.canCompare)
            assertTrue("A ${today.dayOfWeek} ${a.changeRatio}", a.changeRatio!! in 0.1..2.0)
            assertTrue("B ${today.dayOfWeek} ${b.changeRatio}", b.changeRatio!! in -0.95..-0.3)
        }
    }

    @Test
    fun `the agent still sees only the last seven days`() {
        val today = LocalDate.of(2026, 11, 5)
        val now = at(today, 20)
        val b = DemoSeedData.build(Scenario.LATE_NIGHT_SKIPPER, today, zone, now)
        val recent = UsageStatsCalculator.recent(b.sessions, b.interventions, zone, now)
        assertEquals(7, recent.days.size)
        assertEquals(17, recent.deliveredInterventions)      // 지난주 개입은 세지 않는다
        assertEquals(today.minusDays(7), recent.days.first().date)
    }

    @Test
    fun `saved time baseline comes from the ordinary week`() {
        val today = LocalDate.of(2026, 11, 5)
        val now = at(today, 20)
        for (s in Scenario.entries) {
            val b = DemoSeedData.build(s, today, zone, now)
            assertEquals(193L * minute / 7, SavedTimeCalculator.firstWeekAverageMs(b.sessions, zone, today))
        }
    }

    @Test
    fun `today's share is left out before its time and has no interventions`() {
        val today = LocalDate.of(2026, 11, 5)
        val early = at(today, 0)
        val b = DemoSeedData.build(Scenario.LATE_NIGHT_SKIPPER, today, zone, early)
        assertTrue(b.sessions.all { it.startedAtMs + it.estimatedWatchMs < early })
        val later = DemoSeedData.build(Scenario.LATE_NIGHT_SKIPPER, today, zone, at(today, 20))
        assertTrue(later.interventions.none { it.occurredAtMs >= at(today, 0) })
    }

    @Test
    fun `reset removes the whole two week seed and keeps real records on seed delete`() {
        val today = LocalDate.of(2026, 11, 5)
        val store = InMemoryUsageStore()
        val b = DemoSeedData.build(Scenario.EASY_STOPPER, today, zone, at(today, 20))
        val real = session(at(today, 9), minute)
        store.upsertSession(real)
        b.sessions.forEach(store::upsertSession)
        b.interventions.forEach(store::insertIntervention)
        assertTrue(store.allSessions().size > 14)

        store.deleteSeedRecords()
        assertEquals(listOf(real), store.allSessions())
        assertTrue(store.allInterventions().isEmpty())

        b.sessions.forEach(store::upsertSession)
        store.deleteAll()
        assertTrue(store.allSessions().isEmpty())
    }

    // --- 비교 숨김 ---

    private fun session(start: Long, watch: Long) = SessionRecord(
        "s$start", "pkg", "yt-2", start, start + watch, SessionStatus.CLOSED, watch, 0, 0, 0, "HIGH", null, null, 0
    )

    private val thursday = LocalDate.of(2026, 11, 5)
    private val lastMonday = LocalDate.of(2026, 10, 26)

    private fun weekWith(lastWeekDays: List<Long>): WeekStats {
        val sessions = lastWeekDays.map { session(at(lastMonday.plusDays(it), 12), 10 * minute) } +
            session(at(thursday, 9), 30 * minute)
        return UsageStatsCalculator.week(sessions, emptyList(), zone, at(thursday, 20))
    }

    @Test
    fun `comparison is hidden under three days of last week and the ratio is not altered`() {
        val two = weekWith(listOf(0, 1))
        assertEquals(2, two.previousWeekRecordDays)
        assertFalse(two.canCompare)
        assertEquals(0.5, two.changeRatio!!, 1e-9)               // 비율 자체는 그대로 계산된다(20분 → 30분)

        val three = weekWith(listOf(0, 1, 2))
        assertEquals(3, three.previousWeekRecordDays)
        assertTrue(three.canCompare)
    }

    @Test
    fun `days after the same weekday still count as record days`() {
        val w = weekWith(listOf(0, 5, 6))
        assertEquals(3, w.previousWeekRecordDays)
        assertTrue(w.canCompare)
    }

    @Test
    fun `no last week means no comparison`() {
        val w = weekWith(emptyList())
        assertEquals(0, w.previousWeekRecordDays)
        assertFalse(w.canCompare)
    }

    @Test
    fun `three days of last week outside the compared part give no ratio and stay hidden`() {
        val w = weekWith(listOf(4, 5, 6))
        assertEquals(3, w.previousWeekRecordDays)
        assertFalse(w.canCompare)
    }

    // --- 시연 기준 시간 ---

    @Test
    fun `demo threshold choice overlays the saved policy and bad values fall back`() {
        val p = FakeSharedPreferences()
        InterventionPolicyPreferences.write(p, InterventionPolicy(thresholdMs = 600_000))
        InterventionPolicyPreferences.writeDemoMode(p, true)
        assertEquals(10_000, InterventionPolicyPreferences.resolve(p, emptyList()).thresholdMs)

        InterventionPolicyPreferences.writeDemoThresholdMs(p, 30_000)
        assertEquals(30_000, InterventionPolicyPreferences.resolve(p, emptyList()).thresholdMs)
        assertEquals(600_000, InterventionPolicyPreferences.read(p).thresholdMs)

        InterventionPolicyPreferences.writeDemoThresholdMs(p, 12_345)
        assertEquals(DemoMode.THRESHOLD_MS, InterventionPolicyPreferences.readDemoThresholdMs(p))
    }

    @Test
    fun `sunday is a full week`() {
        assertEquals(DayOfWeek.SUNDAY, everyWeekday.last().dayOfWeek)
        assertEquals(7, week(Scenario.EASY_STOPPER, everyWeekday.last()).days.size)
    }
}
