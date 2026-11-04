package com.example.shortsdetector.data.goal

import com.example.shortsdetector.data.usage.SessionRecord
import com.example.shortsdetector.data.usage.SessionStatus
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

class GoalProgressTest {

    private val minute = 60_000L
    private val none = GoalDayMetrics(0, 0, 0, 0, 0)

    private fun eval(type: GoalType, value: Int, m: GoalDayMetrics, finished: Boolean = false) =
        GoalProgressCalculator.evaluate(GoalRules.userGoal(type, value), m, finished)

    @Test
    fun `daily total is in progress up to the limit and exceeded past it`() {
        val at = eval(GoalType.DAILY_TOTAL, 30, none.copy(watchMs = 30 * minute))
        assertEquals(GoalStatus.ON_TRACK, at.status)
        assertEquals(30, at.currentValue)

        val over = eval(GoalType.DAILY_TOTAL, 30, none.copy(watchMs = 30 * minute + 1))
        assertEquals(GoalStatus.EXCEEDED, over.status)
        assertEquals(31, over.currentValue)           // 올림: 넘겼으면 목표보다 크게 보인다
    }

    @Test
    fun `limit goals are achieved only when the day is over`() {
        val m = none.copy(watchMs = 10 * minute, longestSessionMs = 5 * minute, sessionCount = 2)
        assertEquals(GoalStatus.ON_TRACK, eval(GoalType.DAILY_TOTAL, 30, m).status)
        assertEquals(GoalStatus.ACHIEVED, eval(GoalType.DAILY_TOTAL, 30, m, finished = true).status)
        assertEquals(GoalStatus.ACHIEVED, eval(GoalType.PER_SESSION, 5, m, finished = true).status)
        assertEquals(GoalStatus.ACHIEVED, eval(GoalType.DAILY_OPENS, 2, m, finished = true).status)
        assertEquals(GoalStatus.ACHIEVED, eval(GoalType.TIME_WINDOW, 1380, m, finished = true).status)
    }

    @Test
    fun `exceeding stays exceeded after the day is over`() {
        val m = none.copy(watchMs = 90 * minute)
        assertEquals(GoalStatus.EXCEEDED, eval(GoalType.DAILY_TOTAL, 30, m, finished = true).status)
    }

    @Test
    fun `per session uses the longest session, not the daily sum`() {
        val m = none.copy(watchMs = 60 * minute, longestSessionMs = 8 * minute)
        assertEquals(GoalStatus.ON_TRACK, eval(GoalType.PER_SESSION, 10, m).status)
        assertEquals(GoalStatus.EXCEEDED, eval(GoalType.PER_SESSION, 7, m).status)
    }

    @Test
    fun `daily opens counts sessions`() {
        assertEquals(GoalStatus.ON_TRACK, eval(GoalType.DAILY_OPENS, 3, none.copy(sessionCount = 3)).status)
        assertEquals(GoalStatus.EXCEEDED, eval(GoalType.DAILY_OPENS, 3, none.copy(sessionCount = 4)).status)
    }

    @Test
    fun `self stops are achieved at once and missed only after the day ends`() {
        assertEquals(GoalStatus.ON_TRACK, eval(GoalType.SELF_STOPS, 2, none.copy(selfStopCount = 1)).status)
        assertEquals(GoalStatus.ACHIEVED, eval(GoalType.SELF_STOPS, 2, none.copy(selfStopCount = 2)).status)
        assertEquals(GoalStatus.MISSED, eval(GoalType.SELF_STOPS, 2, none.copy(selfStopCount = 1), finished = true).status)
    }

    @Test
    fun `time window is exceeded by any restricted watching`() {
        val p = eval(GoalType.TIME_WINDOW, 1380, none.copy(restrictedWatchMs = 1))
        assertEquals(GoalStatus.EXCEEDED, p.status)
        assertEquals(1, p.currentValue)
    }

    // --- 제한 구간 ---

    private val seoul = ZoneId.of("Asia/Seoul")
    private val day = LocalDate.of(2026, 11, 5)

    private fun at(d: LocalDate, h: Int, m: Int = 0, zone: ZoneId = seoul) =
        LocalDateTime.of(d.year, d.month, d.dayOfMonth, h, m).atZone(zone).toInstant().toEpochMilli()

    private fun session(start: Long, end: Long, watch: Long) = SessionRecord(
        "s$start", "pkg", "yt-2", start, end, SessionStatus.CLOSED, watch, 0, 0, 0, "HIGH", null, null, 0
    )

    private fun restricted(cutoff: Int, vararg s: SessionRecord) =
        TimeWindowUsage.restrictedWatchMs(s.toList(), cutoff, day, seoul)

    @Test
    fun `watching before the cutoff does not count`() {
        assertEquals(0, restricted(1380, session(at(day, 22), at(day, 22, 30), 1_000)))
        assertEquals(0, restricted(1380, session(at(day, 22, 59), at(day, 22, 59), 1_000)))
    }

    @Test
    fun `session crossing the cutoff counts only the overlapping share`() {
        assertEquals(500, restricted(1380, session(at(day, 22, 30), at(day, 23, 30), 1_000)))
    }

    @Test
    fun `early morning counts until the 5am release`() {
        assertEquals(1_000, restricted(1380, session(at(day, 1), at(day, 2), 1_000)))
        assertEquals(1_000, restricted(1380, session(at(day, 4, 59), at(day, 4, 59), 1_000)))
        assertEquals(0, restricted(1380, session(at(day, 5), at(day, 6), 1_000)))
    }

    @Test
    fun `cutoff past midnight leaves the evening free`() {
        val cutoff = GoalRules.timeWindowValue(1, 0)
        assertEquals(0, restricted(cutoff, session(at(day, 23, 30), at(day, 23, 40), 1_000)))
        assertEquals(0, restricted(cutoff, session(at(day, 0, 30), at(day, 0, 40), 1_000)))
        assertEquals(1_000, restricted(cutoff, session(at(day, 1, 30), at(day, 1, 40), 1_000)))
    }

    @Test
    fun `session across midnight only counts the part inside this date`() {
        val s = session(at(day, 23, 30), at(day.plusDays(1), 0, 30), 1_000)
        assertEquals(500, restricted(1380, s))
    }

    @Test
    fun `restricted intervals follow local time on a daylight saving day`() {
        val ny = ZoneId.of("America/New_York")
        val dst = LocalDate.of(2026, 3, 8)             // 02:00 → 03:00 이라 자정~05:00 이 4시간
        val (from, to) = TimeWindowUsage.restrictedIntervals(1380, dst, ny).first()
        assertEquals(4 * 60 * minute, to - from)
    }
}
