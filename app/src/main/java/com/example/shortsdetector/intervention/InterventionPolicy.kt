package com.example.shortsdetector.intervention

import com.example.shortsdetector.DetectionConfig
import com.example.shortsdetector.data.goal.Goal
import com.example.shortsdetector.data.goal.GoalRules
import com.example.shortsdetector.data.goal.GoalType

/**
 * 개입 정책. 결정론적 엔진이 쓰는 값이고, 나중에 에이전트가 제안하는 값이다(명세 2장 MVP-D).
 * 어디서 온 값이든 적용 전에 [clamped] 를 지난다. 이번 단계에서 서비스는 [level] 만 읽어 기록에 남긴다.
 */
data class InterventionPolicy(
    /** 개입 기준 시간(세션 안 확정 시청시간) */
    val thresholdMs: Long = DetectionConfig.DEFAULT_WATCH_THRESHOLD_MS,
    /** 현재 단계 1~3 */
    val level: Int = InterventionPolicyBounds.MIN_LEVEL,
    /** Lv.1 에서 몇 번 연속 넘기면 Lv.2 로 올리는가 */
    val escalateAfterSkips: Int = 2,
    /** 몇 번 연속 스스로 멈추면 한 단계 내리는가 */
    val deescalateAfterStops: Int = 2,
    val cooldownMs: Long = DetectionConfig.MIN_RENOTIFY_INTERVAL_MS,
    val maxDailyInterventions: Int = 10,
    /** 올라갈 수 있는 최대 단계. 2 면 전체 화면 숨 고르기(Lv.3)를 쓰지 않는다. */
    val maxLevel: Int = InterventionPolicyBounds.MAX_LEVEL
) {
    /** 모든 값을 하드 범위 안으로 자른다. */
    fun clamped(): InterventionPolicy = InterventionPolicyBounds.run {
        val top = maxLevel.coerceIn(MAX_LEVEL_SETTING)
        copy(
            thresholdMs = thresholdMs.coerceIn(THRESHOLD_MS),
            level = level.coerceIn(MIN_LEVEL, top),
            maxLevel = top,
            escalateAfterSkips = escalateAfterSkips.coerceIn(ESCALATE_AFTER_SKIPS),
            deescalateAfterStops = deescalateAfterStops.coerceIn(DEESCALATE_AFTER_STOPS),
            cooldownMs = cooldownMs.coerceIn(COOLDOWN_MS),
            maxDailyInterventions = maxDailyInterventions.coerceIn(MAX_DAILY_INTERVENTIONS)
        )
    }

    /**
     * 실제로 쓸 개입 기준 시간. PER_SESSION 목표가 있으면 그 값이 정책 값보다 우선한다.
     * 정책을 덮어쓰지 않고 그때그때 계산하므로, 목표를 지우면 원래 기준으로 돌아간다.
     */
    fun effectiveThresholdMs(goals: List<Goal>): Long {
        val goal = goals.firstOrNull { it.type == GoalType.PER_SESSION }
        val ms = goal?.let { GoalRules.clampValue(GoalType.PER_SESSION, it.value) * MS_PER_MINUTE } ?: thresholdMs
        return ms.coerceIn(InterventionPolicyBounds.THRESHOLD_MS)
    }

    private companion object {
        const val MS_PER_MINUTE = 60_000L
    }
}

/** 하드 범위. 사용자·에이전트 어느 쪽도 이 밖으로 나갈 수 없다. 값은 임시값이다(명세의 "미정: 하드 범위 값"). */
object InterventionPolicyBounds {
    const val MIN_LEVEL = 1
    const val MAX_LEVEL = 3

    /** 정책의 최대 단계로 고를 수 있는 값 */
    val MAX_LEVEL_SETTING = 2..MAX_LEVEL

    /** 하한은 임계값 선택지의 최솟값(10초, 기기 테스트용)과 같다. */
    val THRESHOLD_MS = 10_000L..3_600_000L
    val ESCALATE_AFTER_SKIPS = 1..10
    val DEESCALATE_AFTER_STOPS = 1..10

    /** 하한은 기존 재알림 쿨다운과 같다. 그 밑으로 내리면 알림 폭주 방어선이 약해진다. */
    val COOLDOWN_MS = DetectionConfig.MIN_RENOTIFY_INTERVAL_MS..3_600_000L
    val MAX_DAILY_INTERVENTIONS = 1..30
}

/**
 * 정책 ↔ 문자열("thresholdMs=180000;level=1;…"). 설정값과 policy_changes 행에 같은 형식으로 저장한다.
 * 범위를 자르지 않는다: 거절된 제안은 잘리기 전 값이 기록으로 남아야 한다. 적용하는 쪽이 [InterventionPolicy.clamped] 를 부른다.
 */
object InterventionPolicyCodec {

    fun encode(p: InterventionPolicy): String = listOf(
        "thresholdMs" to p.thresholdMs,
        "level" to p.level,
        "escalateAfterSkips" to p.escalateAfterSkips,
        "deescalateAfterStops" to p.deescalateAfterStops,
        "cooldownMs" to p.cooldownMs,
        "maxDailyInterventions" to p.maxDailyInterventions,
        "maxLevel" to p.maxLevel
    ).joinToString(";") { (k, v) -> "$k=$v" }

    /** 없는 항목은 기본값으로 채운다(나중에 필드가 늘어도 옛 기록을 읽기 위함). 읽을 항목이 하나도 없으면 null. */
    fun decode(text: String): InterventionPolicy? {
        val values = text.split(';').mapNotNull { part ->
            val (k, v) = part.split('=', limit = 2).takeIf { it.size == 2 } ?: return@mapNotNull null
            v.toLongOrNull()?.let { k to it }
        }.toMap()
        if (values.isEmpty()) return null
        val d = InterventionPolicy()
        fun int(key: String, fallback: Int) =
            values[key]?.coerceIn(Int.MIN_VALUE.toLong(), Int.MAX_VALUE.toLong())?.toInt() ?: fallback
        return InterventionPolicy(
            thresholdMs = values["thresholdMs"] ?: d.thresholdMs,
            level = int("level", d.level),
            escalateAfterSkips = int("escalateAfterSkips", d.escalateAfterSkips),
            deescalateAfterStops = int("deescalateAfterStops", d.deescalateAfterStops),
            cooldownMs = values["cooldownMs"] ?: d.cooldownMs,
            maxDailyInterventions = int("maxDailyInterventions", d.maxDailyInterventions),
            maxLevel = int("maxLevel", d.maxLevel)
        )
    }
}

/**
 * 판정에 쓸 최종 값.
 * @param policy 시연 모드면 시연 값을 덧씌운 정책
 * @param thresholdMs PER_SESSION 목표까지 반영한 기준 시간
 * @param demoRunId 시연 모드를 켠 시각. 켤 때마다 바뀌어 시연 전용 하루 카운터를 초기화하는 기준이 된다. 시연이 아니면 null.
 */
data class ResolvedPolicy(
    val policy: InterventionPolicy,
    val thresholdMs: Long,
    val demoMode: Boolean,
    val demoRunId: Long? = null
)
