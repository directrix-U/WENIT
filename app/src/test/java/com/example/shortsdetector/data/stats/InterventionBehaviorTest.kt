package com.example.shortsdetector.data.stats

import com.example.shortsdetector.ai.AgentInputJson
import com.example.shortsdetector.data.goal.Goal
import com.example.shortsdetector.data.profile.WatchTimeSlot
import com.example.shortsdetector.data.usage.DemoSeedData
import com.example.shortsdetector.data.usage.InterventionMethod
import com.example.shortsdetector.data.usage.InterventionOutcome
import com.example.shortsdetector.data.usage.InterventionRecord
import com.example.shortsdetector.data.usage.InterventionReturn
import com.example.shortsdetector.data.usage.InterventionUserReason
import com.example.shortsdetector.data.usage.SessionRecord
import com.example.shortsdetector.intervention.InterventionPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

class InterventionBehaviorTest {

    private val zone = ZoneId.of("Asia/Seoul")
    private val today = LocalDate.of(2026, 11, 5)
    private val now = today.atTime(20, 0).atZone(zone).toInstant().toEpochMilli()
    private val minute = 60_000L
    private var n = 0

    private fun at(hour: Int) = today.minusDays(1).atTime(hour, 0).atZone(zone).toInstant().toEpochMilli()

    private fun record(
        level: Int = 1,
        outcome: InterventionOutcome? = InterventionOutcome.CONTINUED,
        hour: Int = 20,
        reason: InterventionUserReason? = null,
        status: InterventionReturn? = null,
        back: Long? = null,
        method: InterventionMethod = InterventionMethod.POPUP,
        demo: Boolean = false
    ) = InterventionRecord(
        "i${++n}", null, at(hour), method, 0, 0, level = level, userReason = reason, outcome = outcome,
        returnStatus = status, shortsReturnMs = back, isDemo = demo
    )

    private fun behavior(records: List<InterventionRecord>, sessions: List<SessionRecord> = emptyList()) =
        UsageStatsCalculator.recent(sessions, records, zone, now).behavior

    private fun stop(status: InterventionReturn, back: Long? = null) =
        record(outcome = InterventionOutcome.STOPPED, status = status, back = back)

    @Test
    fun `level rates need five reactions`() {
        val four = behavior(List(4) { record() }).levelRates.first { it.level == 1 }
        assertEquals(4, four.samples)
        assertNull(four.stopRate)
        assertNull(four.continueRate)

        val five = behavior(List(3) { record() } + List(2) { record(outcome = InterventionOutcome.STOPPED) })
            .levelRates.first { it.level == 1 }
        assertEquals(0.4, five.stopRate!!, 1e-9)
        assertEquals(0.6, five.continueRate!!, 1e-9)
    }

    @Test
    fun `interrupted, suppressed and demo records stay out of the rates`() {
        val noise = listOf(
            record(outcome = InterventionOutcome.INTERRUPTED),
            record(method = InterventionMethod.SUPPRESSED_BY_COOLDOWN),
            record(method = InterventionMethod.SUPPRESSED_BY_DAILY_LIMIT),
            record(demo = true)
        )
        assertEquals(0, behavior(noise).levelRates.first { it.level == 1 }.samples)
    }

    @Test
    fun `returns give rates, a median and counts of the rest`() {
        val r = behavior(
            listOf(
                stop(InterventionReturn.SHORTS, 4 * minute),
                stop(InterventionReturn.SHORTS, 8 * minute),
                stop(InterventionReturn.SHORTS, 20 * minute),
                stop(InterventionReturn.SHORTS, 90 * minute),
                stop(InterventionReturn.APP_ONLY),
                stop(InterventionReturn.NONE)
            )
        ).returns
        assertEquals(6, r.samples)
        assertEquals(2.0 / 6, r.within10MinRate!!, 1e-9)
        assertEquals(3.0 / 6, r.within30MinRate!!, 1e-9)
        assertEquals(20 * minute, r.medianReturnMs)
        assertEquals(1, r.appOnly)
        assertEquals(1, r.notReturned)
    }

    @Test
    fun `few returns are marked and stops still waiting are not counted`() {
        val r = behavior(
            listOf(stop(InterventionReturn.SHORTS, minute), record(outcome = InterventionOutcome.STOPPED))
        ).returns
        assertEquals(1, r.samples)
        assertFalse(r.enough)
        assertNull(r.within10MinRate)
        assertNull(r.medianReturnMs)
    }

