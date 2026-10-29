package com.example.shortsdetector.detection.adapters.instagram

import com.example.shortsdetector.detection.adapters.RecentActivity
import com.example.shortsdetector.detection.model.Confidence
import com.example.shortsdetector.detection.model.ReasonCode
import com.example.shortsdetector.detection.model.ScreenSnapshot
import com.example.shortsdetector.detection.model.VerdictKind
import com.example.shortsdetector.testutil.Fixtures
import org.junit.Assert.assertEquals
import org.junit.Test

/** 모두 synthetic fixture. Instagram 실측 덤프는 아직 없다(TODO: VERIFY ON DEVICE). */
class InstagramReelsAdapterTest {

    private val adapter = InstagramReelsAdapter()

    @Test
    fun `reels pager with repeated vertical page flips enters with medium confidence`() {
        val v = adapter.evaluate(Fixtures.instagramReels(), RecentActivity(pageFlips = 2))
        assertEquals(VerdictKind.SHORTFORM, v.kind)
        assertEquals(Confidence.MEDIUM, v.confidence)
        assertEquals(ReasonCode.PAGER_WITH_PAGING, v.reason)
    }

    @Test
    fun `pager with a single flip or none is low confidence only`() {
        for (flips in 0..1) {
            val v = adapter.evaluate(Fixtures.instagramReels(), RecentActivity(pageFlips = flips))
            assertEquals(VerdictKind.SHORTFORM, v.kind)
            assertEquals(Confidence.LOW, v.confidence)
        }
    }

    @Test
    fun `home feed without page flips never reaches medium`() {
        val v = adapter.evaluate(Fixtures.instagramFeed(), RecentActivity(pageFlips = 0))
        assertEquals(Confidence.LOW, v.confidence)
    }

    @Test
    fun `direct messages screen is not shortform even with flips`() {
        val v = adapter.evaluate(Fixtures.instagramDirect(), RecentActivity(pageFlips = 5))
        assertEquals(VerdictKind.NOT_SHORTFORM, v.kind)
    }

    @Test
    fun `empty tree is unknown`() {
        val v = adapter.evaluate(ScreenSnapshot(Fixtures.IG, Fixtures.W, Fixtures.H, emptyList()), RecentActivity())
        assertEquals(VerdictKind.UNKNOWN, v.kind)
    }

    @Test
    fun `truncated tree without pager is unknown`() {
        val v = adapter.evaluate(Fixtures.instagramDirect().copy(truncated = true), RecentActivity())
        assertEquals(VerdictKind.UNKNOWN, v.kind)
    }
}
