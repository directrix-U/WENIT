package com.example.shortsdetector.detection.adapters.youtube

import com.example.shortsdetector.DetectionConfig
import com.example.shortsdetector.testutil.Fixtures
import com.example.shortsdetector.testutil.Fixtures.node
import com.example.shortsdetector.testutil.plus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 기존 프로토타입 규칙이 옮긴 뒤에도 같은 결과를 내는지 고정하는 회귀 테스트. */
class LegacyShortsPlayerRuleTest {

    private val threshold = DetectionConfig.MIN_PLAYER_AREA_RATIO

    @Test
    fun `real shorts dump - player found fullscreen with measured ratio 0_863`() {
        val r = LegacyShortsPlayerRule.evaluate(Fixtures.youtubeShorts(), threshold)
        assertEquals(1, r.matchedCount)
        assertTrue(r.playerVisible)
        // [0,142][1080,2235] / 1080x2424 = 0.8635
        assertEquals(0.863f, r.maxAreaRatio, 0.001f)
        assertTrue(r.isShortsPlaying)
    }

    @Test
    fun `real watch-page ad dump - no shorts player even though reel_time_bar exists`() {
        val snapshot = Fixtures.youtubeWatchAd()
        assertTrue(snapshot.nodes.any { it.viewId == Fixtures.id("reel_time_bar") })
        val r = LegacyShortsPlayerRule.evaluate(snapshot, threshold)
        assertFalse(r.found)
        assertFalse(r.playerVisible)
        assertFalse(r.isShortsPlaying)
    }

    @Test
    fun `partial id match is never accepted`() {
        val s = Fixtures.snapshot(
            Fixtures.YT,
            node(Fixtures.id("reel_watch_player_container")),
            node("reel_watch_player"),
            node(Fixtures.id("reel_watch_player", pkg = "com.example.other"))
        )
        assertFalse(LegacyShortsPlayerRule.evaluate(s, threshold).found)
    }

    @Test
    fun `invisible or zero-area player is found but not visible`() {
        val invisible = Fixtures.snapshot(Fixtures.YT, node(YouTubeViewIds.SHORTS_PLAYER, visible = false))
        val empty = Fixtures.snapshot(Fixtures.YT, node(YouTubeViewIds.SHORTS_PLAYER, top = 500, bottom = 500))
        for (s in listOf(invisible, empty)) {
            val r = LegacyShortsPlayerRule.evaluate(s, threshold)
            assertTrue(r.found)
            assertFalse(r.playerVisible)
            assertFalse(r.isShortsPlaying)
        }
    }

    @Test
    fun `comment sheet shrinks player - visible but below entry threshold`() {
        val r = LegacyShortsPlayerRule.evaluate(Fixtures.youtubeShortsCommentsOpen(), threshold)
        assertTrue(r.playerVisible)
        assertEquals(0.292f, r.maxAreaRatio, 0.005f)
        assertFalse(r.isShortsPlaying)
    }

    @Test
    fun `unknown screen size never divides by zero`() {
        val s = Fixtures.youtubeShorts().copy(screenWidth = 0, screenHeight = 0)
        val r = LegacyShortsPlayerRule.evaluate(s, threshold)
        assertTrue(r.found)
        assertFalse(r.playerVisible)
        assertEquals(0f, r.maxAreaRatio, 0f)
    }

    @Test
    fun `largest visible player wins when several match`() {
        val s = Fixtures.snapshot(
            Fixtures.YT,
            node(YouTubeViewIds.SHORTS_PLAYER, top = 0, bottom = 500),
            node(YouTubeViewIds.SHORTS_PLAYER, top = 0, bottom = 2424)
        ) + node(null)
        val r = LegacyShortsPlayerRule.evaluate(s, threshold)
        assertEquals(2, r.matchedCount)
        assertEquals(1f, r.maxAreaRatio, 0.0001f)
    }
}
