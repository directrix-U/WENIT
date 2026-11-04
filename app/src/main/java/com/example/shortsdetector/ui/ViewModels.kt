package com.example.shortsdetector.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.example.shortsdetector.ai.AiFailureKind
import com.example.shortsdetector.ai.agent.AgentMessages
import com.example.shortsdetector.data.goal.Goal
import com.example.shortsdetector.data.goal.GoalProgress
import com.example.shortsdetector.data.goal.GoalRules
import com.example.shortsdetector.data.goal.GoalStatus
import com.example.shortsdetector.data.goal.GoalType
import com.example.shortsdetector.data.profile.AgeBand
import com.example.shortsdetector.data.profile.Gender
import com.example.shortsdetector.data.profile.ShortformApp
import com.example.shortsdetector.data.profile.UserProfile
import com.example.shortsdetector.data.profile.UsualDailyWatch
import com.example.shortsdetector.data.profile.WatchTimeSlot
import com.example.shortsdetector.data.stats.SavedTime
import com.example.shortsdetector.data.stats.SavedTimeCalculator
import com.example.shortsdetector.data.stats.UsageStatsCalculator
import com.example.shortsdetector.data.stats.WeekStats
import com.example.shortsdetector.data.usage.DemoSeedData
import com.example.shortsdetector.data.usage.InterventionMethod
import com.example.shortsdetector.data.usage.InterventionRecord
import com.example.shortsdetector.data.usage.PolicyChangeRecord
import com.example.shortsdetector.data.usage.PolicyChangeSource
import com.example.shortsdetector.data.usage.PolicyChangeStatus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.time.DayOfWeek

/** 화면마다 하나. 상태는 [state] 로만 내보내고, 저장소 호출은 [TaskRunner] 로 넘긴다. */
abstract class StateViewModel<S>(initial: S) : ViewModel() {
    private val mutable = MutableStateFlow(initial)
    val state: StateFlow<S> = mutable.asStateFlow()
    protected val current: S get() = mutable.value
    protected fun set(change: (S) -> S) = mutable.update(change)
}

// --- a. 온보딩 설문 ---

enum class SurveyStep { AGE, GENDER, APPS, TIME_SLOTS, DAYS, USUAL_WATCH }

data class SurveyState(
    val stepIndex: Int = 0,
    val ageBand: AgeBand? = null,
    val gender: Gender? = null,
    val apps: Set<ShortformApp> = emptySet(),
    val slots: Set<WatchTimeSlot> = emptySet(),
    val days: Set<DayOfWeek> = emptySet(),
    val usualWatch: UsualDailyWatch? = null,
    val finished: Boolean = false
) {
    val step: SurveyStep get() = SurveyStep.entries[stepIndex]
    val isFirst: Boolean get() = stepIndex == 0
    val isLast: Boolean get() = stepIndex == SurveyStep.entries.lastIndex

    /** 한 질문에 하나 이상 답해야 넘어간다. 성별에는 "응답 안 함" 선택지가 있다. */
    val canGoNext: Boolean
        get() = when (step) {
            SurveyStep.AGE -> ageBand != null
            SurveyStep.GENDER -> gender != null
            SurveyStep.APPS -> apps.isNotEmpty()
            SurveyStep.TIME_SLOTS -> slots.isNotEmpty()
            SurveyStep.DAYS -> days.isNotEmpty()
            SurveyStep.USUAL_WATCH -> usualWatch != null
        }
}

class SurveyViewModel(private val repo: AppRepository) : StateViewModel<SurveyState>(SurveyState()) {

    fun selectAge(v: AgeBand) = set { it.copy(ageBand = v) }
    fun selectGender(v: Gender) = set { it.copy(gender = v) }
    fun toggleApp(v: ShortformApp) = set { it.copy(apps = it.apps.toggled(v)) }
    fun toggleSlot(v: WatchTimeSlot) = set { it.copy(slots = it.slots.toggled(v)) }
    fun toggleDay(v: DayOfWeek) = set { it.copy(days = it.days.toggled(v)) }
    fun selectUsualWatch(v: UsualDailyWatch) = set { it.copy(usualWatch = v) }
    fun back() = set { if (it.isFirst) it else it.copy(stepIndex = it.stepIndex - 1) }

