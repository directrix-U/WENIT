package com.example.shortsdetector.data.usage

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

class DailyUsageCalculatorTest {

    private val seoul = ZoneId.of("Asia/Seoul")
    private val utc = ZoneId.of("UTC")

    private fun at(zone: ZoneId, y: Int, m: Int, d: Int, h: Int, min: Int = 0) =
        LocalDateTime.of(y, m, d, h, min).atZone(zone).toInstant().toEpochMilli()

    private fun session(start: Long, end: Long, watch: Long, id: String = "s$start") = SessionRecord(
        id, "com.google.android.youtube", "yt-2", start, end, SessionStatus.CLOSED,
        watch, 0, 0, 0, "HIGH", "EXIT_CONFIRMED", null, 0
    )

    private val d1 = LocalDate.of(2026, 11, 1)
    private val d2 = LocalDate.of(2026, 11, 2)

    @Test
    fun `sessions within one day are summed`() {
        val s = listOf(
            session(at(seoul, 2026, 11, 1, 9), at(seoul, 2026, 11, 1, 9, 10), 500_000),
            session(at(seoul, 2026, 11, 1, 21), at(seoul, 2026, 11, 1, 21, 5), 250_000)
        )
        val r = DailyUsageCalculator.aggregate(s, seoul, d1, d2)
        assertEquals(DailyUsage(d1, 750_000, 2), r[0])
        assertEquals(DailyUsage(d2, 0, 0), r[1])
    }

    @Test
    fun `session across local midnight is split by overlap and sums exactly`() {
        // 23:50 ~ 00:10 (20분), 시청 1_000_001ms → 절반씩, 나머지 1ms 는 마지막 날
        val s = session(at(seoul, 2026, 11, 1, 23, 50), at(seoul, 2026, 11, 2, 0, 10), 1_000_001)
        val r = DailyUsageCalculator.aggregate(listOf(s), seoul, d1, d2)
        assertEquals(500_000, r[0].estimatedWatchMs)
        assertEquals(500_001, r[1].estimatedWatchMs)
        assertEquals(1, r[0].sessionCount)            // 시작한 날에만 센다
        assertEquals(0, r[1].sessionCount)
    }

    @Test
    fun `day boundary follows the given time zone`() {
        // 서울 2026-11-02 08:00 = UTC 2026-11-01 23:00
        val s = session(at(seoul, 2026, 11, 2, 8), at(seoul, 2026, 11, 2, 8, 5), 60_000)
        assertEquals(60_000, DailyUsageCalculator.aggregate(listOf(s), seoul, d1, d2)[1].estimatedWatchMs)
        assertEquals(60_000, DailyUsageCalculator.aggregate(listOf(s), utc, d1, d2)[0].estimatedWatchMs)
    }

    @Test
    fun `daylight saving day is 23 hours and still splits correctly`() {
        val ny = ZoneId.of("America/New_York")
        val dst = LocalDate.of(2026, 3, 8)             // 02:00 → 03:00
        val s = session(at(ny, 2026, 3, 8, 1, 30), at(ny, 2026, 3, 8, 3, 30), 120_000)
        val r = DailyUsageCalculator.aggregate(listOf(s), ny, dst, dst)
        assertEquals(120_000, r.single().estimatedWatchMs)
        assertEquals(1, r.single().sessionCount)
    }

    @Test
    fun `zero-length session goes to its start day and range clips outside parts`() {
        val zero = session(at(seoul, 2026, 11, 1, 12), at(seoul, 2026, 11, 1, 12), 5_000, "zero")
        // 10월 31일 23:00 ~ 11월 1일 01:00, 범위는 11월 1일만 → 겹친 절반만
        val before = session(at(seoul, 2026, 10, 31, 23), at(seoul, 2026, 11, 1, 1), 100_000, "before")
        val r = DailyUsageCalculator.aggregate(listOf(zero, before), seoul, d1, d1).single()
        assertEquals(5_000 + 50_000, r.estimatedWatchMs)
        assertEquals(1, r.sessionCount)                // "before" 는 10월 31일 시작
    }
}
