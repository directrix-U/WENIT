package com.example.shortsdetector.data.usage

import java.time.LocalDate
import java.time.ZoneId

/**
 * 발표용 7일치 사용 기록. 실제 기록처럼 통계·에이전트가 읽도록 is_demo=0 이고 is_seed=1 로 구분한다.
 * 값은 고정 표에서 만들어 같은 날에 넣으면 같은 결과가 나온다. 화면 내용은 들어 있지 않다.
 */
object DemoSeedData {

    enum class Scenario { LATE_NIGHT_SKIPPER, EASY_STOPPER }

    data class Batch(val sessions: List<SessionRecord>, val interventions: List<InterventionRecord>)

    private const val PACKAGE = "com.google.android.youtube"
    private const val THRESHOLD_MS = 180_000L
    private const val MS_PER_MINUTE = 60_000L

    private class Row(
        val daysAgo: Long,
        val hour: Int,
        val minute: Int,
        val watchMinutes: Long,
        /** 개입 하나당 (단계, 결과, 이유) */
        val reactions: List<Triple<Int, InterventionOutcome, InterventionUserReason?>>
    )

    private val C = InterventionOutcome.CONTINUED
    private val I = InterventionOutcome.IGNORED
    private val S = InterventionOutcome.STOPPED

    /** 밤에 길게 보고 알림을 자주 넘긴다 */
    private val lateNight = listOf(
        Row(7, 23, 10, 38, listOf(Triple(1, I, null), Triple(1, C, null))),
        Row(6, 23, 40, 52, listOf(Triple(1, C, null), Triple(2, C, InterventionUserReason.BORED))),
        Row(6, 21, 30, 14, listOf(Triple(1, S, null))),
        Row(5, 23, 5, 44, listOf(Triple(2, I, null), Triple(2, C, InterventionUserReason.PROCRASTINATING))),
        Row(4, 22, 50, 61, listOf(Triple(1, I, null), Triple(1, C, null), Triple(2, C, InterventionUserReason.BORED))),
        Row(3, 23, 30, 47, listOf(Triple(1, C, null), Triple(2, I, null))),
        Row(2, 23, 15, 55, listOf(Triple(1, I, null), Triple(2, C, InterventionUserReason.TIRED))),
        Row(2, 20, 40, 11, listOf(Triple(1, C, null))),
        Row(1, 23, 25, 49, listOf(Triple(1, C, null), Triple(2, S, InterventionUserReason.TIRED)))
    )

    /** 알림만 받아도 잘 멈춘다 */
    private val easyStopper = listOf(
        Row(7, 12, 20, 6, listOf(Triple(1, S, null))),
        Row(7, 19, 45, 8, listOf(Triple(1, S, null))),
        Row(6, 18, 30, 5, listOf(Triple(1, S, null))),
        Row(5, 12, 40, 7, listOf(Triple(1, S, null))),
        Row(4, 20, 10, 9, listOf(Triple(1, C, null), Triple(1, S, null))),
        Row(3, 13, 5, 4, listOf()),
        Row(3, 21, 0, 6, listOf(Triple(1, S, null))),
        Row(2, 19, 20, 7, listOf(Triple(1, S, null))),
        Row(1, 12, 50, 5, listOf(Triple(1, S, null)))
    )

    fun build(scenario: Scenario, today: LocalDate, zone: ZoneId, nowMs: Long): Batch {
        val rows = if (scenario == Scenario.LATE_NIGHT_SKIPPER) lateNight else easyStopper
        val sessions = ArrayList<SessionRecord>()
        val interventions = ArrayList<InterventionRecord>()
        rows.forEachIndexed { index, row ->
            val id = "seed-${scenario.name}-$index"
            val start = today.minusDays(row.daysAgo).atTime(row.hour, row.minute).atZone(zone).toInstant().toEpochMilli()
            val watch = row.watchMinutes * MS_PER_MINUTE
            sessions += SessionRecord(
                id, PACKAGE, "seed", start, minOf(start + watch, nowMs - 1), SessionStatus.CLOSED,
                watch, 0, 0, 0, "HIGH", EndReason, "ROOT_UNAVAILABLE", 0, isSeed = true
            )
            row.reactions.forEachIndexed { n, (level, outcome, reason) ->
                val watched = THRESHOLD_MS * (n + 1)
                interventions += InterventionRecord(
                    id = "$id-$n",
                    sessionId = id,
                    occurredAtMs = start + watched,
                    method = InterventionMethod.POPUP,
                    watchMs = watched,
                    thresholdMs = THRESHOLD_MS,
                    level = level,
                    userReason = reason,
                    outcome = outcome,
                    isSeed = true
                )
            }
        }
        return Batch(sessions, interventions)
    }

    private const val EndReason = "EXIT_CONFIRMED"
}
