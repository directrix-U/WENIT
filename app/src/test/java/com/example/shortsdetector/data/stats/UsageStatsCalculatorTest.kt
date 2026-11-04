package com.example.shortsdetector.data.stats

import com.example.shortsdetector.data.goal.GoalRules
import com.example.shortsdetector.data.goal.GoalStatus
import com.example.shortsdetector.data.goal.GoalType
import com.example.shortsdetector.data.usage.InterventionMethod
import com.example.shortsdetector.data.usage.InterventionOutcome
import com.example.shortsdetector.data.usage.InterventionRecord
import com.example.shortsdetector.data.usage.SessionRecord
import com.example.shortsdetector.data.usage.SessionStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZoneId

class UsageStatsCalculatorTest {

    private val zone = ZoneId.of("Asia/Seoul")
    private val minute = 60_000L

    /** 2026-11-05 는 목요일 */
    private val thursday = LocalDate.of(2026, 11, 5)
    private val monday = LocalDate.of(2026, 11, 2)

    private fun at(d: LocalDate, h: Int, m: Int = 0) =
        d.atTime(h, m).atZone(zone).toInstant().toEpochMilli()

    private val now = at(thursday, 20)

    private fun session(start: Long, watch: Long, status: SessionStatus = SessionStatus.CLOSED) = SessionRecord(
        "s$start", "pkg", "yt-2", start, start + watch, status, watch, 0, 0, 0, "HIGH", null, null, 0
    )

    private var seq = 0
    private fun intervention(
        at: Long,
        level: Int? = 1,
        outcome: InterventionOutcome? = null,
        method: InterventionMethod = InterventionMethod.POPUP
    ) = InterventionRecord("i${seq++}", null, at, method, 1, 1, level = level, outcome = outcome)

    @Test
    fun `empty records give zeros, not crashes`() {
        val t = UsageStatsCalculator.today(emptyList(), emptyList(), emptyList(), zone, now)
        assertEquals(TodayStats(thursday, 0, emptyList(), 0, 0, 0, 0), t)

        val w = UsageStatsCalculator.week(emptyList(), emptyList(), zone, now)
        assertEquals(4, w.days.size)
        assertEquals(0, w.dailyAverageMs)
        assertNull(w.changeRatio)
        assertNull(w.levelStopRates.first().stopRate)
    }

    @Test
    fun `today sums watch time, sessions and longest session, ignoring other days`() {
        val sessions = listOf(
            session(at(thursday, 9), 5 * minute),
            session(at(thursday, 12), 12 * minute, SessionStatus.OPEN),
            session(at(thursday, 15), 3 * minute, SessionStatus.ABORTED),
            session(at(thursday.minusDays(1), 12), 90 * minute)
        )
        val t = UsageStatsCalculator.today(sessions, emptyList(), emptyList(), zone, now)
        assertEquals(20 * minute, t.estimatedWatchMs)
        assertEquals(3, t.sessionCount)
        assertEquals(12 * minute, t.longestSessionMs)
    }

    @Test
    fun `session started yesterday counts its watch share but not as a session today`() {
        val s = session(at(thursday.minusDays(1), 23, 50), 20 * minute)
        val t = UsageStatsCalculator.today(listOf(s), emptyList(), emptyList(), zone, now)
        assertEquals(10 * minute, t.estimatedWatchMs)
        assertEquals(0, t.sessionCount)
        assertEquals(0, t.longestSessionMs)
    }

    @Test
    fun `intervention count excludes cooldown skips and failures, self stops need an outcome`() {
        val list = listOf(
            intervention(at(thursday, 9), outcome = InterventionOutcome.STOPPED),
            intervention(at(thursday, 10), method = InterventionMethod.NOTIFICATION, outcome = InterventionOutcome.CONTINUED),
            intervention(at(thursday, 11)),
            intervention(at(thursday, 12), method = InterventionMethod.SUPPRESSED_BY_COOLDOWN),
            intervention(at(thursday, 13), method = InterventionMethod.FAILED),
            intervention(at(thursday.minusDays(1), 9), outcome = InterventionOutcome.STOPPED)
        )
        val t = UsageStatsCalculator.today(emptyList(), list, emptyList(), zone, now)
        assertEquals(3, t.interventionCount)
        assertEquals(1, t.selfStopCount)
    }

