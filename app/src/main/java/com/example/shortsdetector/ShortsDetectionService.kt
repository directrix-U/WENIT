package com.example.shortsdetector

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.SharedPreferences
import android.graphics.Point
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.example.shortsdetector.data.DetectionPreferences
import com.example.shortsdetector.data.InterventionPolicyPreferences
import com.example.shortsdetector.data.usage.BackgroundUsageStore
import com.example.shortsdetector.data.goal.Goal
import com.example.shortsdetector.data.goal.GoalPreferences
import com.example.shortsdetector.data.usage.DailyUsageCalculator
import com.example.shortsdetector.data.usage.InterventionMethod
import com.example.shortsdetector.data.usage.InterventionOutcome
import com.example.shortsdetector.data.usage.InterventionUserReason
import com.example.shortsdetector.data.usage.SessionRecorder
import com.example.shortsdetector.data.usage.SessionStatus
import com.example.shortsdetector.data.usage.SystemUsageClock
import com.example.shortsdetector.data.usage.UsageDatabase
import com.example.shortsdetector.detection.DetectionEngine
import com.example.shortsdetector.detection.EngineOutput
import com.example.shortsdetector.detection.adapters.instagram.InstagramReelsAdapter
import com.example.shortsdetector.detection.adapters.youtube.YouTubeShortsAdapter
import com.example.shortsdetector.detection.adapters.youtube.YouTubeViewIds
import com.example.shortsdetector.detection.features.AccessibilitySnapshotter
import com.example.shortsdetector.detection.state.DetectionTuning
import com.example.shortsdetector.intervention.GoalRemaining
import com.example.shortsdetector.intervention.InterventionCoordinator
import com.example.shortsdetector.intervention.InterventionCopyInput
import com.example.shortsdetector.intervention.InterventionPopup
import com.example.shortsdetector.intervention.PreferencesPolicyStore
import com.example.shortsdetector.intervention.ResourceInterventionCopyProvider
import com.example.shortsdetector.platform.DetectionStatus
import com.example.shortsdetector.platform.ScrollEventMapper
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * 숏폼(YouTube Shorts, 실험적으로 Instagram Reels) 재생 화면을 감지해서 로컬 알림을 띄우는 접근성 서비스.
 *
 * 이 클래스는 플랫폼 신호를 모으고 결과를 실행하는 역할만 한다. 판정은 [DetectionEngine]:
 *  1) 패키지  : packageNames 로 OS 레벨에서 1차 차단 (설정에서 켠 앱만)
 *  2) 스냅샷  : 활성 창 트리를 구조 값만 담은 스냅샷으로 복사 (화면 원문은 복사하지 않음)
 *  3) 어댑터  : 앱별 규칙 → SHORTFORM / PARTIAL / NOT_SHORTFORM / UNKNOWN
 *  4) 상태    : 공통 상태 머신 (연속 확인, 일시 가림, 이탈 대기)
 *  5) 개입    : 세션 안 확정 시청시간이 정책의 기준 시간에 닿으면 1회 (쿨다운·하루 상한 적용).
 *              현재 단계의 팝업(TYPE_ACCESSIBILITY_OVERLAY)을 띄우고, 팝업을 끄거나 실패하면 알림으로 대신한다.
 *              세션이 끝나면 팝업·알림을 지운다. 반응은 [InterventionCoordinator] 가 기록하고 단계에 반영한다.
 *
 * 감시 타이머: 확인 중이거나 세션이 열린 동안에는 이벤트를 기다리지 않고
 * [DetectionConfig.WATCHDOG_INTERVAL_MS] 마다 직접 화면을 확인한다.
 * 대상 앱을 벗어나면 packageNames 필터 때문에 이벤트가 오지 않고, 숏폼을 가만히 볼 때도
 * 이벤트가 끊기기 때문에, 이벤트만으로는 이탈/확정을 제때 알 수 없다.
 *
 * 컴포넌트 이름(패키지/클래스)은 바꾸지 않는다. 바꾸면 사용자가 켜 둔 접근성 설정이 풀린다.
 */
class ShortsDetectionService : AccessibilityService() {

    /** 마지막으로 트리를 실제 순회한 시각 (앱 레벨 스로틀용) */
    private var lastAnalysisAt = 0L

