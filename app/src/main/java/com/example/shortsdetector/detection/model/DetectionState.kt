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

/**
 * 세션 하나의 요약. 원문 없이 시간과 코드만 담는다.
 *
 * 세션이 이어지면(재진입 유예 안에 다시 진입) 같은 [sessionNumber] 로 요약이 다시 나온다.
 * 나중 요약이 누적값이므로 앞 요약을 **대체**한다(합산하면 중복이다). [resumeCount] 로 구분한다.
 */
data class SessionSummary(
    val sessionNumber: Int,
    val packageName: String,
    val ruleVersion: String?,
    val startedAtMs: Long,
    val endedAtMs: Long,
    /** 확정 시청시간: SHORTFORM_ESTIMATED 시간 + 댓글창 등 부분 플레이어 시간([partialWatchMs]) */
    val estimatedWatchMs: Long,
    /** 대화상자(OVERLAY) 등으로 가려진 시간(확정 시청시간에 합산하지 않는다) */
    val obscuredMs: Long,
    /** [estimatedWatchMs] 중 댓글창 등으로 플레이어가 줄어든 상태의 시간(이미 포함됨) */
    val partialWatchMs: Long,
    /** EXIT_PENDING 상태로 보낸 시간(불확실, 합산하지 않는다) */
    val uncertainMs: Long,
    val maxConfidence: Confidence,
    val endReason: EndReason,
    /** 이 세션이 끊겼다가 이어진 횟수 */
    val resumeCount: Int,
    /** EXIT_CONFIRMED 일 때 마지막 이탈 신호 */
    val exitCause: ExitCause?
)

/** 열린 세션의 현재 누적값(중간 저장용). 시각은 관찰 시계(elapsedRealtime) 기준. */
data class SessionProgress(
    val sessionNumber: Int,
    val packageName: String,
    val ruleVersion: String?,
    val startedAtMs: Long,
    val lastObservedAtMs: Long,
    val estimatedWatchMs: Long,
    val partialWatchMs: Long,
    val obscuredMs: Long,
    val uncertainMs: Long,
    val maxConfidence: Confidence,
    val resumeCount: Int
)

enum class ExitCause { ROOT_UNAVAILABLE, FOREIGN_WINDOW, NO_SHORTFORM_SIGNAL, UNKNOWN_SCREEN }
