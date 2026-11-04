package com.example.shortsdetector.intervention

import com.example.shortsdetector.data.usage.InterventionOutcome

/** 단계 판정에 쓰는 연속 횟수. */
data class LevelStreaks(val skips: Int = 0, val stops: Int = 0)

data class LevelStep(val policy: InterventionPolicy, val streaks: LevelStreaks, val levelChanged: Boolean)

/**
 * 개입 단계 규칙. 결정론적이고 Android 의존성이 없다. 같은 입력이면 같은 결과다.
 *
 *  - Lv.1: 넘김([InterventionOutcome.CONTINUED] / [InterventionOutcome.IGNORED])이 연속
 *    escalateAfterSkips 번이면 Lv.2.
 *  - Lv.2: CONTINUED(계속 보기를 고름)이면 바로 Lv.3. IGNORED 는 고른 것이 아니므로 단계를 유지한다.
 *    정책의 maxLevel 이 2 면 Lv.3 으로 올라가지 않는다.
 *  - STOPPED 가 연속 deescalateAfterStops 번이면 한 단계 내린다(Lv.1 이 바닥).
 *  - 멈추면 넘김 연속이 끊기고, 멈추지 않으면 멈춤 연속이 끊긴다. 단계가 바뀌면 둘 다 0 부터 다시 센다.
 */
object InterventionLevelRules {

    fun next(policy: InterventionPolicy, streaks: LevelStreaks, outcome: InterventionOutcome): LevelStep {
        val p = policy.clamped()
        return when {
            outcome == InterventionOutcome.STOPPED -> {
                val stops = streaks.stops + 1
                if (stops >= p.deescalateAfterStops && p.level > InterventionPolicyBounds.MIN_LEVEL) {
                    moved(p, p.level - 1)
                } else {
                    LevelStep(p, LevelStreaks(stops = stops), levelChanged = false)
                }
            }
            p.level == 1 -> {
                val skips = streaks.skips + 1
                if (skips >= p.escalateAfterSkips) moved(p, 2) else LevelStep(p, LevelStreaks(skips = skips), false)
            }
            p.level == 2 && outcome == InterventionOutcome.CONTINUED && p.maxLevel >= 3 -> moved(p, 3)
            else -> LevelStep(p, LevelStreaks(), levelChanged = false)
        }
    }

    private fun moved(p: InterventionPolicy, level: Int) =
        LevelStep(p.copy(level = level), LevelStreaks(), levelChanged = true)
}
