package com.example.shortsdetector.intervention

import com.example.shortsdetector.data.usage.InterventionOutcome

/**
 * 발표 시연용 값. 저장된 정책 위에 덧씌워 계산만 하고 저장하지 않는다.
 * 쿨다운 10초는 하드 범위 밖이라 [InterventionPolicy.clamped] 를 지나지 않는다(시연 모드 전용 예외).
 */
object DemoMode {
    const val THRESHOLD_MS = 30_000L
    const val COOLDOWN_MS = 10_000L
    const val ESCALATE_AFTER_SKIPS = 1

    fun overlay(saved: InterventionPolicy, demoLevel: Int): InterventionPolicy = saved.copy(
        thresholdMs = THRESHOLD_MS,
        cooldownMs = COOLDOWN_MS,
        escalateAfterSkips = ESCALATE_AFTER_SKIPS,
        level = demoLevel.coerceIn(InterventionPolicyBounds.MIN_LEVEL, saved.maxLevel)
    )
}

/** 계속 보기를 고른 뒤 같은 세션에서 다시 개입할 시점. */
object ReinterventionRule {

    /** Lv.3 "5분만 더 보기". 벽시계가 아니라 시청시간 기준이다. */
    const val FIVE_MORE_MINUTES_MS = 300_000L

    /**
     * @param level 사용자가 답한 개입의 단계
     * @param demoThresholdMs 시연 모드면 기준 시간. 시연에서는 Lv.1·2 의 계속 보기도 재개입해야 한 세션 안에서 단계가 이어진다.
     * @return 지금 시청시간에서 얼마 뒤에 재개입할지. 재개입이 없으면 null.
     */
    fun afterWatchMs(level: Int, outcome: InterventionOutcome, demoThresholdMs: Long?): Long? = when {
        outcome != InterventionOutcome.CONTINUED -> null
        level >= InterventionPolicyBounds.MAX_LEVEL -> FIVE_MORE_MINUTES_MS
        else -> demoThresholdMs
    }
}
