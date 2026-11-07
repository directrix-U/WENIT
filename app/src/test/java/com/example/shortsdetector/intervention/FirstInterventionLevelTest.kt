package com.example.shortsdetector.intervention

import com.example.shortsdetector.data.InterventionPolicyPreferences
import com.example.shortsdetector.data.usage.InMemoryUsageStore
import com.example.shortsdetector.data.usage.InterventionAction
import com.example.shortsdetector.data.usage.InterventionMethod
import com.example.shortsdetector.data.usage.InterventionOutcome
import com.example.shortsdetector.data.usage.InterventionOutcome.CONTINUED
import com.example.shortsdetector.data.usage.InterventionOutcome.STOPPED
import com.example.shortsdetector.data.usage.InterventionRecord
import com.example.shortsdetector.data.usage.SessionRecord
import com.example.shortsdetector.data.usage.SessionStatus
import com.example.shortsdetector.data.usage.UsageClock
import com.example.shortsdetector.testutil.FakeSharedPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 실기기에서 첫 개입이 Lv.3 으로 뜨던 문제(시연 단계가 세션을 넘어 남음)와 알림 탭 반응. */
class FirstInterventionLevelTest {

    private val prefs = FakeSharedPreferences()
    private val store = InMemoryUsageStore()
    private val clock = object : UsageClock {
        override fun epochNowMs() = 1_000L
        override fun elapsedNowMs() = 0L
    }
    private val copy = object : InterventionCopyProvider {
        override fun copyFor(input: InterventionCopyInput) = InterventionCopy("t")
        override fun levelChangeReason(from: Int, to: Int, policy: InterventionPolicy) = "$from>$to"
    }
    private var ids = 0
    private val coordinator = InterventionCoordinator(store, PreferencesPolicyStore(prefs), copy, clock) { "c${++ids}" }

    private fun level() = InterventionPolicyPreferences.resolve(prefs, emptyList()).policy.level
    private fun savedStartLevel() = InterventionPolicyPreferences.read(prefs).level
    private fun streaks() = InterventionPolicyPreferences.readLevelStreaks(prefs)

    private var seq = 0
    private fun show(session: Int = 1, method: InterventionMethod = InterventionMethod.POPUP): String {
        val id = "i${++seq}"
        store.insertIntervention(InterventionRecord(id, null, 1, method, 1, 1, level = level()))
        coordinator.begin(id, session, level())
        return id
    }

    private fun record(id: String) = store.allInterventions().single { it.id == id }

    private fun startDemo(startLevel: Int = 1) {
        InterventionPolicyPreferences.write(prefs, InterventionPolicy(level = startLevel))
        InterventionPolicyPreferences.writeDemoMode(prefs, true, nowMs = 5L)
    }

    /** 실기기 기록과 같은 순서: Lv.1 → 계속 보기 → Lv.2 → 계속 보기 → Lv.3 */
    private fun escalateToLv3() {
        repeat(2) {
            show()
            coordinator.respond(CONTINUED)
        }
        assertEquals(3, level())
    }

    @Test
    fun `first intervention of a fresh install is at the policy start level`() {
        assertEquals(savedStartLevel(), level())
        assertEquals(1, level())
    }

    @Test
    fun `first intervention of a new demo session never exceeds the policy start level`() {
        startDemo()
        escalateToLv3()

        InterventionPolicyPreferences.resetDemoLevel(prefs)

        assertTrue(level() <= savedStartLevel())
        assertEquals(1, level())
        assertEquals(LevelStreaks(), streaks())
    }

    @Test
    fun `demo level still climbs inside one session`() {
        startDemo()
        InterventionPolicyPreferences.resetDemoLevel(prefs)
        escalateToLv3()
    }

    @Test
    fun `new demo session keeps the saved policy`() {
        startDemo()
        val before = InterventionPolicyPreferences.read(prefs)
        escalateToLv3()
        InterventionPolicyPreferences.resetDemoLevel(prefs)
        assertEquals(before, InterventionPolicyPreferences.read(prefs))
    }

