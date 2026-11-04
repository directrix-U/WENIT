package com.example.shortsdetector.ui

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.ComponentName
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.accessibility.AccessibilityManager
import com.example.shortsdetector.BuildConfig
import com.example.shortsdetector.R
import com.example.shortsdetector.ShortsDetectionService
import com.example.shortsdetector.ShortsNotifier
import com.example.shortsdetector.ai.AiGoalRecommender
import com.example.shortsdetector.ai.ClaudeMessagesClient
import com.example.shortsdetector.ai.GoalRecommendation
import com.example.shortsdetector.ai.HttpUrlConnectionTransport
import com.example.shortsdetector.ai.TestSurvey
import com.example.shortsdetector.ai.agent.AgentMessages
import com.example.shortsdetector.ai.agent.AgentPreferences
import com.example.shortsdetector.ai.agent.AgentRunResult
import com.example.shortsdetector.ai.agent.AgentSchedule
import com.example.shortsdetector.ai.agent.PolicyAgent
import com.example.shortsdetector.ai.agent.PreferencesAgentStore
import com.example.shortsdetector.data.DetectionPreferences
import com.example.shortsdetector.data.InterventionPolicyPreferences
import com.example.shortsdetector.data.goal.Goal
import com.example.shortsdetector.data.goal.GoalPreferences
import com.example.shortsdetector.data.goal.GoalType
import com.example.shortsdetector.data.profile.UserProfile
import com.example.shortsdetector.data.profile.UserProfilePreferences
import com.example.shortsdetector.data.stats.AgentInput
import com.example.shortsdetector.data.stats.AgentInputBuilder
import com.example.shortsdetector.data.stats.SavedTimeBaselinePreferences
import com.example.shortsdetector.data.stats.SavedTimeCalculator
import com.example.shortsdetector.data.stats.UsageStatsCalculator
import com.example.shortsdetector.data.usage.DemoSeedData
import com.example.shortsdetector.data.usage.InterventionAction
import com.example.shortsdetector.data.usage.InterventionRecord
import com.example.shortsdetector.data.usage.PolicyChangeRecord
import com.example.shortsdetector.data.usage.SessionRecord
import com.example.shortsdetector.data.usage.UsageDatabase
import com.example.shortsdetector.intervention.InterventionCoordinator
import com.example.shortsdetector.intervention.InterventionPolicy
import com.example.shortsdetector.intervention.ResolvedPolicy
import com.example.shortsdetector.platform.DetectionStatus
import java.io.File
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID
import java.util.concurrent.Executors

data class PermissionStatus(val accessibility: Boolean, val notifications: Boolean, val serviceConnected: Boolean) {
    /** 서비스가 실제로 연결돼 있을 때만 작동 중이라고 표시한다(명세 3.1-7). */
    val detecting: Boolean get() = accessibility && serviceConnected
}

/** 화면이 쓰는 데이터 경계. 호출은 [TaskRunner] 의 작업 스레드에서 한다(설정 읽기·쓰기는 메인에서도 가능). */
interface AppRepository {
    fun nowMs(): Long
    fun zone(): ZoneId

    fun profile(): UserProfile?
    fun saveProfile(profile: UserProfile)
    fun isOnboardingDone(): Boolean
    fun markOnboardingDone()
    fun isAiConsentGiven(): Boolean
    fun setAiConsent(given: Boolean)

    fun goals(): List<Goal>
    fun putGoal(goal: Goal): Boolean
    fun removeGoal(type: GoalType)

    fun savedPolicy(): InterventionPolicy
    fun resolvedPolicy(): ResolvedPolicy
    fun setMaxLevel(level: Int)
    fun setThreshold(ms: Long)

    fun isAgentEnabled(): Boolean
    fun setAgentEnabled(enabled: Boolean)
    fun agentMessages(): AgentMessages?
    fun lastAgentResult(): String?
    fun isAgentDue(): Boolean
    fun runAgent(): AgentRunResult
    fun revertAgentChange(): PolicyChangeRecord?

    fun isDemoMode(): Boolean
    fun setDemoMode(enabled: Boolean)
    fun isPopupEnabled(): Boolean
    fun setPopupEnabled(enabled: Boolean)
    fun isYouTubeEnabled(): Boolean
    fun setYouTubeEnabled(enabled: Boolean)
    fun isInstagramEnabled(): Boolean
    fun setInstagramEnabled(enabled: Boolean)
    fun permissions(): PermissionStatus

    fun sessions(fromMs: Long, toMs: Long): List<SessionRecord>
    fun interventions(fromMs: Long, toMs: Long): List<InterventionRecord>
    fun policyChanges(limit: Int): List<PolicyChangeRecord>
    fun firstWeekAverageMs(): Long?

