package com.example.shortsdetector.detection.features

import com.example.shortsdetector.detection.model.ScrollSignal
import kotlin.math.abs

/**
 * 최근 스크롤 이벤트에서 "세로 한 페이지 넘김" 패턴을 센다.
 *
 * 두 가지 경로:
 *  1) 인덱스가 있을 때 — 가설(TODO: VERIFY ON DEVICE): 숏폼 pager 는 정착 후 한 화면에 아이템이 하나만 보여
 *     fromIndex == toIndex 이고, 다음 영상으로 넘기면 그 인덱스가 바뀐다. 일반 피드는 보통 여러 아이템이
 *     동시에 보여 fromIndex < toIndex 다. (에뮬레이터 YouTube 홈 피드: from=0 to=4 처럼 나와 세지 않음을 확인)
 *  2) 인덱스가 없을 때(-1) — 에뮬레이터의 YouTube 21.18.164 쇼츠 reel_recycler 는 인덱스 없이 dy 만 줬다.
 *     세로 이동량이 화면 높이의 [minPageDeltaFraction] 이상이면 한 번 넘김으로 센다.
 *     OS 디바운스로 이벤트가 합쳐지거나 나뉠 수 있어 정확한 넘김 수가 아니라 "넘기고 있음"의 근사치다.
 *
 * 메모리: 최근 [capacity] 개만 보관한다. 이벤트 객체는 들고 있지 않는다.
 */
class ScrollSignalTracker(
    private val capacity: Int = 32,
    private val minPageDeltaFraction: Float = DEFAULT_MIN_PAGE_DELTA_FRACTION
) {

    private data class Entry(val signal: ScrollSignal, val pageFlip: Boolean)

    private val entries = ArrayDeque<Entry>()

    /** @return 이 이벤트를 페이지 넘김으로 셌는지 */
    fun record(signal: ScrollSignal): Boolean {
        val previous = entries.lastOrNull {
            it.signal.packageName == signal.packageName && it.signal.className == signal.className
        }?.signal
        val flip = isPageFlip(previous, signal, minPageDeltaFraction)
        entries.addLast(Entry(signal, flip))
        while (entries.size > capacity) entries.removeFirst()
        return flip
    }

    /** [packageName] 에서 (nowMs - windowMs, nowMs] 사이에 일어난 페이지 넘김 수 */
    fun pageFlipsWithin(packageName: String, nowMs: Long, windowMs: Long): Int =
        entries.count {
            it.pageFlip && it.signal.packageName == packageName &&
                it.signal.atMs <= nowMs && nowMs - it.signal.atMs < windowMs
        }

    fun clear() = entries.clear()

    companion object {
        /** TODO: VERIFY ON DEVICE — 에뮬레이터 쇼츠 스와이프에서 dy=1162 (화면 2424 의 48%) 관찰 */
        const val DEFAULT_MIN_PAGE_DELTA_FRACTION = 0.40f

        fun isPageFlip(
            previous: ScrollSignal?,
            current: ScrollSignal,
            minPageDeltaFraction: Float = DEFAULT_MIN_PAGE_DELTA_FRACTION
        ): Boolean {
            // 가로 넘김(스토리 등)은 제외. 델타를 모르면 방향으로 거르지 않는다.
            val dx = current.deltaX
            val dy = current.deltaY
            if (dx != null && dy != null && (dx != 0 || dy != 0) && abs(dx) > abs(dy)) return false

            if (current.fromIndex < 0 || current.toIndex < 0) {
                // 인덱스 없음: 이동량으로만 판단. 이동량이나 화면 높이도 모르면 세지 않는다.
                if (dy == null || current.screenHeight <= 0) return false
                return abs(dy) >= current.screenHeight * minPageDeltaFraction
            }
            if (current.fromIndex != current.toIndex) return false

            if (previous == null || previous.fromIndex < 0) return false
            return previous.fromIndex != current.fromIndex
        }
    }
}
