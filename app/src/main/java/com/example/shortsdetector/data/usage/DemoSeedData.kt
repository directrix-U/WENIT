package com.example.shortsdetector.data.usage

import java.time.LocalDate
import java.time.ZoneId

/**
 * 발표용 2주치 사용 기록. 실제 기록처럼 통계·에이전트가 읽도록 is_demo=0 이고 is_seed=1 로 구분한다.
 * 값은 고정 표에서 만들어 같은 날에 넣으면 같은 결과가 나온다. 화면 내용은 들어 있지 않다.
 *
 * 8~14일 전은 두 시나리오가 같은 평범한 패턴이고, 1~7일 전이 시나리오별 패턴이다. 에이전트는 최근 7일만 보고,
 * 앞 7일은 지난주 비교와 아낀 시간 기준값(첫 7일 평균)에 쓰인다.
 * 통계의 "이번 주"는 월요일에 시작하므로 월요일에 시연해도 비교가 나오도록 오늘 몫도 넣는다. 오늘 행에 개입은 없다.
 */
object DemoSeedData {

    enum class Scenario { LATE_NIGHT_SKIPPER, EASY_STOPPER }

    data class Batch(val sessions: List<SessionRecord>, val interventions: List<InterventionRecord>)

    private const val PACKAGE = "com.google.android.youtube"
    private const val THRESHOLD_MS = 180_000L
    private const val MS_PER_MINUTE = 60_000L

    /**
     * 개입 하나의 반응.
     * @param backMinutes 멈춘 뒤 쇼츠로 돌아오기까지 걸린 분
     * @param appMinutes 멈춘 뒤 쇼츠 없이 앱만 열기까지 걸린 분
     */
    private class Reaction(
        val level: Int,
        val outcome: InterventionOutcome,
        val reason: InterventionUserReason? = null,
        val backMinutes: Long? = null,
        val appMinutes: Long? = null
    ) {
        val returnStatus: InterventionReturn?
            get() = when {
                outcome != InterventionOutcome.STOPPED -> null
                backMinutes != null -> InterventionReturn.SHORTS
                appMinutes != null -> InterventionReturn.APP_ONLY
                else -> InterventionReturn.NONE
            }
    }

    private class Row(
        val daysAgo: Long,
        val hour: Int,
        val minute: Int,
        val watchMinutes: Long,
        val reactions: List<Reaction>
    )

    private val BORED = InterventionUserReason.BORED
    private val TIRED = InterventionUserReason.TIRED
    private val PROCRASTINATING = InterventionUserReason.PROCRASTINATING

    private fun c(level: Int, reason: InterventionUserReason? = null) = Reaction(level, InterventionOutcome.CONTINUED, reason)
    private fun i(level: Int) = Reaction(level, InterventionOutcome.IGNORED)
    private fun s(level: Int, reason: InterventionUserReason? = null, back: Long? = null, app: Long? = null) =
        Reaction(level, InterventionOutcome.STOPPED, reason, back, app)

    /** 지난주: 저녁에 하루 30분 안팎 */
    private val ordinaryWeek = listOf(
        Row(14, 19, 30, 22, listOf(s(1, back = 120))),
        Row(13, 20, 10, 28, listOf(c(1))),
        Row(12, 12, 40, 9, listOf()),
        Row(12, 21, 0, 18, listOf(s(1, app = 200))),
        Row(11, 19, 50, 30, listOf(c(1))),
        Row(10, 20, 30, 25, listOf(i(1))),
        Row(9, 14, 20, 12, listOf()),
        Row(9, 21, 10, 22, listOf(s(1, back = 90))),
        Row(8, 20, 0, 27, listOf(c(1)))
    )

