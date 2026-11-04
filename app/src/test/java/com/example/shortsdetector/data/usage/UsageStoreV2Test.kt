package com.example.shortsdetector.data.usage

import com.example.shortsdetector.intervention.InterventionPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Proxy
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** DB 버전 2 에서 늘어난 기록. SQLite 자체는 JVM 에서 못 돌려 VERIFICATION_LOG 의 에뮬레이터 확인을 함께 본다. */
class UsageStoreV2Test {

    private val store = InMemoryUsageStore()

    private fun intervention(id: String, at: Long, level: Int? = 1) =
        InterventionRecord(id, null, at, InterventionMethod.POPUP, 1, 1, level = level)

    private fun change(id: String, at: Long, status: PolicyChangeStatus = PolicyChangeStatus.APPLIED) =
        PolicyChangeRecord(id, at, PolicyChangeSource.USER, null, InterventionPolicy(), "이유", null, status)

    @Test
    fun `recorder stores the level given at intervention time`() {
        val recorder = SessionRecorder(store, object : UsageClock {
            override fun epochNowMs() = 1_000L
            override fun elapsedNowMs() = 0L
        })
        recorder.onIntervention(null, InterventionMethod.POPUP, 1, 1, level = 2)
        recorder.onIntervention(null, InterventionMethod.SUPPRESSED_BY_COOLDOWN, 1, 1, level = 3)
        assertEquals(listOf(2, 3), store.allInterventions().map { it.level })
    }

    @Test
    fun `only the first outcome is kept, with its reason`() {
        store.insertIntervention(intervention("i", 1))
        assertNull(store.allInterventions().single().outcome)

        store.setInterventionOutcome("i", InterventionOutcome.STOPPED, InterventionUserReason.TIRED)
        store.setInterventionOutcome("i", InterventionOutcome.CONTINUED, null)
        val r = store.allInterventions().single()
        assertEquals(InterventionOutcome.STOPPED, r.outcome)
        assertEquals(InterventionUserReason.TIRED, r.userReason)
    }

    @Test
    fun `outcome and button reaction are independent`() {
        store.insertIntervention(intervention("i", 1))
        store.setInterventionOutcome("i", InterventionOutcome.IGNORED, null)
        store.setInterventionAction("i", InterventionAction.DISMISS, 5)
        val r = store.allInterventions().single()
        assertEquals(InterventionOutcome.IGNORED, r.outcome)
        assertEquals(InterventionAction.DISMISS, r.userAction)
    }

    @Test
    fun `policy changes come back newest first and status can change`() {
        store.insertPolicyChange(change("old", 1))
        store.insertPolicyChange(change("new", 2))
        store.insertPolicyChange(change("new", 3))            // 같은 id 는 무시
        assertEquals(listOf("new", "old"), store.recentPolicyChanges(10).map { it.id })
        assertEquals(listOf("new"), store.recentPolicyChanges(1).map { it.id })

        store.setPolicyChangeStatus("old", PolicyChangeStatus.REVERTED_BY_USER)
        assertEquals(PolicyChangeStatus.REVERTED_BY_USER, store.recentPolicyChanges(10)[1].status)
    }

    @Test
    fun `retention and delete all also cover policy changes`() {
        store.insertPolicyChange(change("old", 1))
        store.insertPolicyChange(change("new", 100))
        store.deleteBefore(50)
        assertEquals(listOf("new"), store.recentPolicyChanges(10).map { it.id })

        store.deleteAll()
        assertTrue(store.recentPolicyChanges(10).isEmpty())
    }

    @Test
    fun `new write methods run on the writer thread, not the caller`() {
        val threads = Collections.synchronizedList(ArrayList<String>())
        val done = CountDownLatch(3)
        val delegate = Proxy.newProxyInstance(
            UsageStore::class.java.classLoader, arrayOf(UsageStore::class.java)
        ) { _, _, _ ->
            threads += Thread.currentThread().name
            done.countDown()
            null
        } as UsageStore

        val background = BackgroundUsageStore(delegate)
        background.setInterventionOutcome("i", InterventionOutcome.STOPPED, null)
        background.insertPolicyChange(change("c", 1))
        background.setPolicyChangeStatus("c", PolicyChangeStatus.APPLIED)
        assertTrue(done.await(5, TimeUnit.SECONDS))
        background.shutdown()
        assertEquals(setOf("wenit-usage-writer"), threads.toSet())
    }

    @Test
    fun `migration only adds, and new columns have no invented default`() {
        val sql = UsageSchema.MIGRATE_1_TO_2.joinToString("\n").uppercase()
        assertEquals(2, UsageSchema.VERSION)
        for (word in listOf("DROP", "DELETE", "UPDATE", "DEFAULT", "RENAME")) assertFalse(word, word in sql)
    }
}