    @Test
    fun `reasons split by time slot and unanswered level 2 is counted apart`() {
        val b = behavior(
            List(5) { record(level = 2, hour = 23, reason = InterventionUserReason.BORED) } +
                record(level = 2, hour = 13, reason = InterventionUserReason.TIRED) +
                record(level = 2, outcome = InterventionOutcome.IGNORED, hour = 13)
        )
        assertEquals(1, b.lv2Unanswered)
        val night = b.lv2ReasonsBySlot.first { it.slot == WatchTimeSlot.LATE_NIGHT }
        assertTrue(night.enough)
        assertEquals(InterventionUserReason.BORED, night.reasons.single().reason)
        assertFalse(b.lv2ReasonsBySlot.first { it.slot == WatchTimeSlot.DAYTIME }.enough)
    }

    @Test
    fun `continue choices split by level and time slot`() {
        val b = behavior(List(4) { record(level = 1, hour = 23) } + record(level = 2, hour = 23) + record(level = 1, hour = 9))
        val night = b.continuedBySlot.first { it.slot == WatchTimeSlot.LATE_NIGHT }
        assertEquals(5, night.samples)
        assertEquals(listOf(1 to 4, 2 to 1, 3 to 0), night.levels)
        assertEquals(1, b.continuedBySlot.first { it.slot == WatchTimeSlot.MORNING }.samples)
    }

    // --- AI 입력 ---

    private fun input(records: List<InterventionRecord>, sessions: List<SessionRecord> = emptyList()) = AgentInputBuilder.build(
        null, emptyList<Goal>(), InterventionPolicy(), sessions, records, emptyList(), zone, now
    )

    @Test
    fun `small samples reach the agent as insufficient data with no rates`() {
        val json = AgentInputJson.toJson(input(listOf(stop(InterventionReturn.SHORTS, minute)))).getJSONObject("behavior")

        val returns = json.getJSONObject("returnAfterStop")
        assertEquals("INSUFFICIENT_DATA", returns.getString("status"))
        assertFalse(returns.has("within10MinRate"))
        assertFalse(returns.has("medianReturnMs"))
        assertFalse(returns.has("notReturned"))
        assertEquals("INSUFFICIENT_DATA", json.getJSONArray("levelRates").getJSONObject(1).getString("status"))
        assertEquals(5, json.getInt("minSamples"))
    }

    @Test
    fun `enough samples reach the agent as rounded aggregates only`() {
        val records = List(3) { stop(InterventionReturn.SHORTS, 4 * minute) } + List(4) { stop(InterventionReturn.NONE) }
        val text = AgentInputJson.toJson(input(records)).toString()
        val returns = AgentInputJson.toJson(input(records)).getJSONObject("behavior").getJSONObject("returnAfterStop")

        assertEquals("OK", returns.getString("status"))
        assertEquals(0.43, returns.getDouble("within10MinRate"), 1e-9)
        assertEquals(4 * minute, returns.getLong("medianReturnMs"))
        assertEquals(4, returns.getInt("notReturned"))
        assertFalse("i1" in text)
        assertFalse("shortsReturnMs" in text)
        assertFalse("occurredAtMs" in text)
    }

    // --- 시드 ---

    private fun seedInput(s: DemoSeedData.Scenario): AgentInput {
        val b = DemoSeedData.build(s, today, zone, now)
        return input(b.interventions, b.sessions)
    }

    @Test
    fun `scenario A comes back fast, mostly bored or tired at night`() {
        val b = seedInput(DemoSeedData.Scenario.LATE_NIGHT_SKIPPER).recent.behavior

        assertTrue(b.returns.enough)
        assertTrue(b.returns.within10MinRate!! >= 0.6)
        assertNotNull(b.returns.medianReturnMs)
        assertTrue(b.returns.medianReturnMs!! <= 10 * minute)
        assertEquals(0, b.returns.notReturned)
        val night = b.lv2ReasonsBySlot.first { it.slot == WatchTimeSlot.LATE_NIGHT }
        assertTrue(night.enough)
        val tiredOrBored = night.reasons
            .filter { it.reason == InterventionUserReason.BORED || it.reason == InterventionUserReason.TIRED }
            .sumOf { it.count }
        assertTrue(tiredOrBored > night.samples / 2)
    }

    @Test
    fun `scenario B mostly does not come back`() {
        val b = seedInput(DemoSeedData.Scenario.EASY_STOPPER).recent.behavior

        assertTrue(b.returns.enough)
        assertEquals(0.0, b.returns.within30MinRate!!, 1e-9)
        assertTrue(b.returns.notReturned > b.returns.samples / 2)
        assertTrue(b.returns.appOnly >= 1)
    }
}
