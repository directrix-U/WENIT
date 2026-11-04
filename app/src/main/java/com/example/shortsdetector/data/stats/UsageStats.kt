package com.example.shortsdetector.data.stats

import com.example.shortsdetector.data.goal.Goal
import com.example.shortsdetector.data.goal.GoalDayMetrics
import com.example.shortsdetector.data.goal.GoalProgress
import com.example.shortsdetector.data.goal.GoalProgressCalculator
import com.example.shortsdetector.data.goal.GoalType
import com.example.shortsdetector.data.goal.TimeWindowUsage
import com.example.shortsdetector.data.profile.WatchTimeSlot
import com.example.shortsdetector.data.usage.DailyUsageCalculator
import com.example.shortsdetector.data.usage.InterventionMethod
import com.example.shortsdetector.data.usage.InterventionOutcome
import com.example.shortsdetector.data.usage.InterventionRecord
import com.example.shortsdetector.data.usage.InterventionUserReason
import com.example.shortsdetector.data.usage.SessionRecord
import com.example.shortsdetector.intervention.InterventionPolicyBounds
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters

data class TodayStats(
    val date: LocalDate,
    val estimatedWatchMs: Long,
    val goals: List<GoalProgress>,
    /** 오늘 시작한 세션 수 */
    val sessionCount: Int,
    /** 오늘 시작한 세션 중 가장 긴 추정 시청시간 */
    val longestSessionMs: Long,
    /** 사용자에게 실제로 보여 준 개입 수(쿨다운 생략·실패 제외) */
    val interventionCount: Int,
    /** 위 개입 중 결과가 STOPPED 인 수 */
    val selfStopCount: Int
)

/** 끝난 하루의 목표 결과. */
data class DayGoalResults(val date: LocalDate, val goals: List<GoalProgress>)

data class DailyWatch(val date: LocalDate, val watchMs: Long)

/**
 * 단계별 멈춤 비율.
 * @param answered 결과가 기록된 개입 수. 결과를 모르거나 앱이 닫은(INTERRUPTED) 개입은 비율에 넣지 않는다.
 * @param stopRate stopped / answered. answered 가 0 이면 null(0% 와 다르다).
 */
data class LevelStopRate(val level: Int, val delivered: Int, val answered: Int, val stopped: Int, val stopRate: Double?)

data class WeekStats(
    /** 이번 주 월요일 ~ 오늘 */
    val days: List<DailyWatch>,
    val totalWatchMs: Long,
    /** [days] 일수로 나눈 평균 */
    val dailyAverageMs: Long,
    /** 지난주 같은 구간(월요일 ~ 지난주 같은 요일)의 합 */
    val previousPeriodWatchMs: Long,
    val changeMs: Long,
    /** (이번 주 - 지난주) / 지난주. 지난주 기록이 0 이면 null. */
    val changeRatio: Double?,
    val levelStopRates: List<LevelStopRate>
)

data class TimeSlotWatch(val slot: WatchTimeSlot, val watchMs: Long)

data class LevelOutcomes(val level: Int, val stopped: Int, val continued: Int, val ignored: Int)

data class ReasonCount(val reason: InterventionUserReason, val count: Int)

/**
 * 에이전트 입력용 최근 이용 집계.
 * [days] 는 어제까지의 완전한 7일이다. 나머지 항목은 그 7일에 오늘까지 더해 센다(방금 쌓인 반응도 보도록).
 */
data class RecentUsageStats(
    val days: List<DailyWatch>,
    val sessionCount: Int,
    val averageSessionMs: Long,
    val longestSessionMs: Long,
    /** 세션이 시작한 시각의 시간대로 나눈다 */
    val timeSlots: List<TimeSlotWatch>,
    /** 사용자에게 보여 준 개입 수 */
    val deliveredInterventions: Int,
    val levelOutcomes: List<LevelOutcomes>,
    val lv2Reasons: List<ReasonCount>
)

data class StatsDataWindow(val fromMs: Long, val toMs: Long)

/**
 * 오늘·이번 주 통계. 순수 함수. 저장소에서 [dataWindow] 범위를 읽어 넘기면 된다.
 * 시연 모드 세션과 개입은 기본으로 뺀다(includeDemo).
 *
 * 주는 월요일에 시작한다. 지난주 비교는 주 중간에도 공정하도록 같은 요일까지만 합산한다.
 * 다만 오늘은 아직 진행 중이고 지난주 같은 요일은 하루 전체라, 이른 시간에는 감소 쪽으로 치우친다.
 */