    fun next() {
        val s = current
        if (!s.canGoNext) return
        if (!s.isLast) {
            set { it.copy(stepIndex = it.stepIndex + 1) }
            return
        }
        repo.saveProfile(
            UserProfile(
                ageBand = checkNotNull(s.ageBand),
                gender = s.gender ?: Gender.NO_ANSWER,
                shortformApps = s.apps,
                watchTimeSlots = s.slots,
                watchDays = s.days,
                usualDailyWatch = s.usualWatch,
                aiRecommendationConsent = repo.isAiConsentGiven()
            )
        )
        set { it.copy(finished = true) }
    }

    private fun <T> Set<T>.toggled(v: T) = if (v in this) this - v else this + v
}

// --- b. AI 사용 동의 ---

class ConsentViewModel(private val repo: AppRepository) : StateViewModel<Boolean?>(null) {
    /** 기본 선택이 없다. 사용자가 고른 값만 저장한다. */
    fun decide(agree: Boolean) {
        repo.setAiConsent(agree)
        set { agree }
    }
}

// --- c. 권한 안내 ---

class PermissionsViewModel(private val repo: AppRepository) : StateViewModel<PermissionStatus>(repo.permissions()) {
    fun refresh() = set { repo.permissions() }
}

// --- d. 목표 추천 ---

data class GoalPickState(
    val loading: Boolean = true,
    val cards: List<Goal> = emptyList(),
    val selected: Set<GoalType> = emptySet(),
    /** 추천에 없는 유형으로 이미 저장한 목표 수 */
    val otherGoals: Int = 0,
    val failure: AiFailureKind? = null,
    val finished: Boolean = false
) {
    val canSelectMore: Boolean get() = selected.size + otherGoals < GoalRules.MAX_GOALS
}

class GoalPickViewModel(private val repo: AppRepository, private val tasks: TaskRunner) :
    StateViewModel<GoalPickState>(GoalPickState()) {

    private var requested = false

    fun load() {
        if (requested) return
        requested = true
        set { it.copy(loading = true) }
        tasks.run(
            work = { repo.recommendGoals() },
            failed = { set { it.copy(loading = false, failure = AiFailureKind.NETWORK) } }
        ) { r ->
            val saved = repo.goals()
            set {
                it.copy(
                    loading = false, cards = r.goals, failure = r.failure,
                    otherGoals = saved.count { g -> r.goals.none { c -> c.type == g.type } }
                )
            }
        }
    }

    /** "직접 정하기" 에서 돌아왔을 때 저장한 목표 수를 다시 센다. */
    fun refreshSaved() {
        val saved = repo.goals()
        set { s -> s.copy(otherGoals = saved.count { g -> s.cards.none { c -> c.type == g.type } }) }
    }

    fun toggle(type: GoalType) = set {
        when {
            type in it.selected -> it.copy(selected = it.selected - type)
            it.canSelectMore -> it.copy(selected = it.selected + type)
            else -> it
        }
    }

    /** 유형당 하나라서 같은 유형의 목표는 고른 카드로 바뀐다. */
    fun save() {
        val s = current
        s.cards.filter { it.type in s.selected }.forEach(repo::putGoal)
        repo.markOnboardingDone()
        set { it.copy(finished = true) }
    }
}

// --- e. 직접 목표 ---

data class CustomGoalState(
    val type: GoalType? = null,
    val number: String = "",
    val hour: String = "",
    val minute: String = "",
    val full: Boolean = false,
    val saved: Boolean = false
) {
    val value: Int?
        get() = when (type) {
            null -> null
            GoalType.TIME_WINDOW -> {
                val h = hour.toIntOrNull()
                val m = minute.toIntOrNull()
                if (h == null || m == null || h !in 0..23 || m !in 0..59) null
                else h * 60 + m + if (h < EARLY_MORNING_END) 24 * 60 else 0
            }
            else -> number.toIntOrNull()
        }

    val isValid: Boolean get() = type != null && value?.let { it in GoalRules.rangeOf(type) } == true

    private companion object {
        const val EARLY_MORNING_END = 6
    }
}