    fun recommendGoals(): GoalRecommendation
    fun deleteAllRecords()
    fun resetForDemo()
    fun insertSeed(scenario: DemoSeedData.Scenario): Int
    fun runGoalEval(): Int
    fun recordNotificationTap(interventionId: String)
}

/** 화면 작업을 메인 스레드 밖에서 돌리고 결과를 메인 스레드로 넘긴다. 테스트는 바로 실행하는 구현을 쓴다. */
interface TaskRunner {
    fun <T> run(work: () -> T, failed: () -> Unit = {}, done: (T) -> Unit)
}

class BackgroundTaskRunner : TaskRunner {
    private val executor = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())

    override fun <T> run(work: () -> T, failed: () -> Unit, done: (T) -> Unit) {
        executor.execute {
            try {
                val result = work()
                main.post { done(result) }
            } catch (e: RuntimeException) {
                Log.w(ShortsDetectionService.TAG, "화면 작업 실패: ${e.javaClass.simpleName}")
                main.post(failed)
            }
        }
    }
}

class AndroidAppRepository(context: Context) : AppRepository {

    private val app = context.applicationContext
    private val db get() = UsageDatabase.get(app)

    override fun nowMs() = System.currentTimeMillis()
    override fun zone(): ZoneId = ZoneId.systemDefault()

    override fun profile() = UserProfilePreferences.load(app)

    override fun saveProfile(profile: UserProfile) = UserProfilePreferences.save(app, profile)

    override fun isOnboardingDone() = UserProfilePreferences.isOnboardingDone(app)

    override fun markOnboardingDone() = UserProfilePreferences.markOnboardingDone(app)

    override fun isAiConsentGiven() = UserProfilePreferences.isAiConsentGiven(app)

    override fun setAiConsent(given: Boolean) = UserProfilePreferences.setAiConsent(app, given)

    override fun goals() = GoalPreferences.load(app)

    override fun putGoal(goal: Goal) = GoalPreferences.put(app, goal)

    override fun removeGoal(type: GoalType) = GoalPreferences.remove(app, type)

    override fun savedPolicy() = InterventionPolicyPreferences.load(app)

    override fun resolvedPolicy() = InterventionPolicyPreferences.resolve(app)

    override fun setMaxLevel(level: Int) = InterventionPolicyPreferences.save(app, savedPolicy().copy(maxLevel = level))

    override fun setThreshold(ms: Long) {
        val change = InterventionCoordinator.userThresholdChange(
            savedPolicy(), ms, UUID.randomUUID().toString(), nowMs(), app.getString(R.string.policy_reason_user_threshold)
        ) ?: return
        InterventionPolicyPreferences.save(app, change.newPolicy)
        db.insertPolicyChange(change)
    }

    override fun isAgentEnabled() = AgentPreferences.isEnabled(app)

    override fun setAgentEnabled(enabled: Boolean) = AgentPreferences.setEnabled(app, enabled)

    override fun agentMessages() = AgentPreferences.activeMessages(app)

    override fun lastAgentResult() = AgentPreferences.lastResult(app)

    override fun isAgentDue() =
        AgentSchedule.isDue(nowMs(), AgentPreferences.lastRunAtMs(app), isAgentEnabled(), profile()?.aiRecommendationConsent == true)

    private fun client() = ClaudeMessagesClient(BuildConfig.ANTHROPIC_API_KEY, HttpUrlConnectionTransport())

    private fun agent() = PolicyAgent(
        client(),
        PreferencesAgentStore(InterventionPolicyPreferences.prefs(app), AgentPreferences.prefs(app)),
        db
    )

    private fun input(profile: UserProfile?): AgentInput {
        val window = UsageStatsCalculator.dataWindow(nowMs(), zone())
        return AgentInputBuilder.build(
            profile, goals(), savedPolicy(),
            db.sessionsOverlapping(window.fromMs, window.toMs),
            db.interventionsBetween(window.fromMs, window.toMs),
            db.recentPolicyChanges(RECENT_CHANGES),
            zone(), nowMs()
        )
    }

    override fun runAgent() = agent().run(input(profile()))

    override fun revertAgentChange() = agent().revertLatest(app.getString(R.string.agent_revert_reason))

    override fun isDemoMode() = InterventionPolicyPreferences.isDemoMode(app)

    override fun setDemoMode(enabled: Boolean) = InterventionPolicyPreferences.setDemoMode(app, enabled)

