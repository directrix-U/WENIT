package com.example.shortsdetector.intervention

import com.example.shortsdetector.data.InterventionPolicyPreferences
import com.example.shortsdetector.data.usage.InMemoryUsageStore
import com.example.shortsdetector.data.usage.InterventionAction
import com.example.shortsdetector.data.usage.InterventionMethod
import com.example.shortsdetector.data.usage.InterventionOutcome.CONTINUED
import com.example.shortsdetector.data.usage.InterventionOutcome.IGNORED
import com.example.shortsdetector.data.usage.InterventionOutcome.STOPPED
import com.example.shortsdetector.data.usage.InterventionRecord
import com.example.shortsdetector.data.usage.InterventionUserReason
import com.example.shortsdetector.data.usage.PolicyChangeSource
import com.example.shortsdetector.data.usage.PolicyChangeStatus
import com.example.shortsdetector.data.usage.UsageClock
import com.example.shortsdetector.detection.DetectionEngine
import com.example.shortsdetector.detection.EngineOutput
import com.example.shortsdetector.detection.adapters.youtube.YouTubeShortsAdapter
import com.example.shortsdetector.detection.state.DetectionTuning
import com.example.shortsdetector.testutil.FakeSharedPreferences
import com.example.shortsdetector.testutil.Fixtures
import com.example.shortsdetector.testutil.Fixtures.YT
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 반응 기록 → 단계 규칙 → 정책 저장 → 재개입까지 서비스와 같은 순서로 잇는다. */
class InterventionFlowTest {

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

    private fun savedLevel() = InterventionPolicyPreferences.read(prefs).level
    private fun effective() = InterventionPolicyPreferences.resolve(prefs, emptyList())

    /** 개입 하나를 기록하고 시작한다. */
    private var interventionSeq = 0
    private fun show(session: Int = 1): String {
        val id = "i${++interventionSeq}"
        store.insertIntervention(
            InterventionRecord(id, null, 1, InterventionMethod.POPUP, 1, 1, level = effective().policy.level)
        )
        coordinator.begin(id, session, effective().policy.level)
        return id
    }

    private fun intervention(id: String) = store.allInterventions().single { it.id == id }

    // --- 반응 기록과 단계 ---

    @Test
    fun `two skips on level 1 raise the level and record a RULE change with its reason`() {
        coordinator.respond(CONTINUED.also { show() })
        assertEquals(1, savedLevel())
        assertTrue(store.recentPolicyChanges(10).isEmpty())

        show()
        val r = coordinator.respond(CONTINUED)!!
        assertEquals(1, r.level)
        assertEquals(2, r.nextLevel)
        assertEquals(2, savedLevel())

        val change = store.recentPolicyChanges(10).single()
        assertEquals(PolicyChangeSource.RULE, change.source)
        assertEquals(PolicyChangeStatus.APPLIED, change.status)
        assertEquals(1, change.previousPolicy!!.level)
        assertEquals(2, change.newPolicy.level)
        assertEquals("1>2", change.reasonText)
        assertFalse(change.isDemo)
    }

    @Test
    fun `level 2 continue goes straight to level 3 with the chosen reason stored`() {
        InterventionPolicyPreferences.write(prefs, InterventionPolicy(level = 2))
        val id = show()
        coordinator.chooseReason(InterventionUserReason.BORED)
        coordinator.chooseReason(InterventionUserReason.PROCRASTINATING)     // 고쳐 고를 수 있다
        coordinator.respond(CONTINUED)
        assertEquals(3, savedLevel())
        assertEquals(CONTINUED, intervention(id).outcome)
        assertEquals(InterventionUserReason.PROCRASTINATING, intervention(id).userReason)
    }

    @Test
    fun `consecutive stops lower the level`() {
        InterventionPolicyPreferences.write(prefs, InterventionPolicy(level = 3))
        show(); coordinator.respond(STOPPED)
        assertEquals(3, savedLevel())
        show(); coordinator.respond(STOPPED)
        assertEquals(2, savedLevel())
        assertEquals("3>2", store.recentPolicyChanges(1).single().reasonText)
    }

