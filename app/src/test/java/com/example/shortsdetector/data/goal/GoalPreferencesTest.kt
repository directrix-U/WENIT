package com.example.shortsdetector.data.goal

import com.example.shortsdetector.testutil.FakeSharedPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GoalPreferencesTest {

    private val p = FakeSharedPreferences()
    private val preset = Goal(GoalType.TIME_WINDOW, 1380, "23:00 이후에는 보지 않기", "이유", GoalSource.PRESET)

    @Test
    fun `no goals by default`() {
        assertTrue(GoalPreferences.read(p).isEmpty())
    }

    @Test
    fun `all fields survive a round trip including null reason`() {
        val goals = listOf(preset, GoalRules.userGoal(GoalType.DAILY_OPENS, 4))
        GoalPreferences.write(p, goals)
        assertEquals(goals, GoalPreferences.read(p))
    }

    @Test
    fun `saving is limited to three goals and removes old slots`() {
        GoalPreferences.write(p, GoalType.entries.map { GoalRules.userGoal(it, 5) })
        assertEquals(3, GoalPreferences.read(p).size)

        GoalPreferences.write(p, listOf(preset))
        assertEquals(listOf(preset), GoalPreferences.read(p))
        assertFalse(p.contains("goal_1_type"))
    }

    @Test
    fun `put, replace and remove reach storage`() {
        assertTrue(GoalPreferences.put(p, preset))
        assertTrue(GoalPreferences.put(p, GoalRules.userGoal(GoalType.DAILY_TOTAL, 60)))
        assertTrue(GoalPreferences.put(p, GoalRules.userGoal(GoalType.PER_SESSION, 10)))
        assertFalse(GoalPreferences.put(p, GoalRules.userGoal(GoalType.SELF_STOPS, 1)))

        assertTrue(GoalPreferences.replace(p, GoalType.DAILY_TOTAL, GoalRules.userGoal(GoalType.SELF_STOPS, 2)))
        assertEquals(
            listOf(GoalType.TIME_WINDOW, GoalType.SELF_STOPS, GoalType.PER_SESSION),
            GoalPreferences.read(p).map { it.type }
        )

        GoalPreferences.remove(p, GoalType.TIME_WINDOW)
        assertEquals(2, GoalPreferences.read(p).size)
    }

    @Test
    fun `stored values outside the range are clipped when read and corrupt slots are skipped`() {
        GoalPreferences.write(p, listOf(GoalRules.userGoal(GoalType.DAILY_TOTAL, 60), preset))
        p.values["goal_0_value"] = 9_999
        p.values["goal_1_type"] = "REMOVED_TYPE"
        val goals = GoalPreferences.read(p)
        assertEquals(240, goals.single().value)
        assertEquals("하루 240분 이하로 보기", goals.single().label)
    }
}
