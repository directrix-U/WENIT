package com.example.shortsdetector.intervention

/**
 * Lv.3 숨 고르기의 시간 흐름. Android 의존성 없음. 예약은 [post] 로 넘긴다.
 * 카운트다운 → 버튼 표시 → 답이 없으면 자동 닫힘. 사용자가 답하거나 창이 닫히면 [cancel] 한다.
 */
class BreathingTimeline(
    private val post: (delayMs: Long, action: () -> Unit) -> Unit,
    private val onCount: (Int) -> Unit,
    private val onButtons: () -> Unit,
    private val onTimedOut: () -> Unit
) {
    private var left = COUNTDOWN_SECONDS
    private var cancelled = false

    fun start() {
        onCount(left)
        post(SECOND_MS, ::tick)
    }

    fun cancel() {
        cancelled = true
    }

    private fun tick() {
        if (cancelled) return
        left--
        if (left > 0) {
            onCount(left)
            post(SECOND_MS, ::tick)
        } else {
            onButtons()
            post(AUTO_CLOSE_MS) { if (!cancelled) onTimedOut() }
        }
    }

    companion object {
        const val COUNTDOWN_SECONDS = 10
        const val AUTO_CLOSE_MS = 30_000L
        private const val SECOND_MS = 1_000L
    }
}