    @Test
    fun `streaks survive a new coordinator (service restart)`() {
        show(); coordinator.respond(IGNORED)
        val restarted = InterventionCoordinator(store, PreferencesPolicyStore(prefs), copy, clock)
        restarted.begin(show(), 1, 1)
        restarted.respond(IGNORED)
        assertEquals(2, savedLevel())
    }

    @Test
    fun `button reaction column stays filled for choices`() {
        val stop = show(); coordinator.respond(STOPPED)
        val go = show(); coordinator.respond(CONTINUED)
        assertEquals(InterventionAction.OPEN_APP, intervention(stop).userAction)
        assertEquals(InterventionAction.DISMISS, intervention(go).userAction)
    }

    // --- 반응 없는 팝업 ---

    @Test
    fun `popup that disappears without a reaction is IGNORED and counts as a skip`() {
        val id = show()
        assertNotNull(coordinator.abandon())
        assertEquals(IGNORED, intervention(id).outcome)
        assertNull(intervention(id).userAction)

        show(); coordinator.abandon()
        assertEquals(2, savedLevel())
    }

    @Test
    fun `a reason chosen before disappearing is kept`() {
        InterventionPolicyPreferences.write(prefs, InterventionPolicy(level = 2))
        val id = show()
        coordinator.chooseReason(InterventionUserReason.TIRED)
        coordinator.abandon()
        assertEquals(IGNORED, intervention(id).outcome)
        assertEquals(InterventionUserReason.TIRED, intervention(id).userReason)
    }

    @Test
    fun `session end after a button does not overwrite the answer or count twice`() {
        val id = show()
        coordinator.respond(STOPPED)
        assertNull(coordinator.abandon())
        assertNull(coordinator.respond(CONTINUED))
        assertEquals(STOPPED, intervention(id).outcome)
        assertEquals(LevelStreaks(stops = 1), InterventionPolicyPreferences.readLevelStreaks(prefs))
    }

    @Test
    fun `nothing pending means nothing is written`() {
        assertNull(coordinator.abandon())
        assertEquals(1, savedLevel())
        assertEquals(LevelStreaks(), InterventionPolicyPreferences.readLevelStreaks(prefs))
    }

    @Test
    fun `a new intervention closes an unanswered one as IGNORED`() {
        val first = show()
        val second = show()
        assertEquals(IGNORED, intervention(first).outcome)
        assertNull(intervention(second).outcome)
    }

    // --- 재개입 ---

    @Test
    fun `five more minutes applies only to level 3 continue outside demo mode`() {
        assertEquals(300_000L, ReinterventionRule.afterWatchMs(3, CONTINUED, null))
        assertNull(ReinterventionRule.afterWatchMs(1, CONTINUED, null))
        assertNull(ReinterventionRule.afterWatchMs(2, CONTINUED, null))
        assertNull(ReinterventionRule.afterWatchMs(3, STOPPED, null))
        assertNull(ReinterventionRule.afterWatchMs(3, IGNORED, null))
    }

    @Test
    fun `demo mode continues after the demo threshold and level 3 waits 30 seconds`() {
        assertEquals(30_000L, ReinterventionRule.afterWatchMs(1, CONTINUED, 30_000))
        assertEquals(30_000L, ReinterventionRule.afterWatchMs(2, CONTINUED, 30_000))
        assertEquals(30_000L, ReinterventionRule.afterWatchMs(3, CONTINUED, 30_000))
        assertNull(ReinterventionRule.afterWatchMs(1, STOPPED, 30_000))
    }

    private val tuning = DetectionTuning()

    private fun engine(cooldownMs: Long = 30_000) =
        DetectionEngine(tuning, listOf(YouTubeShortsAdapter(tuning)), cooldownMs, watchThresholdMs = 30_000)
            .also { it.setEnabledPackages(setOf(YT), 0) }

    /** 서비스처럼 개입이 나오면 게시 처리한다. */
    private fun DetectionEngine.frame(t: Long): EngineOutput =
        onSnapshot(t, Fixtures.youtubeShorts()).also { if (it.notifySession != null) onNotificationPosted(t) }

