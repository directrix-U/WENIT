package com.example.shortsdetector.data.goal

import com.example.shortsdetector.data.usage.SessionRecord
import java.time.LocalDate
import java.time.ZoneId

enum class GoalStatus {
    ACHIEVED,
    IN_PROGRESS,
    /** 제한형 목표를 넘겼다. */
    EXCEEDED,
    /** 하루가 끝났는데 SELF_STOPS 횟수를 못 채웠다. 넘긴 것이 아니어서 [EXCEEDED] 와 구분한다. */
    MISSED
}

/** 하루치 측정값. 시간은 ms. */
data class GoalDayMetrics(
    val watchMs: Long,
    val longestSessionMs: Long,
    val sessionCount: Int,
    val selfStopCount: Int,
    /** TIME_WINDOW 제한 구간 안의 추정 시청시간. [TimeWindowUsage] 로 계산한다. */
    val restrictedWatchMs: Long
)

/** @param currentValue [Goal.value] 와 같은 단위의 현재 값. TIME_WINDOW 는 제한 구간에서 본 분. 시간은 올림이라 넘겼으면 항상 목표보다 크다. */
data class GoalProgress(val goal: Goal, val currentValue: Int, val status: GoalStatus)

/**
 * 목표별 달성 상태. 순수 함수.
 *
 * 제한형(시간·횟수 상한)은 하루가 끝나야 지켰다고 말할 수 있다. 그래서 그날이 진행 중이면 넘기기 전까지
 * [GoalStatus.IN_PROGRESS] 이고, [GoalStatus.ACHIEVED] 는 dayFinished = true 일 때만 나온다.
 * SELF_STOPS 는 횟수를 채우는 즉시 ACHIEVED 다. 상한과 정확히 같은 값은 넘긴 것이 아니다.
 */
object GoalProgressCalculator {

    private const val MS_PER_MINUTE = 60_000L

    fun evaluate(goal: Goal, metrics: GoalDayMetrics, dayFinished: Boolean = false): GoalProgress =
        when (goal.type) {
            GoalType.DAILY_TOTAL -> limitByMinutes(goal, metrics.watchMs, dayFinished)
            GoalType.PER_SESSION -> limitByMinutes(goal, metrics.longestSessionMs, dayFinished)
            GoalType.TIME_WINDOW -> {
                val status = limitStatus(metrics.restrictedWatchMs > 0L, dayFinished)
                GoalProgress(goal, ceilMinutes(metrics.restrictedWatchMs), status)
            }
            GoalType.DAILY_OPENS ->
                GoalProgress(goal, metrics.sessionCount, limitStatus(metrics.sessionCount > goal.value, dayFinished))
            GoalType.SELF_STOPS -> GoalProgress(
                goal, metrics.selfStopCount,
                when {
                    metrics.selfStopCount >= goal.value -> GoalStatus.ACHIEVED
                    dayFinished -> GoalStatus.MISSED
                    else -> GoalStatus.IN_PROGRESS
                }
            )
        }

    private fun limitByMinutes(goal: Goal, ms: Long, dayFinished: Boolean) =
        GoalProgress(goal, ceilMinutes(ms), limitStatus(ms > goal.value * MS_PER_MINUTE, dayFinished))

    private fun limitStatus(exceeded: Boolean, dayFinished: Boolean) = when {
        exceeded -> GoalStatus.EXCEEDED
        dayFinished -> GoalStatus.ACHIEVED
        else -> GoalStatus.IN_PROGRESS
    }

    private fun ceilMinutes(ms: Long): Int = ((ms + MS_PER_MINUTE - 1) / MS_PER_MINUTE).toInt()
}

/**
 * TIME_WINDOW 제한 구간의 시청시간. 제한은 그날 05:00 에 풀린다(이른 아침 시청은 위반이 아니다).
 *
 *  - 달력 날짜 하나에는 구간이 둘 생긴다: 자정~05:00(전날 밤에서 이어짐), 기준 시각~자정.
 *  - 기준이 자정을 넘기면(예: 새벽 1:00) 01:00~05:00 하나뿐이다.
 *  - 세션 안의 정확한 시청 위치는 저장하지 않으므로, 일별 집계와 같이 겹친 비율만큼 나눈 근사값이다.
 */
object TimeWindowUsage {

    private const val MINUTES_PER_DAY = 24 * 60
    private const val RELEASE_HOUR = 5

    fun restrictedWatchMs(sessions: List<SessionRecord>, cutoffValue: Int, date: LocalDate, zone: ZoneId): Long {
        val intervals = restrictedIntervals(cutoffValue, date, zone)
        return sessions.sumOf { s -> intervals.sumOf { (from, to) -> shareOf(s, from, to) } }
    }

    internal fun restrictedIntervals(cutoffValue: Int, date: LocalDate, zone: ZoneId): List<Pair<Long, Long>> {
        fun at(minuteOfDay: Int) =
            date.atTime(minuteOfDay / 60, minuteOfDay % 60).atZone(zone).toInstant().toEpochMilli()

        val dayStart = date.atStartOfDay(zone).toInstant().toEpochMilli()
        val nextDayStart = date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        val release = at(RELEASE_HOUR * 60)
        return if (cutoffValue >= MINUTES_PER_DAY) {
            listOf(at(cutoffValue - MINUTES_PER_DAY) to release)
        } else {
            listOf(dayStart to release, at(cutoffValue) to nextDayStart)
        }
    }

    private fun shareOf(s: SessionRecord, from: Long, to: Long): Long {
        val end = maxOf(s.endedAtMs, s.startedAtMs)
        val span = end - s.startedAtMs
        if (span == 0L) return if (s.startedAtMs in from until to) s.estimatedWatchMs else 0L
        val overlap = minOf(end, to) - maxOf(s.startedAtMs, from)
        return if (overlap <= 0L) 0L else s.estimatedWatchMs * overlap / span
    }
}
