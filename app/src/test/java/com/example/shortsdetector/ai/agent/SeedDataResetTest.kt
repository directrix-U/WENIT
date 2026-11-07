package com.example.shortsdetector.ai.agent

import com.example.shortsdetector.data.InterventionPolicyPreferences
import com.example.shortsdetector.data.stats.AgentInputBuilder
import com.example.shortsdetector.data.usage.DemoSeedData
import com.example.shortsdetector.data.usage.InMemoryUsageStore
import com.example.shortsdetector.data.usage.InterventionMethod
import com.example.shortsdetector.data.usage.InterventionRecord
import com.example.shortsdetector.data.usage.PolicyChangeRecord
import com.example.shortsdetector.data.usage.PolicyChangeSource
import com.example.shortsdetector.data.usage.PolicyChangeStatus
import com.example.shortsdetector.data.usage.SessionRecord
import com.example.shortsdetector.data.usage.SessionStatus
import com.example.shortsdetector.intervention.InterventionPolicy
import com.example.shortsdetector.intervention.LevelStreaks
import com.example.shortsdetector.testutil.FakeSharedPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

class SeedDataResetTest {

    private val zone = ZoneId.of("Asia/Seoul")
    private val today = LocalDate.of(2026, 11, 5)
    private val now = today.atTime(20, 0).atZone(zone).toInstant().toEpochMilli()

    private val policyPrefs = FakeSharedPreferences()
    private val agentPrefs = FakeSharedPreferences()
    private val store = InMemoryUsageStore()
    private var ids = 0
    private val reset = SeedDataReset(store, policyPrefs, agentPrefs, { now }) { "reset-${++ids}" }

    private val raised = InterventionPolicy(thresholdMs = 30_000, level = 3)

    private fun seed() {
        val b = DemoSeedData.build(DemoSeedData.Scenario.LATE_NIGHT_SKIPPER, today, zone, now)
        b.sessions.forEach(store::upsertSession)
        b.interventions.forEach(store::insertIntervention)
    }

    private fun realSession() = SessionRecord(
        "real-s", "com.google.android.youtube", "yt", now - 90_000, now - 30_000, SessionStatus.CLOSED,
        60_000, 0, 0, 0, "HIGH", "EXIT_CONFIRMED", null, 0
    )

    private fun realIntervention() =
        InterventionRecord("real-i", "real-s", now - 60_000, InterventionMethod.POPUP, 30_000, 30_000, level = 1)

    private fun change(id: String, source: PolicyChangeSource, isSeed: Boolean = false, isDemo: Boolean = false) =
        PolicyChangeRecord(
            id, now - 1_000, source, InterventionPolicy(), raised, "이유", null, PolicyChangeStatus.APPLIED,
            isDemo = isDemo, isSeed = isSeed
        )

    @Test
    fun `seed records, seed changes, agent text and policy all go`() {
        seed()
        InterventionPolicyPreferences.write(policyPrefs, raised)
        AgentPreferences.writeMessages(agentPrefs, AgentMessages("밤에 자주 넘겨요", "왜 보고 있어요?"))
        AgentPreferences.writeLastResult(agentPrefs, "CHANGED")
        store.insertPolicyChange(change("seed-agent", PolicyChangeSource.AGENT, isSeed = true))

        reset.run("지웠어요")

        assertFalse(store.hasSeedRecords())
        assertTrue(store.allSessions().isEmpty())
        assertEquals(InterventionPolicy(), InterventionPolicyPreferences.read(policyPrefs))
        assertNull(AgentPreferences.activeMessages(agentPrefs))
        assertNull(agentPrefs.getString("last_result", null))
        assertTrue(store.recentPolicyChanges(10).none { it.id == "seed-agent" })
    }

    @Test
    fun `real records and changes made without seed stay`() {
        seed()
        store.upsertSession(realSession())
        store.insertIntervention(realIntervention())
        store.insertPolicyChange(change("real-agent", PolicyChangeSource.AGENT))
        store.insertPolicyChange(change("rule", PolicyChangeSource.RULE))
        store.insertPolicyChange(change("demo", PolicyChangeSource.RULE, isDemo = true))
        store.insertPolicyChange(change("seed-agent", PolicyChangeSource.AGENT, isSeed = true))

        reset.run("지웠어요")

        assertEquals(listOf("real-s"), store.allSessions().map { it.id })
        assertEquals(listOf("real-i"), store.allInterventions().map { it.id })
        val left = store.recentPolicyChanges(10).map { it.id }
        assertTrue(left.containsAll(listOf("real-agent", "rule", "demo")))
        assertFalse("seed-agent" in left)
    }

    @Test
    fun `the reset itself is recorded and is not seed based`() {
        seed()
        InterventionPolicyPreferences.write(policyPrefs, raised)

        reset.run("지웠어요")

        val logged = store.recentPolicyChanges(10).single()
        assertEquals(PolicyChangeSource.DEFAULT, logged.source)
        assertEquals(raised.clamped(), logged.previousPolicy)
        assertEquals(InterventionPolicy(), logged.newPolicy)
        assertEquals("지웠어요", logged.reasonText)
        assertFalse(logged.isSeed)
    }

    @Test
    fun `nothing is logged when the policy was already default`() {
        seed()
        InterventionPolicyPreferences.write(policyPrefs, InterventionPolicy())

        reset.run("지웠어요")

        assertTrue(store.recentPolicyChanges(10).isEmpty())
    }

    @Test
    fun `level streaks restart`() {
        InterventionPolicyPreferences.writeLevelState(policyPrefs, 2, LevelStreaks(1, 1))

        reset.run("지웠어요")

        assertEquals(LevelStreaks(0, 0), InterventionPolicyPreferences.readLevelStreaks(policyPrefs))
    }

    @Test
    fun `last run time stays so opening the app does not call the api again`() {
        AgentPreferences.writeLastRun(agentPrefs, now)

        reset.run("지웠어요")

        assertTrue(agentPrefs.contains("last_run_at"))
    }

    @Test
    fun `input knows when seed data is in it`() {
        val policy = InterventionPolicy()
        val clean = AgentInputBuilder.build(null, emptyList(), policy, listOf(realSession()), emptyList(), emptyList(), zone, now)
        assertFalse(clean.usedSeedData)

        val b = DemoSeedData.build(DemoSeedData.Scenario.EASY_STOPPER, today, zone, now)
        val seeded = AgentInputBuilder.build(null, emptyList(), policy, b.sessions, b.interventions, emptyList(), zone, now)
        assertTrue(seeded.usedSeedData)
    }
}
