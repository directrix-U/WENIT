package com.example.shortsdetector.ai.agent

import com.example.shortsdetector.ai.TextFit
import com.example.shortsdetector.intervention.InterventionPolicy
import com.example.shortsdetector.intervention.InterventionPolicyBounds

/** 에이전트 전용 범위. 앱 전체 하드 범위([InterventionPolicyBounds])보다 좁다. 앱 전체 10초 하한과 별개다. */
object AgentPolicyBounds {
    val THRESHOLD_MS = 30_000L..600_000L
    val COOLDOWN_MS = 30_000L..600_000L

    /** 한 번 실행에서 바꿀 수 있는 폭 */
    const val THRESHOLD_STEP_RATIO = 0.5
    const val LEVEL_STEP = 1
}

data class AgentProposal(
    val keep: Boolean,
    val thresholdSec: Int,
    val cooldownSec: Int,
    val startLevel: Int,
    val reason: String,
    val lv1Message: String,
    val lv2Question: String
)

object AgentPolicyRules {

    private const val MS_PER_SECOND = 1_000L

    /**
     * 제안을 변경 폭 → 에이전트 범위 순서로 자른다. 범위를 마지막에 적용하므로, 지금 값이 범위 밖이어도
     * 결과는 항상 범위 안이다(예: 지금 기준이 10초여도 30초 미만이 나오지 않는다).
     */
    fun apply(current: InterventionPolicy, p: AgentProposal): InterventionPolicy {
        val b = AgentPolicyBounds
        val step = (current.thresholdMs * b.THRESHOLD_STEP_RATIO).toLong()
        return current.copy(
            thresholdMs = (p.thresholdSec * MS_PER_SECOND)
                .coerceIn(current.thresholdMs - step, current.thresholdMs + step)
                .coerceIn(b.THRESHOLD_MS),
            cooldownMs = (p.cooldownSec * MS_PER_SECOND).coerceIn(b.COOLDOWN_MS),
            level = p.startLevel
                .coerceIn(current.level - b.LEVEL_STEP, current.level + b.LEVEL_STEP)
                .coerceIn(InterventionPolicyBounds.MIN_LEVEL, current.maxLevel)
        ).clamped()
    }

    /** 바뀐 값만 짧게 적는다. 임시 화면 표시용. */
    fun describeChange(before: InterventionPolicy?, after: InterventionPolicy): String {
        if (before == null) return "처음 값"
        val parts = buildList {
            if (before.thresholdMs != after.thresholdMs) add("기준 ${before.thresholdMs / 1000}→${after.thresholdMs / 1000}초")
            if (before.level != after.level) add("단계 ${before.level}→${after.level}")
            if (before.cooldownMs != after.cooldownMs) add("쿨다운 ${before.cooldownMs / 1000}→${after.cooldownMs / 1000}초")
        }
        return parts.ifEmpty { listOf("값 변화 없음") }.joinToString(", ")
    }
}

/** 에이전트 문구 검증. 통과하지 못하면 null 이고, 호출 측은 고정 문구를 쓴다. */
object AgentText {

    const val MAX_REASON_LENGTH = 40
    const val MAX_MESSAGE_LENGTH = 30

    /** 의학적 주장과 비난에 쓰이는 표현. 문구는 비난·수치심·진단을 피한다(명세 5.1). */
    private val BANNED = listOf(
        "중독", "질환", "장애", "우울", "불안증", "치료", "진단", "처방", "의사", "병원", "정신과", "도파민", "뇌",
        "한심", "게으", "나태", "실패자", "부끄", "창피", "망가", "최악", "의지력"
    )

    /** 사용자에게 보이면 안 되는 내부 이름: lv1, Lv.2, lv1Message, returnAfterStop 같은 표기 */
    private val INTERNAL_TERM = Regex("""(?i)lv\.?\s*\d|[a-z][A-Z]""")

    fun isAllowed(text: String): Boolean = BANNED.none { it in text } && !INTERNAL_TERM.containsMatchIn(text)

    fun clean(text: String, max: Int): String? {
        val oneLine = text.replace('\n', ' ').trim()
        if (oneLine.isEmpty() || !isAllowed(oneLine)) return null
        return TextFit.fit(oneLine, max)
    }
}

/** 앱을 열 때 자동 실행할지. 끄기 스위치와 동의를 먼저 본다. */
object AgentSchedule {

    const val INTERVAL_MS = 24L * 60 * 60 * 1000

    fun isDue(nowMs: Long, lastRunAtMs: Long?, enabled: Boolean, consent: Boolean): Boolean =
        enabled && consent && (lastRunAtMs == null || nowMs - lastRunAtMs >= INTERVAL_MS)
}
