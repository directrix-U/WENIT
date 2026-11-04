package com.example.shortsdetector.detection

import com.example.shortsdetector.DetectionConfig
import com.example.shortsdetector.detection.adapters.RecentActivity
import com.example.shortsdetector.detection.adapters.ShortformAdapter
import com.example.shortsdetector.detection.adapters.instagram.InstagramReelsAdapter
import com.example.shortsdetector.detection.adapters.youtube.YouTubeShortsAdapter
import com.example.shortsdetector.detection.model.AdapterVerdict
import com.example.shortsdetector.detection.model.DetectionState
import com.example.shortsdetector.detection.model.ReasonCode
import com.example.shortsdetector.detection.model.ScreenSnapshot
import com.example.shortsdetector.detection.model.VerdictKind
import com.example.shortsdetector.detection.model.WindowStatus
import com.example.shortsdetector.detection.state.DetectionTuning
import com.example.shortsdetector.testutil.Fixtures
import com.example.shortsdetector.testutil.Fixtures.IG
import com.example.shortsdetector.testutil.Fixtures.YT
import com.example.shortsdetector.testutil.Fixtures.scroll
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 서비스와 같은 순서(스냅샷 → 어댑터 → 상태 머신 → 알림 정책)로 시나리오 전체를 재현한다.
 * 시간은 서비스의 타이머 간격(1초)·스로틀(250ms)을 흉내 낸 가상 시각이다.
 */
class DetectionEngineScenarioTest {

    private val tuning = DetectionTuning()

    /** 기본 임계값 0 = 세션 시작 즉시 개입(기존 동작). 아래 기존 회귀 시나리오는 이 값으로 그대로 돈다. */
    private fun engine(packages: Set<String> = setOf(YT, IG), thresholdMs: Long = 0L) = DetectionEngine(
        tuning,
        listOf(YouTubeShortsAdapter(tuning), InstagramReelsAdapter(tuning)),
        DetectionConfig.MIN_RENOTIFY_INTERVAL_MS,
        thresholdMs
    ).also { it.setEnabledPackages(packages, 0) }

    /** 알림을 실제로 띄운 것처럼 처리하고 결과를 돌려준다(서비스의 handleOutput 과 같은 순서). */
    private fun DetectionEngine.frame(t: Long, s: ScreenSnapshot): EngineOutput =
        onSnapshot(t, s).also { if (it.notifySession != null) onNotificationPosted(t) }

    private fun DetectionEngine.enterShorts(from: Long): EngineOutput {
        frame(from, Fixtures.youtubeShorts())
        frame(from + 250, Fixtures.youtubeShorts())
        return frame(from + 500, Fixtures.youtubeShorts())
    }

    @Test
    fun `shorts detected from real dump - one notification`() {
        val e = engine()
        assertNull(e.frame(0, Fixtures.youtubeShorts()).notifySession)
        assertNull(e.frame(250, Fixtures.youtubeShorts()).notifySession)
        val out = e.frame(500, Fixtures.youtubeShorts())
        assertEquals(1, out.notifySession)
        assertEquals(DetectionState.SHORTFORM_ESTIMATED, e.state)
        // 계속 봐도 추가 알림 없음
        for (t in 1_500L..20_000L step 1_000L) assertNull(e.frame(t, Fixtures.youtubeShorts()).notifySession)
    }

    @Test
    fun `regular youtube watch page (real ad dump) and home never notify`() {
        val e = engine()
        for (i in 0 until 30) {
            val s = if (i % 2 == 0) Fixtures.youtubeWatchAd() else Fixtures.youtubeHome()
            assertNull(e.frame(i * 1_000L, s).notifySession)
        }
        assertEquals(DetectionState.TARGET_FOREGROUND, e.state)
    }

    @Test
    fun `comments open and close keep the session and notification`() {
        val e = engine()
        e.enterShorts(0)
        for (t in 1_500L..10_000L step 1_000L) {
            val out = e.frame(t, Fixtures.youtubeShortsCommentsOpen())
            assertFalse(out.cancelNotification)
            assertEquals(DetectionState.TEMPORARILY_OBSCURED, e.state)
        }
        val back = e.frame(11_000, Fixtures.youtubeShorts())
        assertNull(back.notifySession)
        assertEquals(DetectionState.SHORTFORM_ESTIMATED, e.state)
    }

