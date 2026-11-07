package com.example.shortsdetector.ui

import com.example.shortsdetector.ai.AgentInputJson
import com.example.shortsdetector.ai.AiFailureKind
import com.example.shortsdetector.ai.GoalRecommendation
import com.example.shortsdetector.ai.agent.AgentMessages
import com.example.shortsdetector.ai.agent.AgentRunResult
import com.example.shortsdetector.ai.agent.AgentRunStatus
import com.example.shortsdetector.ai.agent.PolicyAgent
import com.example.shortsdetector.data.goal.Goal
import com.example.shortsdetector.data.goal.GoalRules
import com.example.shortsdetector.data.goal.GoalSource
import com.example.shortsdetector.data.goal.GoalStatus
import com.example.shortsdetector.data.goal.GoalType
import com.example.shortsdetector.data.profile.AgeBand
import com.example.shortsdetector.data.profile.Gender
import com.example.shortsdetector.data.profile.ShortformApp
import com.example.shortsdetector.data.profile.UserProfile
import com.example.shortsdetector.data.profile.UsualDailyWatch
import com.example.shortsdetector.data.profile.WatchTimeSlot
import com.example.shortsdetector.data.stats.AgentInputBuilder
import com.example.shortsdetector.data.stats.SavedTimeBaseline
import com.example.shortsdetector.data.usage.DemoSeedData
import com.example.shortsdetector.data.usage.InterventionMethod
import com.example.shortsdetector.data.usage.InterventionOutcome
import com.example.shortsdetector.data.usage.InterventionRecord
import com.example.shortsdetector.data.usage.PolicyChangeRecord
import com.example.shortsdetector.data.usage.PolicyChangeSource
import com.example.shortsdetector.data.usage.PolicyChangeStatus
import com.example.shortsdetector.data.usage.SessionRecord
import com.example.shortsdetector.data.usage.SessionStatus
import com.example.shortsdetector.intervention.InterventionCoordinator
import com.example.shortsdetector.intervention.InterventionPolicy
import com.example.shortsdetector.intervention.ResolvedPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZoneId

class ViewModelsTest {

    private val zone = ZoneId.of("Asia/Seoul")
    private val today = LocalDate.of(2026, 11, 5)
    private val now = today.atTime(20, 0).atZone(zone).toInstant().toEpochMilli()
    private val minute = 60_000L

    private object Direct : TaskRunner {
        override fun <T> run(work: () -> T, failed: () -> Unit, done: (T) -> Unit) {
            val r = try {
                work()
            } catch (e: IllegalStateException) {
                failed()
                return
            }
            done(r)
        }
    }

