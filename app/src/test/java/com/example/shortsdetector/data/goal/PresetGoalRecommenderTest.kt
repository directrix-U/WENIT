package com.example.shortsdetector.data.goal

import com.example.shortsdetector.data.profile.AgeBand
import com.example.shortsdetector.data.profile.Gender
import com.example.shortsdetector.data.profile.UserProfile
import com.example.shortsdetector.data.profile.WatchTimeSlot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek

class PresetGoalRecommenderTest {

    private fun profile(
        slots: Set<WatchTimeSlot> = emptySet(),
        days: Set<DayOfWeek> = emptySet(),
        age: AgeBand = AgeBand.TWENTIES,
        gender: Gender = Gender.NO_ANSWER
    ) = UserProfile(age, gender, emptySet(), slots, days)

    private fun assertValidPreset(goals: List<Goal>) {
        assertEquals(3, goals.size)
        assertEquals(3, goals.map { it.type }.toSet().size)
        assertTrue(goals.all { it.source == GoalSource.PRESET && !it.reason.isNullOrBlank() })
        assertEquals(goals, GoalRules.normalize(goals))       // 전부 범위 안
    }

    @Test
    fun `incomplete survey and empty answers still give three goals`() {
        assertValidPreset(PresetGoalRecommender.recommend(null))
        assertValidPreset(PresetGoalRecommender.recommend(profile()))
    }

    @Test
    fun `late night answer includes the time window goal`() {
        val goals = PresetGoalRecommender.recommend(profile(slots = setOf(WatchTimeSlot.LATE_NIGHT)))
        assertValidPreset(goals)
        assertEquals(GoalType.TIME_WINDOW, goals.first().type)
        assertEquals(23 * 60, goals.first().value)
    }

    @Test
    fun `no late night answer means no time window goal`() {
        val goals = PresetGoalRecommender.recommend(profile(slots = setOf(WatchTimeSlot.EVENING)))
        assertFalse(goals.any { it.type == GoalType.TIME_WINDOW })
    }

    @Test
    fun `daytime answer adds the opens goal and watching all day tightens the total`() {
        assertTrue(
            PresetGoalRecommender.recommend(profile(slots = setOf(WatchTimeSlot.DAYTIME)))
                .any { it.type == GoalType.DAILY_OPENS }
        )
        val goals = PresetGoalRecommender.recommend(profile(slots = WatchTimeSlot.entries.toSet()))
        assertValidPreset(goals)
        assertEquals(45, goals.first { it.type == GoalType.DAILY_TOTAL }.value)
    }

    @Test
    fun `weekend-only answer gets a longer session goal`() {
        val goals = PresetGoalRecommender.recommend(profile(days = setOf(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY)))
        assertValidPreset(goals)
        assertEquals(20, goals.first { it.type == GoalType.PER_SESSION }.value)
    }

    @Test
    fun `age and gender do not change the recommendation`() {
        val slots = setOf(WatchTimeSlot.LATE_NIGHT)
        val base = PresetGoalRecommender.recommend(profile(slots = slots))
        for (age in AgeBand.entries) for (gender in Gender.entries) {
            assertEquals(base, PresetGoalRecommender.recommend(profile(slots = slots, age = age, gender = gender)))
        }
    }
}