class CustomGoalViewModel(private val repo: AppRepository) : StateViewModel<CustomGoalState>(CustomGoalState()) {

    fun selectType(type: GoalType) = set { CustomGoalState(type = type) }
    fun setNumber(text: String) = set { it.copy(number = text.filter(Char::isDigit).take(4), full = false) }
    fun setHour(text: String) = set { it.copy(hour = text.filter(Char::isDigit).take(2), full = false) }
    fun setMinute(text: String) = set { it.copy(minute = text.filter(Char::isDigit).take(2), full = false) }

    fun save() {
        val s = current
        val type = s.type ?: return
        val value = s.value
        if (!s.isValid || value == null) return
        val ok = repo.putGoal(GoalRules.userGoal(type, value))
        set { it.copy(saved = ok, full = !ok) }
    }
}

// --- f. 홈 ---

data class YesterdaySummary(val achieved: Int, val total: Int)

data class HomeState(
    val loading: Boolean = true,
    val permissions: PermissionStatus = PermissionStatus(false, false, false),
    val hasRecords: Boolean = false,
    val watchMs: Long = 0,
    val goals: List<GoalProgress> = emptyList(),
    val level: Int = 1,
    val demoMode: Boolean = false,
    val lastChangeReason: String? = null,
    val yesterday: YesterdaySummary? = null,
    val savedTime: SavedTime? = null,
    /** 개입 팝업에서 멈추고 넘어왔을 때의 시청시간 */
    val stoppedAtMs: Long? = null
)

class HomeViewModel(private val repo: AppRepository, private val tasks: TaskRunner) :
    StateViewModel<HomeState>(HomeState()) {

    fun refresh() {
        tasks.run(work = { load() }, failed = { set { it.copy(loading = false) } }) { loaded ->
            set { loaded.copy(stoppedAtMs = it.stoppedAtMs) }
        }
    }

    fun showStopped(watchMs: Long) = set { it.copy(stoppedAtMs = watchMs) }

    fun dismissStopped() = set { it.copy(stoppedAtMs = null) }

    private fun load(): HomeState {
        if (repo.isAgentDue()) repo.runAgent()
        val zone = repo.zone()
        val now = repo.nowMs()
        val window = UsageStatsCalculator.dataWindow(now, zone)
        val sessions = repo.sessions(window.fromMs, window.toMs)
        val interventions = repo.interventions(window.fromMs, window.toMs)
        val goals = repo.goals()
        val today = UsageStatsCalculator.today(sessions, interventions, goals, zone, now)
        val resolved = repo.resolvedPolicy()
        val hasRecords = sessions.any { !it.isDemo }
        val hadEarlier = sessions.any { !it.isDemo && it.startedAtMs < startOfToday(now) }
        val yesterday = if (goals.isEmpty() || !hadEarlier) {
            null
        } else {
            val r = UsageStatsCalculator.yesterday(sessions, interventions, goals, zone, now)
            YesterdaySummary(r.goals.count { it.status == GoalStatus.ACHIEVED }, r.goals.size)
        }
        return HomeState(
            loading = false,
            permissions = repo.permissions(),
            hasRecords = hasRecords,
            watchMs = today.estimatedWatchMs,
            goals = today.goals,
            level = resolved.policy.level,
            demoMode = resolved.demoMode,
            lastChangeReason = repo.policyChanges(RECENT).firstOrNull { !it.isDemo }?.reasonText,
            yesterday = yesterday,
            // 아직 보지 않은 날에 평소 시간 전부를 "아꼈다" 고 말하지 않는다.
            savedTime = if (!hasRecords) null else SavedTimeCalculator.estimate(
                repo.firstWeekAverageMs(), repo.profile()?.usualDailyWatch, today.estimatedWatchMs
            )
        )
    }

    private fun startOfToday(now: Long): Long {
        val zone = repo.zone()
        return java.time.Instant.ofEpochMilli(now).atZone(zone).toLocalDate().atStartOfDay(zone).toInstant().toEpochMilli()
    }

    private companion object {
        const val RECENT = 5
    }
}