    private inner class FakeRepo : AppRepository {
        var seedData = false
        var lastResult: String? = null
        var savedProfile: UserProfile? = null
        var onboardingDone = false
        var consent = false
        val goalList = ArrayList<Goal>()
        var currentPolicy = InterventionPolicy()
        var demoOn = false
        var agentOn = true
        var agentDue = false
        var agentRuns = 0
        var reverted = 0
        val sessionList = ArrayList<SessionRecord>()
        val interventionList = ArrayList<InterventionRecord>()
        val changes = ArrayList<PolicyChangeRecord>()
        var firstWeek: Long? = null
        var recommendation = GoalRecommendation(presets(), null, null, 1)
        var recommendFails = false
        var permission = PermissionStatus(true, true, true)
        var deleted = 0
        var resets = 0

        fun presets() = listOf(
            Goal(GoalType.DAILY_TOTAL, 60, "하루 60분", "이유", GoalSource.AI),
            Goal(GoalType.PER_SESSION, 10, "한 번에 10분", "이유", GoalSource.AI),
            Goal(GoalType.SELF_STOPS, 1, "하루 한 번", "이유", GoalSource.AI)
        )

        var clockMs = this@ViewModelsTest.now
        var demoThreshold = 10_000L
        var restarts = 0
        override fun nowMs() = clockMs
        override fun zone() = this@ViewModelsTest.zone
        override fun profile() = savedProfile
        override fun saveProfile(profile: UserProfile) { savedProfile = profile }
        override fun isOnboardingDone() = onboardingDone
        override fun markOnboardingDone() { onboardingDone = true }
        override fun isAiConsentGiven() = consent
        override fun setAiConsent(given: Boolean) { consent = given }
        override fun goals() = goalList.toList()
        override fun putGoal(goal: Goal): Boolean {
            val next = GoalRules.put(goalList, goal) ?: return false
            goalList.clear()
            goalList.addAll(next)
            return true
        }
        override fun removeGoal(type: GoalType) { goalList.removeAll { it.type == type } }
        override fun savedPolicy() = currentPolicy
        override fun resolvedPolicy() = ResolvedPolicy(currentPolicy, currentPolicy.effectiveThresholdMs(goalList), demoOn)
        override fun setMaxLevel(level: Int) { currentPolicy = currentPolicy.copy(maxLevel = level) }
        override fun setThreshold(ms: Long) { currentPolicy = currentPolicy.copy(thresholdMs = ms) }
        override fun isAgentEnabled() = agentOn
        override fun setAgentEnabled(enabled: Boolean) { agentOn = enabled }
        override fun agentMessages(): AgentMessages? = null
        override fun lastAgentResult(): String? = lastResult
        override fun isAgentDue() = agentDue
        override fun runAgent(): AgentRunResult {
            agentRuns++
            agentDue = false
            return AgentRunResult(AgentRunStatus.KEPT, currentPolicy, currentPolicy, 1)
        }
        override fun revertAgentChange(): PolicyChangeRecord? {
            reverted++
            val latest = changes.firstOrNull { it.source == PolicyChangeSource.AGENT } ?: return null
            changes[changes.indexOf(latest)] = latest.copy(status = PolicyChangeStatus.REVERTED_BY_USER)
            return latest
        }
        override fun isDemoMode() = demoOn
        override fun setDemoMode(enabled: Boolean) { demoOn = enabled }
        override fun demoThresholdMs() = demoThreshold
        override fun setDemoThresholdMs(ms: Long) { demoThreshold = ms }
        override fun restartOnboarding() { restarts++ }
        override fun isPopupEnabled() = true
        override fun setPopupEnabled(enabled: Boolean) = Unit
        override fun isYouTubeEnabled() = true
        override fun setYouTubeEnabled(enabled: Boolean) = Unit
        override fun isInstagramEnabled() = false
        override fun setInstagramEnabled(enabled: Boolean) = Unit
        override fun permissions() = permission
        override fun sessions(fromMs: Long, toMs: Long) = sessionList.toList()
        override fun interventions(fromMs: Long, toMs: Long) = interventionList.toList()
        override fun policyChanges(limit: Int) = changes.take(limit)
        override fun firstWeekAverageMs() = firstWeek
        override fun recommendGoals(): GoalRecommendation {
            check(!recommendFails)
            return recommendation
        }
        override fun deleteAllRecords() { deleted++ }
        override fun resetForDemo() { resets++ }
        override fun insertSeed(scenario: DemoSeedData.Scenario) = 7
        override fun hasSeedData() = seedData
        override fun deleteSeedData() {
            seedData = false
            lastResult = null
        }
        override fun runGoalEval() = 15
        override fun recordNotificationTap(interventionId: String) = Unit
    }

    private val repo = FakeRepo()

    private fun at(d: LocalDate, h: Int) = d.atTime(h, 0).atZone(zone).toInstant().toEpochMilli()

    private fun session(start: Long, watch: Long, demo: Boolean = false) = SessionRecord(
        "s$start", "pkg", "yt-2", start, start + watch, SessionStatus.CLOSED, watch, 0, 0, 0, "HIGH", null, null, 0, isDemo = demo
    )

    // --- 온보딩 설문 ---

