package com.example.shortsdetector.intervention

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackPauserTest {

    private class FakeControls(
        var playing: Boolean = true,
        val keyWorks: Boolean = false,
        val focusGranted: Boolean = true,
        val focusStopsPlayback: Boolean = true
    ) : PlaybackControls {
        var keys = 0
        var focusRequests = 0
        var focusHeld = false

        override fun sendPauseKey() {
            keys++
            if (keyWorks) playing = false
        }

        override fun isPlaying() = playing

        override fun requestFocus(): Boolean {
            focusRequests++
            if (!focusGranted) return false
            focusHeld = true
            if (focusStopsPlayback) playing = false
            return true
        }

        override fun releaseFocus() {
            focusHeld = false
        }
    }

    /** 예약된 동작을 시각 순서로 실행한다. */
    private class Clock {
        private val queue = ArrayList<Pair<Long, () -> Unit>>()
        private var now = 0L

        fun post(delayMs: Long, action: () -> Unit) {
            queue += (now + delayMs) to action
        }

        fun runAll() {
            while (queue.isNotEmpty()) {
                val next = queue.minBy { it.first }
                queue.remove(next)
                now = next.first
                next.second()
            }
        }
    }

    private val clock = Clock()
    private fun pauser(controls: PlaybackControls) = PlaybackPauser(controls, clock::post)

    @Test
    fun `nothing playing - no key, no focus, done at once`() {
        val c = FakeControls(playing = false)
        var result: PauseResult? = null
        pauser(c).pause { result = it }

        assertEquals(PauseResult.NOT_PLAYING, result)
        assertEquals(0, c.keys)
        assertEquals(0, c.focusRequests)
    }

    @Test
    fun `media key works - focus is not requested`() {
        val c = FakeControls(keyWorks = true)
        var result: PauseResult? = null
        pauser(c).pause { result = it }
        clock.runAll()

        assertEquals(PauseResult.KEY, result)
        assertEquals(1, c.keys)
        assertEquals(0, c.focusRequests)
    }

    @Test
    fun `media key ignored (real device shorts) - falls back to audio focus`() {
        val c = FakeControls(keyWorks = false)
        var result: PauseResult? = null
        pauser(c).pause { result = it }
        clock.runAll()

        assertEquals(PauseResult.FOCUS, result)
        assertEquals(1, c.keys)
        assertEquals(1, c.focusRequests)
    }

    @Test
    fun `focus is handed back after pausing`() {
        val c = FakeControls()
        pauser(c).pause()
        clock.runAll()
        assertFalse(c.focusHeld)
    }

    @Test
    fun `focus denied reports failure and still finishes`() {
        val c = FakeControls(focusGranted = false)
        var result: PauseResult? = null
        pauser(c).pause { result = it }
        clock.runAll()
        assertEquals(PauseResult.FAILED, result)
    }

    @Test
    fun `player that ignores focus reports failure and gives focus back`() {
        val c = FakeControls(focusStopsPlayback = false)
        var result: PauseResult? = null
        pauser(c).pause { result = it }
        clock.runAll()

        assertEquals(PauseResult.FAILED, result)
        assertFalse(c.focusHeld)
    }

    @Test
    fun `a second request while pausing shares the same attempt`() {
        val c = FakeControls()
        val results = ArrayList<PauseResult>()
        val p = pauser(c)
        p.pause { results += it }
        p.pause { results += it }
        clock.runAll()

        assertEquals(listOf(PauseResult.FOCUS, PauseResult.FOCUS), results)
        assertEquals(1, c.keys)
    }

    @Test
    fun `leaving for the app always proceeds even when pausing fails`() {
        var proceeded = false
        pauser(FakeControls(focusGranted = false)).pause { proceeded = true }
        clock.runAll()
        assertTrue(proceeded)
    }

    @Test
    fun `paused state survives until sound comes back and is reported once`() {
        val c = FakeControls()
        val p = pauser(c)
        p.pause()
        clock.runAll()
        assertTrue(p.pausedByApp)
        assertFalse(p.checkResumed())

        c.playing = true

        assertTrue(p.checkResumed())
        assertFalse(p.pausedByApp)
        assertFalse(p.checkResumed())
    }

    @Test
    fun `a video that was already stopped is not marked as paused by the app`() {
        val p = pauser(FakeControls(playing = false))
        p.pause()
        assertFalse(p.pausedByApp)
    }

    @Test
    fun `failed pause is not marked as paused by the app`() {
        val p = pauser(FakeControls(focusGranted = false))
        p.pause()
        clock.runAll()
        assertFalse(p.pausedByApp)
    }

    @Test
    fun `asking again while already paused keeps the mark`() {
        val c = FakeControls()
        val p = pauser(c)
        p.pause()
        clock.runAll()
        p.pause()
        assertTrue(p.pausedByApp)
    }

    @Test
    fun `release is safe when focus was never held`() {
        val c = FakeControls(playing = false)
        pauser(c).release()
        assertFalse(c.focusHeld)
    }
}
