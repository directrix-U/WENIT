package com.example.shortsdetector.data.usage

import com.example.shortsdetector.DetectionConfig
import com.example.shortsdetector.detection.DetectionEngine
import com.example.shortsdetector.detection.EngineOutput
import com.example.shortsdetector.detection.adapters.instagram.InstagramReelsAdapter
import com.example.shortsdetector.detection.adapters.youtube.YouTubeShortsAdapter
import com.example.shortsdetector.detection.model.ScreenSnapshot
import com.example.shortsdetector.detection.state.DetectionTuning
import com.example.shortsdetector.testutil.Fixtures
import com.example.shortsdetector.testutil.Fixtures.YT
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 실제 [DetectionEngine] 을 fixture 로 돌리면서 [SessionRecorder] 가 남기는 기록을 확인한다.
 * 서비스와 같은 순서: 엔진 출력 → recorder.onOutput(output, engine.sessionProgress()).
 */
class SessionRecorderTest {

    /** elapsed = 엔진 관찰 시각. epoch = base + elapsed + shift(사용자가 시계를 바꾼 상황 흉내). */
    private class FakeClock(var elapsed: Long = 0L, val base: Long = 1_800_000_000_000L) : UsageClock {
        var shift = 0L
        override fun epochNowMs() = base + elapsed + shift
        override fun elapsedNowMs() = elapsed
    }

    private val tuning = DetectionTuning()
    private val clock = FakeClock()
    private val store = InMemoryUsageStore()
    private var ids = 0
    private val recorder = SessionRecorder(store, clock, checkpointIntervalMs = 15_000) { "id-${++ids}" }
    private val engine = DetectionEngine(
        tuning, listOf(YouTubeShortsAdapter(tuning), InstagramReelsAdapter(tuning)),
        DetectionConfig.MIN_RENOTIFY_INTERVAL_MS, watchThresholdMs = 30_000
    ).also { it.setEnabledPackages(setOf(YT), 0) }

    private fun frame(t: Long, s: ScreenSnapshot = Fixtures.youtubeShorts()): EngineOutput {
        clock.elapsed = t
        return engine.onSnapshot(t, s).also { recorder.onOutput(it, engine.sessionProgress()) }
    }

    private fun gone(t: Long): EngineOutput {
        clock.elapsed = t
        return engine.onRootUnavailable(t).also { recorder.onOutput(it, engine.sessionProgress()) }
    }

    private fun enter(from: Long) { frame(from); frame(from + 250); frame(from + 500) }

    /** from 부터 1초 간격으로 until 까지 */
    private fun watch(from: Long, until: Long, s: () -> ScreenSnapshot = { Fixtures.youtubeShorts() }) {
        for (t in from..until step 1_000L) frame(t, s())
    }

    private fun leave(from: Long) { gone(from); gone(from + 1_000); gone(from + 2_000) }

    @Test
    fun `session start writes an OPEN row and session end closes the same row`() {
        enter(0)                                      // 세션 시작 500
        val open = store.allSessions().single()
        assertEquals(SessionStatus.OPEN, open.status)
        assertEquals(clock.base + 500, open.startedAtMs)
        assertNull(open.endReason)

        watch(1_500, 10_500)
        leave(11_500)                                 // 13_500 에 종료
        val closed = store.allSessions().single()
        assertEquals(open.id, closed.id)
        assertEquals(SessionStatus.CLOSED, closed.status)
        assertEquals("EXIT_CONFIRMED", closed.endReason)
        assertEquals("ROOT_UNAVAILABLE", closed.exitCause)
        assertEquals(clock.base + 13_500, closed.endedAtMs)
        assertEquals(11_000, closed.estimatedWatchMs)  // 500 → 11_500
        assertEquals(YT, closed.packageName)
        assertEquals("HIGH", closed.maxConfidence)
    }

    @Test
    fun `comments time is stored inside watch time and as its own column`() {
        enter(0)
        watch(1_500, 5_500)
        watch(6_500, 15_500) { Fixtures.youtubeShortsCommentsOpen() }
        watch(16_500, 20_500) { Fixtures.youtubeShortsCommentsExpanded() }
        engine.forceEnd(21_000).also { recorder.onOutput(it, engine.sessionProgress()) }
        val r = store.allSessions().single()
        assertEquals("FORCED", r.endReason)
        assertEquals(20_000, r.estimatedWatchMs)
        assertEquals(14_000, r.partialWatchMs)        // 6_500 에 댓글창 진입 → 7_500..20_500 의 14회
        assertEquals(0, r.obscuredMs)
    }