    @Test
    fun `survey needs an answer on every step and can go back`() {
        val vm = SurveyViewModel(repo)
        assertFalse(vm.state.value.canGoNext)
        vm.next()
        assertEquals(0, vm.state.value.stepIndex)

        vm.selectAge(AgeBand.TWENTIES)
        vm.next()
        assertEquals(SurveyStep.GENDER, vm.state.value.step)
        vm.back()
        assertEquals(SurveyStep.AGE, vm.state.value.step)
        assertEquals(AgeBand.TWENTIES, vm.state.value.ageBand)
    }

    @Test
    fun `finishing the survey saves every answer with no-answer gender available`() {
        val vm = SurveyViewModel(repo)
        repo.consent = true
        vm.selectAge(AgeBand.THIRTIES); vm.next()
        vm.selectGender(Gender.NO_ANSWER); vm.next()
        vm.toggleApp(ShortformApp.YOUTUBE_SHORTS); vm.toggleApp(ShortformApp.OTHER); vm.toggleApp(ShortformApp.OTHER); vm.next()
        vm.toggleSlot(WatchTimeSlot.LATE_NIGHT); vm.next()
        vm.toggleDay(DayOfWeek.FRIDAY); vm.next()
        assertTrue(vm.state.value.isLast)
        assertFalse(vm.state.value.finished)
        vm.selectUsualWatch(UsualDailyWatch.HOUR_1_TO_2); vm.next()

        assertTrue(vm.state.value.finished)
        assertEquals(
            UserProfile(
                AgeBand.THIRTIES, Gender.NO_ANSWER, setOf(ShortformApp.YOUTUBE_SHORTS), setOf(WatchTimeSlot.LATE_NIGHT),
                setOf(DayOfWeek.FRIDAY), UsualDailyWatch.HOUR_1_TO_2, aiRecommendationConsent = true
            ),
            repo.savedProfile
        )
    }

    @Test
    fun `multi choice steps need at least one and unselecting removes the answer`() {
        val vm = SurveyViewModel(repo)
        vm.selectAge(AgeBand.TEENS); vm.next()
        vm.selectGender(Gender.FEMALE); vm.next()
        vm.toggleApp(ShortformApp.OTHER)
        assertTrue(vm.state.value.canGoNext)
        vm.toggleApp(ShortformApp.OTHER)
        assertFalse(vm.state.value.canGoNext)
    }

    @Test
    fun `consent has no default and stores only the choice`() {
        val vm = ConsentViewModel(repo)
        assertNull(vm.state.value)
        assertFalse(repo.consent)
        vm.decide(true)
        assertTrue(repo.consent)
        vm.decide(false)
        assertFalse(repo.consent)
        assertEquals(false, vm.state.value)
    }

    @Test
    fun `permission state refreshes when returning`() {
        repo.permission = PermissionStatus(false, false, false)
        val vm = PermissionsViewModel(repo)
        assertFalse(vm.state.value.detecting)
        repo.permission = PermissionStatus(true, false, true)
        vm.refresh()
        assertTrue(vm.state.value.detecting)
    }

    @Test
    fun `accessibility on without a connected service is not detecting`() {
        assertFalse(PermissionStatus(true, true, false).detecting)
    }

    // --- 목표 선택 ---

    @Test
    fun `goal pick loads once and shows the cards`() {
        val vm = GoalPickViewModel(repo, Direct)
        assertTrue(vm.state.value.loading)
        vm.load()
        assertFalse(vm.state.value.loading)
        assertEquals(3, vm.state.value.cards.size)
        repo.recommendFails = true
        vm.load()
        assertEquals(3, vm.state.value.cards.size)
    }

