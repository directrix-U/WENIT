package com.example.shortsdetector.intervention

import com.example.shortsdetector.DetectionConfig
import com.example.shortsdetector.data.InterventionPolicyPreferences
import com.example.shortsdetector.data.goal.Goal
import com.example.shortsdetector.data.goal.GoalRules
import com.example.shortsdetector.data.goal.GoalSource
import com.example.shortsdetector.data.goal.GoalType
import com.example.shortsdetector.testutil.FakeSharedPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class InterventionPolicyTest {

    private val low = InterventionPolicy(Long.MIN_VALUE, -1, -1, -1, Long.MIN_VALUE, -1)
    private val high = InterventionPolicy(Long.MAX_VALUE, 99, 99, 99, Long.MAX_VALUE, 999)

    @Test
    fun `default policy matches current behaviour and is inside the bounds`() {
        val d = InterventionPolicy()
        assertEquals(d, d.clamped())
        assertEquals(DetectionConfig.DEFAULT_WATCH_THRESHOLD_MS, d.thresholdMs)
        assertEquals(DetectionConfig.MIN_RENOTIFY_INTERVAL_MS, d.cooldownMs)
        assertEquals(1, d.level)
    }

    @Test
    fun `clamping pulls every field to the hard bounds`() {
        val b = InterventionPolicyBounds
        assertEquals(
            InterventionPolicy(
                b.THRESHOLD_MS.first, b.MIN_LEVEL, b.ESCALATE_AFTER_SKIPS.first, b.DEESCALATE_AFTER_STOPS.first,
                b.COOLDOWN_MS.first, b.MAX_DAILY_INTERVENTIONS.first
            ),
            low.clamped()
        )
        assertEquals(
            InterventionPolicy(
                b.THRESHOLD_MS.last, b.MAX_LEVEL, b.ESCALATE_AFTER_SKIPS.last, b.DEESCALATE_AFTER_STOPS.last,
                b.COOLDOWN_MS.last, b.MAX_DAILY_INTERVENTIONS.last
            ),
            high.clamped()
        )
    }

    @Test
    fun `cooldown can never go below the existing renotify guard`() {
        assertEquals(30_000, InterventionPolicy(cooldownMs = 1).clamped().cooldownMs)
    }

    @Test
    fun `per session goal drives the threshold and removing it restores the policy value`() {
        val p = InterventionPolicy(thresholdMs = 300_000)
        val goals = listOf(GoalRules.userGoal(GoalType.DAILY_TOTAL, 60), GoalRules.userGoal(GoalType.PER_SESSION, 7))
        assertEquals(420_000, p.effectiveThresholdMs(goals))
        assertEquals(300_000, p.thresholdMs)                  // 정책 자체는 그대로
        assertEquals(300_000, p.effectiveThresholdMs(goals.take(1)))
        assertEquals(300_000, p.effectiveThresholdMs(emptyList()))
    }

    @Test
    fun `unvalidated per session value is still clipped`() {
        val bad = Goal(GoalType.PER_SESSION, 100_000, "x", null, GoalSource.AI)
        assertEquals(3_600_000, InterventionPolicy().effectiveThresholdMs(listOf(bad)))
    }

    @Test
    fun `codec round trip keeps out of range values for the history`() {
        val weird = InterventionPolicy(1, 9, 8, 7, 6, 5)
        assertEquals(weird, InterventionPolicyCodec.decode(InterventionPolicyCodec.encode(weird)))
    }

    @Test
    fun `codec fills missing fields and rejects garbage`() {
        assertEquals(InterventionPolicy(level = 3), InterventionPolicyCodec.decode("level=3;futureField=1"))
        assertNull(InterventionPolicyCodec.decode(""))
        assertNull(InterventionPolicyCodec.decode("not a policy"))
        assertNull(InterventionPolicyCodec.decode("level=abc"))
    }

    @Test
    fun `preferences return defaults, then the saved clamped policy`() {
        val p = FakeSharedPreferences()
        assertEquals(InterventionPolicy(), InterventionPolicyPreferences.read(p))

        InterventionPolicyPreferences.write(p, InterventionPolicy(thresholdMs = 120_000, level = 99))
        val saved = InterventionPolicyPreferences.read(p)
        assertEquals(120_000, saved.thresholdMs)
        assertEquals(3, saved.level)
    }

    @Test
    fun `corrupt or tampered stored policy cannot leave the bounds`() {
        val p = FakeSharedPreferences()
        p.values["policy"] = "level=50;maxDailyInterventions=100000"
        val read = InterventionPolicyPreferences.read(p)
        assertEquals(3, read.level)
        assertEquals(30, read.maxDailyInterventions)

        p.values["policy"] = "garbage"
        assertEquals(1, InterventionPolicyPreferences.read(p).level)
    }

    @Test
    fun `streaks survive a restart`() {
        val p = FakeSharedPreferences()
        assertEquals(LevelStreaks(), InterventionPolicyPreferences.readLevelStreaks(p))
        InterventionPolicyPreferences.writeLevelState(p, 1, LevelStreaks(skips = 1, stops = 2))
        assertEquals(LevelStreaks(1, 2), InterventionPolicyPreferences.readLevelStreaks(p))
    }
}