    /** [from] 부터 [to] 까지 1초 간격으로 보고 나온 출력들 */
    private fun DetectionEngine.watch(from: Long, to: Long): List<EngineOutput> =
        (from..to step 1_000L).map { frame(it) }

    private fun DetectionEngine.enter(from: Long) {
        frame(from); frame(from + 250); frame(from + 500)
    }

    private fun DetectionEngine.leave(from: Long) {
        onRootUnavailable(from); onRootUnavailable(from + 1_000); onRootUnavailable(from + 2_000)
    }

    @Test
    fun `without any answer a session repeats every cooldown of watching`() {
        val e = engine()
        e.enter(0)
        assertEquals(19, e.watch(1_000, 600_000).count { it.notifySession != null })
    }

    @Test
    fun `five more minutes is kept, then the cooldown takes over again`() {
        val e = engine()
        e.enter(0)
        val first = e.watch(1_000, 40_000).first { it.notifySession != null }
        val session = first.notifySession!!
        val at = e.sessionProgress()!!.estimatedWatchMs
        e.delayIntervention(session, at + ReinterventionRule.FIVE_MORE_MINUTES_MS)

        val again = e.watch(41_000, 400_000).filter { it.notifySession != null }
        val watched = again.first().notifyWatchMs!!
        assertTrue(watched >= at + 300_000 && watched < at + 302_000)
        assertTrue(again.size >= 2)
    }

    @Test
    fun `a promised delay for another session is ignored`() {
        val e = engine()
        e.enter(0)
        e.watch(1_000, 40_000)
        e.delayIntervention(99, 400_000)
        assertEquals(1, e.watch(41_000, 80_000).count { it.notifySession != null })
    }

    @Test
    fun `policy changes reach an engine that is already running`() {
        val e = engine()
        e.applyPolicy(60_000, 1_000)
        assertEquals(60_000, e.watchThresholdMs)
        e.enter(0)
        val first = e.watch(1_000, 70_000).first { it.notifySession != null }
        assertTrue(first.notifyWatchMs!! >= 60_000)
    }

    // --- 시연 모드 ---

    @Test
    fun `demo mode overlays fast values and does not touch the saved policy`() {
        val saved = InterventionPolicy(thresholdMs = 600_000, cooldownMs = 120_000, escalateAfterSkips = 4)
        InterventionPolicyPreferences.write(prefs, saved)
        InterventionPolicyPreferences.writeDemoMode(prefs, true)

        val r = effective()
        assertTrue(r.demoMode)
        assertEquals(10_000, r.thresholdMs)
        assertEquals(10_000, r.policy.cooldownMs)
        assertEquals(1, r.policy.escalateAfterSkips)
        assertEquals(saved, InterventionPolicyPreferences.read(prefs))
    }

    @Test
    fun `demo mode climbs one level per continue and turning it off restores the policy`() {
        val saved = InterventionPolicy(thresholdMs = 600_000, escalateAfterSkips = 4)
        InterventionPolicyPreferences.write(prefs, saved)
        InterventionPolicyPreferences.writeDemoMode(prefs, true)

        show(); val one = coordinator.respond(CONTINUED)!!
        assertEquals(2, one.nextLevel)
        assertEquals(10_000L, one.reinterveneAfterWatchMs)
        show(); val two = coordinator.respond(CONTINUED)!!
        assertEquals(3, two.nextLevel)
        show(); val three = coordinator.respond(CONTINUED)!!
        assertEquals(DemoMode.MORE_TIME_MS, three.reinterveneAfterWatchMs)

        assertEquals(3, effective().policy.level)
        assertEquals(saved, InterventionPolicyPreferences.read(prefs))
        val changes = store.recentPolicyChanges(10)
        assertEquals(2, changes.size)
        assertTrue(changes.all { it.isDemo && it.source == PolicyChangeSource.RULE })

        InterventionPolicyPreferences.writeDemoMode(prefs, false)
        val off = effective()
        assertFalse(off.demoMode)
        assertEquals(saved, off.policy)
        assertEquals(600_000, off.thresholdMs)
    }