    @Test
    fun `selection stops at three counting goals already saved elsewhere`() {
        repo.goalList += GoalRules.userGoal(GoalType.DAILY_OPENS, 5)
        val vm = GoalPickViewModel(repo, Direct)
        vm.load()
        vm.toggle(GoalType.DAILY_TOTAL)
        vm.toggle(GoalType.PER_SESSION)
        assertFalse(vm.state.value.canSelectMore)
        vm.toggle(GoalType.SELF_STOPS)
        assertEquals(setOf(GoalType.DAILY_TOTAL, GoalType.PER_SESSION), vm.state.value.selected)

        vm.toggle(GoalType.PER_SESSION)
        vm.toggle(GoalType.SELF_STOPS)
        assertEquals(setOf(GoalType.DAILY_TOTAL, GoalType.SELF_STOPS), vm.state.value.selected)
    }

    @Test
    fun `saving replaces a goal of the same type and finishes onboarding`() {
        repo.goalList += GoalRules.userGoal(GoalType.DAILY_TOTAL, 200)
        val vm = GoalPickViewModel(repo, Direct)
        vm.load()
        vm.toggle(GoalType.DAILY_TOTAL)
        vm.save()
        assertEquals(60, repo.goalList.single().value)
        assertTrue(repo.onboardingDone)
        assertTrue(vm.state.value.finished)
    }

    @Test
    fun `finishing with nothing selected is allowed`() {
        val vm = GoalPickViewModel(repo, Direct)
        vm.load()
        vm.save()
        assertTrue(repo.goalList.isEmpty())
        assertTrue(repo.onboardingDone)
    }

    @Test
    fun `AI failure shows the failure kind with preset cards`() {
        val presets = repo.presets().map { it.copy(source = GoalSource.PRESET) }
        repo.recommendation = GoalRecommendation(presets, AiFailureKind.TIMEOUT, null, 15_000)
        val vm = GoalPickViewModel(repo, Direct)
        vm.load()
        assertEquals(AiFailureKind.TIMEOUT, vm.state.value.failure)
        assertTrue(vm.state.value.cards.all { it.source == GoalSource.PRESET })
    }

    @Test
    fun `unexpected error ends loading`() {
        repo.recommendFails = true
        val vm = GoalPickViewModel(repo, Direct)
        vm.load()
        assertFalse(vm.state.value.loading)
    }

    @Test
    fun `custom goal validates the range and only saves valid values`() {
        val vm = CustomGoalViewModel(repo)
        vm.selectType(GoalType.DAILY_TOTAL)
        vm.setNumber("1000")
        assertFalse(vm.state.value.isValid)
        vm.save()
        assertTrue(repo.goalList.isEmpty())

        vm.setNumber("6a0")
        assertEquals("60", vm.state.value.number)
        assertTrue(vm.state.value.isValid)
        vm.save()
        assertEquals(GoalSource.USER, repo.goalList.single().source)
        assertTrue(vm.state.value.saved)
    }

    @Test
    fun `time goal uses hour and minute, past midnight continues, out of window is refused`() {
        val vm = CustomGoalViewModel(repo)
        vm.selectType(GoalType.TIME_WINDOW)
        vm.setHour("23"); vm.setMinute("30")
        assertEquals(1410, vm.state.value.value)
        assertTrue(vm.state.value.isValid)
        vm.setHour("1"); vm.setMinute("0")
        assertEquals(1500, vm.state.value.value)
        assertTrue(vm.state.value.isValid)
        vm.setHour("10")
        assertFalse(vm.state.value.isValid)
        vm.setHour("24")
        assertNull(vm.state.value.value)
    }

    @Test
    fun `fourth goal reports full and keeps the screen open`() {
        listOf(GoalType.DAILY_TOTAL, GoalType.PER_SESSION, GoalType.SELF_STOPS).forEach { repo.putGoal(GoalRules.userGoal(it, 5)) }
        val vm = CustomGoalViewModel(repo)
        vm.selectType(GoalType.DAILY_OPENS)
        vm.setNumber("5")
        vm.save()
        assertTrue(vm.state.value.full)
        assertFalse(vm.state.value.saved)
    }

    // --- 홈 ---

