package com.example.shortsdetector.data.usage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReturnTrackerTest {

    private val store = InMemoryUsageStore()
    private val tracker = ReturnTracker(store)
    private val minute = 60_000L
    private val stoppedAt = 1_000_000_000L

    private fun stopped(id: String = "a", at: Long = stoppedAt) = store.insertIntervention(
        InterventionRecord(
            id, null, at - 1_000, InterventionMethod.POPUP, 0, 0,
            userAction = InterventionAction.OPEN_APP, actionAtMs = at, level = 1,
            outcome = InterventionOutcome.STOPPED
        )
    )

    private fun record(id: String = "a") = store.allInterventions().first { it.id == id }

    @Test
    fun `coming back to shorts records the gap from the stop`() {
        stopped()
        tracker.onShortsReturned(stoppedAt + 7 * minute)

        assertEquals(InterventionReturn.SHORTS, record().returnStatus)
        assertEquals(7 * minute, record().shortsReturnMs)
        assertNull(record().appReturnMs)
    }

    @Test
    fun `opening the app without shorts is counted apart and keeps watching`() {
        stopped()
        tracker.onTargetAppOpened(stoppedAt + 3 * minute)

        assertNull(record().returnStatus)
        assertEquals(3 * minute, record().appReturnMs)
        assertEquals(1, store.interventionsAwaitingReturn().size)
    }

    @Test
    fun `the first app opening is the one that counts`() {
        stopped()
        tracker.onTargetAppOpened(stoppedAt + 3 * minute)
        tracker.onTargetAppOpened(stoppedAt + 9 * minute)
        assertEquals(3 * minute, record().appReturnMs)
    }

    @Test
    fun `shorts after the app keeps both gaps`() {
        stopped()
        tracker.onTargetAppOpened(stoppedAt + 3 * minute)
        tracker.onShortsReturned(stoppedAt + 5 * minute)

        assertEquals(InterventionReturn.SHORTS, record().returnStatus)
        assertEquals(5 * minute, record().shortsReturnMs)
        assertEquals(3 * minute, record().appReturnMs)
    }

    @Test
    fun `a day without coming back ends as not returned`() {
        stopped()
        tracker.sweep(stoppedAt + ReturnTracker.WINDOW_MS - 1)
        assertNull(record().returnStatus)

        tracker.sweep(stoppedAt + ReturnTracker.WINDOW_MS)
        assertEquals(InterventionReturn.NONE, record().returnStatus)
        assertNull(record().shortsReturnMs)
    }

    @Test
    fun `a day with the app but no shorts ends as app only`() {
        stopped()
        tracker.onTargetAppOpened(stoppedAt + minute)
        tracker.sweep(stoppedAt + ReturnTracker.WINDOW_MS)

        assertEquals(InterventionReturn.APP_ONLY, record().returnStatus)
        assertEquals(minute, record().appReturnMs)
    }

    @Test
    fun `shorts after the day has passed does not count as a return`() {
        stopped()
        tracker.onShortsReturned(stoppedAt + ReturnTracker.WINDOW_MS + minute)

        assertEquals(InterventionReturn.NONE, record().returnStatus)
        assertNull(record().shortsReturnMs)
    }

    @Test
    fun `every stop still waiting gets its own gap`() {
        stopped("a", stoppedAt)
        stopped("b", stoppedAt + 2 * minute)
        tracker.onShortsReturned(stoppedAt + 10 * minute)

        assertEquals(10 * minute, record("a").shortsReturnMs)
        assertEquals(8 * minute, record("b").shortsReturnMs)
    }

    @Test
    fun `a finished record is not changed by later sessions`() {
        stopped()
        tracker.onShortsReturned(stoppedAt + minute)
        tracker.onShortsReturned(stoppedAt + 50 * minute)
        assertEquals(minute, record().shortsReturnMs)
    }

    @Test
    fun `only stops are watched`() {
        store.insertIntervention(
            InterventionRecord(
                "c", null, stoppedAt, InterventionMethod.POPUP, 0, 0, level = 1, outcome = InterventionOutcome.CONTINUED
            )
        )
        tracker.onShortsReturned(stoppedAt + minute)
        assertNull(record("c").returnStatus)
        assertTrue(store.interventionsAwaitingReturn().isEmpty())
    }

    @Test
    fun `migration adds only nullable columns and leaves old stops out of tracking`() {
        val sql = UsageSchema.MIGRATE_6_TO_7
        assertEquals(3, sql.count { it.startsWith("ALTER TABLE interventions ADD COLUMN") })
        assertTrue(sql.none { "DROP" in it.uppercase() || "DELETE" in it.uppercase() })
        assertTrue(sql.last().contains("WHERE outcome = 'STOPPED'"))
    }
}
