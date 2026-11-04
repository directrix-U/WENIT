package com.example.shortsdetector

import android.Manifest
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.ComponentName
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.view.accessibility.AccessibilityManager
import android.view.View
import android.widget.Button
import android.widget.RadioButton
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.example.shortsdetector.ai.AiGoalRecommender
import com.example.shortsdetector.ai.agent.AgentPolicyRules
import com.example.shortsdetector.ai.agent.AgentPreferences
import com.example.shortsdetector.ai.agent.AgentSchedule
import com.example.shortsdetector.ai.agent.PolicyAgent
import com.example.shortsdetector.ai.agent.PreferencesAgentStore
import com.example.shortsdetector.data.stats.AgentInput
import com.example.shortsdetector.data.usage.DemoSeedData
import com.example.shortsdetector.ai.ClaudeMessagesClient
import com.example.shortsdetector.ai.GoalRecommendation
import com.example.shortsdetector.ai.HttpUrlConnectionTransport
import com.example.shortsdetector.ai.TestSurvey
import com.example.shortsdetector.data.DetectionPreferences
import com.example.shortsdetector.data.InterventionPolicyPreferences
import com.example.shortsdetector.data.goal.Goal
import com.example.shortsdetector.data.goal.GoalPreferences
import com.example.shortsdetector.data.profile.UserProfile
import com.example.shortsdetector.data.profile.UserProfilePreferences
import com.example.shortsdetector.data.stats.AgentInputBuilder
import com.example.shortsdetector.data.stats.SavedTimeBaselinePreferences
import com.example.shortsdetector.data.stats.UsageStatsCalculator
import com.example.shortsdetector.data.usage.DailyUsageCalculator
import com.example.shortsdetector.data.usage.InterventionAction
import com.example.shortsdetector.data.usage.UsageDatabase
import com.example.shortsdetector.intervention.InterventionCoordinator
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.ZoneId
import java.util.UUID
import java.util.concurrent.Executors
import com.example.shortsdetector.databinding.ActivityMainBinding
import com.example.shortsdetector.platform.DetectionStatus