    @Test
    fun `first day has no records, no yesterday and shows the empty state`() {
        val vm = HomeViewModel(repo, Direct)
        vm.refresh()
        val s = vm.state.value
        assertFalse(s.loading)
        assertFalse(s.hasRecords)
        assertNull(s.yesterday)
        assertNull(s.savedTime)
        assertEquals(0, s.watchMs)
    }

    @Test
    fun `home computes watch time, badges, level and last reason, leaving demo out`() {
        repo.sessionList += session(at(today, 9), 40 * minute)
        repo.sessionList += session(at(today, 10), 500 * minute, demo = true)
        repo.goalList += GoalRules.userGoal(GoalType.DAILY_TOTAL, 30)
        repo.currentPolicy = InterventionPolicy(level = 2)
        repo.changes += PolicyChangeRecord("d", now, PolicyChangeSource.RULE, null, repo.currentPolicy, "시연 이유", null, PolicyChangeStatus.APPLIED, isDemo = true)
        repo.changes += PolicyChangeRecord("r", now, PolicyChangeSource.AGENT, null, repo.currentPolicy, "진짜 이유", null, PolicyChangeStatus.APPLIED)
        val vm = HomeViewModel(repo, Direct)
        vm.refresh()
        val s = vm.state.value
        assertEquals(40 * minute, s.watchMs)
        assertEquals(GoalStatus.EXCEEDED, s.goals.single().status)
        assertEquals(2, s.level)
        assertEquals("진짜 이유", s.lastChangeReason)
    }

    @Test
    fun `yesterday line counts achieved goals once there is history`() {
        repo.sessionList += session(at(today.minusDays(1), 12), 10 * minute)
        repo.goalList += GoalRules.userGoal(GoalType.DAILY_TOTAL, 30)
        repo.goalList += GoalRules.userGoal(GoalType.SELF_STOPS, 1)
        val vm = HomeViewModel(repo, Direct)
        vm.refresh()
        assertEquals(YesterdaySummary(1, 2), vm.state.value.yesterday)
    }

    @Test
    fun `saved time uses the survey first, then the first week, and is marked as an estimate`() {
        repo.savedProfile = UserProfile(AgeBand.TWENTIES, usualDailyWatch = UsualDailyWatch.HOUR_1_TO_2)
        repo.sessionList += session(at(today, 9), 30 * minute)
        val vm = HomeViewModel(repo, Direct)
        vm.refresh()
        assertEquals(60 * minute, vm.state.value.savedTime!!.savedMs)
        assertEquals(SavedTimeBaseline.SURVEY, vm.state.value.savedTime!!.baseline)
        assertTrue(vm.state.value.savedTime!!.isEstimate)

        repo.firstWeek = 40 * minute
        vm.refresh()
        assertEquals(10 * minute, vm.state.value.savedTime!!.savedMs)
        assertEquals(SavedTimeBaseline.FIRST_WEEK, vm.state.value.savedTime!!.baseline)
    }

    @Test
    fun `stop card survives a refresh until dismissed`() {
        val vm = HomeViewModel(repo, Direct)
        vm.showStopped(3 * minute)
        vm.refresh()
        assertEquals(3 * minute, vm.state.value.stoppedAtMs)
        vm.dismissStopped()
        assertNull(vm.state.value.stoppedAtMs)
    }

    @Test
    fun `stop card goes away when the date changes`() {
        val vm = HomeViewModel(repo, Direct)
        vm.showStopped(3 * minute)
        repo.clockMs = at(today.plusDays(1), 9)
        vm.refresh()
        assertNull(vm.state.value.stoppedAtMs)
    }

    @Test
    fun `stop card goes away when a new session starts, including a demo one`() {
        for (demo in listOf(false, true)) {
            val vm = HomeViewModel(repo, Direct)
            repo.sessionList.clear()
            vm.showStopped(3 * minute)
            repo.sessionList += session(repo.clockMs + 60_000, minute, demo)
            repo.clockMs += 120_000
            vm.refresh()
            assertNull(vm.state.value.stoppedAtMs)
        }
    }

