package com.example.shortsdetector.intervention

import com.example.shortsdetector.data.usage.InterventionOutcome
import com.example.shortsdetector.data.usage.InterventionOutcome.CONTINUED
import com.example.shortsdetector.data.usage.InterventionOutcome.IGNORED
import com.example.shortsdetector.data.usage.InterventionOutcome.STOPPED
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class InterventionLevelRulesTest {

    /** 결과를 차례로 넣고 마지막 상태를 돌려준다. */
    private fun run(start: InterventionPolicy, vararg outcomes: InterventionOutcome): LevelStep {
        var step = LevelStep(start, LevelStreaks(), false)
        for (o in outcomes) step = InterventionLevelRules.next(step.policy, step.streaks, o)
        return step
    }

    private fun policy(level: Int, skips: Int = 2, stops: Int = 2) =
        InterventionPolicy(level = level, escalateAfterSkips = skips, deescalateAfterStops = stops)

    @Test
    fun `level 1 rises to level 2 after consecutive skips`() {
        assertEquals(1, run(policy(1), IGNORED).policy.level)
        val up = run(policy(1), IGNORED, CONTINUED)
        assertEquals(2, up.policy.level)
        assertTrue(up.levelChanged)
        assertEquals(LevelStreaks(), up.streaks)
    }

    @Test
    fun `a stop breaks the skip streak on level 1`() {
        assertEquals(1, run(policy(1), IGNORED, STOPPED, IGNORED).policy.level)
    }

    @Test
    fun `escalation threshold comes from the policy`() {
        assertEquals(1, run(policy(1, skips = 3), IGNORED, IGNORED).policy.level)
        assertEquals(2, run(policy(1, skips = 3), IGNORED, IGNORED, IGNORED).policy.level)
        assertEquals(2, run(policy(1, skips = 1), IGNORED).policy.level)
    }

    @Test
    fun `choosing to continue on level 2 rises to level 3 at once`() {
        val up = run(policy(2), CONTINUED)
        assertEquals(3, up.policy.level)
        assertTrue(up.levelChanged)
    }

    @Test
    fun `ignoring on level 2 is not a choice and keeps the level`() {
        val s = run(policy(2), IGNORED, IGNORED, IGNORED)
        assertEquals(2, s.policy.level)
        assertFalse(s.levelChanged)
    }

    @Test
    fun `level 3 is the ceiling`() {
        assertEquals(3, run(policy(3), CONTINUED, CONTINUED, IGNORED).policy.level)
    }

    @Test
    fun `consecutive stops lower one level at a time`() {
        val once = run(policy(3), STOPPED, STOPPED)
        assertEquals(2, once.policy.level)
        assertEquals(LevelStreaks(), once.streaks)
        assertEquals(1, run(policy(3), STOPPED, STOPPED, STOPPED, STOPPED).policy.level)
    }

    @Test
    fun `not stopping breaks the stop streak`() {
        assertEquals(3, run(policy(3), STOPPED, IGNORED, STOPPED).policy.level)
    }

    @Test
    fun `level 1 is the floor`() {
        val s = run(policy(1), STOPPED, STOPPED, STOPPED)
        assertEquals(1, s.policy.level)
        assertFalse(s.levelChanged)
    }

    @Test
    fun `out of range input is clamped before the rule runs`() {
        val s = InterventionLevelRules.next(InterventionPolicy(level = 42), LevelStreaks(), CONTINUED)
        assertEquals(3, s.policy.level)
        assertFalse(s.levelChanged)
    }

    @Test
    fun `only the level changes and same input gives same output`() {
        val p = policy(1, skips = 1).copy(thresholdMs = 60_000, cooldownMs = 90_000)
        val a = InterventionLevelRules.next(p, LevelStreaks(), IGNORED)
        assertEquals(p.copy(level = 2), a.policy)
        assertEquals(a, InterventionLevelRules.next(p, LevelStreaks(), IGNORED))
    }
}
