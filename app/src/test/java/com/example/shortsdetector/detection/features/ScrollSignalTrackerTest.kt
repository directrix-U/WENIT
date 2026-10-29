package com.example.shortsdetector.detection.features

import com.example.shortsdetector.testutil.Fixtures
import com.example.shortsdetector.testutil.Fixtures.IG
import com.example.shortsdetector.testutil.Fixtures.YT
import com.example.shortsdetector.testutil.Fixtures.scroll
import org.junit.Assert.assertEquals
import org.junit.Test

class ScrollSignalTrackerTest {

    @Test
    fun `single-item page changes count as flips`() {
        val t = ScrollSignalTracker()
        t.record(scroll(1_000, IG, from = 3))
        t.record(scroll(2_000, IG, from = 4))
        t.record(scroll(3_000, IG, from = 5))
        // 첫 이벤트는 비교 대상이 없어 세지 않는다.
        assertEquals(2, t.pageFlipsWithin(IG, 3_000, 10_000))
    }

    @Test
    fun `duplicate event with same index is not a flip`() {
        val t = ScrollSignalTracker()
        t.record(scroll(1_000, IG, from = 3))
        t.record(scroll(1_100, IG, from = 3))
        t.record(scroll(1_200, IG, from = 3))
        assertEquals(0, t.pageFlipsWithin(IG, 1_200, 10_000))
    }

    @Test
    fun `feed scroll with several visible items is not a flip`() {
        val t = ScrollSignalTracker()
        t.record(scroll(1_000, IG, from = 0, to = 2))
        t.record(scroll(1_500, IG, from = 1, to = 3))
        t.record(scroll(2_000, IG, from = 4, to = 6))
        assertEquals(0, t.pageFlipsWithin(IG, 2_000, 10_000))
    }

    @Test
    fun `horizontal page changes (stories) are excluded`() {
        val t = ScrollSignalTracker()
        t.record(scroll(1_000, IG, from = 0, dx = Fixtures.W, dy = 0))
        t.record(scroll(2_000, IG, from = 1, dx = Fixtures.W, dy = 0))
        assertEquals(0, t.pageFlipsWithin(IG, 2_000, 10_000))
    }

    @Test
    fun `missing indices or deltas are handled - indices required, deltas optional`() {
        val t = ScrollSignalTracker()
        t.record(scroll(1_000, IG, from = -1, to = -1))
        t.record(scroll(2_000, IG, from = -1, to = -1))
        assertEquals(0, t.pageFlipsWithin(IG, 2_000, 10_000))

        t.record(scroll(3_000, IG, from = 7, dx = null, dy = null))
        t.record(scroll(4_000, IG, from = 8, dx = null, dy = null))
        assertEquals(1, t.pageFlipsWithin(IG, 4_000, 10_000))
    }

    @Test
    fun `emulator-observed shorts swipe without indices counts by vertical delta`() {
        val t = ScrollSignalTracker()
        // 관찰값: 스와이프 dy=1162, 정착 직후 dy=0/2/-6/108 (화면 높이 2424)
        assertEquals(true, t.record(Fixtures.youtubeShortsSwipe(1_000, dy = 1162)))
        for ((i, dy) in listOf(0, 2, -6, 108).withIndex()) {
            assertEquals(false, t.record(Fixtures.youtubeShortsSwipe(1_100L + i * 100, dy = dy)))
        }
        // 위로 되돌리기(음수)도 넘김으로 본다
        assertEquals(true, t.record(Fixtures.youtubeShortsSwipe(2_000, dy = -1200)))
        assertEquals(2, t.pageFlipsWithin(YT, 2_000, 10_000))
    }

    @Test
    fun `emulator-observed youtube home feed scroll is not a flip`() {
        val t = ScrollSignalTracker()
        // 관찰값: from=0 to=4 n=12 dy=658 → from=1 to=6 dy=530 → from=5 to=9 dy=406
        t.record(scroll(1_000, YT, from = 0, to = 4, dy = 658, screenHeight = Fixtures.H))
        t.record(scroll(2_000, YT, from = 1, to = 6, dy = 530, screenHeight = Fixtures.H))
        t.record(scroll(3_000, YT, from = 5, to = 9, dy = 1500, screenHeight = Fixtures.H))
        assertEquals(0, t.pageFlipsWithin(YT, 3_000, 10_000))
    }

    @Test
    fun `window and package are respected`() {
        val t = ScrollSignalTracker()
        t.record(scroll(1_000, IG, from = 1))
        t.record(scroll(2_000, IG, from = 2))
        t.record(scroll(2_500, YT, from = 1))
        t.record(scroll(3_000, YT, from = 2))
        assertEquals(1, t.pageFlipsWithin(IG, 5_000, 10_000))
        assertEquals(1, t.pageFlipsWithin(YT, 5_000, 10_000))
        assertEquals(0, t.pageFlipsWithin(IG, 20_000, 10_000))
    }

    @Test
    fun `rapid scroll burst is bounded by capacity`() {
        val t = ScrollSignalTracker(capacity = 8)
        for (i in 0 until 100) t.record(scroll(1_000L + i * 10, YT, from = i))
        assertEquals(8, t.pageFlipsWithin(YT, 3_000, 10_000))
    }
}