    @Test
    fun `the session that was stopped does not clear its own card`() {
        repo.sessionList += session(repo.clockMs - 5 * minute, 5 * minute)
        val vm = HomeViewModel(repo, Direct)
        vm.showStopped(5 * minute)
        repo.clockMs += minute
        vm.refresh()
        assertEquals(5 * minute, vm.state.value.stoppedAtMs)
    }

    @Test
    fun `home warns when the service is off and runs the agent when due`() {
        repo.permission = PermissionStatus(false, true, false)
        repo.agentDue = true
        val vm = HomeViewModel(repo, Direct)
        vm.refresh()
        assertFalse(vm.state.value.permissions.detecting)
        assertEquals(1, repo.agentRuns)
        vm.refresh()
        assertEquals(1, repo.agentRuns)
    }

    // --- 기록 ---

    private fun change(id: String, source: PolicyChangeSource, status: PolicyChangeStatus = PolicyChangeStatus.APPLIED) =
        PolicyChangeRecord(id, now, source, InterventionPolicy(), InterventionPolicy(thresholdMs = 60_000), "이유", null, status)

    @Test
    fun `demo mode home shows what was just watched`() {
        repo.demoOn = true
        repo.sessionList += session(at(today, 9), 395_000L, demo = true)
        val vm = HomeViewModel(repo, Direct)
        vm.refresh()
        assertEquals(395_000L, vm.state.value.watchMs)
    }

    @Test
    fun `demo sessions stay out of home once demo mode is off`() {
        repo.sessionList += session(at(today, 9), 395_000L, demo = true)
        val vm = HomeViewModel(repo, Direct)
        vm.refresh()
        assertEquals(0L, vm.state.value.watchMs)
    }

    @Test
    fun `demo mode records screen shows demo watch time and interventions`() {
        repo.demoOn = true
        repo.sessionList += session(at(today, 9), 395_000L, demo = true)
        repo.interventionList += InterventionRecord(
            "i", null, at(today, 9), InterventionMethod.POPUP, 1, 1, level = 1, isDemo = true
        )
        val vm = RecordsViewModel(repo, Direct)
        vm.refresh()
        assertEquals(395_000L, vm.state.value.week?.totalWatchMs)
        assertEquals(1, vm.state.value.interventions.size)
    }

    @Test
    fun `demo records stay out of the records screen once demo mode is off`() {
        repo.sessionList += session(at(today, 9), 395_000L, demo = true)
        repo.interventionList += InterventionRecord(
            "i", null, at(today, 9), InterventionMethod.POPUP, 1, 1, level = 1, isDemo = true
        )
        val vm = RecordsViewModel(repo, Direct)
        vm.refresh()
        assertEquals(0L, vm.state.value.week?.totalWatchMs)
        assertTrue(vm.state.value.interventions.isEmpty())
    }

    @Test
    fun `records are empty on the first day`() {
        val vm = RecordsViewModel(repo, Direct)
        vm.refresh()
        assertTrue(vm.state.value.isEmpty)
    }

    @Test
    fun `only the latest applied agent change can be reverted`() {
        repo.changes += change("rule", PolicyChangeSource.RULE)
        repo.changes += change("new", PolicyChangeSource.AGENT)
        repo.changes += change("old", PolicyChangeSource.AGENT)
        val vm = RecordsViewModel(repo, Direct)
        vm.refresh()
        assertEquals(listOf(false, true, false), vm.state.value.changes.map { it.canRevert })
    }

    @Test
    fun `revert refreshes the list and the button goes away`() {
        repo.changes += change("a", PolicyChangeSource.AGENT)
        val vm = RecordsViewModel(repo, Direct)
        vm.refresh()
        vm.revert()
        assertEquals(1, repo.reverted)
        assertTrue(vm.state.value.reverted)
        assertFalse(vm.state.value.changes.single().canRevert)
    }