    override fun isPopupEnabled() = DetectionPreferences.isPopupEnabled(app)

    override fun setPopupEnabled(enabled: Boolean) = DetectionPreferences.setPopupEnabled(app, enabled)

    override fun isYouTubeEnabled() = DetectionPreferences.isYouTubeEnabled(app)

    override fun setYouTubeEnabled(enabled: Boolean) = DetectionPreferences.setYouTubeEnabled(app, enabled)

    override fun isInstagramEnabled() = DetectionPreferences.isInstagramEnabled(app)

    override fun setInstagramEnabled(enabled: Boolean) = DetectionPreferences.setInstagramEnabled(app, enabled)

    override fun permissions() = PermissionStatus(
        accessibility = isAccessibilityServiceEnabled(),
        notifications = ShortsNotifier.hasPostPermission(app),
        serviceConnected = DetectionStatus.current.connected
    )

    private fun isAccessibilityServiceEnabled(): Boolean {
        val target = ComponentName(app, ShortsDetectionService::class.java)
        val manager = app.getSystemService(AccessibilityManager::class.java)
        if (manager != null) {
            val enabled = manager.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
            if (enabled.any { ComponentName.unflattenFromString(it.id) == target }) return true
        }
        // 일부 기기에서 위 목록 갱신이 늦어, Secure 설정 문자열도 함께 확인한다.
        val setting = Settings.Secure.getString(app.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
            ?: return false
        return setting.split(':').any { ComponentName.unflattenFromString(it.trim()) == target }
    }

    override fun sessions(fromMs: Long, toMs: Long) = db.sessionsOverlapping(fromMs, toMs)

    override fun interventions(fromMs: Long, toMs: Long) = db.interventionsBetween(fromMs, toMs)

    override fun policyChanges(limit: Int) = db.recentPolicyChanges(limit)

    /** 고정해 둔 값이 없을 때만 첫 7일을 읽는다. */
    override fun firstWeekAverageMs(): Long? = SavedTimeBaselinePreferences.resolve(app) {
        SavedTimeCalculator.firstWeekAverageMs(db.sessionsOverlapping(0L, nowMs()), zone(), LocalDate.now(zone()))
    }

    override fun recommendGoals(): GoalRecommendation = AiGoalRecommender(client()).recommend(input(profile()))

    override fun deleteAllRecords() {
        db.deleteAll()
        SavedTimeBaselinePreferences.clear(app)
        InterventionPolicyPreferences.markRecordsCleared(app)
    }

    /** 정책 전체를 시연 시작값으로 되돌린다. 기록을 지운 뒤에 남기는 DEFAULT 한 건이 첫 변경 이력이 된다. */
    override fun resetForDemo() {
        db.deleteAll()
        SavedTimeBaselinePreferences.clear(app)
        AgentPreferences.clearRunState(app)
        val before = InterventionPolicyPreferences.resetForDemo(app)
        db.insertPolicyChange(
            InterventionCoordinator.demoResetChange(
                before, InterventionPolicyPreferences.load(app), UUID.randomUUID().toString(), nowMs(),
                app.getString(R.string.policy_reason_demo_reset)
            )
        )
    }

    override fun insertSeed(scenario: DemoSeedData.Scenario): Int {
        val batch = DemoSeedData.build(scenario, LocalDate.now(zone()), zone(), nowMs())
        db.deleteSeedRecords()
        batch.sessions.forEach(db::upsertSession)
        batch.interventions.forEach(db::insertIntervention)
        return batch.interventions.size
    }

    /** 디버그 전용: 설문 조합을 실제 호출해 앱 캐시 파일에 쓴다. 응답 내용을 로그에 남기지 않기 위함이다. */
    override fun runGoalEval(): Int {
        val consent = isAiConsentGiven()
        val rows = TestSurvey.cases.flatMap { (name, survey) ->
            val r = AiGoalRecommender(client()).recommend(input(survey.copy(aiRecommendationConsent = consent)))
            r.goals.map { g ->
                listOf(name, g.type.name, g.value, g.label, g.reason.orEmpty(), g.reason.orEmpty().length, g.source.name, r.failure ?: "-", r.elapsedMs)
                    .joinToString("\t")
            }
        }
        File(app.cacheDir, GOAL_EVAL_FILE).writeText(rows.joinToString("\n"))
        return rows.size
    }

    override fun recordNotificationTap(interventionId: String) =
        db.setInterventionAction(interventionId, InterventionAction.OPEN_APP, nowMs())

    private companion object {
        const val RECENT_CHANGES = 10
        const val GOAL_EVAL_FILE = "goal_eval.tsv"
    }
}