    @Test
    fun `real mode session start does not touch the saved start level or streaks`() {
        InterventionPolicyPreferences.write(prefs, InterventionPolicy(level = 2))
        InterventionPolicyPreferences.writeLevelState(prefs, 2, LevelStreaks(stops = 1))

        InterventionPolicyPreferences.resetDemoLevel(prefs)

        assertEquals(2, level())
        assertEquals(LevelStreaks(stops = 1), streaks())
    }

    @Test
    fun `first intervention respects maxLevel in demo`() {
        InterventionPolicyPreferences.write(prefs, InterventionPolicy(maxLevel = 2))
        InterventionPolicyPreferences.writeDemoMode(prefs, true, nowMs = 5L)
        InterventionPolicyPreferences.writeLevelState(prefs, 3, LevelStreaks())
        assertEquals(2, level())
    }

    // --- 알림 탭 ---

    @Test
    fun `tapping the notification is a response, not ignored`() {
        val id = show(method = InterventionMethod.NOTIFICATION)

        val result = coordinator.respondTo(id, STOPPED)

        assertNotNull(result)
        assertEquals(STOPPED, record(id).outcome)
        assertEquals(InterventionAction.OPEN_APP, record(id).userAction)
    }

    @Test
    fun `session end after the tap does not overwrite with IGNORED`() {
        val id = show(method = InterventionMethod.NOTIFICATION)
        coordinator.respondTo(id, STOPPED)

        assertNull(coordinator.abandon())

        assertEquals(STOPPED, record(id).outcome)
    }

    @Test
    fun `repeated notification taps never escalate`() {
        repeat(4) { coordinator.respondTo(show(method = InterventionMethod.NOTIFICATION), STOPPED) }
        assertEquals(1, level())
        assertEquals(0, streaks().skips)
    }

    @Test
    fun `notification left unanswered is still ignored and counted as a skip`() {
        val id = show(method = InterventionMethod.NOTIFICATION)
        coordinator.abandon()
        assertEquals(InterventionOutcome.IGNORED, record(id).outcome)
        assertEquals(1, streaks().skips)
    }

    @Test
    fun `tap on another intervention does nothing`() {
        val id = show()
        assertNull(coordinator.respondTo("other", STOPPED))
        assertNull(record(id).outcome)
    }

    // --- 시드·시연 기록 ---

    private fun intervention(id: String, at: Long = 10L, method: InterventionMethod = InterventionMethod.POPUP, demo: Boolean = false, seed: Boolean = false) =
        InterventionRecord(id, null, at, method, 1, 1, level = 1, isDemo = demo, isSeed = seed)

    private fun session(id: String, status: SessionStatus = SessionStatus.CLOSED, demo: Boolean = false, seed: Boolean = false) =
        SessionRecord(id, "p", "r", 0, 1, status, 1, 0, 0, 0, "HIGH", null, null, 0, isDemo = demo, isSeed = seed)

    @Test
    fun `seed and demo records do not spend the real daily limit`() {
        val records = listOf(
            intervention("real"),
            intervention("seed", seed = true),
            intervention("demo", demo = true),
            intervention("suppressed", method = InterventionMethod.SUPPRESSED_BY_DAILY_LIMIT)
        )
        assertEquals(1, LiveRecordFilter.realCount(records))
    }

    @Test
    fun `demo counter only counts this demo run`() {
        val records = listOf(
            intervention("old", at = 1L, demo = true),
            intervention("new", at = 10L, demo = true),
            intervention("seed", at = 10L, demo = true, seed = true)
        )
        assertEquals(1, LiveRecordFilter.demoCount(records, demoFromMs = 5L))
    }

    @Test
    fun `seed and demo sessions are not used for live goal remaining`() {
        val kept = LiveRecordFilter.finishedRealSessions(
            listOf(
                session("real"),
                session("open", status = SessionStatus.OPEN),
                session("seed", seed = true),
                session("demo", demo = true)
            )
        )
        assertEquals(listOf("real"), kept.map { it.id })
    }
}
