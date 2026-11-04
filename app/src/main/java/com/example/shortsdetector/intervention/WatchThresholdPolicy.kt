package com.example.shortsdetector.intervention

/**
 * "숏폼을 어느 정도 본 뒤에 알린다" 규칙. 결정론적이고 Android 의존성이 없다.
 *
 *  - 기준: 세션 하나 안의 확정 시청시간([thresholdMs] 이상). 일 누적은 아직 없다.
 *  - 세션당 한 번만 판정한다. 기준을 넘는 순간 쿨다운에 걸리면 그 세션은 다시 알리지 않는다
 *    (기존 "세션 시작 시 1회 + 쿨다운" 동작과 같은 의미. thresholdMs = 0 이면 완전히 같다).
 */
class WatchThresholdPolicy(thresholdMs: Long) {

    var thresholdMs: Long = thresholdMs.coerceAtLeast(0L)
        set(value) { field = value.coerceAtLeast(0L) }

    /** 이미 판정을 끝낸(알렸거나 쿨다운으로 생략한) 세션 번호 */
    private var decidedSession: Int? = null

    /** 이번 관찰에서 이 세션에 대해 판정해야 하면 true. true 를 돌려주면 그 세션은 판정 완료로 기록한다. */
    fun shouldDecide(sessionNumber: Int?, watchMs: Long): Boolean {
        if (sessionNumber == null || sessionNumber == decidedSession) return false
        if (watchMs < thresholdMs) return false
        decidedSession = sessionNumber
        return true
    }
}
