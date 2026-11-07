package com.example.shortsdetector.intervention

/**
 * "숏폼을 어느 정도 본 뒤에, 얼마나 자주 알리는가" 규칙. 결정론적이고 Android 의존성이 없다.
 *
 *  - 첫 개입: 세션 하나 안의 확정 시청시간이 [thresholdMs] 에 닿으면.
 *  - 다음 개입: 앞선 개입 뒤 쿨다운만큼 더 보면. 세션이 이어지는 동안 반복한다.
 *  - 시점은 시청시간으로 잰다. 영상이 멈춰 있거나 가려진 동안에는 다가오지 않는다.
 *  - 판정이 쿨다운 등으로 미뤄져도 기회는 사라지지 않는다. [markDecided] 를 부를 때까지 계속 true 를 돌려준다.
 */
class WatchThresholdPolicy(thresholdMs: Long) {

    var thresholdMs: Long = thresholdMs.coerceAtLeast(0L)
        set(value) { field = value.coerceAtLeast(0L) }

    /** 이미 개입한 세션 번호 */
    private var decidedSession: Int? = null

    /** [decidedSession] 의 다음 개입 시점(시청시간) */
    private var nextAtWatchMs = 0L

    /** 이 세션이 첫 개입을 기다리는 중이면 true */
    fun isFirst(sessionNumber: Int?): Boolean = sessionNumber != null && sessionNumber != decidedSession

    fun isDue(sessionNumber: Int?, watchMs: Long): Boolean = when {
        sessionNumber == null -> false
        sessionNumber == decidedSession -> watchMs >= nextAtWatchMs
        else -> watchMs >= thresholdMs
    }

    /** 개입했을 때 부른다. 다음 개입은 [watchMs] + [intervalMs] 이후다. */
    fun markDecided(sessionNumber: Int, watchMs: Long, intervalMs: Long) {
        decidedSession = sessionNumber
        nextAtWatchMs = watchMs + intervalMs
    }

    /** 다음 개입을 [atWatchMs] 까지 미룬다. 앞당기지는 않는다. 다른 세션이면 무시한다. */
    fun delayUntil(sessionNumber: Int, atWatchMs: Long) {
        if (sessionNumber == decidedSession) nextAtWatchMs = maxOf(nextAtWatchMs, atWatchMs)
    }
}