// --- g. 기록 ---

data class PolicyChangeRow(val record: PolicyChangeRecord, val canRevert: Boolean)

data class RecordsState(
    val loading: Boolean = true,
    val week: WeekStats? = null,
    val changes: List<PolicyChangeRow> = emptyList(),
    val interventions: List<InterventionRecord> = emptyList(),
    val reverted: Boolean = false
) {
    val isEmpty: Boolean
        get() = !loading && changes.isEmpty() && interventions.isEmpty() && week?.totalWatchMs == 0L
}

class RecordsViewModel(private val repo: AppRepository, private val tasks: TaskRunner) :
    StateViewModel<RecordsState>(RecordsState()) {

    fun refresh() {
        tasks.run(work = { load() }, failed = { set { it.copy(loading = false) } }) { loaded -> set { loaded } }
    }

    fun revert() {
        tasks.run(work = {
            val reverted = repo.revertAgentChange() != null
            load().copy(reverted = reverted)
        }) { loaded -> set { loaded } }
    }

    private fun load(): RecordsState {
        val zone = repo.zone()
        val now = repo.nowMs()
        val window = UsageStatsCalculator.dataWindow(now, zone)
        val sessions = repo.sessions(window.fromMs, window.toMs)
        val interventions = repo.interventions(window.fromMs, window.toMs)
        val changes = repo.policyChanges(CHANGE_LIMIT).filterNot { it.isDemo }
        val latestAgent = changes.firstOrNull { it.source == PolicyChangeSource.AGENT }
        return RecordsState(
            loading = false,
            week = UsageStatsCalculator.week(sessions, interventions, zone, now),
            changes = changes.map {
                PolicyChangeRow(it, it === latestAgent && it.status == PolicyChangeStatus.APPLIED && it.previousPolicy != null)
            },
            interventions = interventions
                .filter { !it.isDemo && (it.method == InterventionMethod.POPUP || it.method == InterventionMethod.NOTIFICATION) }
                .sortedByDescending { it.occurredAtMs }
                .take(INTERVENTION_LIMIT)
        )
    }

    private companion object {
        const val CHANGE_LIMIT = 20
        const val INTERVENTION_LIMIT = 30
    }
}

// --- h. 설정 ---

data class SettingsState(
    val goals: List<Goal> = emptyList(),
    val maxLevel: Int = 3,
    val aiConsent: Boolean = false,
    val agentEnabled: Boolean = true,
    val youtube: Boolean = true,
    val instagram: Boolean = false,
    val demoMode: Boolean = false,
    val permissions: PermissionStatus = PermissionStatus(false, false, false),
    val analyzing: Boolean = false,
    val lastResult: String? = null,
    val messages: AgentMessages? = null,
    val deleted: Boolean = false,
    val versionTaps: Int = 0,
    val openDeveloper: Boolean = false
)

