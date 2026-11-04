package com.example.shortsdetector.data.goal

/**
 * 앱이 측정할 수 있는 목표 유형. [Goal.value] 의 단위가 유형마다 다르다.
 *
 * TIME_WINDOW 의 값은 "자정을 넘겨 이어지는 분"이다: 23:00 = 1380, 다음 날 01:00 = 1500.
 * 저녁부터 새벽까지가 한 줄로 이어져 범위 자르기가 단순해진다([GoalRules.timeWindowValue] 로 만든다).
 */
enum class GoalType {
    /** 하루 총 시청 분 이하 */
    DAILY_TOTAL,
    /** 한 번에 보는 분 이하 */
    PER_SESSION,
    /** 이 시각 이후 시청 안 함 */
    TIME_WINDOW,
    /** 하루 시청 세션 수 이하 */
    DAILY_OPENS,
    /** 하루 스스로 멈춘 횟수 이상 */
    SELF_STOPS
}

enum class GoalSource { AI, PRESET, USER }

/** 목표 하나. 유형당 하나만 둘 수 있어 [type] 이 곧 식별자다([GoalRules.put]). */
data class Goal(
    val type: GoalType,
    val value: Int,
    /** 화면 표시 문구 */
    val label: String,
    /** 추천 이유. 직접 만든 목표는 null. */
    val reason: String? = null,
    val source: GoalSource
)