    @Test
    fun `already reverted change offers no button`() {
        repo.changes += change("a", PolicyChangeSource.AGENT, PolicyChangeStatus.REVERTED_BY_USER)
        val vm = RecordsViewModel(repo, Direct)
        vm.refresh()
        assertFalse(vm.state.value.changes.single().canRevert)
    }

    @Test
    fun `intervention list shows shown interventions, newest first, without demo or suppressed`() {
        fun rec(id: String, at: Long, method: InterventionMethod = InterventionMethod.POPUP, demo: Boolean = false) =
            InterventionRecord(id, null, at, method, 1, 1, level = 1, outcome = InterventionOutcome.STOPPED, isDemo = demo)
        repo.interventionList += rec("old", now - 2_000)
        repo.interventionList += rec("new", now - 1_000)
        repo.interventionList += rec("demo", now - 500, demo = true)
        repo.interventionList += rec("skip", now - 400, InterventionMethod.SUPPRESSED_BY_COOLDOWN)
        val vm = RecordsViewModel(repo, Direct)
        vm.refresh()
        assertEquals(listOf("new", "old"), vm.state.value.interventions.map { it.id })
    }

    // --- 설정 ---

    @Test
    fun `settings reflect and write through`() {
        val vm = SettingsViewModel(repo, Direct, debugBuild = true)
        vm.refresh()
        vm.setMaxLevel(2)
        assertEquals(2, repo.currentPolicy.maxLevel)
        assertEquals(2, vm.state.value.maxLevel)
        vm.setAiConsent(true)
        assertTrue(vm.state.value.aiConsent)
        vm.setAgentEnabled(false)
        assertFalse(repo.agentOn)
        vm.setDemoMode(true)
        assertTrue(vm.state.value.demoMode)
    }

    @Test
    fun `goal can be removed from settings`() {
        repo.goalList += GoalRules.userGoal(GoalType.DAILY_TOTAL, 30)
        val vm = SettingsViewModel(repo, Direct, debugBuild = true)
        vm.refresh()
        vm.removeGoal(GoalType.DAILY_TOTAL)
        assertTrue(vm.state.value.goals.isEmpty())
    }

    @Test
    fun `failed analysis tells the policy is kept and a good run clears it`() {
        var status = AgentRunStatus.FAILED
        val failing = object : AppRepository by repo {
            override fun runAgent() = AgentRunResult(status, InterventionPolicy(), InterventionPolicy(), 1)
        }
        val vm = SettingsViewModel(failing, Direct, debugBuild = true)
        vm.analyzeNow()
        assertEquals(AnalysisNotice.POLICY_KEPT, vm.state.value.analysisNotice)
        status = AgentRunStatus.NOT_ENOUGH_DATA
        vm.analyzeNow()
        assertEquals(AnalysisNotice.NOT_ENOUGH_DATA, vm.state.value.analysisNotice)
        status = AgentRunStatus.CHANGED
        vm.analyzeNow()
        assertNull(vm.state.value.analysisNotice)
    }

    @Test
    fun `analyze now runs once and finishes`() {
        val vm = SettingsViewModel(repo, Direct, debugBuild = true)
        vm.analyzeNow()
        assertEquals(1, repo.agentRuns)
        assertFalse(vm.state.value.analyzing)
    }

    @Test
    fun `delete all reports back once`() {
        val vm = SettingsViewModel(repo, Direct, debugBuild = true)
        vm.deleteAll()
        assertEquals(1, repo.deleted)
        assertTrue(vm.state.value.deleted)
        vm.consumeDeleted()
        assertFalse(vm.state.value.deleted)
    }

    @Test
    fun `developer screen opens on the seventh tap in debug builds only`() {
        val debug = SettingsViewModel(repo, Direct, debugBuild = true)
        repeat(SettingsViewModel.DEVELOPER_TAPS - 1) { debug.tapVersion() }
        assertFalse(debug.state.value.openDeveloper)
        debug.tapVersion()
        assertTrue(debug.state.value.openDeveloper)

        val release = SettingsViewModel(repo, Direct, debugBuild = false)
        repeat(20) { release.tapVersion() }
        assertFalse(release.state.value.openDeveloper)
    }

