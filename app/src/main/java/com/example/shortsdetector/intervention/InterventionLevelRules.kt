package com.example.shortsdetector.intervention

import com.example.shortsdetector.data.usage.InterventionOutcome

/** 단계 판정에 쓰는 연속 횟수. */
data class LevelStreaks(val skips: Int = 0, val stops: Int = 0)

data class LevelStep(val policy: InterventionPolicy, val streaks: LevelStreaks, val levelChanged: Boolean)

/**
 * 개입 단계 규칙. 결정론적이고 Android 의존성이 없다. 같은 입력이면 같은 결과다.
 *
 *  - 상승은 단계마다 [EscalationRule] 을 따른다. 정책의 maxLevel 위로는 올라가지 않는다.
 *    Lv.1 은 넘김(CONTINUED / IGNORED)이 연속 escalateAfterSkips 번, Lv.2 는 CONTINUED 한 번이다.
 *  - STOPPED 가 연속 deescalateAfterStops 번이면 한 단계 내린다(Lv.1 이 바닥).
 *  - INTERRUPTED 는 사용자의 반응이 아니므로 단계도 연속 횟수도 바꾸지 않는다.
 *  - 멈추면 넘김 연속이 끊기고, 멈추지 않으면 멈춤 연속이 끊긴다. 단계가 바뀌면 둘 다 0 부터 다시 센다.
 */
object InterventionLevelRules {

    fun next(policy: InterventionPolicy, streaks: LevelStreaks, outcome: InterventionOutcome): LevelStep {
        val p = policy.clamped()
        return when {
            outcome == InterventionOutcome.INTERRUPTED -> LevelStep(p, streaks, levelChanged = false)
            outcome == InterventionOutcome.STOPPED -> {
                val stops = streaks.stops + 1
                if (stops >= p.deescalateAfterStops && p.level > InterventionPolicyBounds.MIN_LEVEL) {
                    moved(p, p.level - 1)
                } else {
                    LevelStep(p, LevelStreaks(stops = stops), levelChanged = false)
                }
            }
            else -> skipped(p, streaks, outcome)
        }
    }

    private fun skipped(p: InterventionPolicy, streaks: LevelStreaks, outcome: InterventionOutcome): LevelStep {
        val up = (p.level + 1).takeIf { it <= p.maxLevel }
        val rule = if (up == null) EscalationRule.NONE else InterventionLevels.of(p.level).escalation
        return when {
            rule == EscalationRule.AFTER_SKIPS -> {
                val skips = streaks.skips + 1
                if (skips >= p.escalateAfterSkips) moved(p, up!!) else LevelStep(p, LevelStreaks(skips = skips), false)
            }
            rule == EscalationRule.ON_CONTINUE && outcome == InterventionOutcome.CONTINUED -> moved(p, up!!)
            else -> LevelStep(p, LevelStreaks(), levelChanged = false)
        }
    }

    private fun moved(p: InterventionPolicy, level: Int) =
        LevelStep(p.copy(level = level), LevelStreaks(), levelChanged = true)
}
