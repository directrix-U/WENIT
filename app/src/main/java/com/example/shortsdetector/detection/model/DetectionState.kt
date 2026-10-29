package com.example.shortsdetector.detection.model

/**
 * 감지 상태. 명세서(WENIT_PROJECT_SPEC.md 3.1-4)의 상태 이름을 그대로 쓴다.
 *
 * 기존 프로토타입과의 대응:
 *  - 대기(consecutiveHits == 0)     → OUTSIDE_TARGET / TARGET_FOREGROUND
 *  - 확인 중(0 < hits < N)          → CANDIDATE
 *  - 래치(shortsActive)             → SHORTFORM_ESTIMATED / TEMPORARILY_OBSCURED
 *  - (없음, 즉시 해제했음)           → EXIT_PENDING
 */
enum class DetectionState(val sessionActive: Boolean, val needsWatchdog: Boolean) {
    OUTSIDE_TARGET(false, false),
    TARGET_FOREGROUND(false, false),
    CANDIDATE(false, true),
    SHORTFORM_ESTIMATED(true, true),
    TEMPORARILY_OBSCURED(true, true),
    EXIT_PENDING(true, true),
    ENDED(false, false)
}

enum class EndReason {
    /** 이탈 신호가 grace 기간 동안 유지됐다. 원인은 [SessionSummary.exitCause] 참고. */
    EXIT_CONFIRMED,

    /** 다른 대상 앱의 숏폼 화면으로 바로 넘어갔다. */
    APP_SWITCHED,

    /** 관찰 공백이 너무 길었다(타이머 정지 등). */
    STALE,

    /** 서비스 중단/해제, 설정 변경 등 외부 요인. */
    FORCED
}

/** 세션 하나의 요약. 원문 없이 시간과 코드만 담는다. */
data class SessionSummary(
    val sessionNumber: Int,
    val packageName: String,
    val ruleVersion: String?,
    val startedAtMs: Long,
    val endedAtMs: Long,
    /** SHORTFORM_ESTIMATED 상태로 보낸 시간 */
    val estimatedWatchMs: Long,
    /** TEMPORARILY_OBSCURED 상태로 보낸 시간(확정 시청시간에 합산하지 않는다) */
    val obscuredMs: Long,
    /** EXIT_PENDING 상태로 보낸 시간(불확실, 합산하지 않는다) */
    val uncertainMs: Long,
    val maxConfidence: Confidence,
    val endReason: EndReason,
    /** EXIT_CONFIRMED 일 때 마지막 이탈 신호 */
    val exitCause: ExitCause?
)

enum class ExitCause { ROOT_UNAVAILABLE, FOREIGN_WINDOW, NO_SHORTFORM_SIGNAL, UNKNOWN_SCREEN }
