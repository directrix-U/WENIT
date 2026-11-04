package com.example.shortsdetector.detection.adapters.youtube

import com.example.shortsdetector.DetectionConfig
import com.example.shortsdetector.detection.adapters.RecentActivity
import com.example.shortsdetector.detection.model.Confidence
import com.example.shortsdetector.detection.model.ReasonCode
import com.example.shortsdetector.detection.model.ScreenSnapshot
import com.example.shortsdetector.detection.model.Signal
import com.example.shortsdetector.detection.model.VerdictKind
import com.example.shortsdetector.testutil.Fixtures
import com.example.shortsdetector.testutil.plus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class YouTubeShortsAdapterTest {

    private val adapter = YouTubeShortsAdapter()
    private val idle = RecentActivity(pageFlips = 0)
    private val paging = RecentActivity(pageFlips = 2)

    @Test
    fun `real shorts dump - shortform with high confidence (player plus reel containers)`() {
        val v = adapter.evaluate(Fixtures.youtubeShorts(), idle)
        assertEquals(VerdictKind.SHORTFORM, v.kind)
        assertEquals(Confidence.HIGH, v.confidence)
        assertEquals(ReasonCode.PLAYER_FULLSCREEN, v.reason)
        assertTrue(Signal.REEL_CONTAINER_VISIBLE in v.signals)
    }

    @Test
    fun `real watch-page ad dump - not shortform, regular player`() {
        val v = adapter.evaluate(Fixtures.youtubeWatchAd(), idle)
        assertEquals(VerdictKind.NOT_SHORTFORM, v.kind)
        assertEquals(ReasonCode.REGULAR_PLAYER, v.reason)
    }

    @Test
    fun `synthetic comment sheet - partial, not a new entry`() {
        val v = adapter.evaluate(Fixtures.youtubeShortsCommentsOpen(), idle)
        assertEquals(VerdictKind.SHORTFORM_PARTIAL, v.kind)
        assertEquals(ReasonCode.PLAYER_PARTIAL, v.reason)
    }

    @Test
    fun `synthetic clear screen - overlay gone, still shortform`() {
        val v = adapter.evaluate(Fixtures.youtubeShortsClearScreen(), idle)
        assertEquals(VerdictKind.SHORTFORM, v.kind)
        assertEquals(ReasonCode.PLAYER_FULLSCREEN, v.reason)
    }

    @Test
    fun `synthetic player id renamed - containers alone are low confidence`() {
        val v = adapter.evaluate(Fixtures.youtubeShortsPlayerIdRenamed(), idle)
        assertEquals(VerdictKind.SHORTFORM, v.kind)
        assertEquals(Confidence.LOW, v.confidence)
        assertEquals(ReasonCode.REEL_CONTAINERS_ONLY, v.reason)
    }

    @Test
    fun `synthetic player id renamed - containers plus vertical paging reach medium`() {
        val v = adapter.evaluate(Fixtures.youtubeShortsPlayerIdRenamed(), paging)
        assertEquals(VerdictKind.SHORTFORM, v.kind)
        assertEquals(Confidence.MEDIUM, v.confidence)
        assertEquals(ReasonCode.REEL_CONTAINERS_WITH_PAGING, v.reason)
    }

    @Test
    fun `player only without containers or paging is medium (legacy path kept)`() {
        val s = Fixtures.snapshot(Fixtures.YT, Fixtures.node(YouTubeViewIds.SHORTS_PLAYER, top = 142, bottom = 2235))
        val v = adapter.evaluate(s, idle)
        assertEquals(VerdictKind.SHORTFORM, v.kind)
        assertEquals(Confidence.MEDIUM, v.confidence)
        assertEquals(Confidence.HIGH, adapter.evaluate(s, paging).confidence)
    }

    @Test
    fun `synthetic home feed - not shortform, reel_time_bar ignored`() {
        val v = adapter.evaluate(Fixtures.youtubeHome(), paging)
        assertEquals(VerdictKind.NOT_SHORTFORM, v.kind)
        assertEquals(ReasonCode.NO_SHORTFORM_SIGNAL, v.reason)
    }

    @Test
    fun `synthetic small shorts preview on home is partial at most`() {
        val v = adapter.evaluate(Fixtures.youtubeHomeWithSmallShortsPreview(), idle)
        assertEquals(VerdictKind.SHORTFORM_PARTIAL, v.kind)
    }

    @Test
    fun `miniature player (pip-like 0_077) does not hold a session`() {
        val v = adapter.evaluate(Fixtures.youtubeShortsMiniature(), idle)
        assertEquals(VerdictKind.NOT_SHORTFORM, v.kind)
        assertEquals(ReasonCode.PLAYER_MINIATURE, v.reason)
        assertEquals(0.077f, v.playerAreaRatio, 0.002f)
    }

    @Test
    fun `comments sheet fully expanded (emulator 0_014, user log 0_001) still holds as partial`() {
        for (bottom in listOf(175, 145)) {
            val v = adapter.evaluate(Fixtures.youtubeShortsCommentsExpanded(playerBottom = bottom), idle)
            assertEquals(VerdictKind.SHORTFORM_PARTIAL, v.kind)
            assertEquals(ReasonCode.PLAYER_COLLAPSED_UNDER_PANEL, v.reason)
            assertTrue(v.playerAreaRatio < 0.02f)
        }
    }

    @Test
    fun `tiny player with only one large container is still a miniature`() {
        val one = Fixtures.youtubeShortsMiniature() +
            Fixtures.node(Fixtures.id("reel_recycler"), "androidx.recyclerview.widget.RecyclerView", top = 142, bottom = 2235)
        val v = adapter.evaluate(one, idle)
        assertEquals(VerdictKind.NOT_SHORTFORM, v.kind)
        assertEquals(ReasonCode.PLAYER_MINIATURE, v.reason)
    }

    @Test
    fun `empty tree is unknown, not 'not shortform'`() {
        val v = adapter.evaluate(ScreenSnapshot(Fixtures.YT, Fixtures.W, Fixtures.H, emptyList()), idle)
        assertEquals(VerdictKind.UNKNOWN, v.kind)
        assertEquals(ReasonCode.TREE_EMPTY, v.reason)
    }

    @Test
    fun `truncated tree without shorts signal is unknown`() {
        val v = adapter.evaluate(Fixtures.youtubeHome().copy(truncated = true), idle)
        assertEquals(VerdictKind.UNKNOWN, v.kind)
        assertEquals(ReasonCode.TREE_TRUNCATED, v.reason)
    }

    @Test
    fun `truncated tree that already contains the fullscreen player is still shortform`() {
        val v = adapter.evaluate(Fixtures.youtubeShorts().copy(truncated = true), idle)
        assertEquals(VerdictKind.SHORTFORM, v.kind)
    }

    @Test
    fun `nodes with null ids and classes do not crash`() {
        val s = Fixtures.snapshot(Fixtures.YT, Fixtures.node(null, className = "x").copy(className = null))
        assertEquals(VerdictKind.NOT_SHORTFORM, adapter.evaluate(s, idle).kind)
    }

    /**
     * 서비스는 YouTube 에 대해 전체 순회 대신 root + [YouTubeShortsAdapter.lookupViewIds] ID 검색 결과만 담는다.
     * 그렇게 줄인 스냅샷에서도 판정이 전체 트리와 같아야 한다.
     */
    @Test
    fun `lookup-only snapshot gives the same verdict as the full tree`() {
        assertTrue(!adapter.needsFullTree)
        val screens = listOf(
            Fixtures.youtubeShorts(), Fixtures.youtubeWatchAd(), Fixtures.youtubeShortsCommentsOpen(),
            Fixtures.youtubeShortsClearScreen(), Fixtures.youtubeShortsPlayerIdRenamed(),
            Fixtures.youtubeHome(), Fixtures.youtubeHomeWithSmallShortsPreview()
        )
        for (full in screens) {
            val reduced = full.copy(
                nodes = listOf(full.nodes.first()) + full.nodes.drop(1).filter { it.viewId in adapter.lookupViewIds }
            )
            for (recent in listOf(idle, paging)) {
                val a = adapter.evaluate(full, recent)
                val b = adapter.evaluate(reduced, recent)
                assertEquals(Triple(a.kind, a.confidence, a.reason), Triple(b.kind, b.confidence, b.reason))
            }
        }
    }

    /**
     * 새 어댑터가 기존 규칙의 진입 판정을 그대로 따르는지 비교한다(명세 Phase 1: 기존/새 감지 비교).
     * 기존 규칙이 "쇼츠"라고 한 화면은 새 어댑터도 진입 가능한 SHORTFORM 이어야 하고,
     * 기존 규칙이 플레이어를 보지 못한 화면에서 새 어댑터가 MEDIUM 이상을 내려면 페이징 신호가 필요하다.
     */
    @Test
    fun `parity with legacy rule on all fixtures`() {
        val screens = listOf(
            Fixtures.youtubeShorts(), Fixtures.youtubeWatchAd(), Fixtures.youtubeShortsCommentsOpen(),
            Fixtures.youtubeShortsClearScreen(), Fixtures.youtubeShortsPlayerIdRenamed(),
            Fixtures.youtubeHome(), Fixtures.youtubeHomeWithSmallShortsPreview()
        )
        for (s in screens) {
            val legacy = LegacyShortsPlayerRule.evaluate(s, DetectionConfig.MIN_PLAYER_AREA_RATIO)
            val v = adapter.evaluate(s, idle)
            val enters = v.kind == VerdictKind.SHORTFORM && v.confidence >= Confidence.MEDIUM
            assertEquals("entry parity", legacy.isShortsPlaying, enters)
            val holds = v.kind == VerdictKind.SHORTFORM || v.kind == VerdictKind.SHORTFORM_PARTIAL
            if (legacy.playerVisible) assertTrue("hold parity", holds)
        }
    }
}