    private var screenWidth = 0
    private var screenHeight = 0

    private val tuning = DetectionTuning()
    private val engine = DetectionEngine(
        tuning,
        listOf(YouTubeShortsAdapter(tuning), InstagramReelsAdapter(tuning)),
        DetectionConfig.MIN_RENOTIFY_INTERVAL_MS,
        currentDay = { LocalDate.now().toEpochDay() }
    )
    private val snapshotter = AccessibilitySnapshotter(DetectionConfig.MAX_NODES_VISITED)
    private val popup = InterventionPopup(this)

    /** 사용 기록(세션·개입). onServiceConnected 에서 만든다. 저장 실패는 감지에 영향을 주지 않는다. */
    private var usageStore: BackgroundUsageStore? = null
    private var recorder: SessionRecorder? = null
    private var coordinator: InterventionCoordinator? = null
    private val copyProvider by lazy { ResourceInterventionCopyProvider(this) }

    /** 팝업 문구용 캐시. 메인 스레드에서만 읽고 쓴다. */
    private var goals: List<Goal> = emptyList()
    private var todayWatchBeforeSessionMs = 0L

    private val handler = Handler(Looper.getMainLooper())
    private var watchdogScheduled = false

    private val watchdog = Runnable {
        watchdogScheduled = false
        lastAnalysisAt = SystemClock.elapsedRealtime()
        analyzeCurrentScreen(SOURCE_TIMER)
        scheduleWatchdogIfNeeded()
    }