object UsageStatsCalculator {

    private val DELIVERED = setOf(InterventionMethod.POPUP, InterventionMethod.NOTIFICATION)
    private const val RECENT_DAYS = 7L

    /** [today]·[week] 계산에 필요한 기록 범위(지난주 월요일 00:00 ~ 내일 00:00). */
    fun dataWindow(nowMs: Long, zone: ZoneId): StatsDataWindow {
        val date = DailyUsageCalculator.dateOf(nowMs, zone)
        val from = weekStart(date).minusWeeks(1)
        return StatsDataWindow(
            from.atStartOfDay(zone).toInstant().toEpochMilli(),
            date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        )
    }

    fun today(
        sessions: List<SessionRecord>,
        interventions: List<InterventionRecord>,
        goals: List<Goal>,
        zone: ZoneId,
        nowMs: Long,
        includeDemo: Boolean = false
    ): TodayStats {
        val date = DailyUsageCalculator.dateOf(nowMs, zone)
        return dayStats(
            chosenSessions(sessions, includeDemo), chosen(interventions, includeDemo), goals, zone, date,
            dayFinished = false
        )
    }

    /**
     * 어제의 목표 결과(하루가 끝났으므로 ON_TRACK 이 나오지 않는다).
     * 목표 변경 이력은 저장하지 않아서 [goals] 는 지금 목표다. 어제 없던 목표도 어제 기록으로 판정된다.
     */
    fun yesterday(
        sessions: List<SessionRecord>,
        interventions: List<InterventionRecord>,
        goals: List<Goal>,
        zone: ZoneId,
        nowMs: Long,
        includeDemo: Boolean = false
    ): DayGoalResults {
        val date = DailyUsageCalculator.dateOf(nowMs, zone).minusDays(1)
        val results = dayStats(
            chosenSessions(sessions, includeDemo), chosen(interventions, includeDemo), goals, zone, date,
            dayFinished = true
        )
        return DayGoalResults(date, results.goals)
    }

    private fun dayStats(
        sessions: List<SessionRecord>,
        interventions: List<InterventionRecord>,
        goals: List<Goal>,
        zone: ZoneId,
        date: LocalDate,
        dayFinished: Boolean
    ): TodayStats {
        val usage = DailyUsageCalculator.aggregate(sessions, zone, date, date).single()
        val longest = sessions.filter { DailyUsageCalculator.dateOf(it.startedAtMs, zone) == date }
            .maxOfOrNull { it.estimatedWatchMs } ?: 0L
        val delivered = interventions.filter {
            it.method in DELIVERED && DailyUsageCalculator.dateOf(it.occurredAtMs, zone) == date
        }
        val stops = delivered.count { it.outcome == InterventionOutcome.STOPPED }
        val restricted = goals.firstOrNull { it.type == GoalType.TIME_WINDOW }
            ?.let { TimeWindowUsage.restrictedWatchMs(sessions, it.value, date, zone) } ?: 0L
        val metrics = GoalDayMetrics(usage.estimatedWatchMs, longest, usage.sessionCount, stops, restricted)
        return TodayStats(
            date = date,
            estimatedWatchMs = usage.estimatedWatchMs,
            goals = goals.map { GoalProgressCalculator.evaluate(it, metrics, dayFinished) },
            sessionCount = usage.sessionCount,
            longestSessionMs = longest,
            interventionCount = delivered.size,
            selfStopCount = stops
        )
    }

    fun week(
        sessions: List<SessionRecord>,
        interventions: List<InterventionRecord>,
        zone: ZoneId,
        nowMs: Long,
        includeDemo: Boolean = false
    ): WeekStats {
        val date = DailyUsageCalculator.dateOf(nowMs, zone)
        val monday = weekStart(date)
        val counted = chosenSessions(sessions, includeDemo)
        val days = DailyUsageCalculator.aggregate(counted, zone, monday, date)
            .map { DailyWatch(it.date, it.estimatedWatchMs) }
        val total = days.sumOf { it.watchMs }
        val previous = DailyUsageCalculator
            .aggregate(counted, zone, monday.minusWeeks(1), date.minusWeeks(1))
            .sumOf { it.estimatedWatchMs }
        val thisWeek = chosen(interventions, includeDemo).filter {
            val d = DailyUsageCalculator.dateOf(it.occurredAtMs, zone)
            it.method in DELIVERED && !d.isBefore(monday) && !d.isAfter(date)
        }
        return WeekStats(
            days = days,
            totalWatchMs = total,
            dailyAverageMs = total / days.size,
            previousPeriodWatchMs = previous,
            changeMs = total - previous,
            changeRatio = if (previous > 0L) (total - previous).toDouble() / previous else null,
            levelStopRates = levelStopRates(thisWeek)
        )
    }