    @Test
    fun `notification shade pulled down for a second keeps the session`() {
        val e = engine()
        e.enterShorts(0)
        assertFalse(e.onRootUnavailable(1_500).cancelNotification)
        assertFalse(e.onForeignWindow(2_000, "com.android.systemui").cancelNotification)
        val back = e.frame(2_400, Fixtures.youtubeShorts())
        assertFalse(back.cancelNotification)
        assertNull(back.notifySession)
    }

    @Test
    fun `dialog window over shorts (emulator-shaped) is obscured, not an exit`() {
        val e = engine()
        e.enterShorts(0)
        for (t in 1_500L..20_000L step 1_000L) {
            val out = e.frame(t, Fixtures.youtubeDialogWindow())
            assertEquals(VerdictKind.OVERLAY, out.observation.verdict!!.kind)
            assertFalse(out.cancelNotification)
            assertEquals(DetectionState.TEMPORARILY_OBSCURED, e.state)
        }
        val back = e.frame(21_000, Fixtures.youtubeShorts())
        assertEquals(DetectionState.SHORTFORM_ESTIMATED, back.step.current)
        assertNull(back.step.startedSession)
    }

    @Test
    fun `dialog window without a session never starts one`() {
        val e = engine()
        repeat(10) { assertNull(e.frame(it * 1_000L, Fixtures.youtubeDialogWindow()).notifySession) }
        assertEquals(DetectionState.TARGET_FOREGROUND, e.state)
    }

    @Test
    fun `miniature player after home (pip-like) ends the session instead of holding it`() {
        val e = engine()
        e.enterShorts(0)
        var ended = false
        for (t in 1_500L..6_000L step 1_000L) {
            if (e.frame(t, Fixtures.youtubeShortsMiniature()).cancelNotification) ended = true
        }
        assertTrue(ended)
    }

    @Test
    fun `going home ends the session within grace plus one timer tick`() {
        val e = engine()
        e.enterShorts(0)
        var endedAt: Long? = null
        for (t in 1_500L..10_000L step DetectionConfig.WATCHDOG_INTERVAL_MS) {
            if (e.onRootUnavailable(t).cancelNotification) { endedAt = t; break }
        }
        assertNotNull(endedAt)
        assertTrue(endedAt!! - 1_500 <= tuning.exitGraceMs + DetectionConfig.WATCHDOG_INTERVAL_MS)
        assertFalse(e.state.needsWatchdog)
    }

    @Test
    fun `re-entering within cooldown starts a new session but does not re-notify`() {
        val e = engine()
        e.enterShorts(0)
        e.onRootUnavailable(1_000); e.onRootUnavailable(2_000); e.onRootUnavailable(3_000)
        assertEquals(DetectionState.ENDED, e.state)
        // 세션 이어 가기 유예(10초)는 넘기고 쿨다운(30초) 안에 재진입
        val again = e.enterShorts(15_000)
        assertEquals(2, again.step.startedSession)
        assertTrue(again.suppressedByCooldown)
        assertNull(again.notifySession)
        // 쿨다운이 지난 뒤에는 다시 알린다
        e.onRootUnavailable(16_000); e.onRootUnavailable(17_000); e.onRootUnavailable(18_000)
        assertEquals(3, e.enterShorts(40_000).notifySession)
    }

    @Test
    fun `re-entering within the resume window continues the same session without re-notifying`() {
        val e = engine()
        assertEquals(1, e.enterShorts(0).notifySession)
        e.onRootUnavailable(1_000); e.onRootUnavailable(2_000)
        assertTrue(e.onRootUnavailable(3_000).cancelNotification)
        val again = e.enterShorts(5_000)
        assertNull(again.step.startedSession)
        assertEquals(1, again.step.resumedSession)
        assertNull(again.notifySession)
        assertFalse(again.suppressedByCooldown)   // 이미 판정한 세션이라 다시 판정하지 않는다
    }

