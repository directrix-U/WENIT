package com.example.shortsdetector.intervention

import com.example.shortsdetector.data.InterventionPolicyPreferences
import com.example.shortsdetector.data.usage.InMemoryUsageStore
import com.example.shortsdetector.data.usage.InterventionMethod
import com.example.shortsdetector.data.usage.InterventionOutcome
import com.example.shortsdetector.data.usage.InterventionRecord
import com.example.shortsdetector.data.usage.SystemUsageClock
import com.example.shortsdetector.detection.DetectionEngine
import com.example.shortsdetector.detection.EngineOutput
import com.example.shortsdetector.detection.adapters.youtube.YouTubeShortsAdapter
import com.example.shortsdetector.detection.model.DetectionState
import com.example.shortsdetector.detection.state.DetectionTuning
import com.example.shortsdetector.testutil.FakeSharedPreferences
import com.example.shortsdetector.testutil.Fixtures
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 한 세션 안의 개입 반복. 엔진, 단계 규칙, 재개입 규칙을 서비스처럼 이어 붙인다. */
class InterventionRepeatTest {

    private val tuning = DetectionTuning()
    private val prefs = FakeSharedPreferences()
    private val store = InMemoryUsageStore()

    private val copy = object : InterventionCopyProvider {
        override fun copyFor(input: InterventionCopyInput) = InterventionCopy("t")
        override fun levelChangeReason(from: Int, to: Int, policy: InterventionPolicy) = "r"
    }
    private var ids = 0
    private val coordinator = InterventionCoordinator(store, PreferencesPolicyStore(prefs), copy, SystemUsageClock)

    private fun engine(cooldownMs: Long, thresholdMs: Long) =
        DetectionEngine(tuning, listOf(YouTubeShortsAdapter(tuning)), cooldownMs, watchThresholdMs = thresholdMs)

    /**
     * 1초마다 쇼츠를 본다. 개입이 뜨면 [answerAfterMs] 뒤에 [answer] 로 답한다.
     * @return 개입마다 (시청시간, 단계)
     */
    private fun watch(
        e: DetectionEngine,
        seconds: Int,
        answerAfterMs: Long = 0,
        answer: (Int) -> InterventionOutcome
    ): List<Pair<Long, Int>> {
        val shown = ArrayList<Pair<Long, Int>>()
        var t = 0L
        var open: Pair<Long, Int>? = null
        repeat(seconds) {
            t += 1_000
            val out = e.onSnapshot(t, Fixtures.youtubeShorts())
            out.notifySession?.let { n ->
                val level = InterventionPolicyPreferences.resolve(prefs, emptyList()).policy.level
                shown += out.notifyWatchMs!! to level
                val id = "i${++ids}"
                store.insertIntervention(InterventionRecord(id, null, t, InterventionMethod.POPUP, 0, 0, level = level))
                coordinator.begin(id, n, level)
                e.onNotificationPosted(t)
                e.setInterventionOpen(true, t)
                open = t to level
            }
            open?.let { (shownAt, level) ->
                if (t - shownAt >= answerAfterMs) {
                    val r = coordinator.respond(answer(level))
                    r?.reinterveneAfterWatchMs?.let { after ->
                        e.sessionProgress()?.let { p -> e.delayIntervention(p.sessionNumber, p.estimatedWatchMs + after) }
                    }
                    e.setInterventionOpen(false, t)
                    open = null
                }
            }
        }
        return shown
    }

    private fun levels(shown: List<Pair<Long, Int>>) = shown.map { it.second }

    @Test
    fun `one session climbs level 1, 2, 3 and then keeps repeating level 3 outside demo mode`() {
        InterventionPolicyPreferences.write(prefs, InterventionPolicy(thresholdMs = 30_000, cooldownMs = 30_000))
        val shown = watch(engine(30_000, 30_000), 1_500) { InterventionOutcome.CONTINUED }

        // Lv.1 은 넘김 2번에 오른다(기본 정책). Lv.3 뒤에는 "5분만 더" 간격으로 반복한다.
        assertEquals(listOf(1, 1, 2, 3, 3, 3, 3), levels(shown).take(7))
        assertTrue(shown.size >= 7)
    }

    @Test
    fun `demo mode climbs one step per answer and keeps repeating level 3 at its short interval`() {
        InterventionPolicyPreferences.write(prefs, InterventionPolicy(thresholdMs = 30_000))
        InterventionPolicyPreferences.writeDemoMode(prefs, true)
        val shown = watch(engine(DemoMode.COOLDOWN_MS, DemoMode.THRESHOLD_MS), 400) { InterventionOutcome.CONTINUED }

        assertEquals(listOf(1, 2, 3, 3, 3), levels(shown).take(5))
        val gaps = shown.drop(2).zipWithNext { a, b -> b.first - a.first }
        assertTrue(gaps.isNotEmpty() && gaps.all { it in DemoMode.MORE_TIME_MS..(DemoMode.MORE_TIME_MS + 2_000) })
        assertTrue("상한 없이 계속 반복해야 한다", shown.size > 10)
    }