    fun recent(
        sessions: List<SessionRecord>,
        interventions: List<InterventionRecord>,
        zone: ZoneId,
        nowMs: Long
    ): RecentUsageStats {
        val date = DailyUsageCalculator.dateOf(nowMs, zone)
        val first = date.minusDays(RECENT_DAYS)
        val counted = chosenSessions(sessions, false)
        val inRange = counted.filter { !DailyUsageCalculator.dateOf(it.startedAtMs, zone).isBefore(first) }
        val days = DailyUsageCalculator.aggregate(counted, zone, first, date.minusDays(1))
            .map { DailyWatch(it.date, it.estimatedWatchMs) }
        val delivered = chosen(interventions, false).filter {
            it.method in DELIVERED && !DailyUsageCalculator.dateOf(it.occurredAtMs, zone).isBefore(first)
        }
        val slots = inRange.groupBy { slotOf(it.startedAtMs, zone) }
        return RecentUsageStats(
            days = days,
            sessionCount = inRange.size,
            averageSessionMs = if (inRange.isEmpty()) 0L else inRange.sumOf { it.estimatedWatchMs } / inRange.size,
            longestSessionMs = inRange.maxOfOrNull { it.estimatedWatchMs } ?: 0L,
            timeSlots = WatchTimeSlot.entries.map { s -> TimeSlotWatch(s, slots[s].orEmpty().sumOf { it.estimatedWatchMs }) },
            deliveredInterventions = delivered.size,
            levelOutcomes = (InterventionPolicyBounds.MIN_LEVEL..InterventionPolicyBounds.MAX_LEVEL).map { level ->
                val atLevel = delivered.filter { it.level == level }
                LevelOutcomes(
                    level,
                    atLevel.count { it.outcome == InterventionOutcome.STOPPED },
                    atLevel.count { it.outcome == InterventionOutcome.CONTINUED },
                    atLevel.count { it.outcome == InterventionOutcome.IGNORED }
                )
            },
            lv2Reasons = delivered.filter { it.level == 2 }.mapNotNull { it.userReason }
                .groupingBy { it }.eachCount().map { (reason, n) -> ReasonCount(reason, n) }
                .sortedByDescending { it.count }
        )
    }

    private fun slotOf(epochMs: Long, zone: ZoneId): WatchTimeSlot =
        when (Instant.ofEpochMilli(epochMs).atZone(zone).hour) {
            in 5..11 -> WatchTimeSlot.MORNING
            in 12..17 -> WatchTimeSlot.DAYTIME
            in 18..22 -> WatchTimeSlot.EVENING
            else -> WatchTimeSlot.LATE_NIGHT
        }

    /** 단계를 모르는 기록(DB 버전 1)은 뺀다. 표본이 없는 단계도 행은 돌려준다. */
    private fun levelStopRates(delivered: List<InterventionRecord>): List<LevelStopRate> =
        (InterventionPolicyBounds.MIN_LEVEL..InterventionPolicyBounds.MAX_LEVEL).map { level ->
            val atLevel = delivered.filter { it.level == level }
            val answered = atLevel.count { it.outcome != null && it.outcome != InterventionOutcome.INTERRUPTED }
            val stopped = atLevel.count { it.outcome == InterventionOutcome.STOPPED }
            LevelStopRate(
                level, atLevel.size, answered, stopped,
                if (answered > 0) stopped.toDouble() / answered else null
            )
        }

    private fun chosenSessions(sessions: List<SessionRecord>, includeDemo: Boolean) =
        if (includeDemo) sessions else sessions.filterNot { it.isDemo }

    private fun chosen(interventions: List<InterventionRecord>, includeDemo: Boolean) =
        if (includeDemo) interventions else interventions.filterNot { it.isDemo }

    private fun weekStart(date: LocalDate): LocalDate =
        date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
}