    @Test
    fun `threshold - short blank screen between shorts does not reset watch time (emulator 2s gap)`() {
        val e = engine(thresholdMs = 30_000)
        e.enterShorts(0)
        assertNull(e.watchUntilNotified(1_500, 15_500))
        // 2초 넘게 숏폼 신호 없음 → 세션 종료, 곧바로 재진입
        var ended = false
        for (t in 16_000L..19_000L step 1_000L) {
            if (e.frame(t, Fixtures.youtubeHome()).endedSession != null) ended = true
        }
        assertTrue(ended)
        val back = e.enterShorts(19_500)
        assertEquals(1, back.step.resumedSession)
        // 끊기기 전 약 15.5초 + 이후 약 14.5초 → 세션 시작 기준 약 34초 근처에서 개입(끊긴 3.5초는 안 셈)
        val hit = e.watchUntilNotified(21_000, 60_000)
        assertNotNull(hit)
        assertEquals(1, hit!!.second.notifySession)
        assertTrue("notified at ${hit.first}", hit.first in 33_000L..36_000L)
    }

    @Test
    fun `comments sheet fully expanded keeps the session and counts toward the threshold`() {
        val e = engine(thresholdMs = 30_000)
        e.enterShorts(0)
        e.frame(1_500, Fixtures.youtubeShortsCommentsOpen())
        var hit: Long? = null
        for (t in 2_500L..60_000L step 1_000L) {
            val s = if (t % 2_000L == 500L) Fixtures.youtubeShortsCommentsExpanded(playerBottom = 145)
            else Fixtures.youtubeShortsCommentsExpanded()
            val out = e.frame(t, s)
            assertFalse("ended at $t", out.cancelNotification)
            assertEquals(DetectionState.TEMPORARILY_OBSCURED, e.state)
            if (out.notifySession != null && hit == null) hit = t
        }
        assertNotNull(hit)
        assertTrue("notified at $hit", hit!! in 30_500L..32_500L)
        assertEquals(DetectionState.SHORTFORM_ESTIMATED, e.frame(61_000, Fixtures.youtubeShorts()).step.current)
    }

    @Test
    fun `notification permission missing - cooldown is not armed`() {
        val e = engine()
        // 알림 게시 실패를 흉내: onNotificationPosted 를 부르지 않는다
        e.onSnapshot(0, Fixtures.youtubeShorts()); e.onSnapshot(250, Fixtures.youtubeShorts())
        assertEquals(1, e.onSnapshot(500, Fixtures.youtubeShorts()).notifySession)
        e.forceEnd(600)
        e.onSnapshot(700, Fixtures.youtubeShorts()); e.onSnapshot(950, Fixtures.youtubeShorts())
        assertEquals(2, e.onSnapshot(1_200, Fixtures.youtubeShorts()).notifySession)
    }

    @Test
    fun `rapid swiping with scroll events gives one notification`() {
        val e = engine()
        var notified = 0
        for (i in 0 until 60) {
            val t = i * 250L
            e.onScroll(scroll(t, YT, from = i))
            if (e.frame(t, Fixtures.youtubeShorts()).notifySession != null) notified++
        }
        assertEquals(1, notified)
    }

    @Test
    fun `player id renamed by update - no entry without paging, entry with paging`() {
        val quiet = engine()
        for (i in 0 until 10) assertNull(quiet.frame(i * 1_000L, Fixtures.youtubeShortsPlayerIdRenamed()).notifySession)
        assertEquals(DetectionState.CANDIDATE, quiet.state)

        val swiping = engine()
        var notified: Int? = null
        for (i in 0 until 10) {
            val t = i * 1_000L
            swiping.onScroll(scroll(t, YT, from = i))
            notified = notified ?: swiping.frame(t, Fixtures.youtubeShortsPlayerIdRenamed()).notifySession
        }
        assertEquals(1, notified)
    }

    @Test
    fun `player id renamed - emulator-shaped swipes (no indices) also enable the fallback`() {
        val e = engine()
        var notified: Int? = null
        for (i in 0 until 10) {
            val t = i * 1_000L
            e.onScroll(Fixtures.youtubeShortsSwipe(t))
            notified = notified ?: e.frame(t, Fixtures.youtubeShortsPlayerIdRenamed()).notifySession
        }
        assertEquals(1, notified)
    }

