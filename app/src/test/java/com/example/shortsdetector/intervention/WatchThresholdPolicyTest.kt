package com.example.shortsdetector.intervention

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WatchThresholdPolicyTest {

    @Test
    fun `no session never decides`() {
        assertFalse(WatchThresholdPolicy(0).shouldDecide(null, 999_999))
    }

    @Test
    fun `decides exactly at the threshold and only once per session`() {
        val p = WatchThresholdPolicy(60_000)
        assertFalse(p.shouldDecide(1, 59_999))
        assertTrue(p.shouldDecide(1, 60_000))
        assertFalse(p.shouldDecide(1, 61_000))
        assertFalse(p.shouldDecide(1, 600_000))
    }

    @Test
    fun `a new session is judged again from zero`() {
        val p = WatchThresholdPolicy(60_000)
        assertTrue(p.shouldDecide(1, 60_000))
        assertFalse(p.shouldDecide(2, 1_000))
        assertTrue(p.shouldDecide(2, 60_000))
    }

    @Test
    fun `zero threshold decides at session start (legacy behaviour)`() {
        assertTrue(WatchThresholdPolicy(0).shouldDecide(1, 0))
    }

    @Test
    fun `negative threshold is clamped and lowering it applies to the open session`() {
        val p = WatchThresholdPolicy(-5)
        assertEquals(0L, p.thresholdMs)
        p.thresholdMs = 300_000
        assertFalse(p.shouldDecide(1, 120_000))
        p.thresholdMs = 60_000
        assertTrue(p.shouldDecide(1, 120_000))
    }

    @Test
    fun `rearm decides once more at the given watch time and stays spent`() {
        val p = WatchThresholdPolicy(60_000)
        assertTrue(p.shouldDecide(1, 60_000))
        p.rearm(1, 360_000)
        assertFalse(p.shouldDecide(1, 359_999))
        assertTrue(p.shouldDecide(1, 360_000))
        assertFalse(p.shouldDecide(1, 999_000))
    }

    @Test
    fun `rearm before a decision or for another session does nothing, and a new session clears it`() {
        val p = WatchThresholdPolicy(60_000)
        p.rearm(1, 1_000)
        assertFalse(p.shouldDecide(1, 1_000))
        assertTrue(p.shouldDecide(1, 60_000))
        p.rearm(2, 61_000)
        assertFalse(p.shouldDecide(1, 61_000))

        p.rearm(1, 70_000)
        assertTrue(p.shouldDecide(2, 60_000))
        assertFalse(p.shouldDecide(2, 70_000))
    }
}