    @Test
    fun `resumed session updates the same row - no double counting`() {
        enter(0)
        watch(1_500, 10_500)
        leave(11_500)
        assertEquals(SessionStatus.CLOSED, store.allSessions().single().status)

        enter(15_000)                                 // 1.5초 뒤 재진입 → 이어짐
        assertEquals(SessionStatus.OPEN, store.allSessions().single().status)
        watch(16_500, 20_500)
        leave(21_500)

        val r = store.allSessions().single()
        assertEquals(SessionStatus.CLOSED, r.status)
        assertEquals(1, r.resumeCount)
        assertEquals(11_000 + 6_000, r.estimatedWatchMs) // 15_500 → 21_500
        assertEquals(clock.base + 500, r.startedAtMs)
    }

    @Test
    fun `re-entry after the resume window is a new row`() {
        enter(0)
        watch(1_500, 5_500)
        leave(6_500)
        enter(30_000)
        assertEquals(2, store.allSessions().size)
        assertEquals(listOf("id-1", "id-2"), store.allSessions().map { it.id })
    }

    @Test
    fun `open session is checkpointed every 15 seconds, not every frame`() {
        enter(0)
        var writes = 0
        var lastWatch = -1L
        for (t in 1_500L..61_500L step 1_000L) {
            frame(t)
            val w = store.allSessions().single().estimatedWatchMs
            if (w != lastWatch) { writes++; lastWatch = w }
        }
        // 0(시작) 이후 15초마다: 15_500, 30_500, 45_500, 60_500
        assertEquals(1 + 4, writes)
        val r = store.allSessions().single()
        assertEquals(SessionStatus.OPEN, r.status)
        assertEquals(60_000, r.estimatedWatchMs)
        assertEquals(clock.base + 60_500, r.endedAtMs)
    }

    @Test
    fun `process death leaves OPEN row, next service start marks it ABORTED`() {
        enter(0)
        watch(1_500, 20_500)                          // 마지막 중간 저장 15_500
        // 프로세스 종료: forceEnd 없이 새 recorder 가 같은 저장소로 시작
        val next = SessionRecorder(store, clock)
        next.onServiceStarted(retentionMs = 90L * 24 * 3_600_000)
        val r = store.allSessions().single()
        assertEquals(SessionStatus.ABORTED, r.status)
        assertEquals(15_000, r.estimatedWatchMs)
        assertEquals(clock.base + 15_500, r.endedAtMs)
    }

    @Test
    fun `retention deletes old closed sessions and interventions on service start`() {
        val day = 24L * 3_600_000
        store.upsertSession(record("old", startedAt = clock.base - 100 * day, endedAt = clock.base - 99 * day))
        store.upsertSession(record("new", startedAt = clock.base - 10 * day, endedAt = clock.base - 10 * day))
        store.insertIntervention(
            InterventionRecord("i-old", "old", clock.base - 99 * day, InterventionMethod.POPUP, 1, 1)
        )
        recorder.onServiceStarted(retentionMs = 90 * day)
        assertEquals(listOf("new"), store.allSessions().map { it.id })
        assertTrue(store.allInterventions().isEmpty())
    }

    @Test
    fun `wall clock change mid-session does not change the stored duration`() {
        enter(0)
        watch(1_500, 10_500)
        clock.shift = -3_600_000                      // 사용자가 시계를 1시간 뒤로
        leave(11_500)
        val r = store.allSessions().single()
        assertEquals(13_000, r.endedAtMs - r.startedAtMs)
    }

    @Test
    fun `intervention is linked to the session and only the first reaction is kept`() {
        enter(0)
        watch(1_500, 30_500)
        val id = recorder.onIntervention(1, InterventionMethod.POPUP, 30_000, 30_000)
        recorder.onInterventionAction(id, InterventionAction.DISMISS)
        recorder.onInterventionAction(id, InterventionAction.OPEN_APP)
        val i = store.allInterventions().single()
        assertEquals(store.allSessions().single().id, i.sessionId)
        assertEquals(InterventionAction.DISMISS, i.userAction)
        assertNotNull(i.actionAtMs)
        assertEquals(30_000, i.thresholdMs)
    }

    @Test
    fun `intervention for an unknown session is stored without a session id`() {
        recorder.onIntervention(7, InterventionMethod.FAILED, 0, 30_000)
        assertNull(store.allInterventions().single().sessionId)
    }

    @Test
    fun `stored records contain only codes, numbers and package names`() {
        enter(0)
        watch(1_500, 5_500)
        leave(6_500)
        val r = store.allSessions().single()
        val texts = listOfNotNull(r.packageName, r.ruleVersion, r.maxConfidence, r.endReason, r.exitCause)
        assertTrue(texts.all { it.matches(Regex("[A-Za-z0-9_.\\-]+")) })
    }

    private fun record(id: String, startedAt: Long, endedAt: Long) = SessionRecord(
        id, YT, "yt-2", startedAt, endedAt, SessionStatus.CLOSED, 1_000, 0, 0, 0, "HIGH",
        "EXIT_CONFIRMED", "ROOT_UNAVAILABLE", 0
    )
}
