package com.example.shortsdetector.data.usage

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** 하루(기기 현지 시간 기준) 집계. */
data class DailyUsage(
    val date: LocalDate,
    /** 추정 시청시간(댓글창 포함) */
    val estimatedWatchMs: Long,
    /** 이날 시작한 세션 수 */
    val sessionCount: Int
)

/**
 * 세션 기록 → 일별 집계. 순수 함수.
 *
 * 자정을 넘긴 세션의 시청시간은 날짜별로 세션 벽시계 구간이 겹친 비율만큼 나눈다(세션 안의 정확한 시청 위치는
 * 저장하지 않으므로 근사값). 세션 수는 시작한 날에 센다. 날짜 경계는 [zone] 의 자정이다(서머타임 포함).
 */
object DailyUsageCalculator {

    fun aggregate(
        sessions: List<SessionRecord>,
        zone: ZoneId,
        from: LocalDate,
        toInclusive: LocalDate
    ): List<DailyUsage> {
        require(!toInclusive.isBefore(from)) { "range" }
        val days = generateSequence(from) { it.plusDays(1) }.takeWhile { !it.isAfter(toInclusive) }.toList()
        val watch = LongArray(days.size)
        val count = IntArray(days.size)

        for (s in sessions) {
            val startDate = dateOf(s.startedAtMs, zone)
            val idxStart = days.indexOf(startDate)
            if (idxStart >= 0) count[idxStart]++

            val start = s.startedAtMs
            val end = maxOf(s.endedAtMs, start)
            val span = end - start
            if (s.estimatedWatchMs <= 0L) continue
            if (span == 0L) {
                if (idxStart >= 0) watch[idxStart] += s.estimatedWatchMs
                continue
            }
            // 날짜별로 나누고, 반올림 오차는 마지막 겹친 날에 몰아 합이 정확히 맞게 한다.
            var assigned = 0L
            var lastIdx = -1
            for ((i, day) in days.withIndex()) {
                val dayStart = day.atStartOfDay(zone).toInstant().toEpochMilli()
                val dayEnd = day.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
                val overlap = minOf(end, dayEnd) - maxOf(start, dayStart)
                if (overlap <= 0L) continue
                val part = s.estimatedWatchMs * overlap / span
                watch[i] += part
                assigned += part
                lastIdx = i
            }
            // 범위 안에 세션 전체가 들어온 경우에만 오차를 보정한다.
            val inRange = idxStart >= 0 && days.contains(dateOf(end - 1, zone))
            if (inRange && lastIdx >= 0) watch[lastIdx] += s.estimatedWatchMs - assigned
        }
        return days.mapIndexed { i, d -> DailyUsage(d, watch[i], count[i]) }
    }

    fun dateOf(epochMs: Long, zone: ZoneId): LocalDate = Instant.ofEpochMilli(epochMs).atZone(zone).toLocalDate()
}
