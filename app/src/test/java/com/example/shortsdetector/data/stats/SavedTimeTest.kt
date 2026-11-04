package com.example.shortsdetector.data.stats

import com.example.shortsdetector.data.goal.GoalRules
import com.example.shortsdetector.data.goal.GoalStatus
import com.example.shortsdetector.data.goal.GoalType
import com.example.shortsdetector.data.profile.AgeBand
import com.example.shortsdetector.data.profile.Gender
import com.example.shortsdetector.data.profile.UserProfile
import com.example.shortsdetector.data.profile.UserProfilePreferences
import com.example.shortsdetector.data.profile.UsualDailyWatch
import com.example.shortsdetector.data.usage.InterventionMethod
import com.example.shortsdetector.data.usage.InterventionOutcome
import com.example.shortsdetector.data.usage.InterventionRecord
import com.example.shortsdetector.data.usage.SessionRecord
import com.example.shortsdetector.data.usage.SessionStatus
import com.example.shortsdetector.intervention.GoalRemaining
import com.example.shortsdetector.testutil.FakeSharedPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

/** 1단계 보고 뒤 확정한 결정: 아낀 시간, 어제 결과, 프로필 기본값, 목표까지 남은 시간. */
class SavedTimeTest {

    private val zone = ZoneId.of("Asia/Seoul")
    private val minute = 60_000L
    private val first = LocalDate.of(2026, 11, 1)

    private fun at(d: LocalDate, h: Int) = d.atTime(h, 0).atZone(zone).toInstant().toEpochMilli()

    private fun session(d: LocalDate, watch: Long) = SessionRecord(
        "s$d", "pkg", "yt-2", at(d, 12), at(d, 12) + watch, SessionStatus.CLOSED, watch, 0, 0, 0, "HIGH", null, null, 0
    )

    @Test
    fun `survey value is the baseline until the first week is available`() {
        val s = SavedTimeCalculator.estimate(null, UsualDailyWatch.HOUR_1_TO_2, 30 * minute)!!
        assertEquals(60 * minute, s.savedMs)
        assertEquals(SavedTimeBaseline.SURVEY, s.baseline)
        assertTrue(s.isEstimate)
    }

    @Test
    fun `first week average replaces the survey value`() {
        val s = SavedTimeCalculator.estimate(40 * minute, UsualDailyWatch.HOUR_1_TO_2, 30 * minute)!!
        assertEquals(10 * minute, s.savedMs)
        assertEquals(SavedTimeBaseline.FIRST_WEEK, s.baseline)
        assertTrue(s.isEstimate)
    }

    @Test
    fun `watching more than the baseline gives zero, not a negative value`() {
        assertEquals(0, SavedTimeCalculator.estimate(10 * minute, null, 90 * minute)!!.savedMs)
    }

    @Test
    fun `no baseline at all gives no value`() {
        assertNull(SavedTimeCalculator.estimate(null, null, minute))
    }

    @Test
    fun `first week average needs seven full days and counts empty days`() {
        val sessions = listOf(session(first, 70 * minute), session(first.plusDays(6), 70 * minute))
        assertNull(SavedTimeCalculator.firstWeekAverageMs(sessions, zone, first.plusDays(6)))
        assertEquals(20 * minute, SavedTimeCalculator.firstWeekAverageMs(sessions, zone, first.plusDays(7)))
        assertNull(SavedTimeCalculator.firstWeekAverageMs(emptyList(), zone, first.plusDays(30)))
    }

    @Test
    fun `sessions after the first seven days do not change the average`() {
        val sessions = listOf(session(first, 70 * minute), session(first.plusDays(8), 700 * minute))
        assertEquals(10 * minute, SavedTimeCalculator.firstWeekAverageMs(sessions, zone, first.plusDays(10)))
    }