/**
 * 프로토타입용 최소 화면.
 *  - 접근성 서비스 활성화 상태 표시 + 설정 화면으로 이동
 *  - Android 13+ 알림 권한 요청
 *  - 감지 대상 앱 선택(YouTube 기본 켜짐, Instagram 실험적·기본 꺼짐)
 *  - 개입 기준 시간(정책 값)과 화면 중앙 팝업 사용 여부, 임시 시연 모드 스위치
 *  - 개입 팝업을 누르면 이 화면으로 온다([EXTRA_FROM_INTERVENTION])
 *  - 서비스가 연결된 경우에만 현재 감지 상태 표시
 *  - 사용 기록: 오늘 집계 한 줄 + 전체 삭제, 시연 준비 초기화(둘 다 확인 대화상자). 통계 화면은 다음 단계.
 *  - AI 목표 추천 임시 확인: 동의 스위치, 추천 버튼, 결과와 저장.
 *  - 개입 정책 에이전트 임시 확인: 현재 정책, 변경 기록, 지금 분석, 되돌리기, 끄기, 시연용 시나리오.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val io = Executors.newSingleThreadExecutor()

    @Volatile private var agentRunning = false

    private val requestNotificationPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        Toast.makeText(
            this,
            if (granted) R.string.toast_permission_granted else R.string.toast_permission_denied,
            Toast.LENGTH_SHORT
        ).show()
        refreshStatus()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        ShortsNotifier.ensureChannel(this)

        binding.buttonOpenAccessibilitySettings.setOnClickListener {
            openAccessibilitySettings()
        }
        binding.buttonRequestNotification.setOnClickListener {
            requestNotificationPermissionIfNeeded()
        }

        // 감지 대상 앱 선택. 서비스가 SharedPreferences 변경을 듣고 바로 반영한다.
        binding.switchYouTube.isChecked = DetectionPreferences.isYouTubeEnabled(this)
        binding.switchInstagram.isChecked = DetectionPreferences.isInstagramEnabled(this)
        binding.switchYouTube.setOnCheckedChangeListener { _, checked ->
            DetectionPreferences.setYouTubeEnabled(this, checked)
        }
        binding.switchInstagram.setOnCheckedChangeListener { _, checked ->
            DetectionPreferences.setInstagramEnabled(this, checked)
        }

        setupInterventionSettings()
        binding.buttonDeleteRecords.setOnClickListener { confirmDeleteRecords() }
        binding.buttonResetForDemo.setOnClickListener { confirmResetForDemo() }
        setupAiRecommendation()
        setupPolicyAgent()
        recordNotificationTap(intent)

        // 첫 실행이면 알림 권한을 미리 물어본다. 없으면 감지해도 알림이 안 뜬다.
        requestNotificationPermissionIfNeeded(silentIfGranted = true)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        recordNotificationTap(intent)
    }

    override fun onDestroy() {
        io.shutdown()
        super.onDestroy()
    }

    /** 개입 알림을 눌러서 왔으면 그 개입에 "앱 열기" 반응을 기록한다(팝업 반응은 서비스가 기록). */
    private fun recordNotificationTap(intent: Intent?) {
        val id = intent?.getStringExtra(EXTRA_INTERVENTION_ID) ?: return
        intent.removeExtra(EXTRA_INTERVENTION_ID) // 화면 회전 등으로 다시 기록하지 않게
        runIo {
            UsageDatabase.get(this).setInterventionAction(id, InterventionAction.OPEN_APP, System.currentTimeMillis())
        }
    }

    /** 오늘(기기 현지 시간) 집계와 저장된 세션 수를 보여 준다. */
    private fun refreshRecords() {
        runIo {
            val db = UsageDatabase.get(this)
            val zone = ZoneId.systemDefault()
            val today = LocalDate.now(zone)
            val from = today.atStartOfDay(zone).toInstant().toEpochMilli()
            val to = today.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
            val sessions = db.sessionsOverlapping(from, to).filterNot { it.isDemo }
            val day = DailyUsageCalculator.aggregate(sessions, zone, today, today).single()
            val total = db.sessionCount()
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                binding.textRecords.text = getString(
                    R.string.records_summary,
                    ShortsNotifier.formatWatch(this, day.estimatedWatchMs),
                    day.sessionCount,
                    total,
                    DetectionConfig.USAGE_RETENTION_DAYS.toInt()
                )
            }
        }
    }

    private fun confirmDeleteRecords() {
        AlertDialog.Builder(this)
            .setTitle(R.string.dialog_delete_records_title)
            .setMessage(R.string.dialog_delete_records_body)
            .setNegativeButton(R.string.dialog_cancel, null)
            .setPositiveButton(R.string.dialog_delete) { _, _ ->
                runIo {
                    UsageDatabase.get(this).deleteAll()
                    SavedTimeBaselinePreferences.clear(this)
                    InterventionPolicyPreferences.markRecordsCleared(this)
                    runOnUiThread {
                        if (isDestroyed) return@runOnUiThread
                        Toast.makeText(this, R.string.toast_records_deleted, Toast.LENGTH_SHORT).show()
                        refreshRecords()
                    }
                }
            }
            .show()
    }

    /** 시연 직전에 쓴다: 기록 전체 삭제 + 단계 Lv.1 + 연속 횟수·하루 카운터 초기화. 설정값은 둔다. */
    private fun confirmResetForDemo() {
        AlertDialog.Builder(this)
            .setTitle(R.string.dialog_reset_demo_title)
            .setMessage(R.string.dialog_reset_demo_body)
            .setNegativeButton(R.string.dialog_cancel, null)
            .setPositiveButton(R.string.dialog_reset_demo_confirm) { _, _ ->
                runIo {
                    UsageDatabase.get(this).deleteAll()
                    SavedTimeBaselinePreferences.clear(this)
                    InterventionPolicyPreferences.resetForDemo(this)
                    AgentPreferences.clearRunState(this)
                    runOnUiThread {
                        if (isDestroyed) return@runOnUiThread
                        Toast.makeText(this, R.string.toast_reset_demo_done, Toast.LENGTH_SHORT).show()
                        refreshRecords()
                        refreshStatus()
                        refreshAgentPanel()
                    }
                }
            }
            .show()
    }

    private fun setupAiRecommendation() {
        binding.switchAiConsent.isChecked = UserProfilePreferences.isAiConsentGiven(this)
        binding.switchAiConsent.setOnCheckedChangeListener { _, checked ->
            UserProfilePreferences.setAiConsent(this, checked)
        }
        binding.buttonAiRecommend.setOnClickListener { requestGoalRecommendation() }
    }

    /** 설문 화면이 없으므로 디버그 빌드는 고정 설문을 쓴다. 동의는 스위치 값을 따른다. */
    private fun surveyForRecommendation(): UserProfile? =
        UserProfilePreferences.load(this)
            ?: if (BuildConfig.DEBUG) {
                TestSurvey.profile.copy(aiRecommendationConsent = UserProfilePreferences.isAiConsentGiven(this))
            } else {
                null
            }

    /** IO 스레드에서 부른다. 정책은 시연 값이 아니라 저장된 값을 보낸다. */
    private fun agentInput(profile: UserProfile?): AgentInput {
        val db = UsageDatabase.get(this)
        val zone = ZoneId.systemDefault()
        val now = System.currentTimeMillis()
        val window = UsageStatsCalculator.dataWindow(now, zone)
        return AgentInputBuilder.build(
            profile, GoalPreferences.load(this), InterventionPolicyPreferences.load(this),
            db.sessionsOverlapping(window.fromMs, window.toMs),
            db.interventionsBetween(window.fromMs, window.toMs),
            db.recentPolicyChanges(RECENT_POLICY_CHANGES),
            zone, now
        )
    }

    private fun aiClient() = ClaudeMessagesClient(BuildConfig.ANTHROPIC_API_KEY, HttpUrlConnectionTransport())

    private fun requestGoalRecommendation() {
        binding.buttonAiRecommend.isEnabled = false
        binding.textAiResult.setText(R.string.ai_loading)
        binding.groupAiGoals.removeAllViews()
        val profile = surveyForRecommendation()
        runIo {
            val result = AiGoalRecommender(aiClient()).recommend(agentInput(profile))
            Log.i(ShortsDetectionService.TAG, "AI 목표 추천: ${result.elapsedMs}ms 실패=${result.failure} HTTP=${result.httpStatus} 출처=${result.goals.map { it.source }}")
            runOnUiThread { if (!isDestroyed) showRecommendation(result) }
        }
    }

    private fun showRecommendation(result: GoalRecommendation) {
        binding.buttonAiRecommend.isEnabled = true
        binding.textAiResult.text = getString(
            R.string.ai_result_summary, result.elapsedMs,
            result.failure?.name ?: getString(R.string.ai_no_failure),
            result.httpStatus?.toString() ?: "-"
        )
        for (goal in result.goals) {
            binding.groupAiGoals.addView(
                Button(this).apply {
                    isAllCaps = false
                    text = getString(R.string.ai_goal_item, goal.label, goal.reason.orEmpty(), goal.source.name)
                    setOnClickListener { saveGoal(goal, this) }
                }
            )
        }
    }

    private fun saveGoal(goal: Goal, button: View) {
        val saved = GoalPreferences.put(this, goal)
        if (saved) button.isEnabled = false
        Toast.makeText(
            this, if (saved) R.string.toast_goal_saved else R.string.toast_goal_full, Toast.LENGTH_SHORT
        ).show()
    }

    private fun policyAgent() = PolicyAgent(
        aiClient(),
        PreferencesAgentStore(InterventionPolicyPreferences.prefs(this), AgentPreferences.prefs(this)),
        UsageDatabase.get(this)
    )

    private fun setupPolicyAgent() {
        binding.switchAgentEnabled.isChecked = AgentPreferences.isEnabled(this)
        binding.switchAgentEnabled.setOnCheckedChangeListener { _, checked ->
            AgentPreferences.setEnabled(this, checked)
            refreshAgentPanel()
        }
        binding.buttonAgentRun.setOnClickListener { runPolicyAgent() }
        binding.buttonAgentRevert.setOnClickListener { revertAgentChange() }
        binding.buttonSeedA.setOnClickListener { insertSeed(DemoSeedData.Scenario.LATE_NIGHT_SKIPPER) }
        binding.buttonSeedB.setOnClickListener { insertSeed(DemoSeedData.Scenario.EASY_STOPPER) }
        binding.buttonGoalEval.visibility = if (BuildConfig.DEBUG) View.VISIBLE else View.GONE
        binding.buttonGoalEval.setOnClickListener { runGoalEval() }
    }

    private fun runPolicyAgent() {
        if (agentRunning) return
        agentRunning = true
        binding.buttonAgentRun.isEnabled = false
        binding.textAgentResult.setText(R.string.ai_loading)
        val profile = surveyForRecommendation()
        runIo {
            try {
                val result = policyAgent().run(agentInput(profile))
                Log.i(ShortsDetectionService.TAG, "에이전트: ${result.status} ${result.elapsedMs}ms 실패=${result.failure} HTTP=${result.httpStatus}")
            } finally {
                agentRunning = false
                runOnUiThread {
                    if (isDestroyed) return@runOnUiThread
                    binding.buttonAgentRun.isEnabled = true
                    refreshAgentPanel()
                    refreshStatus()
                }
            }
        }
    }

    /** 앱을 열 때 하루 한 번. 끄기 스위치와 동의는 [AgentSchedule] 이 본다. */
    private fun runAgentIfDue() {
        val consent = surveyForRecommendation()?.aiRecommendationConsent == true
        val due = AgentSchedule.isDue(
            System.currentTimeMillis(), AgentPreferences.lastRunAtMs(this), AgentPreferences.isEnabled(this), consent
        )
        if (due) runPolicyAgent()
    }

    private fun revertAgentChange() {
        val reason = getString(R.string.agent_revert_reason)
        runIo {
            val reverted = policyAgent().revertLatest(reason)
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                Toast.makeText(
                    this, if (reverted != null) R.string.toast_agent_reverted else R.string.toast_agent_nothing_to_revert,
                    Toast.LENGTH_SHORT
                ).show()
                refreshAgentPanel()
                refreshStatus()
            }
        }
    }

    /** 다른 시나리오의 시드를 지우고 넣는다. 실제 기록은 그대로 둔다. */
    private fun insertSeed(scenario: DemoSeedData.Scenario) {
        runIo {
            val db = UsageDatabase.get(this)
            val zone = ZoneId.systemDefault()
            val batch = DemoSeedData.build(scenario, LocalDate.now(zone), zone, System.currentTimeMillis())
            db.deleteSeedRecords()
            batch.sessions.forEach(db::upsertSession)
            batch.interventions.forEach(db::insertIntervention)
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                Toast.makeText(this, getString(R.string.toast_seed_done, batch.sessions.size, batch.interventions.size), Toast.LENGTH_SHORT).show()
                refreshRecords()
            }
        }
    }

    /**
     * 디버그 전용 품질 점검: 설문 조합을 실제 호출하고 결과를 앱 캐시 파일에 쓴다.
     * 응답 내용을 로그에 남기지 않기 위해 파일을 쓰며, 확인 후 지운다.
     */
    private fun runGoalEval() {
        if (!BuildConfig.DEBUG) return
        val consent = UserProfilePreferences.isAiConsentGiven(this)
        binding.textAgentResult.setText(R.string.ai_loading)
        runIo {
            val rows = TestSurvey.cases.flatMap { (name, profile) ->
                val r = AiGoalRecommender(aiClient()).recommend(agentInput(profile.copy(aiRecommendationConsent = consent)))
                r.goals.map { g ->
                    listOf(name, g.type.name, g.value, g.label, g.reason.orEmpty(), g.reason.orEmpty().length, g.source.name, r.failure ?: "-", r.elapsedMs)
                        .joinToString("\t")
                }
            }
            File(cacheDir, GOAL_EVAL_FILE).writeText(rows.joinToString("\n"))
            runOnUiThread { if (!isDestroyed) binding.textAgentResult.text = getString(R.string.ai_goal_eval_done, rows.size) }
        }
    }

    private fun refreshAgentPanel() {
        val messages = AgentPreferences.activeMessages(this)
        val policy = InterventionPolicyPreferences.load(this)
        binding.textAgentPolicy.text = getString(
            R.string.agent_policy_summary,
            policy.thresholdMs / 1000, policy.cooldownMs / 1000, policy.maxDailyInterventions, policy.level, policy.maxLevel,
            messages?.lv1Message ?: getString(R.string.agent_fixed_copy),
            messages?.lv2Question ?: getString(R.string.agent_fixed_copy)
        )
        val last = AgentPreferences.lastResult(this)
        binding.textAgentResult.text = last?.let { getString(R.string.agent_last_result, it) }.orEmpty()
        runIo {
            val changes = UsageDatabase.get(this).recentPolicyChanges(PANEL_CHANGES).filterNot { it.isDemo }
            val zone = ZoneId.systemDefault()
            val format = DateTimeFormatter.ofPattern("MM-dd HH:mm")
            val text = changes.joinToString("\n") {
                val at = format.format(Instant.ofEpochMilli(it.occurredAtMs).atZone(zone))
                "$at ${it.source} · ${AgentPolicyRules.describeChange(it.previousPolicy, it.newPolicy)} · ${it.reasonText}"
            }
            runOnUiThread {
                if (!isDestroyed) binding.textAgentChanges.text = text.ifEmpty { getString(R.string.agent_no_changes) }
            }
        }
    }

    /** DB 작업은 메인 스레드 밖에서. 실패는 화면에 "못 읽음"으로만 표시하고 원문을 남기지 않는다. */
    private fun runIo(block: () -> Unit) {
        try {
            io.execute {
                try {
                    block()
                } catch (e: RuntimeException) {
                    Log.w(ShortsDetectionService.TAG, "기록 화면 작업 실패: ${e.javaClass.simpleName}")
                    runOnUiThread {
                        if (!isDestroyed) binding.textRecords.setText(R.string.records_unavailable)
                    }
                }
            }
        } catch (e: RuntimeException) {
            Log.w(ShortsDetectionService.TAG, "기록 화면 작업 예약 실패: ${e.javaClass.simpleName}")
        }
    }

    private fun setupInterventionSettings() {
        val current = InterventionPolicyPreferences.load(this).thresholdMs
        DetectionConfig.WATCH_THRESHOLD_CHOICES_MS.forEach { ms ->
            val label = ShortsNotifier.formatWatch(this, ms)
            val button = RadioButton(this).apply {
                id = android.view.View.generateViewId()
                text = if (ms < DetectionConfig.TEST_THRESHOLD_BELOW_MS) {
                    getString(R.string.threshold_test_suffix, label)
                } else {
                    label
                }
                isChecked = ms == current
                setOnCheckedChangeListener { _, checked ->
                    if (checked) changeThreshold(ms)
                }
            }
            binding.groupThreshold.addView(button)
        }
        binding.switchPopup.isChecked = DetectionPreferences.isPopupEnabled(this)
        binding.switchPopup.setOnCheckedChangeListener { _, checked ->
            DetectionPreferences.setPopupEnabled(this, checked)
            refreshStatus()
        }
        // 정식 설정 화면은 Compose 단계에서 만든다. 켤 때마다 시연용 단계가 Lv.1 부터 시작한다.
        binding.switchDemoMode.isChecked = InterventionPolicyPreferences.isDemoMode(this)
        binding.switchDemoMode.setOnCheckedChangeListener { _, checked ->
            InterventionPolicyPreferences.setDemoMode(this, checked)
            refreshStatus()
        }
    }

    /** 사용자가 바꾼 기준 시간은 정책에 저장하고 source=USER 로 기록한다. */
    private fun changeThreshold(ms: Long) {
        val before = InterventionPolicyPreferences.load(this)
        val change = InterventionCoordinator.userThresholdChange(
            before, ms, UUID.randomUUID().toString(), System.currentTimeMillis(),
            getString(R.string.policy_reason_user_threshold)
        ) ?: return
        InterventionPolicyPreferences.save(this, change.newPolicy)
        runIo { UsageDatabase.get(this).insertPolicyChange(change) }
    }

    override fun onResume() {
        super.onResume()
        // 설정 화면에서 돌아왔을 때 상태를 다시 읽는다.
        refreshStatus()
        refreshRecords()
        refreshAgentPanel()
        runAgentIfDue()
    }

    private fun refreshStatus() {
        val accessibilityOn = isAccessibilityServiceEnabled()
        val notificationOn = ShortsNotifier.hasPostPermission(this)

        binding.textAccessibilityStatus.text = getString(
            R.string.status_accessibility,
            getString(if (accessibilityOn) R.string.state_on else R.string.state_off)
        )
        binding.textNotificationStatus.text = getString(
            R.string.status_notification,
            getString(if (notificationOn) R.string.state_granted else R.string.state_denied)
        )
        binding.buttonRequestNotification.isEnabled = !notificationOn

        // 서비스가 실제로 연결돼 있을 때만 상태를 보여 준다. 꺼져 있는데 "작동 중"처럼 보이면 안 된다.
        val status = DetectionStatus.current
        binding.textDetectionStatus.text = when {
            !accessibilityOn || !status.connected -> getString(R.string.status_detection_inactive)
            status.reason != null ->
                getString(R.string.status_detection_detail, status.state.name, status.reason.name)
            else -> getString(R.string.status_detection, status.state.name)
        }

        val policy = InterventionPolicyPreferences.resolve(this)
        binding.textPolicyStatus.text = getString(
            if (policy.demoMode) R.string.status_policy_demo else R.string.status_policy,
            policy.policy.level,
            ShortsNotifier.formatWatch(this, policy.thresholdMs)
        )

        binding.textHint.text = when {
            !accessibilityOn -> getString(R.string.hint_enable_accessibility)
            !notificationOn && !DetectionPreferences.isPopupEnabled(this) ->
                getString(R.string.hint_enable_notification)
            else -> getString(R.string.hint_ready)
        }
    }

    private fun openAccessibilitySettings() {
        try {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            Toast.makeText(this, R.string.toast_find_service, Toast.LENGTH_LONG).show()
        } catch (e: android.content.ActivityNotFoundException) {
            Toast.makeText(this, R.string.toast_settings_unavailable, Toast.LENGTH_SHORT).show()
        }
    }

    private fun requestNotificationPermissionIfNeeded(silentIfGranted: Boolean = false) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        if (ShortsNotifier.hasPostPermission(this)) {
            if (!silentIfGranted) {
                Toast.makeText(this, R.string.toast_permission_granted, Toast.LENGTH_SHORT).show()
            }
            return
        }
        requestNotificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    companion object {
        /** 개입 팝업에서 열렸음을 표시. 지금은 화면 구분 없이 같은 화면을 연다(개입 선택 화면은 다음 단계). */
        const val EXTRA_FROM_INTERVENTION = "from_intervention"

        private const val RECENT_POLICY_CHANGES = 10
        private const val PANEL_CHANGES = 3
        private const val GOAL_EVAL_FILE = "goal_eval.tsv"

        /** 개입 알림을 눌렀을 때 반응을 기록할 개입 기록 id */
        const val EXTRA_INTERVENTION_ID = "intervention_id"
    }

    /** 설정 > 접근성에서 우리 서비스가 켜져 있는지 확인 */
    private fun isAccessibilityServiceEnabled(): Boolean {
        val target = ComponentName(this, ShortsDetectionService::class.java)

        val manager = getSystemService(AccessibilityManager::class.java)
        if (manager != null) {
            val enabled = manager.getEnabledAccessibilityServiceList(
                AccessibilityServiceInfo.FEEDBACK_ALL_MASK
            )
            if (enabled.any { ComponentName.unflattenFromString(it.id) == target }) return true
        }

        // 일부 기기에서 위 목록 갱신이 늦어, Secure 설정 문자열도 함께 확인한다.
        val setting = Settings.Secure.getString(
            contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false

        return setting.split(':').any {
            ComponentName.unflattenFromString(it.trim()) == target
        }
    }
}
