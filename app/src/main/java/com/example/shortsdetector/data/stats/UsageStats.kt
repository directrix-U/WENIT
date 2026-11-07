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
import com.example.shortsdetector.data.usage.InterventionReturn
import com.example.shortsdetector.data.usage.InterventionUserReason
import com.example.shortsdetector.data.usage.SessionRecord
import com.example.shortsdetector.intervention.InterventionKind
import com.example.shortsdetector.intervention.InterventionLevels
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
    val levelStopRates: List<LevelStopRate>,
    /** 지난주 월~일 중 시청 기록이 있는 날 수 */
    val previousWeekRecordDays: Int
) {
    /** 지난주 기록이 적으면 비율이 의미 없이 커진다. 숫자를 깎지 않고 비교를 숨긴다. */
    val canCompare: Boolean get() = changeRatio != null && previousWeekRecordDays >= MIN_COMPARE_DAYS

    companion object {
        const val MIN_COMPARE_DAYS = 3
    }
}

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
    val lv2Reasons: List<ReasonCount>,
    val behavior: InterventionBehavior
)

/** 단계별 반응 비율. 표본이 적으면 비율은 null 이다. */
data class LevelRate(val level: Int, val samples: Int, val stopRate: Double?, val continueRate: Double?)

/** 스스로 멈춘 뒤의 복귀 집계. 표본은 결과가 정해진 기록만 센다. 표본이 적으면 비율과 중간값은 null 이다. */
data class ReturnStats(
    val samples: Int,
    val within10MinRate: Double?,
    val within30MinRate: Double?,
    val medianReturnMs: Long?,
    val appOnly: Int,
    val notReturned: Int
) {
    val enough: Boolean get() = samples >= InterventionBehavior.MIN_SAMPLES
}

data class SlotReasons(val slot: WatchTimeSlot, val samples: Int, val reasons: List<ReasonCount>) {
    val enough: Boolean get() = samples >= InterventionBehavior.MIN_SAMPLES
}

/** 시간대별 계속 보기 횟수. [levels] 는 단계 → 횟수. */
data class SlotContinued(val slot: WatchTimeSlot, val samples: Int, val levels: List<Pair<Int, Int>>) {
    val enough: Boolean get() = samples >= InterventionBehavior.MIN_SAMPLES
}

/** 개입 뒤 행동 집계. 에이전트 입력용이고 원본 기록은 담지 않는다. */
data class InterventionBehavior(
    val levelRates: List<LevelRate>,
    val returns: ReturnStats,
    /** Lv.2 에서 이유를 고르지 않고 지나간 횟수 */
    val lv2Unanswered: Int,
    val lv2ReasonsBySlot: List<SlotReasons>,
    val continuedBySlot: List<SlotContinued>
) {
    companion object {
        /** 이보다 적으면 "데이터 부족" 으로 표시한다 */
        const val MIN_SAMPLES = 5
    }
}

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
    private const val TEN_MINUTES_MS = 600_000L
    private const val THIRTY_MINUTES_MS = 1_800_000L

    /** 사용자의 반응. INTERRUPTED 는 앱이 닫은 것이라 뺀다. */
    private val REACTED = setOf(InterventionOutcome.STOPPED, InterventionOutcome.CONTINUED, InterventionOutcome.IGNORED)

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
        val recordDays = DailyUsageCalculator
            .aggregate(counted, zone, monday.minusWeeks(1), monday.minusDays(1))
            .count { it.estimatedWatchMs > 0L }
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
            levelStopRates = levelStopRates(thisWeek),
            previousWeekRecordDays = recordDays
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
            lv2Reasons = delivered.filter(::isQuestion).mapNotNull { it.userReason }
                .groupingBy { it }.eachCount().map { (reason, n) -> ReasonCount(reason, n) }
                .sortedByDescending { it.count },
            behavior = behavior(delivered, zone)
        )
    }

    private fun behavior(delivered: List<InterventionRecord>, zone: ZoneId): InterventionBehavior {
        val min = InterventionBehavior.MIN_SAMPLES
        val levels = InterventionPolicyBounds.MIN_LEVEL..InterventionPolicyBounds.MAX_LEVEL
        val levelRates = levels.map { level ->
            val answered = delivered.filter { it.level == level && it.outcome in REACTED }
            val stopped = answered.count { it.outcome == InterventionOutcome.STOPPED }
            val continued = answered.count { it.outcome == InterventionOutcome.CONTINUED }
            val n = answered.size
            LevelRate(
                level, n,
                (stopped.toDouble() / n).takeIf { n >= min },
                (continued.toDouble() / n).takeIf { n >= min }
            )
        }
        val lv2 = delivered.filter(::isQuestion)
        val continued = delivered.filter { it.outcome == InterventionOutcome.CONTINUED && it.level != null }
        return InterventionBehavior(
            levelRates = levelRates,
            returns = returnStats(delivered),
            lv2Unanswered = lv2.count { it.userReason == null && it.outcome in REACTED },
            lv2ReasonsBySlot = WatchTimeSlot.entries.map { slot ->
                val chosen = lv2.filter { slotOf(it.occurredAtMs, zone) == slot }.mapNotNull { it.userReason }
                val counts = chosen.groupingBy { it }.eachCount().map { (reason, n) -> ReasonCount(reason, n) }
                SlotReasons(slot, chosen.size, counts.sortedByDescending { it.count })
            },
            continuedBySlot = WatchTimeSlot.entries.map { slot ->
                val inSlot = continued.filter { slotOf(it.occurredAtMs, zone) == slot }
                SlotContinued(slot, inSlot.size, levels.map { l -> l to inSlot.count { it.level == l } })
            }
        )
    }

    private fun isQuestion(r: InterventionRecord) =
        r.level?.let { InterventionLevels.of(it).kind == InterventionKind.QUESTION } == true

    private fun returnStats(delivered: List<InterventionRecord>): ReturnStats {
        val finished = delivered.filter { it.returnStatus != null }
        val back = finished.filter { it.returnStatus == InterventionReturn.SHORTS }.mapNotNull { it.shortsReturnMs }.sorted()
        val n = finished.size
        val enough = n >= InterventionBehavior.MIN_SAMPLES
        return ReturnStats(
            samples = n,
            within10MinRate = (back.count { it <= TEN_MINUTES_MS }.toDouble() / n).takeIf { enough },
            within30MinRate = (back.count { it <= THIRTY_MINUTES_MS }.toDouble() / n).takeIf { enough },
            medianReturnMs = back.getOrNull(back.size / 2).takeIf { enough },
            appOnly = finished.count { it.returnStatus == InterventionReturn.APP_ONLY },
            notReturned = finished.count { it.returnStatus == InterventionReturn.NONE }
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