    @Test
    fun `computed baseline stays fixed after old records are pruned`() {
        val p = FakeSharedPreferences()
        assertNull(SavedTimeBaselinePreferences.resolve(p) { null })
        assertEquals(5L, SavedTimeBaselinePreferences.resolve(p) { 5L })
        assertEquals(5L, SavedTimeBaselinePreferences.resolve(p) { 999L })
    }

    @Test
    fun `yesterday settles the goals of a finished day`() {
        val now = at(first.plusDays(1), 9)
        val goals = listOf(
            GoalRules.userGoal(GoalType.DAILY_TOTAL, 30),
            GoalRules.userGoal(GoalType.DAILY_OPENS, 1),
            GoalRules.userGoal(GoalType.SELF_STOPS, 1)
        )
        val r = UsageStatsCalculator.yesterday(listOf(session(first, 20 * minute)), emptyList(), goals, zone, now)
        assertEquals(first, r.date)
        assertEquals(listOf(GoalStatus.ACHIEVED, GoalStatus.ACHIEVED, GoalStatus.MISSED), r.goals.map { it.status })
    }

    @Test
    fun `yesterday reports exceeded goals and reads its own stops`() {
        val now = at(first.plusDays(1), 9)
        val goals = listOf(GoalRules.userGoal(GoalType.DAILY_TOTAL, 10), GoalRules.userGoal(GoalType.SELF_STOPS, 1))
        val stop = InterventionRecord(
            "i", null, at(first, 13), InterventionMethod.POPUP, 1, 1, outcome = InterventionOutcome.STOPPED
        )
        val r = UsageStatsCalculator.yesterday(listOf(session(first, 20 * minute)), listOf(stop), goals, zone, now)
        assertEquals(listOf(GoalStatus.EXCEEDED, GoalStatus.ACHIEVED), r.goals.map { it.status })
    }

    @Test
    fun `today never settles a limit goal`() {
        val now = at(first, 20)
        val goals = listOf(GoalRules.userGoal(GoalType.DAILY_TOTAL, 30))
        val t = UsageStatsCalculator.today(listOf(session(first, 20 * minute)), emptyList(), goals, zone, now)
        assertEquals(GoalStatus.ON_TRACK, t.goals.single().status)
    }

    @Test
    fun `consent defaults to false, gender defaults to no answer, and both round trip`() {
        val p = FakeSharedPreferences()
        val profile = UserProfile(AgeBand.TWENTIES)
        assertEquals(Gender.NO_ANSWER, profile.gender)
        assertFalse(profile.aiRecommendationConsent)
        assertNull(profile.usualDailyWatch)

        UserProfilePreferences.write(p, profile)
        assertEquals(profile, UserProfilePreferences.read(p))

        val full = profile.copy(usualDailyWatch = UsualDailyWatch.OVER_3_HOURS, aiRecommendationConsent = true)
        UserProfilePreferences.write(p, full)
        assertEquals(full, UserProfilePreferences.read(p))
    }

    @Test
    fun `missing gender reads as no answer without losing the profile`() {
        val p = FakeSharedPreferences()
        UserProfilePreferences.write(p, UserProfile(AgeBand.TWENTIES, Gender.FEMALE))
        p.values.remove("gender")
        assertEquals(Gender.NO_ANSWER, UserProfilePreferences.read(p)!!.gender)
    }

    @Test
    fun `remaining minutes prefer the daily goal, round up and stop at zero`() {
        val daily = GoalRules.userGoal(GoalType.DAILY_TOTAL, 30)
        val session = GoalRules.userGoal(GoalType.PER_SESSION, 10)
        assertNull(GoalRemaining.minutes(emptyList(), 0, minute))
        assertEquals(19, GoalRemaining.minutes(listOf(session, daily), 10 * minute, minute))
        assertEquals(9, GoalRemaining.minutes(listOf(session), 50 * minute, minute))
        assertEquals(1, GoalRemaining.minutes(listOf(session), 0, 10 * minute - 1))
        assertEquals(0, GoalRemaining.minutes(listOf(daily), 40 * minute, minute))
    }
}
