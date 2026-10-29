package com.example.shortsdetector.detection.state

import com.example.shortsdetector.detection.model.AdapterVerdict
import com.example.shortsdetector.detection.model.Confidence
import com.example.shortsdetector.detection.model.DetectionState
import com.example.shortsdetector.detection.model.EndReason
import com.example.shortsdetector.detection.model.ExitCause
import com.example.shortsdetector.detection.model.Observation
import com.example.shortsdetector.detection.model.ReasonCode
import com.example.shortsdetector.detection.model.VerdictKind
import com.example.shortsdetector.detection.model.WindowStatus
import com.example.shortsdetector.testutil.Fixtures.IG
import com.example.shortsdetector.testutil.Fixtures.YT
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ShortformStateMachineTest {

    private val tuning = DetectionTuning(
        confirmationCount = 3, exitGraceMs = 2_000, staleMs = 60_000, maxCreditPerStepMs = 3_000
    )

    private fun target(
        t: Long, kind: VerdictKind, c: Confidence = Confidence.HIGH, pkg: String = YT
    ) = Observation(t, WindowStatus.TARGET, pkg, AdapterVerdict(kind, c, ReasonCode.PLAYER_FULLSCREEN), "test")

    private fun shorts(t: Long, c: Confidence = Confidence.HIGH, pkg: String = YT) =
        target(t, VerdictKind.SHORTFORM, c, pkg)

    private fun partial(t: Long) = target(t, VerdictKind.SHORTFORM_PARTIAL, Confidence.MEDIUM)
    private fun notShorts(t: Long) = target(t, VerdictKind.NOT_SHORTFORM, Confidence.LOW)
    private fun unknown(t: Long) = target(t, VerdictKind.UNKNOWN, Confidence.LOW)

    /** 3회 확인으로 세션을 연 상태의 머신. 마지막 관찰 시각 = 2_000 */
    private fun estimated(): ShortformStateMachine = ShortformStateMachine(tuning).also { m ->
        m.onObservation(shorts(0))
        m.onObservation(shorts(1_000))
        val s = m.onObservation(shorts(2_000))
        assertEquals(1, s.startedSession)
    }

    @Test
    fun `entry needs consecutive confirmations (legacy CONFIRMATION_COUNT)`() {
        val m = ShortformStateMachine(tuning)
        assertEquals(DetectionState.CANDIDATE, m.onObservation(shorts(0)).current)
        assertNull(m.onObservation(shorts(250)).startedSession)
        val s = m.onObservation(shorts(500))
        assertEquals(DetectionState.SHORTFORM_ESTIMATED, s.current)
        assertEquals(1, s.startedSession)
        assertEquals(Confidence.HIGH, s.startedConfidence)
    }

    @Test
    fun `interrupted confirmation starts over (one-off transition false positive)`() {
        val m = ShortformStateMachine(tuning)
        m.onObservation(shorts(0))
        m.onObservation(shorts(250))
        assertEquals(DetectionState.TARGET_FOREGROUND, m.onObservation(notShorts(500)).current)
        m.onObservation(shorts(750))
        m.onObservation(shorts(1_000))
        assertEquals(DetectionState.CANDIDATE, m.state)
        assertEquals(1, m.onObservation(shorts(1_250)).startedSession)
    }

    @Test
    fun `partial player never starts a session (legacy entry needs fullscreen)`() {
        val m = ShortformStateMachine(tuning)
        repeat(10) { m.onObservation(partial(it * 1_000L)) }
        assertEquals(DetectionState.TARGET_FOREGROUND, m.state)
    }

    @Test
    fun `low confidence shortform is observed as candidate but never estimated`() {
        val m = ShortformStateMachine(tuning)
        repeat(20) {
            val s = m.onObservation(shorts(it * 1_000L, Confidence.LOW))
            assertNull(s.startedSession)
        }
        assertEquals(DetectionState.CANDIDATE, m.state)
    }

    @Test
    fun `low confidence keeps an already estimated session alive`() {
        val m = estimated()
        assertEquals(DetectionState.SHORTFORM_ESTIMATED, m.onObservation(shorts(3_000, Confidence.LOW)).current)
    }

    @Test
    fun `comment sheet is temporarily obscured, resumes without new session`() {
        val m = estimated()
        assertEquals(DetectionState.TEMPORARILY_OBSCURED, m.onObservation(partial(3_000)).current)
        // 댓글을 오래 봐도 세션은 유지된다(기존 래치 유지 규칙)
        for (t in 4_000L..30_000L step 1_000L) m.onObservation(partial(t))
        val back = m.onObservation(shorts(31_000))
        assertEquals(DetectionState.SHORTFORM_ESTIMATED, back.current)
        assertNull(back.startedSession)
    }

    @Test
    fun `obscured time is tracked separately from estimated watch time`() {
        val m = estimated()                          // 2_000
        m.onObservation(shorts(4_000))               // +2_000 watch
        m.onObservation(partial(5_000))              // +1_000 watch
        m.onObservation(partial(7_000))              // +2_000 obscured
        m.onObservation(shorts(8_000))               // +1_000 obscured
        val end = m.forceEnd(9_000)!!
        assertEquals(3_000, end.estimatedWatchMs)
        assertEquals(3_000, end.obscuredMs)
        assertEquals(0, end.uncertainMs)
    }

    @Test
    fun `notification shade - root briefly unavailable does not end the session`() {
        val m = estimated()
        assertEquals(DetectionState.EXIT_PENDING, m.onObservation(Observation.unavailable(3_000)).current)
        assertNull(m.onObservation(Observation.unavailable(4_000)).endedSession)
        val back = m.onObservation(shorts(4_500))
        assertEquals(DetectionState.SHORTFORM_ESTIMATED, back.current)
        assertNull(back.startedSession)
        assertNull(back.endedSession)
    }

    @Test
    fun `home screen - root unavailable past grace ends the session`() {
        val m = estimated()
        m.onObservation(Observation.unavailable(3_000))
        m.onObservation(Observation.unavailable(4_000))
        val s = m.onObservation(Observation.unavailable(5_000))
        assertEquals(DetectionState.ENDED, s.current)
        val end = assertNotNullAndGet(s.endedSession)
        assertEquals(EndReason.EXIT_CONFIRMED, end.endReason)
        assertEquals(ExitCause.ROOT_UNAVAILABLE, end.exitCause)
        assertEquals(2_000, end.uncertainMs)
        // 다음 관찰에서 대기 상태로 돌아간다
        assertEquals(DetectionState.OUTSIDE_TARGET, m.onObservation(Observation.unavailable(6_000)).current)
    }

    @Test
    fun `foreign window (other app or system dialog) past grace ends the session`() {
        val m = estimated()
        m.onObservation(Observation.foreign(3_000, "com.android.systemui"))
        val s = m.onObservation(Observation.foreign(5_000, "com.android.systemui"))
        assertEquals(ExitCause.FOREIGN_WINDOW, s.endedSession!!.exitCause)
    }

    @Test
    fun `leaving shorts inside youtube (home or long video) ends after grace`() {
        val m = estimated()
        m.onObservation(notShorts(3_000))
        assertEquals(DetectionState.EXIT_PENDING, m.state)
        val s = m.onObservation(notShorts(5_000))
        assertEquals(ExitCause.NO_SHORTFORM_SIGNAL, s.endedSession!!.exitCause)
    }

    @Test
    fun `unknown screen while estimated waits instead of ending immediately`() {
        val m = estimated()
        assertNull(m.onObservation(unknown(3_000)).endedSession)
        assertEquals(DetectionState.EXIT_PENDING, m.state)
        assertEquals(DetectionState.SHORTFORM_ESTIMATED, m.onObservation(shorts(3_500)).current)
    }

    @Test
    fun `grace timer restarts after a recovery`() {
        val m = estimated()
        m.onObservation(Observation.unavailable(3_000))
        m.onObservation(shorts(4_000))
        m.onObservation(Observation.unavailable(4_500))
        assertNull(m.onObservation(Observation.unavailable(6_000)).endedSession)
        assertNotNull(m.onObservation(Observation.unavailable(6_500)).endedSession)
    }

    @Test
    fun `switching directly to another target app's shortform ends the first session`() {
        val m = estimated()
        val s = m.onObservation(shorts(3_000, pkg = IG))
        assertEquals(EndReason.APP_SWITCHED, s.endedSession!!.endReason)
        assertEquals(YT, s.endedSession!!.packageName)
        assertEquals(DetectionState.CANDIDATE, s.current)
    }

    @Test
    fun `long observation gap ends the session as stale at the last observation time`() {
        val m = estimated()
        val s = m.onObservation(shorts(2_000 + 61_000))
        val end = s.endedSession!!
        assertEquals(EndReason.STALE, end.endReason)
        assertEquals(2_000, end.endedAtMs)
        // 공백 이후의 관찰은 새 확인부터 시작한다
        assertEquals(DetectionState.CANDIDATE, s.current)
    }

    @Test
    fun `duplicate and out-of-order observations are ignored`() {
        val m = ShortformStateMachine(tuning)
        m.onObservation(shorts(1_000))
        assertTrue(m.onObservation(shorts(1_000)).ignored)
        assertTrue(m.onObservation(shorts(500)).ignored)
        // 무시된 관찰은 확인 횟수를 늘리지 않는다
        assertNull(m.onObservation(shorts(1_250)).startedSession)
        assertEquals(1, m.onObservation(shorts(1_500)).startedSession)
    }

    @Test
    fun `rapid swipes produce one session`() {
        val m = ShortformStateMachine(tuning)
        var started = 0
        for (i in 0 until 200) {
            if (m.onObservation(shorts(i * 250L)).startedSession != null) started++
        }
        assertEquals(1, started)
    }

    @Test
    fun `watch time credit per step is capped`() {
        val m = estimated()
        m.onObservation(shorts(2_000 + 50_000)) // 50초 공백(stale 미만)
        val end = m.forceEnd(52_000)!!
        assertEquals(3_000, end.estimatedWatchMs)
    }

    @Test
    fun `force end closes the session and resets`() {
        val m = estimated()
        val end = m.forceEnd(2_500)
        assertEquals(EndReason.FORCED, end!!.endReason)
        assertEquals(DetectionState.OUTSIDE_TARGET, m.state)
        assertNull(m.forceEnd(3_000))
    }

    @Test
    fun `session numbers increase across sessions`() {
        val m = estimated()
        m.forceEnd(2_500)
        m.onObservation(shorts(3_000))
        m.onObservation(shorts(3_250))
        assertEquals(2, m.onObservation(shorts(3_500)).startedSession)
    }

    @Test
    fun `watchdog runs only while observing or in a session`() {
        assertFalse(DetectionState.OUTSIDE_TARGET.needsWatchdog)
        assertFalse(DetectionState.TARGET_FOREGROUND.needsWatchdog)
        assertFalse(DetectionState.ENDED.needsWatchdog)
        assertTrue(DetectionState.CANDIDATE.needsWatchdog)
        assertTrue(DetectionState.SHORTFORM_ESTIMATED.needsWatchdog)
        assertTrue(DetectionState.TEMPORARILY_OBSCURED.needsWatchdog)
        assertTrue(DetectionState.EXIT_PENDING.needsWatchdog)
    }

    private fun <T> assertNotNullAndGet(value: T?): T {
        assertNotNull(value)
        return value!!
    }
}