    @Test
    fun `goal progress is computed for every goal type from today's records`() {
        val goals = listOf(
            GoalRules.userGoal(GoalType.DAILY_TOTAL, 30),
            GoalRules.userGoal(GoalType.PER_SESSION, 10),
            GoalRules.userGoal(GoalType.TIME_WINDOW, 1200)         // 20:00
        )
        val sessions = listOf(session(at(thursday, 9), 15 * minute), session(at(thursday, 19, 30), 5 * minute))
        val laterNow = at(thursday, 21)
        val late = session(at(thursday, 20, 30), 2 * minute)
        val t = UsageStatsCalculator.today(sessions + late, emptyList(), goals, zone, laterNow)
        assertEquals(
            listOf(GoalStatus.IN_PROGRESS, GoalStatus.EXCEEDED, GoalStatus.EXCEEDED),
            t.goals.map { it.status }
        )
        assertEquals(listOf(22, 15, 2), t.goals.map { it.currentValue })
    }

    @Test
    fun `self stop goal reads the stop count`() {
        val goals = listOf(GoalRules.userGoal(GoalType.SELF_STOPS, 1), GoalRules.userGoal(GoalType.DAILY_OPENS, 1))
        val list = listOf(intervention(at(thursday, 9), outcome = InterventionOutcome.STOPPED))
        val t = UsageStatsCalculator.today(listOf(session(at(thursday, 9), minute)), list, goals, zone, now)
        assertEquals(listOf(GoalStatus.ACHIEVED, GoalStatus.IN_PROGRESS), t.goals.map { it.status })
    }

    @Test
    fun `week lists monday to today with an average over elapsed days`() {
        val sessions = listOf(session(at(monday, 9), 40 * minute), session(at(thursday, 9), 20 * minute))
        val w = UsageStatsCalculator.week(sessions, emptyList(), zone, now)
        assertEquals(DayOfWeek.MONDAY, w.days.first().date.dayOfWeek)
        assertEquals(thursday, w.days.last().date)
        assertEquals(listOf(40L, 0, 0, 20), w.days.map { it.watchMs / minute })
        assertEquals(60 * minute, w.totalWatchMs)
        assertEquals(15 * minute, w.dailyAverageMs)
    }

    @Test
    fun `last week comparison stops at the same weekday`() {
        val lastMonday = monday.minusWeeks(1)
        val sessions = listOf(
            session(at(monday, 9), 15 * minute),
            session(at(lastMonday, 9), 10 * minute),
            session(at(lastMonday.plusDays(3), 9), 10 * minute),   // 지난주 목요일: 포함
            session(at(lastMonday.plusDays(4), 9), 500 * minute)   // 지난주 금요일: 아직 안 지난 요일이라 제외
        )
        val w = UsageStatsCalculator.week(sessions, emptyList(), zone, now)
        assertEquals(20 * minute, w.previousPeriodWatchMs)
        assertEquals(-5 * minute, w.changeMs)
        assertEquals(-0.25, w.changeRatio!!, 1e-9)
    }

    @Test
    fun `week of a monday has one day and sunday has seven`() {
        val onMonday = UsageStatsCalculator.week(emptyList(), emptyList(), zone, at(monday, 8))
        assertEquals(1, onMonday.days.size)
        val onSunday = UsageStatsCalculator.week(emptyList(), emptyList(), zone, at(monday.plusDays(6), 8))
        assertEquals(7, onSunday.days.size)
    }

    @Test
    fun `stop rate is per level, over answered interventions delivered this week`() {
        val list = listOf(
            intervention(at(monday, 9), level = 1, outcome = InterventionOutcome.STOPPED),
            intervention(at(monday, 10), level = 1, outcome = InterventionOutcome.IGNORED),
            intervention(at(monday, 11), level = 1),                    // 결과 모름: 분모 제외
            intervention(at(monday, 12), level = 2, outcome = InterventionOutcome.CONTINUED),
            intervention(at(monday, 13), level = null, outcome = InterventionOutcome.STOPPED),  // 버전 1 기록
            intervention(at(monday, 14), level = 3, method = InterventionMethod.SUPPRESSED_BY_COOLDOWN),
            intervention(at(monday.minusDays(1), 9), level = 1, outcome = InterventionOutcome.STOPPED) // 지난주
        )
        val rates = UsageStatsCalculator.week(emptyList(), list, zone, now).levelStopRates
        assertEquals(LevelStopRate(1, 3, 2, 1, 0.5), rates[0])
        assertEquals(LevelStopRate(2, 1, 1, 0, 0.0), rates[1])
        assertEquals(LevelStopRate(3, 0, 0, 0, null), rates[2])
    }

    @Test
    fun `data window starts last monday and ends after today`() {
        val w = UsageStatsCalculator.dataWindow(now, zone)
        assertEquals(at(monday.minusWeeks(1), 0), w.fromMs)
        assertEquals(at(thursday.plusDays(1), 0), w.toMs)
    }
}