    // --- 개발자 화면 ---

    @Test
    fun `developer actions report results`() {
        val vm = DeveloperViewModel(repo, Direct)
        vm.seed(DemoSeedData.Scenario.LATE_NIGHT_SKIPPER)
        assertEquals(7, vm.state.value.seeded)
        vm.runGoalEval()
        assertEquals(15, vm.state.value.evalRows)
        vm.resetForDemo()
        assertEquals(1, repo.resets)
        assertTrue(vm.state.value.resetDone)
        assertFalse(vm.state.value.working)
    }

    @Test
    fun `restart onboarding reports back so the app can return to the survey`() {
        val vm = DeveloperViewModel(repo, Direct)
        vm.restartOnboarding()
        assertEquals(1, repo.restarts)
        assertTrue(vm.state.value.restarted)
    }

    @Test
    fun `demo threshold is chosen on the developer screen`() {
        val vm = DeveloperViewModel(repo, Direct)
        vm.setDemoThreshold(30_000)
        assertEquals(30_000, repo.demoThreshold)
        assertEquals(30_000, vm.state.value.demoThresholdMs)
    }

    // --- 6단계 후속 ---

    @Test
    fun `demo reset is recorded as a DEFAULT change`() {
        val before = InterventionPolicy(thresholdMs = 120_000, level = 2)
        val change = InterventionCoordinator.demoResetChange(before, InterventionPolicy(), "id", 5, "시연 시작값")
        assertEquals(PolicyChangeSource.DEFAULT, change.source)
        assertEquals(PolicyChangeStatus.APPLIED, change.status)
        assertEquals(before, change.previousPolicy)
        assertEquals(InterventionPolicy(), change.newPolicy)
    }

    @Test
    fun `agent input carries the per session goal value`() {
        fun json(goals: List<Goal>) = AgentInputJson.toJson(
            AgentInputBuilder.build(null, goals, InterventionPolicy(), emptyList(), emptyList(), emptyList(), zone, now)
        ).getJSONObject("policy")
        assertEquals(15, json(listOf(GoalRules.userGoal(GoalType.PER_SESSION, 15))).getInt("perSessionGoalMinutes"))
        assertTrue(json(emptyList()).isNull("perSessionGoalMinutes"))
    }

    @Test
    fun `agent prompt asks for data-based copy and has no fixed popup examples`() {
        val p = PolicyAgent.SYSTEM_PROMPT
        assertTrue("watchMsByTimeSlot" in p && "lv2Reasons" in p && "그대로 쓰지 않습니다" in p)
        assertFalse("잠깐, 지금 계속 볼까요?" in p)
        assertFalse("지금 보는 이유가 뭘까요?" in p)
        assertTrue("perSessionGoalMinutes" in p)
    }

    @Test
    fun `settings shows seed notice while seed data exists and clears it at once`() {
        repo.seedData = true
        val vm = SettingsViewModel(repo, Direct, debugBuild = true)
        vm.refresh()
        assertTrue(vm.state.value.hasSeedData)

        vm.deleteSeedData()

        assertFalse(vm.state.value.hasSeedData)
        assertFalse(repo.seedData)
    }

    @Test
    fun `deleting seed data clears the shown analysis result at once`() {
        repo.seedData = true
        repo.lastResult = "CHANGED"
        val vm = SettingsViewModel(repo, Direct, debugBuild = true)
        vm.refresh()
        assertEquals("CHANGED", vm.state.value.lastResult)

        vm.deleteSeedData()

        assertNull(vm.state.value.lastResult)
    }

    @Test
    fun `settings shows no seed notice without seed data`() {
        val vm = SettingsViewModel(repo, Direct, debugBuild = true)
        vm.refresh()
        assertFalse(vm.state.value.hasSeedData)
    }
}