    @Test
    fun `instagram reels with page flips is detected, feed and DMs are not`() {
        val reels = engine()
        var notified: Int? = null
        for (i in 0 until 8) {
            val t = i * 1_000L
            reels.onScroll(scroll(t, IG, from = i))
            notified = notified ?: reels.frame(t, Fixtures.instagramReels()).notifySession
        }
        assertEquals(1, notified)

        val feed = engine()
        for (i in 0 until 30) {
            val t = i * 500L
            feed.onScroll(scroll(t, IG, from = i, to = i + 2))
            assertNull(feed.frame(t, Fixtures.instagramFeed()).notifySession)
            assertNull(feed.frame(t + 250, Fixtures.instagramDirect()).notifySession)
        }
    }

    @Test
    fun `instagram disabled - its screens are treated as foreign and never detected`() {
        val e = engine(packages = setOf(YT))
        for (i in 0 until 8) {
            val t = i * 1_000L
            e.onScroll(scroll(t, IG, from = i))
            val out = e.frame(t, Fixtures.instagramReels())
            assertEquals(WindowStatus.FOREIGN, out.observation.window)
            assertNull(out.notifySession)
        }
    }

    @Test
    fun `disabling the app of an open session ends it`() {
        val e = engine()
        e.enterShorts(0)
        val out = e.setEnabledPackages(setOf(IG), 1_000)
        assertNotNull(out)
        assertTrue(out!!.cancelNotification)
        assertEquals(DetectionState.OUTSIDE_TARGET, e.state)
    }

    @Test
    fun `adapter exception is isolated as unknown`() {
        val throwing = object : ShortformAdapter {
            override val packageName = YT
            override val ruleVersion = "boom"
            override fun evaluate(snapshot: ScreenSnapshot, recent: RecentActivity): AdapterVerdict =
                throw IllegalStateException("stale node")
        }
        val e = DetectionEngine(tuning, listOf(throwing), 30_000)
        val out = e.onSnapshot(0, Fixtures.youtubeShorts())
        assertEquals(VerdictKind.UNKNOWN, out.observation.verdict!!.kind)
        assertEquals(ReasonCode.ADAPTER_ERROR, out.observation.verdict!!.reason)
    }

    @Test
    fun `observation error during a session waits instead of ending or switching apps`() {
        val e = engine()
        e.enterShorts(0)
        val out = e.onObservationError(1_500, e.sessionPackage)
        assertNull(out.endedSession)
        assertEquals(DetectionState.EXIT_PENDING, e.state)
        // 세션이 없을 때의 오류는 관찰 불가로 처리
        val idle = engine()
        assertEquals(WindowStatus.UNAVAILABLE, idle.onObservationError(0, null).observation.window)
    }

    @Test
    fun `empty tree in youtube is unknown and does not start or end anything`() {
        val e = engine()
        val empty = ScreenSnapshot(YT, Fixtures.W, Fixtures.H, emptyList())
        repeat(5) { assertNull(e.frame(it * 1_000L, empty).notifySession) }
        assertEquals(DetectionState.TARGET_FOREGROUND, e.state)
    }

    @Test
    fun `service interrupted mid-session cancels the notification`() {
        val e = engine()
        e.enterShorts(0)
        val out = e.forceEnd(1_000)
        assertTrue(out.cancelNotification)
        assertFalse(e.forceEnd(1_100).cancelNotification)
    }

    // --- 개입 임계값: "어느 정도 본 뒤에" 알린다 ---

    /** 세션 진입 후 1초 간격(타이머)으로 [frame] 을 돌리고, 개입이 나온 시각과 결과를 돌려준다. */
    private fun DetectionEngine.watchUntilNotified(
        from: Long, until: Long, s: () -> ScreenSnapshot = { Fixtures.youtubeShorts() }
    ): Pair<Long, EngineOutput>? {
        for (t in from..until step 1_000L) {
            val out = frame(t, s())
            if (out.notifySession != null) return t to out
        }
        return null
    }

    @Test
    fun `threshold - no popup at session start, one popup after watching long enough`() {
        val e = engine(thresholdMs = 60_000)
        val start = e.enterShorts(0)
        assertEquals(1, start.step.startedSession)
        assertNull(start.notifySession)
        assertFalse(start.suppressedByCooldown)

        val hit = e.watchUntilNotified(1_500, 120_000)
        assertNotNull(hit)
        val (t, out) = hit!!
        assertEquals(1, out.notifySession)
        assertTrue(out.notifyWatchMs!! >= 60_000)
        // 세션 시작(500ms) 기준 60초 근처, 타이머 1회 오차 이내
        assertTrue("notified at $t", t in 60_500L..62_500L)
        // 계속 봐도 같은 세션에서 다시 뜨지 않는다
        assertNull(e.watchUntilNotified(t + 1_000, t + 300_000))
    }