    private val prefsListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        when (key) {
            DetectionPreferences.KEY_POPUP_ENABLED ->
                if (!DetectionPreferences.isPopupEnabled(this)) closeIntervention()
            else -> applyEnabledPackages()
        }
    }

    /** 정책·시연 모드·목표가 바뀌면 다음 판정부터 반영한다. */
    private val policyListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == InterventionPolicyPreferences.KEY_RECORDS_CLEARED_AT) {
            engine.resetDailyInterventions()
            Log.i(TAG, "기록 삭제: 하루 개입 수 초기화")
        } else {
            applyPolicy()
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()

        ShortsNotifier.ensureChannel(this)
        startUsageRecording()
        cacheScreenSize()
        DetectionPreferences.prefs(this).registerOnSharedPreferenceChangeListener(prefsListener)
        InterventionPolicyPreferences.prefs(this).registerOnSharedPreferenceChangeListener(policyListener)
        GoalPreferences.prefs(this).registerOnSharedPreferenceChangeListener(policyListener)
        applyEnabledPackages()
        applyPolicy()
        DetectionStatus.onConnected()

        Log.i(
            TAG,
            "서비스 연결됨 / targets=${engine.enabledPackages} " +
                "screen=${screenWidth}x${screenHeight} " +
                "기준(yt=${YouTubeShortsAdapter.RULE_VERSION}, ig=${InstagramReelsAdapter.RULE_VERSION}, " +
                "areaRatio>=${tuning.minPlayerAreaRatio}, confirm=${tuning.confirmationCount}, " +
                "exitGrace=${tuning.exitGraceMs}ms, 개입임계=${engine.watchThresholdMs}ms)"
        )
    }

    private fun startUsageRecording() {
        if (recorder != null) return
        try {
            val store = BackgroundUsageStore(UsageDatabase.get(this))
            val r = SessionRecorder(store, SystemUsageClock)
            r.onServiceStarted(TimeUnit.DAYS.toMillis(DetectionConfig.USAGE_RETENTION_DAYS))
            usageStore = store
            recorder = r
            coordinator = InterventionCoordinator(
                store, PreferencesPolicyStore(InterventionPolicyPreferences.prefs(this)), copyProvider, SystemUsageClock
            )
            seedDailyInterventions(store)
        } catch (e: RuntimeException) {
            Log.w(TAG, "기록 저장소 시작 실패: ${e.javaClass.simpleName}")
        }
    }

    /** 재시작해도 하루 상한이 0 부터 다시 세지지 않게 오늘 기록에서 복구한다. 시연 카운터는 이번 시연 이후만 센다. */
    private fun seedDailyInterventions(store: BackgroundUsageStore) {
        val (from, to) = todayRange()
        val demoFrom = InterventionPolicyPreferences.resolve(this).demoRunId ?: Long.MAX_VALUE
        store.read { s ->
            val shown = s.interventionsBetween(from, to).filter {
                it.method == InterventionMethod.POPUP || it.method == InterventionMethod.NOTIFICATION
            }
            val real = shown.count { !it.isDemo }
            val demo = shown.count { it.isDemo && it.occurredAtMs >= demoFrom }
            handler.post { engine.seedDailyInterventions(real, demo) }
        }
    }

    /** 세션이 시작될 때 오늘 앞선 세션의 시청시간을 읽어 둔다("목표까지 M분" 표시용 근사값). */
    private fun refreshTodayWatch(startedSession: Int) {
        val (from, to) = todayRange()
        usageStore?.read { s ->
            val zone = ZoneId.systemDefault()
            val today = DailyUsageCalculator.dateOf(from, zone)
            val earlier = s.sessionsOverlapping(from, to).filter { it.status != SessionStatus.OPEN }
            val watch = DailyUsageCalculator.aggregate(earlier, zone, today, today).single().estimatedWatchMs
            handler.post { if (engine.sessionProgress()?.sessionNumber == startedSession) todayWatchBeforeSessionMs = watch }
        }
    }

    private fun todayRange(): Pair<Long, Long> {
        val zone = ZoneId.systemDefault()
        val today = LocalDate.now(zone)
        return today.atStartOfDay(zone).toInstant().toEpochMilli() to
            today.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
    }

    private fun stopUsageRecording() {
        recorder = null
        coordinator = null
        usageStore?.shutdown()
        usageStore = null
    }

    /** 기록 실패가 감지·개입을 막지 않게 격리한다. */
    private inline fun record(block: (SessionRecorder) -> Unit) {
        val r = recorder ?: return
        try {
            block(r)
        } catch (e: RuntimeException) {
            Log.w(TAG, "기록 실패: ${e.javaClass.simpleName}")
        }
    }

    private fun applyPolicy() {
        goals = GoalPreferences.load(this)
        val r = InterventionPolicyPreferences.resolve(this)
        engine.applyPolicy(r.thresholdMs, r.policy.cooldownMs, r.policy.maxDailyInterventions, r.demoRunId)
        Log.i(
            TAG,
            "개입 정책: 기준=${r.thresholdMs}ms 단계=${r.policy.level} 쿨다운=${r.policy.cooldownMs}ms " +
                "상한=${r.policy.maxDailyInterventions} 시연=${r.demoMode}"
        )
    }

    /**
     * 설정에서 켠 앱만 이벤트를 받도록 serviceInfo 를 갱신한다.
     *
     * 런타임에 대상 앱을 바꿀 수 있어서 XML 에 더해 코드에서도 세팅한다.
     */
    private fun applyEnabledPackages() {
        val now = SystemClock.elapsedRealtime()
        val enabled = DetectionPreferences.enabledPackages(this)
        engine.setEnabledPackages(enabled, now)?.let { handleOutput(it, "설정") }
        scheduleWatchdogIfNeeded()

        // packageNames 가 비면 "모든 앱"이 되므로 절대 비우지 않는다.
        // 전부 꺼진 경우 YouTube 이벤트만 받되 엔진이 대상 아님으로 무시한다.
        val filter = enabled.ifEmpty { setOf(YouTubeViewIds.PACKAGE) }
        try {
            serviceInfo = (serviceInfo ?: AccessibilityServiceInfo()).apply {
                eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or
                    AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED or
                    AccessibilityEvent.TYPE_VIEW_SCROLLED
                feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
                packageNames = filter.toTypedArray()
                notificationTimeout = NOTIFICATION_TIMEOUT_MS
                flags = flags or AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
            }
        } catch (e: RuntimeException) {
            Log.w(TAG, "serviceInfo 갱신 실패: ${e.javaClass.simpleName}")
        }
        Log.i(TAG, "감지 대상 앱: ${engine.enabledPackages.ifEmpty { setOf("(없음)") }}")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        try {
            handleEvent(event)
        } catch (e: RuntimeException) {
            // 어떤 경우에도 서비스 전체가 죽지 않게 한다. 원문 없이 예외 종류만 남긴다.
            Log.w(TAG, "이벤트 처리 실패: ${e.javaClass.simpleName}")
        }
    }

    private fun handleEvent(event: AccessibilityEvent) {
        // packageNames 로 이미 걸러지지만, 방어적으로 한 번 더 확인한다.
        val pkg = event.packageName?.toString()
        if (!engine.isTarget(pkg)) return

        val now = SystemClock.elapsedRealtime()

        if (event.eventType == AccessibilityEvent.TYPE_VIEW_SCROLLED) {
            ScrollEventMapper.toSignal(event, now, screenHeight)?.let { s ->
                val flip = engine.onScroll(s)
                // 페이지 넘김 가설(TODO: VERIFY ON DEVICE) 확인용. 구조 값만 남긴다.
                Log.d(
                    TAG,
                    "스크롤 cls=${s.className?.substringAfterLast('.')} from=${s.fromIndex} to=${s.toIndex} " +
                        "n=${s.itemCount} dx=${s.deltaX} dy=${s.deltaY} flip=$flip"
                )
            }
        }

        // notificationTimeout(OS) 에 더한 앱 레벨 스로틀. 잦은 트리 순회를 막는다.
        if (now - lastAnalysisAt < DetectionConfig.MIN_ANALYSIS_INTERVAL_MS) return
        lastAnalysisAt = now

        analyzeCurrentScreen(SOURCE_EVENT)
        scheduleWatchdogIfNeeded()
    }

    override fun onInterrupt() {
        endSession("onInterrupt")
    }

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        endSession("onUnbind")
        stopWatchdog()
        closeIntervention()
        unregisterPreferenceListeners()
        DetectionStatus.onDisconnected()
        stopUsageRecording()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        stopWatchdog()
        closeIntervention()
        stopUsageRecording()
        unregisterPreferenceListeners()
        DetectionStatus.onDisconnected()
        super.onDestroy()
    }

    private fun unregisterPreferenceListeners() {
        DetectionPreferences.prefs(this).unregisterOnSharedPreferenceChangeListener(prefsListener)
        InterventionPolicyPreferences.prefs(this).unregisterOnSharedPreferenceChangeListener(policyListener)
        GoalPreferences.prefs(this).unregisterOnSharedPreferenceChangeListener(policyListener)
    }

    /** 팝업을 닫는다. 답을 받지 못한 개입은 IGNORED 로 끝낸다. */
    private fun closeIntervention() {
        popup.dismiss()
        engine.setWatchHold(false)
        coordinator?.let { c -> guarded { c.abandon() } }
    }

    /** 기록 실패가 감지·개입을 막지 않게 격리한다. */
    private inline fun guarded(block: () -> Unit) {
        try {
            block()
        } catch (e: RuntimeException) {
            Log.w(TAG, "개입 기록 실패: ${e.javaClass.simpleName}")
        }
    }

    private fun endSession(reason: String) {
        handleOutput(engine.forceEnd(SystemClock.elapsedRealtime()), reason)
        scheduleWatchdogIfNeeded()
    }

    /** 확인 중이거나 세션이 열려 있을 때만 타이머를 돌린다. 평소(숏폼 아님)에는 이벤트만으로 충분하다. */
    private fun scheduleWatchdogIfNeeded() {
        val needed = engine.state.needsWatchdog
        if (needed && !watchdogScheduled) {
            handler.postDelayed(watchdog, DetectionConfig.WATCHDOG_INTERVAL_MS)
            watchdogScheduled = true
        } else if (!needed && watchdogScheduled) {
            stopWatchdog()
        }
    }

    private fun stopWatchdog() {
        handler.removeCallbacks(watchdog)
        watchdogScheduled = false
    }

    // ---------------------------------------------------------------------

    /** @param source 판정을 실행한 쪽. 로그에만 쓴다(타이머가 제 역할을 하는지 확인용). */
    private fun analyzeCurrentScreen(source: String) {
        if (screenWidth <= 0 || screenHeight <= 0) cacheScreenSize()
        val now = SystemClock.elapsedRealtime()

        val output = try {
            observe(now)
        } catch (e: RuntimeException) {
            // 노드가 사라지는 등 예상 못 한 예외: 세션을 바로 끊지 않고 "모름"으로 넘긴다.
            Log.w(TAG, "[$source] 관찰 실패: ${e.javaClass.simpleName}")
            engine.onObservationError(now, engine.sessionPackage)
        }
        handleOutput(output, source)
    }

    private fun observe(now: Long): EngineOutput {
        // 루트를 못 얻는 경우(전환 중, 알림창, 홈 등)는 원인을 구분할 수 없다. 이탈 대기로 넘긴다.
        val root: AccessibilityNodeInfo = getRootInActiveWindow() ?: return engine.onRootUnavailable(now)

        try {
            val pkg = root.packageName?.toString()
            // 활성 창이 대상 앱이 아니면(시스템 다이얼로그 등) 판정하지 않는다.
            val adapter = engine.adapterFor(pkg) ?: return engine.onForeignWindow(now, pkg)

            val started = SystemClock.elapsedRealtime()
            val snapshot = snapshotter.capture(
                root, adapter.packageName, screenWidth, screenHeight,
                adapter.lookupViewIds, adapter.needsFullTree
            )
            val output = engine.onSnapshot(now, snapshot)
            val elapsed = SystemClock.elapsedRealtime() - started
            Log.d(TAG, "측정 nodes=${snapshot.nodes.size}${if (snapshot.truncated) "(잘림)" else ""} / ${elapsed}ms")
            return output
        } finally {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                @Suppress("DEPRECATION")
                root.recycle()
            }
        }
    }

    private fun handleOutput(output: EngineOutput, source: String) {
        val step = output.step
        val verdict = output.observation.verdict
        DetectionStatus.update(step.current, output.observation.packageName, verdict?.reason)
        record { it.onOutput(output, engine.sessionProgress()) }

        // 판정 로그: 원문 없이 상태 / 근거 코드 / 신호 플래그 / 면적비만 남긴다.
        if (!step.ignored) {
            val v = verdict?.let {
                String.format(
                    Locale.US, "%s/%s/%s 면적비=%.3f %s",
                    it.kind, it.confidence, it.reason, it.playerAreaRatio, it.signals
                )
            } ?: output.observation.window.name
            val hits = if (step.candidateHits > 0) " hits=${step.candidateHits}/${tuning.confirmationCount}" else ""
            Log.d(TAG, "[$source] ${step.previous}→${step.current}$hits $v")
        }

        output.endedSession?.let { s ->
            Log.i(
                TAG,
                "세션 #${s.sessionNumber} 종료: ${s.endReason}${s.exitCause?.let { "($it)" } ?: ""} " +
                    "pkg=${s.packageName} 추정시청=${s.estimatedWatchMs}ms(댓글창 ${s.partialWatchMs}ms 포함) 가림=${s.obscuredMs}ms " +
                    "불확실=${s.uncertainMs}ms 최대신뢰도=${s.maxConfidence} rule=${s.ruleVersion}"
            )
        }
        // 종료 판정(grace)을 기다리지 않고 오버레이만 먼저 닫는다. 알림은 세션이 끝날 때 지운다.
        if (output.exitSignalStarted && popup.isShowing) {
            Log.i(TAG, "[$source] 이탈 신호: 개입 오버레이 닫음")
            closeIntervention()
        }
        if (output.cancelNotification) {
            ShortsNotifier.cancel(this)
            closeIntervention()
        }

        step.startedSession?.let { n ->
            todayWatchBeforeSessionMs = 0L
            refreshTodayWatch(n)
            Log.i(
                TAG,
                "[$source] ★ 숏폼 감지 #$n pkg=${output.observation.packageName} 신뢰도=${step.startedConfidence} " +
                    "(개입은 ${engine.watchThresholdMs}ms 시청 후)"
            )
        }
        step.resumedSession?.let { n ->
            Log.i(TAG, "[$source] ★ 세션 #$n 이어짐 pkg=${output.observation.packageName} (누적 시청시간 유지)")
        }
        if (output.suppressedByCooldown) {
            Log.i(TAG, "[$source] 개입 임계 도달 (쿨다운 — 생략)")
            val suppressed = InterventionPolicyPreferences.resolve(this)
            record {
                it.onIntervention(
                    engine.sessionProgress()?.sessionNumber, InterventionMethod.SUPPRESSED_BY_COOLDOWN,
                    engine.sessionProgress()?.estimatedWatchMs ?: 0L, engine.watchThresholdMs,
                    level = suppressed.policy.level, isDemo = suppressed.demoMode
                )
            }
        }
        if (output.suppressedByDailyLimit) {
            Log.i(TAG, "[$source] 개입 임계 도달 (하루 상한 — 생략)")
            val suppressed = InterventionPolicyPreferences.resolve(this)
            record {
                it.onIntervention(
                    engine.sessionProgress()?.sessionNumber, InterventionMethod.SUPPRESSED_BY_DAILY_LIMIT,
                    engine.sessionProgress()?.estimatedWatchMs ?: 0L, engine.watchThresholdMs,
                    level = suppressed.policy.level, isDemo = suppressed.demoMode
                )
            }
        }
        output.notifySession?.let { n ->
            intervene(n, output.observation.packageName, output.notifyWatchMs ?: 0L, output.observation.atMs, source)
        }
    }

    /** 현재 단계의 팝업을 우선 띄우고, 꺼져 있거나 실패하면 알림으로 대신한다. 둘 다 실패하면 쿨다운을 걸지 않는다. */
    private fun intervene(session: Int, pkg: String?, watchMs: Long, atMs: Long, source: String) {
        val interventionId = recorder?.newInterventionId()
        val resolved = InterventionPolicyPreferences.resolve(this)
        val level = resolved.policy.level
        val copy = copyProvider.copyFor(
            InterventionCopyInput(level, watchMs, GoalRemaining.minutes(goals, todayWatchBeforeSessionMs, watchMs))
        )
        val popupShown = DetectionPreferences.isPopupEnabled(this) && popup.show(level, copy, popupListener)
        val notified = !popupShown &&
            ShortsNotifier.notifyWatchThreshold(this, session, pkg, watchMs, interventionId)
        val shown = popupShown || notified
        Log.i(
            TAG,
            "[$source] ★ 개입 #$session Lv.$level 시청=${watchMs}ms 방식=${if (popupShown) "팝업" else if (shown) "알림" else "실패"}"
        )
        if (interventionId != null) record {
            val method = when {
                popupShown -> InterventionMethod.POPUP
                notified -> InterventionMethod.NOTIFICATION
                else -> InterventionMethod.FAILED
            }
            it.onIntervention(
                session, method, watchMs, engine.watchThresholdMs,
                level = level, isDemo = resolved.demoMode, id = interventionId
            )
            if (shown) coordinator?.begin(interventionId, session, level)
        }
        if (shown) engine.onNotificationPosted(atMs)
    }

    private val popupListener = object : InterventionPopup.Listener {
        override fun onReasonChosen(reason: InterventionUserReason) {
            coordinator?.let { c -> guarded { c.chooseReason(reason) } }
        }

        override fun onResponse(outcome: InterventionOutcome) {
            val c = coordinator ?: return
            guarded {
                val result = c.respond(outcome) ?: return@guarded
                Log.i(TAG, "개입 반응: Lv.${result.level} $outcome → Lv.${result.nextLevel}")
                val again = result.reinterveneAfterWatchMs ?: return@guarded
                engine.sessionProgress()?.takeIf { it.sessionNumber == result.sessionNumber }?.let {
                    engine.rearmIntervention(it.sessionNumber, it.estimatedWatchMs + again)
                }
            }
        }

        override fun onBreathingStarted() = engine.setWatchHold(true)

        override fun onBreathingFinished() = engine.setWatchHold(false)

        override fun onTimedOut() = closeIntervention()
    }

    @Suppress("DEPRECATION")
    private fun cacheScreenSize() {
        val wm = getSystemService(WindowManager::class.java) ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val bounds = wm.currentWindowMetrics.bounds
            screenWidth = bounds.width()
            screenHeight = bounds.height()
        } else {
            val size = Point()
            wm.defaultDisplay.getRealSize(size)
            screenWidth = size.x
            screenHeight = size.y
        }
    }

    companion object {
        const val TAG = "ShortsDetector"

        /** OS 레벨 디바운스. accessibility_service_config.xml 의 값과 맞춘다. */
        private const val NOTIFICATION_TIMEOUT_MS = 300L

        private const val SOURCE_EVENT = "이벤트"
        private const val SOURCE_TIMER = "타이머"
    }
}
