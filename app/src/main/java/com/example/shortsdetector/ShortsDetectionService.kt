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
import com.example.shortsdetector.data.usage.InterventionAction
import com.example.shortsdetector.data.usage.InterventionMethod
import com.example.shortsdetector.data.usage.SessionRecorder
import com.example.shortsdetector.data.usage.SystemUsageClock
import com.example.shortsdetector.data.usage.UsageDatabase
import com.example.shortsdetector.detection.DetectionEngine
import com.example.shortsdetector.detection.EngineOutput
import com.example.shortsdetector.detection.adapters.instagram.InstagramReelsAdapter
import com.example.shortsdetector.detection.adapters.youtube.YouTubeShortsAdapter
import com.example.shortsdetector.detection.adapters.youtube.YouTubeViewIds
import com.example.shortsdetector.detection.features.AccessibilitySnapshotter
import com.example.shortsdetector.detection.state.DetectionTuning
import com.example.shortsdetector.intervention.InterventionPopup
import com.example.shortsdetector.platform.DetectionStatus
import com.example.shortsdetector.platform.ScrollEventMapper
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
 *  5) 개입    : 세션 안 확정 시청시간이 설정한 임계값에 닿으면 1회 (재알림 쿨다운).
 *              화면 중앙 팝업(TYPE_ACCESSIBILITY_OVERLAY)을 띄우고, 팝업을 끄거나 실패하면 알림으로 대신한다.
 *              세션이 끝나면 팝업·알림을 지운다.
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
        DetectionConfig.MIN_RENOTIFY_INTERVAL_MS
    )
    private val snapshotter = AccessibilitySnapshotter(DetectionConfig.MAX_NODES_VISITED)
    private val popup = InterventionPopup(this)

    /** 사용 기록(세션·개입). onServiceConnected 에서 만든다. 저장 실패는 감지에 영향을 주지 않는다. */
    private var usageStore: BackgroundUsageStore? = null
    private var recorder: SessionRecorder? = null

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
            DetectionPreferences.KEY_WATCH_THRESHOLD_MS -> applyWatchThreshold()
            DetectionPreferences.KEY_POPUP_ENABLED ->
                if (!DetectionPreferences.isPopupEnabled(this)) popup.dismiss()
            else -> applyEnabledPackages()
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()

        ShortsNotifier.ensureChannel(this)
        startUsageRecording()
        cacheScreenSize()
        DetectionPreferences.prefs(this).registerOnSharedPreferenceChangeListener(prefsListener)
        applyEnabledPackages()
        applyWatchThreshold()
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
        } catch (e: RuntimeException) {
            Log.w(TAG, "기록 저장소 시작 실패: ${e.javaClass.simpleName}")
        }
    }

    private fun stopUsageRecording() {
        recorder = null
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

    private fun applyWatchThreshold() {
        engine.watchThresholdMs = DetectionPreferences.watchThresholdMs(this)
        Log.i(TAG, "개입 임계값: ${engine.watchThresholdMs}ms")
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
        popup.dismiss()
        DetectionPreferences.prefs(this).unregisterOnSharedPreferenceChangeListener(prefsListener)
        DetectionStatus.onDisconnected()
        stopUsageRecording()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        stopWatchdog()
        popup.dismiss()
        stopUsageRecording()
        DetectionPreferences.prefs(this).unregisterOnSharedPreferenceChangeListener(prefsListener)
        DetectionStatus.onDisconnected()
        super.onDestroy()
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
        if (output.cancelNotification) {
            ShortsNotifier.cancel(this)
            popup.dismiss()
        }

        step.startedSession?.let { n ->
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
            record {
                it.onIntervention(
                    engine.sessionProgress()?.sessionNumber, InterventionMethod.SUPPRESSED_BY_COOLDOWN,
                    engine.sessionProgress()?.estimatedWatchMs ?: 0L, engine.watchThresholdMs,
                    level = InterventionPolicyPreferences.load(this).level
                )
            }
        }
        output.notifySession?.let { n ->
            intervene(n, output.observation.packageName, output.notifyWatchMs ?: 0L, output.observation.atMs, source)
        }
    }

    /** 팝업을 우선 띄우고, 꺼져 있거나 실패하면 알림으로 대신한다. 둘 다 실패하면 쿨다운을 걸지 않는다. */
    private fun intervene(session: Int, pkg: String?, watchMs: Long, atMs: Long, source: String) {
        val interventionId = recorder?.newInterventionId()
        val popupShown = DetectionPreferences.isPopupEnabled(this) &&
            popup.show(getString(R.string.popup_title, ShortsNotifier.formatWatch(this, watchMs))) { action ->
                if (interventionId != null) record {
                    it.onInterventionAction(
                        interventionId,
                        when (action) {
                            InterventionPopup.Action.OPEN_APP -> InterventionAction.OPEN_APP
                            InterventionPopup.Action.DISMISS -> InterventionAction.DISMISS
                        }
                    )
                }
            }
        val notified = !popupShown &&
            ShortsNotifier.notifyWatchThreshold(this, session, pkg, watchMs, interventionId)
        val shown = popupShown || notified
        Log.i(
            TAG,
            "[$source] ★ 개입 #$session 시청=${watchMs}ms 방식=${if (popupShown) "팝업" else if (shown) "알림" else "실패"}"
        )
        if (interventionId != null) record {
            val method = when {
                popupShown -> InterventionMethod.POPUP
                notified -> InterventionMethod.NOTIFICATION
                else -> InterventionMethod.FAILED
            }
            it.onIntervention(
                session, method, watchMs, engine.watchThresholdMs,
                level = InterventionPolicyPreferences.load(this).level, id = interventionId
            )
        }
        if (shown) engine.onNotificationPosted(atMs)
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