    @Test
    fun `stopping at level 3 and watching on still repeats every cooldown`() {
        InterventionPolicyPreferences.write(prefs, InterventionPolicy(thresholdMs = 30_000, level = 3, deescalateAfterStops = 10))
        val shown = watch(engine(30_000, 30_000), 300) { InterventionOutcome.STOPPED }

        assertEquals(List(shown.size) { 3 }, levels(shown))
        assertTrue(shown.size >= 9)
        assertTrue(shown.zipWithNext { a, b -> b.first - a.first }.all { it in 30_000L..32_000L })
    }

    @Test
    fun `no answer at the top level repeats at the top level`() {
        InterventionPolicyPreferences.write(prefs, InterventionPolicy(thresholdMs = 30_000, level = 3))
        val shown = watch(engine(30_000, 30_000), 300) { InterventionOutcome.IGNORED }

        assertEquals(List(shown.size) { 3 }, levels(shown))
        assertTrue(shown.size >= 9)
    }

    @Test
    fun `max level 2 repeats level 2 at the cooldown`() {
        InterventionPolicyPreferences.write(prefs, InterventionPolicy(thresholdMs = 30_000, level = 2, maxLevel = 2))
        val shown = watch(engine(30_000, 30_000), 300) { InterventionOutcome.CONTINUED }

        assertEquals(List(shown.size) { 2 }, levels(shown))
        assertTrue(shown.size >= 9)
    }

    @Test
    fun `there is no daily ceiling on repeats`() {
        InterventionPolicyPreferences.write(prefs, InterventionPolicy(thresholdMs = 30_000, level = 1, maxLevel = 2))
        val shown = watch(engine(30_000, 30_000), 3_600) { InterventionOutcome.IGNORED }
        assertTrue(shown.size > 100)
    }

    // --- 간격 ---

    private fun DetectionEngine.frames(from: Long, to: Long): List<EngineOutput> =
        (from..to step 1_000L).map { t ->
            onSnapshot(t, Fixtures.youtubeShorts()).also { if (it.notifySession != null) onNotificationPosted(t) }
        }

    @Test
    fun `a long cooldown only delays the next intervention and does not use it up`() {
        val e = engine(600_000, 30_000)
        val outs = e.frames(0, 1_400_000)
        assertTrue(outs.count { it.notifySession != null } >= 2)
    }

    @Test
    fun `a chance held back by the cooldown is reported once and fires when the cooldown ends`() {
        val e = engine(120_000, 30_000)
        val first = e.frames(0, 40_000)
        assertEquals(1, first.count { it.notifySession != null })

        // 다음 세션: 쿨다운이 남은 채 기준에 닿는다.
        (41_000L..44_000L step 1_000L).forEach { e.onRootUnavailable(it) }
        val second = (60_000L..170_000L step 1_000L).map { t ->
            e.onSnapshot(t, Fixtures.youtubeShorts()).also { if (it.notifySession != null) e.onNotificationPosted(t) }
        }
        assertEquals(1, second.count { it.suppressedByCooldown })
        assertTrue(second.count { it.notifySession != null } >= 1)
    }

    @Test
    fun `a repeat waiting for the cooldown leaves no skipped record`() {
        val e = engine(30_000, 30_000)
        val outs = e.frames(0, 200_000)
        assertTrue(outs.count { it.notifySession != null } >= 5)
        assertEquals(0, outs.count { it.suppressedByCooldown })
    }

    @Test
    fun `nothing fires while a popup is open and the cooldown restarts when it closes`() {
        val e = engine(30_000, 30_000)
        val outs = e.frames(0, 40_000)
        val shownAt = outs.indexOfFirst { it.notifySession != null } * 1_000L
        e.setInterventionOpen(true, shownAt)

        assertEquals(0, e.frames(41_000, 120_000).count { it.notifySession != null })

        e.setInterventionOpen(false, 121_000)
        assertEquals(0, e.frames(121_000, 145_000).count { it.notifySession != null })
        assertEquals(1, e.frames(146_000, 155_000).count { it.notifySession != null })
    }

    @Test
    fun `paused playback does not bring the next intervention closer`() {
        val e = engine(30_000, 30_000)
        e.frames(0, 40_000)
        e.setWatchHold(true)
        assertEquals(0, e.frames(41_000, 200_000).count { it.notifySession != null })
    }

    @Test
    fun `repeats wait until the shorts screen is fully visible again`() {
        val e = engine(30_000, 30_000)
        e.frames(0, 40_000)
        val partial = (41_000L..100_000L step 1_000L).map { e.onSnapshot(it, Fixtures.youtubeShortsCommentsOpen()) }
        assertEquals(DetectionState.TEMPORARILY_OBSCURED, partial.last().step.current)
        assertEquals(0, partial.count { it.notifySession != null })
        assertEquals(1, e.frames(101_000, 104_000).count { it.notifySession != null })
    }

    @Test
    fun `leaving and coming back into the same session keeps the rhythm`() {
        val e = engine(30_000, 30_000)
        e.frames(0, 40_000)
        (41_000L..44_000L step 1_000L).forEach { e.onRootUnavailable(it) }
        val back = e.frames(46_000, 110_000)
        assertTrue(back.any { it.step.resumedSession != null })
        assertTrue(back.count { it.notifySession != null } >= 1)
        assertFalse(back.any { it.step.startedSession != null })
    }
}