    @Test
    fun `turning demo mode on again starts from level 1`() {
        InterventionPolicyPreferences.writeDemoMode(prefs, true)
        show(); coordinator.respond(CONTINUED)
        assertEquals(2, effective().policy.level)
        InterventionPolicyPreferences.writeDemoMode(prefs, false)
        InterventionPolicyPreferences.writeDemoMode(prefs, true)
        assertEquals(1, effective().policy.level)
    }

    @Test
    fun `demo streaks do not leak into the real streaks`() {
        InterventionPolicyPreferences.writeLevelState(prefs, 1, LevelStreaks(skips = 1))
        InterventionPolicyPreferences.writeDemoMode(prefs, true)
        assertEquals(LevelStreaks(), InterventionPolicyPreferences.readLevelStreaks(prefs))
        InterventionPolicyPreferences.writeLevelState(prefs, 2, LevelStreaks(stops = 1))
        InterventionPolicyPreferences.writeDemoMode(prefs, false)
        assertEquals(LevelStreaks(skips = 1), InterventionPolicyPreferences.readLevelStreaks(prefs))
    }

    // --- 기준 시간 통합 ---

    @Test
    fun `legacy threshold moves to the policy once and the old key is removed`() {
        val legacy = FakeSharedPreferences()
        legacy.values["watch_threshold_ms"] = 60_000L
        legacy.values["youtube_enabled"] = true

        assertTrue(InterventionPolicyPreferences.migrateLegacyThreshold(prefs, legacy))
        assertEquals(60_000, InterventionPolicyPreferences.read(prefs).thresholdMs)
        assertFalse(legacy.contains("watch_threshold_ms"))
        assertTrue(legacy.contains("youtube_enabled"))

        // 이후 사용자 변경은 다시 옮겨도 덮이지 않는다
        InterventionPolicyPreferences.write(prefs, InterventionPolicy(thresholdMs = 300_000))
        legacy.values["watch_threshold_ms"] = 30_000L
        assertFalse(InterventionPolicyPreferences.migrateLegacyThreshold(prefs, legacy))
        assertEquals(300_000, InterventionPolicyPreferences.read(prefs).thresholdMs)
    }

    @Test
    fun `missing or invalid legacy threshold falls back to the default`() {
        InterventionPolicyPreferences.migrateLegacyThreshold(prefs, FakeSharedPreferences())
        assertEquals(InterventionPolicy(), InterventionPolicyPreferences.read(prefs))

        val other = FakeSharedPreferences()
        val legacy = FakeSharedPreferences().also { it.values["watch_threshold_ms"] = 12_345L }
        InterventionPolicyPreferences.migrateLegacyThreshold(other, legacy)
        assertEquals(InterventionPolicy(), InterventionPolicyPreferences.read(other))
    }

    @Test
    fun `user threshold change is a clamped USER record, and no change means no record`() {
        val before = InterventionPolicy(thresholdMs = 180_000)
        val change = InterventionCoordinator.userThresholdChange(before, 60_000, "u", 5, "이유")!!
        assertEquals(PolicyChangeSource.USER, change.source)
        assertEquals(before, change.previousPolicy)
        assertEquals(60_000, change.newPolicy.thresholdMs)

        assertEquals(10_000, InterventionCoordinator.userThresholdChange(before, 1, "u", 5, "이유")!!.newPolicy.thresholdMs)
        assertNull(InterventionCoordinator.userThresholdChange(before, 180_000, "u", 5, "이유"))
    }

    @Test
    fun `per session goal drives the resolved threshold outside demo mode only`() {
        val goals = listOf(com.example.shortsdetector.data.goal.GoalRules.userGoal(
            com.example.shortsdetector.data.goal.GoalType.PER_SESSION, 2
        ))
        assertEquals(120_000, InterventionPolicyPreferences.resolve(prefs, goals).thresholdMs)
        InterventionPolicyPreferences.writeDemoMode(prefs, true)
        assertEquals(10_000, InterventionPolicyPreferences.resolve(prefs, goals).thresholdMs)
    }
}