    /** 밤에 길게 보고 알림을 자주 넘긴다. 멈춰도 금방 돌아오고, 이유는 심심해서·피곤해서가 많다 */
    private val lateNight = listOf(
        Row(7, 23, 10, 38, listOf(i(1), c(1), s(1, back = 6))),
        Row(6, 23, 40, 52, listOf(c(1), c(2, BORED), s(2, BORED, back = 4))),
        Row(6, 21, 30, 14, listOf(s(1, back = 8))),
        Row(5, 23, 5, 44, listOf(i(2), c(2, PROCRASTINATING), s(2, TIRED, back = 7))),
        Row(4, 22, 50, 61, listOf(i(1), c(1), c(2, BORED))),
        Row(3, 23, 30, 47, listOf(c(1), i(2), s(2, BORED, back = 12))),
        Row(2, 23, 15, 55, listOf(i(1), c(2, TIRED), s(2, TIRED, back = 5))),
        Row(2, 20, 40, 11, listOf(c(1))),
        Row(1, 23, 25, 49, listOf(c(1), s(2, TIRED, back = 25))),
        Row(0, 0, 30, 55, listOf())
    )

    /** 알림만 받아도 잘 멈추고, 멈춘 뒤 잘 돌아오지 않는다 */
    private val easyStopper = listOf(
        Row(7, 12, 20, 6, listOf(s(1))),
        Row(7, 19, 45, 8, listOf(s(1))),
        Row(6, 18, 30, 5, listOf(s(1, app = 240))),
        Row(5, 12, 40, 7, listOf(s(1))),
        Row(4, 20, 10, 9, listOf(c(1), s(1))),
        Row(3, 13, 5, 4, listOf()),
        Row(3, 21, 0, 6, listOf(s(1, back = 420))),
        Row(2, 19, 20, 7, listOf(s(1))),
        Row(1, 12, 50, 5, listOf(s(1, app = 300))),
        Row(0, 7, 30, 4, listOf())
    )

    fun build(scenario: Scenario, today: LocalDate, zone: ZoneId, nowMs: Long): Batch {
        val rows = ordinaryWeek + if (scenario == Scenario.LATE_NIGHT_SKIPPER) lateNight else easyStopper
        val sessions = ArrayList<SessionRecord>()
        val interventions = ArrayList<InterventionRecord>()
        rows.forEachIndexed { index, row ->
            val id = "seed-${scenario.name}-$index"
            val start = today.minusDays(row.daysAgo).atTime(row.hour, row.minute).atZone(zone).toInstant().toEpochMilli()
            val watch = row.watchMinutes * MS_PER_MINUTE
            if (start + watch >= nowMs) return@forEachIndexed      // 오늘 몫이 아직 오지 않은 시각이면 넣지 않는다
            sessions += SessionRecord(
                id, PACKAGE, "seed", start, minOf(start + watch, nowMs - 1), SessionStatus.CLOSED,
                watch, 0, 0, 0, "HIGH", EndReason, "ROOT_UNAVAILABLE", 0, isSeed = true
            )
            row.reactions.forEachIndexed { n, r ->
                val watched = THRESHOLD_MS * (n + 1)
                val stopped = r.outcome == InterventionOutcome.STOPPED
                interventions += InterventionRecord(
                    id = "$id-$n",
                    sessionId = id,
                    occurredAtMs = start + watched,
                    method = InterventionMethod.POPUP,
                    watchMs = watched,
                    thresholdMs = THRESHOLD_MS,
                    userAction = InterventionAction.OPEN_APP.takeIf { stopped },
                    actionAtMs = (start + watched).takeIf { stopped },
                    level = r.level,
                    userReason = r.reason,
                    outcome = r.outcome,
                    isSeed = true,
                    returnStatus = r.returnStatus,
                    shortsReturnMs = r.backMinutes?.times(MS_PER_MINUTE),
                    appReturnMs = r.appMinutes?.times(MS_PER_MINUTE)
                )
            }
        }
        return Batch(sessions, interventions)
    }

    private const val EndReason = "EXIT_CONFIRMED"
}
