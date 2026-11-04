package com.example.shortsdetector.data.goal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GoalRulesTest {

    private fun ai(type: GoalType, value: Int, label: String = "문구", reason: String? = null) =
        Goal(type, value, label, reason, GoalSource.AI)

    @Test
    fun `every type clamps at both ends of its range`() {
        for (type in GoalType.entries) {
            val r = GoalRules.rangeOf(type)
            assertEquals(r.first, GoalRules.clampValue(type, Int.MIN_VALUE))
            assertEquals(r.last, GoalRules.clampValue(type, Int.MAX_VALUE))
            assertEquals(r.first, GoalRules.clampValue(type, r.first))
            assertEquals(r.last, GoalRules.clampValue(type, r.last))
        }
    }

    @Test
    fun `clipped value replaces the label that quoted the old number`() {
        val g = GoalRules.sanitize(ai(GoalType.DAILY_TOTAL, 500, "하루 500분 이하"))
        assertEquals(240, g.value)
        assertEquals("하루 240분 이하로 보기", g.label)
    }

    @Test
    fun `in-range goal keeps its label and source, trimmed to the length limit`() {
        val g = GoalRules.sanitize(ai(GoalType.PER_SESSION, 10, "  ${"가".repeat(100)} "))
        assertEquals(10, g.value)
        assertEquals(GoalRules.MAX_LABEL_LENGTH, g.label.length)
        assertEquals(GoalSource.AI, g.source)
    }

    @Test
    fun `blank label gets a default and blank reason becomes null`() {
        val g = GoalRules.sanitize(ai(GoalType.SELF_STOPS, 2, "   ", "  "))
        assertEquals("하루 2번 이상 스스로 멈추기", g.label)
        assertNull(g.reason)
        assertEquals(
            GoalRules.MAX_REASON_LENGTH,
            GoalRules.sanitize(ai(GoalType.SELF_STOPS, 2, reason = "이".repeat(500))).reason!!.length
        )
    }

    @Test
    fun `normalize keeps the first of each type and at most three`() {
        val out = GoalRules.normalize(
            listOf(
                ai(GoalType.DAILY_TOTAL, 30), ai(GoalType.DAILY_TOTAL, 90),
                ai(GoalType.PER_SESSION, 5), ai(GoalType.DAILY_OPENS, 3), ai(GoalType.SELF_STOPS, 1)
            )
        )
        assertEquals(listOf(GoalType.DAILY_TOTAL, GoalType.PER_SESSION, GoalType.DAILY_OPENS), out.map { it.type })
        assertEquals(30, out[0].value)
    }

    @Test
    fun `time input continues past midnight and clamps to the allowed window`() {
        assertEquals(23 * 60, GoalRules.timeWindowValue(23, 0))
        assertEquals(25 * 60 + 30, GoalRules.timeWindowValue(1, 30))
        assertEquals(27 * 60, GoalRules.timeWindowValue(5, 0))     // 03:00 까지
        assertEquals(18 * 60, GoalRules.timeWindowValue(10, 0))    // 18:00 부터
        assertEquals("23:00", GoalRules.formatTimeWindow(1380))
        assertEquals("새벽 1:05", GoalRules.formatTimeWindow(1505))
    }

    @Test
    fun `user input builds a USER goal without a reason`() {
        val g = GoalRules.userGoal(GoalType.TIME_WINDOW, GoalRules.timeWindowValue(0, 30))
        assertEquals(GoalSource.USER, g.source)
        assertEquals("새벽 0:30 이후에는 보지 않기", g.label)
        assertNull(g.reason)
    }

    @Test
    fun `put adds, edits by type and refuses a fourth goal`() {
        var goals = emptyList<Goal>()
        goals = GoalRules.put(goals, GoalRules.userGoal(GoalType.DAILY_TOTAL, 60))!!
        goals = GoalRules.put(goals, GoalRules.userGoal(GoalType.PER_SESSION, 10))!!
        goals = GoalRules.put(goals, GoalRules.userGoal(GoalType.SELF_STOPS, 1))!!
        assertNull(GoalRules.put(goals, GoalRules.userGoal(GoalType.DAILY_OPENS, 5)))

        val edited = GoalRules.put(goals, GoalRules.userGoal(GoalType.PER_SESSION, 20))!!
        assertEquals(3, edited.size)
        assertEquals(20, edited[1].value)
    }

    @Test
    fun `replace can change the type and never leaves two goals of one type`() {
        val goals = listOf(GoalRules.userGoal(GoalType.DAILY_TOTAL, 60), GoalRules.userGoal(GoalType.PER_SESSION, 10))
        val changed = GoalRules.replace(goals, GoalType.DAILY_TOTAL, GoalRules.userGoal(GoalType.DAILY_OPENS, 5))!!
        assertEquals(listOf(GoalType.DAILY_OPENS, GoalType.PER_SESSION), changed.map { it.type })

        val merged = GoalRules.replace(goals, GoalType.DAILY_TOTAL, GoalRules.userGoal(GoalType.PER_SESSION, 7))!!
        assertEquals(1, merged.size)
        assertEquals(7, merged.single().value)
    }

    @Test
    fun `remove drops only that type`() {
        val goals = listOf(GoalRules.userGoal(GoalType.DAILY_TOTAL, 60), GoalRules.userGoal(GoalType.PER_SESSION, 10))
        assertTrue(GoalRules.remove(goals, GoalType.DAILY_TOTAL).none { it.type == GoalType.DAILY_TOTAL })
        assertEquals(2, GoalRules.remove(goals, GoalType.SELF_STOPS).size)
    }
}
