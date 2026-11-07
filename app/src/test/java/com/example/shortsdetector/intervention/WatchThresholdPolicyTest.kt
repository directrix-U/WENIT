package com.example.shortsdetector.intervention

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WatchThresholdPolicyTest {

    @Test
    fun `no session is never due`() {
        assertFalse(WatchThresholdPolicy(0).isDue(null, 999_999))
    }

    @Test
    fun `first decision comes exactly at the threshold`() {
        val p = WatchThresholdPolicy(60_000)
        assertTrue(p.isFirst(1))
        assertFalse(p.isDue(1, 59_999))
        assertTrue(p.isDue(1, 60_000))
    }

    @Test
    fun `a chance stays open until it is marked`() {
        val p = WatchThresholdPolicy(60_000)
        assertTrue(p.isDue(1, 60_000))
        assertTrue(p.isDue(1, 90_000))
    }

    @Test
    fun `after a decision the next one is one interval of watching later, again and again`() {
        val p = WatchThresholdPolicy(60_000)
        p.markDecided(1, 60_000, 30_000)
        assertFalse(p.isFirst(1))
        assertFalse(p.isDue(1, 89_999))
        assertTrue(p.isDue(1, 90_000))
        p.markDecided(1, 90_000, 30_000)
        assertFalse(p.isDue(1, 119_999))
        assertTrue(p.isDue(1, 120_000))
    }

    @Test
    fun `a new session starts from the threshold`() {
        val p = WatchThresholdPolicy(60_000)
        p.markDecided(1, 60_000, 30_000)
        assertFalse(p.isDue(2, 1_000))
        assertTrue(p.isDue(2, 60_000))
    }

    @Test
    fun `zero threshold decides at session start (legacy behaviour)`() {
        assertTrue(WatchThresholdPolicy(0).isDue(1, 0))
    }

    @Test
    fun `negative threshold is clamped and lowering it applies to the open session`() {
        val p = WatchThresholdPolicy(-5)
        assertEquals(0L, p.thresholdMs)
        p.thresholdMs = 300_000
        assertFalse(p.isDue(1, 120_000))
        p.thresholdMs = 60_000
        assertTrue(p.isDue(1, 120_000))
    }

    @Test
    fun `a delay pushes the next decision back and never brings it forward`() {
        val p = WatchThresholdPolicy(60_000)
        p.markDecided(1, 60_000, 30_000)
        p.delayUntil(1, 360_000)
        assertFalse(p.isDue(1, 359_999))
        assertTrue(p.isDue(1, 360_000))
        p.delayUntil(1, 100_000)
        assertFalse(p.isDue(1, 359_999))
    }

    @Test
    fun `a delay for another session does nothing`() {
        val p = WatchThresholdPolicy(60_000)
        p.markDecided(1, 60_000, 30_000)
        p.delayUntil(2, 900_000)
        assertTrue(p.isDue(1, 90_000))
    }
}
