package com.example.shortsdetector.data.goal

import com.example.shortsdetector.data.profile.UserProfile
import com.example.shortsdetector.data.profile.WatchTimeSlot
import java.time.DayOfWeek

/**
 * AI 실패 대비 기본 추천: 설문 응답 → 목표 정확히 3개. 결정론적이다.
 *
 * 시청 습관 응답(시간대·요일)만 쓴다. 나이대·성별로 목표를 다르게 하지 않는다.
 * 앞쪽 규칙일수록 우선하고, 같은 유형은 먼저 나온 것을 쓴다. 뒤쪽 세 항목이 항상 남아 있어 3개가 보장된다.
 */
object PresetGoalRecommender {

    private val WEEKEND = setOf(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY)
    private const val MANY_SLOTS = 3
    private const val MANY_DAYS = 6

    fun recommend(profile: UserProfile?): List<Goal> {
        val slots = profile?.watchTimeSlots.orEmpty()
        val days = profile?.watchDays.orEmpty()

        val candidates = buildList {
            if (WatchTimeSlot.LATE_NIGHT in slots) {
                add(goal(GoalType.TIME_WINDOW, GoalRules.timeWindowValue(23, 0), "늦은 밤에 주로 본다고 답해서, 잠들기 전 시간을 먼저 지켜 봐요."))
            }
            if (slots.size >= MANY_SLOTS || days.size >= MANY_DAYS) {
                add(goal(GoalType.DAILY_TOTAL, 45, "여러 시간대에 나눠 보면 하루 합계가 잘 안 보여서 총량을 함께 봐요."))
            }
            if (WatchTimeSlot.MORNING in slots || WatchTimeSlot.DAYTIME in slots) {
                add(goal(GoalType.DAILY_OPENS, 5, "낮에 틈틈이 열어 보는 횟수를 먼저 살펴봐요."))
            }
            if (days.isNotEmpty() && days.all { it in WEEKEND }) {
                add(goal(GoalType.PER_SESSION, 20, "주말에 주로 본다고 답해서, 한 번에 이어 보는 시간을 정해 봐요."))
            }
            add(goal(GoalType.PER_SESSION, 10, "한 번에 보는 시간을 정해 두면 멈출 때를 고르기 쉬워요."))
            add(goal(GoalType.DAILY_TOTAL, 60, "하루 전체 시간을 가볍게 확인해 봐요."))
            add(goal(GoalType.SELF_STOPS, 1, "하루 한 번, 스스로 멈춰 보는 것부터 시작해 봐요."))
        }
        return candidates.distinctBy { it.type }.take(GoalRules.MAX_GOALS)
    }

    private fun goal(type: GoalType, value: Int, reason: String) =
        GoalRules.sanitize(Goal(type, value, GoalRules.defaultLabel(type, value), reason, GoalSource.PRESET))
}
