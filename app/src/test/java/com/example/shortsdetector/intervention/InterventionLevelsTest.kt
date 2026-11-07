package com.example.shortsdetector.intervention

import com.example.shortsdetector.data.usage.InterventionOutcome
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class InterventionLevelsTest {

    @Test
    fun `levels are consecutive from one and the bounds follow the list`() {
        assertEquals((1..InterventionLevels.ALL.size).toList(), InterventionLevels.ALL.map { it.number })
        assertEquals(InterventionLevels.MIN, InterventionPolicyBounds.MIN_LEVEL)
        assertEquals(InterventionLevels.MAX, InterventionPolicyBounds.MAX_LEVEL)
    }

    @Test
    fun `existing levels stay what they were`() {
        val (one, two, three) = InterventionLevels.ALL
        assertEquals(InterventionKind.NOTICE to OverlayWindow.CARD, one.kind to one.window)
        assertEquals(InterventionKind.QUESTION to OverlayWindow.CARD, two.kind to two.window)
        assertEquals(InterventionKind.BREATHING to OverlayWindow.FULL_SCREEN, three.kind to three.window)
        assertTrue(three.promisesMoreTime)
        assertEquals(EscalationRule.NONE, three.escalation)
    }

    @Test
    fun `max level choices match the bounds`() {
        assertEquals(InterventionPolicyBounds.MAX_LEVEL_SETTING.toList(), InterventionLevels.maxLevelChoices.map { it.number })
        assertNull(InterventionLevels.ALL.first().maxLevelLabel)
    }

    @Test
    fun `an out of range number reads as the nearest level`() {
        assertEquals(1, InterventionLevels.of(0).number)
        assertEquals(InterventionLevels.MAX, InterventionLevels.of(99).number)
    }

    @Test
    fun `the top level has nowhere to go`() {
        val p = InterventionPolicy(level = InterventionLevels.MAX)
        val step = InterventionLevelRules.next(p, LevelStreaks(), InterventionOutcome.CONTINUED)
        assertEquals(InterventionLevels.MAX, step.policy.level)
        assertFalse(step.levelChanged)
    }

    @Test
    fun `a promise of more time belongs to the level, not to a number`() {
        assertEquals(
            ReinterventionRule.FIVE_MORE_MINUTES_MS,
            ReinterventionRule.afterWatchMs(3, InterventionOutcome.CONTINUED, null)
        )
        assertNull(ReinterventionRule.afterWatchMs(2, InterventionOutcome.CONTINUED, null))
        assertNull(ReinterventionRule.afterWatchMs(3, InterventionOutcome.STOPPED, null))
    }
}