    @Test
    fun `threshold - leaving before the threshold never notifies`() {
        val e = engine(thresholdMs = 60_000)
        e.enterShorts(0)
        assertNull(e.watchUntilNotified(1_500, 30_500))
        e.onRootUnavailable(31_000); e.onRootUnavailable(32_000)
        val end = e.onRootUnavailable(33_000)
        assertNotNull(end.endedSession)
        assertNull(end.notifySession)
    }

    @Test
    fun `threshold - time with comments open is counted`() {
        val e = engine(thresholdMs = 30_000)
        e.enterShorts(0)
        // 20초 시청 → 댓글창을 연 채로 계속 → 댓글창 안에서 임계값 도달
        assertNull(e.watchUntilNotified(1_500, 20_500))
        val hit = e.watchUntilNotified(21_500, 81_500) { Fixtures.youtubeShortsCommentsOpen() }
        assertNotNull(hit)
        assertTrue("notified at ${hit!!.first}", hit.first in 30_500L..32_500L)
        assertEquals(DetectionState.TEMPORARILY_OBSCURED, e.state)
    }

    @Test
    fun `threshold - time with a dialog window over shorts is not counted`() {
        val e = engine(thresholdMs = 30_000)
        e.enterShorts(0)
        // 20초 시청 → 대화상자 60초 → 다시 시청
        assertNull(e.watchUntilNotified(1_500, 20_500))
        assertNull(e.watchUntilNotified(21_500, 81_500) { Fixtures.youtubeDialogWindow() })
        val hit = e.watchUntilNotified(82_500, 120_000)
        assertNotNull(hit)
        assertTrue("notified at ${hit!!.first}", hit.first in 90_000L..95_000L)
    }

    @Test
    fun `threshold - each new session needs its own watch time`() {
        val e = engine(thresholdMs = 30_000)
        e.enterShorts(0)
        assertNotNull(e.watchUntilNotified(1_500, 40_000))
        e.onRootUnavailable(41_000); e.onRootUnavailable(42_000); e.onRootUnavailable(43_000)
        assertEquals(DetectionState.ENDED, e.state)

        // 이어 가기 유예(10초)를 넘긴 재진입은 새 세션
        val again = e.enterShorts(60_000)
        assertEquals(2, again.step.startedSession)
        assertNull(again.notifySession)
        val hit = e.watchUntilNotified(61_500, 110_000)
        assertEquals(2, hit!!.second.notifySession)
    }

    @Test
    fun `threshold - exit grace blip keeps the session and its watch time`() {
        val e = engine(thresholdMs = 30_000)
        e.enterShorts(0)
        assertNull(e.watchUntilNotified(1_500, 20_500))
        // 알림창을 1초 내렸다 올림 → 같은 세션 유지
        e.onRootUnavailable(21_500)
        val hit = e.watchUntilNotified(22_500, 60_000)
        assertEquals(1, hit!!.second.notifySession)
    }

    @Test
    fun `threshold - popup and notification both failed does not arm cooldown`() {
        val e = engine(thresholdMs = 10_000)
        e.onSnapshot(0, Fixtures.youtubeShorts()); e.onSnapshot(250, Fixtures.youtubeShorts())
        e.onSnapshot(500, Fixtures.youtubeShorts())
        var first: Int? = null
        for (t in 1_500L..20_000L step 1_000L) first = first ?: e.onSnapshot(t, Fixtures.youtubeShorts()).notifySession
        assertEquals(1, first)
        e.forceEnd(21_000)
        e.onSnapshot(21_100, Fixtures.youtubeShorts()); e.onSnapshot(21_350, Fixtures.youtubeShorts())
        e.onSnapshot(21_600, Fixtures.youtubeShorts())
        var second: Int? = null
        for (t in 22_600L..40_000L step 1_000L) second = second ?: e.onSnapshot(t, Fixtures.youtubeShorts()).notifySession
        // 쿨다운(30초) 안이지만 이전 개입이 실제로 표시되지 않았으므로 다시 시도한다
        assertEquals(2, second)
    }
}
