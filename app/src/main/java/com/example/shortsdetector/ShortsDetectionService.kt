package com.example.shortsdetector

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.graphics.Point
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * 유튜브 쇼츠 재생 화면을 감지해서 로컬 알림을 띄우는 접근성 서비스.
 *
 * 판정:
 *  1) 패키지  : accessibility_service_config.xml 의 packageNames 로 OS 레벨에서 1차 차단
 *  2) 플레이어: [DetectionConfig.SHORTS_PLAYER_VIEW_ID] 노드가 화면에 보이고
 *              화면의 [DetectionConfig.MIN_PLAYER_AREA_RATIO] 이상을 차지하는지
 *              (감지된 뒤에는 노드가 보이기만 하면 유지)
 *
 * 조건이 "새로 충족된 순간"에만 알림을 1회 띄운다(rising edge).
 * 감지 상태가 풀리면(쇼츠 이탈) 알림도 지운다.
 *
 * 감시 타이머: 감지 확인 중이거나 감지 상태인 동안에는 이벤트를 기다리지 않고
 * [DetectionConfig.WATCHDOG_INTERVAL_MS] 마다 직접 화면을 확인한다.
 * 유튜브를 벗어나면 packageNames 필터 때문에 이벤트가 오지 않고, 쇼츠를 가만히 볼 때도
 * 이벤트가 끊기기 때문에, 이벤트만으로는 이탈/확정을 제때 알 수 없다.
 */
class ShortsDetectionService : AccessibilityService() {

    /** 마지막으로 트리를 실제 순회한 시각 (앱 레벨 스로틀용) */
    private var lastAnalysisAt = 0L

    /** 마지막으로 유효한 분석이 끝난 시각 (staleness 판정용) */
    private var lastValidAnalysisAt = 0L

    /** 현재 "쇼츠 조건 충족" 상태인지. 이 플래그의 false→true 전환에서만 알림을 띄운다. */
    private var shortsActive = false

    /** 조건이 연속으로 충족된 횟수. CONFIRMATION_COUNT 에 도달해야 알림을 띄운다. */
    private var consecutiveHits = 0

    /** 서비스가 켜진 뒤 누적 감지 횟수 */
    private var detectionCount = 0

    /** 마지막으로 알림을 띄운 시각. 재알림 쿨다운 판정에 쓴다. */
    private var lastNotifiedAt = 0L

    private var screenWidth = 0
    private var screenHeight = 0

    private val handler = Handler(Looper.getMainLooper())
    private var watchdogScheduled = false

    private val watchdog = Runnable {
        watchdogScheduled = false
        lastAnalysisAt = SystemClock.elapsedRealtime()
        analyzeCurrentScreen(SOURCE_TIMER)
        scheduleWatchdogIfNeeded()
    }

    override fun onServiceConnected() {
        super.onServiceConnected()

        // XML 설정만으로 충분하지만, 의도를 코드에서도 드러내고
        // 런타임에 임계값을 바꿔 실험할 여지를 남기기 위해 한 번 더 세팅한다.
        serviceInfo = (serviceInfo ?: AccessibilityServiceInfo()).apply {
            eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or
                    AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
            feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
            // 1단계 필터: OS 가 이 패키지의 이벤트만 우리에게 전달한다.
            packageNames = arrayOf(DetectionConfig.TARGET_PACKAGE)
            notificationTimeout = NOTIFICATION_TIMEOUT_MS
            flags = flags or AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
        }

        ShortsNotifier.ensureChannel(this)
        cacheScreenSize()
        resetState("서비스 연결")

        Log.i(
            TAG,
            "서비스 연결됨 / target=${DetectionConfig.TARGET_PACKAGE} " +
                    "screen=${screenWidth}x${screenHeight} " +
                    "기준(viewId=${DetectionConfig.SHORTS_PLAYER_VIEW_ID}, " +
                    "areaRatio>=${DetectionConfig.MIN_PLAYER_AREA_RATIO})"
        )
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return

        // packageNames 로 이미 걸러지지만, 방어적으로 한 번 더 확인한다.
        if (event.packageName?.toString() != DetectionConfig.TARGET_PACKAGE) return

        val now = SystemClock.elapsedRealtime()

        // 이탈은 보통 감시 타이머가 먼저 잡는다. 타이머가 어떤 이유로 멈췄을 때를 위한 안전장치.
        if (shortsActive && now - lastValidAnalysisAt > DetectionConfig.STATE_STALE_MS) {
            resetState("이벤트 공백 ${DetectionConfig.STATE_STALE_MS}ms 초과")
        }

        // notificationTimeout(OS) 에 더한 앱 레벨 스로틀. 잦은 트리 순회를 막는다.
        if (now - lastAnalysisAt < DetectionConfig.MIN_ANALYSIS_INTERVAL_MS) return
        lastAnalysisAt = now

        analyzeCurrentScreen(SOURCE_EVENT)
        scheduleWatchdogIfNeeded()
    }