class SettingsViewModel(
    private val repo: AppRepository,
    private val tasks: TaskRunner,
    private val debugBuild: Boolean
) : StateViewModel<SettingsState>(SettingsState()) {

    fun refresh() = set {
        it.copy(
            goals = repo.goals(), maxLevel = repo.savedPolicy().maxLevel, aiConsent = repo.isAiConsentGiven(),
            agentEnabled = repo.isAgentEnabled(), youtube = repo.isYouTubeEnabled(), instagram = repo.isInstagramEnabled(),
            demoMode = repo.isDemoMode(), permissions = repo.permissions(), lastResult = repo.lastAgentResult(),
            messages = repo.agentMessages()
        )
    }

    fun removeGoal(type: GoalType) {
        repo.removeGoal(type)
        refresh()
    }

    fun setMaxLevel(level: Int) {
        repo.setMaxLevel(level)
        refresh()
    }

    fun setAiConsent(given: Boolean) {
        repo.setAiConsent(given)
        refresh()
    }

    fun setAgentEnabled(enabled: Boolean) {
        repo.setAgentEnabled(enabled)
        refresh()
    }

    fun setYouTube(enabled: Boolean) {
        repo.setYouTubeEnabled(enabled)
        refresh()
    }

    fun setInstagram(enabled: Boolean) {
        repo.setInstagramEnabled(enabled)
        refresh()
    }

    fun setDemoMode(enabled: Boolean) {
        repo.setDemoMode(enabled)
        refresh()
    }

    fun analyzeNow() {
        if (current.analyzing) return
        set { it.copy(analyzing = true) }
        tasks.run(work = { repo.runAgent() }, failed = { set { it.copy(analyzing = false) } }) {
            set { s -> s.copy(analyzing = false) }
            refresh()
        }
    }

    fun deleteAll() {
        tasks.run(work = { repo.deleteAllRecords() }) { set { it.copy(deleted = true) } }
    }

    fun consumeDeleted() = set { it.copy(deleted = false) }

    /** 버전 표시를 [DEVELOPER_TAPS] 번 누르면 열린다. 디버그 빌드에서만. */
    fun tapVersion() = set {
        val taps = it.versionTaps + 1
        if (debugBuild && taps >= DEVELOPER_TAPS) it.copy(versionTaps = 0, openDeveloper = true) else it.copy(versionTaps = taps)
    }

    fun consumeOpenDeveloper() = set { it.copy(openDeveloper = false) }

    companion object {
        const val DEVELOPER_TAPS = 7
    }
}

// --- i. 개발자 화면 ---

data class DeveloperState(
    val thresholdMs: Long = 0,
    val popupEnabled: Boolean = true,
    val working: Boolean = false,
    val seeded: Int? = null,
    val evalRows: Int? = null,
    val resetDone: Boolean = false
)

class DeveloperViewModel(private val repo: AppRepository, private val tasks: TaskRunner) :
    StateViewModel<DeveloperState>(DeveloperState()) {

    fun refresh() = set { it.copy(thresholdMs = repo.savedPolicy().thresholdMs, popupEnabled = repo.isPopupEnabled()) }

    fun setPopup(enabled: Boolean) {
        repo.setPopupEnabled(enabled)
        refresh()
    }

    fun setThreshold(ms: Long) {
        tasks.run(work = { repo.setThreshold(ms) }) { refresh() }
    }

    fun seed(scenario: DemoSeedData.Scenario) = work({ repo.insertSeed(scenario) }) { n -> set { it.copy(seeded = n) } }

    fun runGoalEval() = work({ repo.runGoalEval() }) { n -> set { it.copy(evalRows = n) } }

    fun resetForDemo() = work({ repo.resetForDemo() }) {
        set { it.copy(resetDone = true) }
        refresh()
    }

    private fun <T> work(job: () -> T, done: (T) -> Unit) {
        set { it.copy(working = true, seeded = null, evalRows = null, resetDone = false) }
        tasks.run(work = job, failed = { set { it.copy(working = false) } }) { r ->
            set { it.copy(working = false) }
            done(r)
        }
    }
}

/** 화면이 [androidx.lifecycle.viewmodel.compose.viewModel] 로 가져갈 때 쓰는 생성기 */
class WenitViewModelFactory(
    private val repo: AppRepository,
    private val tasks: TaskRunner,
    private val debugBuild: Boolean
) : ViewModelProvider.Factory {

    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T = when (modelClass) {
        SurveyViewModel::class.java -> SurveyViewModel(repo)
        ConsentViewModel::class.java -> ConsentViewModel(repo)
        PermissionsViewModel::class.java -> PermissionsViewModel(repo)
        GoalPickViewModel::class.java -> GoalPickViewModel(repo, tasks)
        CustomGoalViewModel::class.java -> CustomGoalViewModel(repo)
        HomeViewModel::class.java -> HomeViewModel(repo, tasks)
        RecordsViewModel::class.java -> RecordsViewModel(repo, tasks)
        SettingsViewModel::class.java -> SettingsViewModel(repo, tasks, debugBuild)
        DeveloperViewModel::class.java -> DeveloperViewModel(repo, tasks)
        else -> error("unknown ViewModel")
    } as T
}
