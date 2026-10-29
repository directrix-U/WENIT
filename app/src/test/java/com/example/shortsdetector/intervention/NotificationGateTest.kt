package com.example.shortsdetector.intervention

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NotificationGateTest {

    @Test
    fun `first notification is always allowed`() {
        val g = NotificationGate(30_000)
        assertTrue(g.canNotify(0))
        assertNull(g.sinceLast(0))
    }

    @Test
    fun `cooldown blocks until the interval has passed`() {
        val g = NotificationGate(30_000)
        g.markNotified(1_000)
        assertFalse(g.canNotify(1_000))
        assertFalse(g.canNotify(30_999))
        assertTrue(g.canNotify(31_000))
        assertEquals(5_000L, g.sinceLast(6_000))
    }
}