    override fun onInterrupt() {
        resetState("onInterrupt")
    }

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        resetState("onUnbind")
        handler.removeCallbacks(watchdog)
        watchdogScheduled = false
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        handler.removeCallbacks(watchdog)
        super.onDestroy()
    }

    /** 감지 확인 중이거나 감지 상태일 때만 타이머를 돌린다. 평소(쇼츠 아님)에는 이벤트만으로 충분하다. */
    private fun scheduleWatchdogIfNeeded() {
        val needed = shortsActive || consecutiveHits > 0
        if (needed && !watchdogScheduled) {
            handler.postDelayed(watchdog, DetectionConfig.WATCHDOG_INTERVAL_MS)
            watchdogScheduled = true
        } else if (!needed && watchdogScheduled) {
            handler.removeCallbacks(watchdog)
            watchdogScheduled = false
        }
    }

    // ---------------------------------------------------------------------

    /** @param source 판정을 실행한 쪽. 로그에만 쓴다(타이머가 제 역할을 하는지 확인용). */
    private fun analyzeCurrentScreen(source: String) {
        if (screenWidth <= 0 || screenHeight <= 0) cacheScreenSize()

        val root: AccessibilityNodeInfo = getRootInActiveWindow() ?: run {
            // 루트를 못 얻는 경우(전환 중 등)는 조건이 깨진 것으로 본다.
            if (shortsActive || consecutiveHits > 0) resetState("rootInActiveWindow == null")
            return
        }

        try {
            // 활성 창이 유튜브가 아니면(시스템 다이얼로그 등) 판정하지 않는다.
            if (root.packageName?.toString() != DetectionConfig.TARGET_PACKAGE) {
                if (shortsActive || consecutiveHits > 0) {
                    resetState("활성 창이 대상 패키지가 아님: ${root.packageName}")
                }
                return
            }

            val started = SystemClock.elapsedRealtime()
            val result = ShortsPlayerDetector.detect(root, screenWidth, screenHeight)
            val elapsed = SystemClock.elapsedRealtime() - started
            lastValidAnalysisAt = SystemClock.elapsedRealtime()

            // 판정 로그: reel_watch_player 발견 여부 / 면적비 / 판정 결과
            val holding = shortsActive && !result.isShortsPlaying && result.playerVisible
            Log.d(TAG, "[$source] $result${if (holding) " → 감지 유지(플레이어 보임)" else ""} / ${elapsed}ms")

            updateDetectionState(result, source)
        } finally {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                @Suppress("DEPRECATION")
                root.recycle()
            }
        }
    }

    /**
     * rising edge 에서만 알림을 띄우는 래치 로직.
     *
     * 화면 전환 순간의 1회성 오탐을 걸러내려고,
     * 조건이 [DetectionConfig.CONFIRMATION_COUNT] 회 연속 충족돼야 알림을 띄운다.
     *
     * 진입과 유지의 기준이 다르다(히스테리시스):
     *  - 진입: 플레이어가 보이고 면적비 >= MIN_PLAYER_AREA_RATIO ([DetectionResult.isShortsPlaying])
     *  - 유지: 플레이어가 보이기만 하면 됨 ([DetectionResult.playerVisible])
     * 댓글창 등으로 플레이어가 줄어들어도 같은 쇼츠를 보는 중이면 래치를 풀지 않는다.
     */
    private fun updateDetectionState(result: DetectionResult, source: String) {
        if (shortsActive) {
            if (!result.playerVisible) resetState("조건 이탈 ($result)")
            return
        }

        if (!result.isShortsPlaying) {
            if (consecutiveHits > 0) resetState("조건 이탈 ($result)")
            return
        }

        consecutiveHits++

        if (consecutiveHits < DetectionConfig.CONFIRMATION_COUNT) {
            Log.d(TAG, "[$source] 조건 충족 $consecutiveHits/${DetectionConfig.CONFIRMATION_COUNT} — 확인 중")
            return
        }

        shortsActive = true
        detectionCount++

        val now = SystemClock.elapsedRealtime()
        val sinceLast = now - lastNotifiedAt
        if (lastNotifiedAt != 0L && sinceLast < DetectionConfig.MIN_RENOTIFY_INTERVAL_MS) {
            Log.i(TAG, "[$source] ★ 쇼츠 감지 #$detectionCount (쿨다운 ${sinceLast}ms — 알림 생략) $result")
            return
        }

        Log.i(TAG, "[$source] ★ 쇼츠 감지 #$detectionCount $result")
        if (ShortsNotifier.notifyShortsDetected(this, result, detectionCount)) {
            lastNotifiedAt = now
        }
    }

    private fun resetState(reason: String) {
        if (shortsActive) {
            Log.i(TAG, "감지 상태 해제: $reason")
            ShortsNotifier.cancel(this)
        }
        shortsActive = false
        consecutiveHits = 0
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
