package com.example.shortsdetector.detection

import com.example.shortsdetector.detection.adapters.RecentActivity
import com.example.shortsdetector.detection.adapters.ShortformAdapter
import com.example.shortsdetector.detection.features.ScrollSignalTracker
import com.example.shortsdetector.detection.model.AdapterVerdict
import com.example.shortsdetector.detection.model.Confidence
import com.example.shortsdetector.detection.model.VerdictKind
import com.example.shortsdetector.detection.model.DetectionState
import com.example.shortsdetector.detection.model.Observation
import com.example.shortsdetector.detection.model.ReasonCode
import com.example.shortsdetector.detection.model.ScreenSnapshot
import com.example.shortsdetector.detection.model.ScrollSignal
import com.example.shortsdetector.detection.model.SessionSummary
import com.example.shortsdetector.detection.model.WindowStatus
import com.example.shortsdetector.detection.state.DetectionTuning
import com.example.shortsdetector.detection.state.ShortformStateMachine
import com.example.shortsdetector.detection.state.Step
import com.example.shortsdetector.intervention.NotificationGate

/** 엔진이 서비스에 요청하는 동작. 서비스는 이것만 실행한다(알림 게시/취소). */
data class EngineOutput(
    val observation: Observation,
    val step: Step,
    /** 새 세션이 시작됐고 쿨다운도 지났다 → 알림을 띄워라 */
    val notifySession: Int? = null,
    /** 새 세션이 시작됐지만 쿨다운 때문에 알림을 생략했다(로그용) */
    val suppressedByCooldown: Boolean = false,
    /** 세션이 끝났다 → 떠 있는 감지 알림을 지워라 */
    val cancelNotification: Boolean = false
) {
    val endedSession: SessionSummary? get() = step.endedSession
}

/**
 * 플랫폼 신호 → 앱별 어댑터 → 상태 머신 → 알림 정책을 잇는 순수 Kotlin 파이프라인.
 *
 * AccessibilityService 는 노드를 [ScreenSnapshot] 으로 바꿔 넘기고, 돌려받은 [EngineOutput] 대로
 * 알림만 처리한다. 덕분에 서비스 없이 JVM 테스트로 시나리오 전체를 재현할 수 있다.
 *
 * 스레드: 메인 스레드(접근성 콜백 + Handler 타이머)에서만 호출한다는 전제다.
 */
class DetectionEngine(
    private val tuning: DetectionTuning,
    adapters: List<ShortformAdapter>,
    renotifyIntervalMs: Long
) {
    private val adaptersByPackage = adapters.associateBy { it.packageName }
    private val scrollTracker = ScrollSignalTracker()
    private val machine = ShortformStateMachine(tuning)
    private val gate = NotificationGate(renotifyIntervalMs)

    /** 현재 감지를 켠 패키지. 어댑터가 있어도 여기에 없으면 "다른 창"으로 본다. */
    var enabledPackages: Set<String> = adaptersByPackage.keys
        private set

    val state: DetectionState get() = machine.state

    /** 열린 세션의 패키지. 없으면 null. */
    val sessionPackage: String? get() = machine.sessionPackage

    fun adapterFor(packageName: String?): ShortformAdapter? =
        packageName?.takeIf { it in enabledPackages }?.let { adaptersByPackage[it] }

    fun isTarget(packageName: String?): Boolean = adapterFor(packageName) != null

    /**
     * 감지 대상 패키지를 바꾼다. 꺼진 패키지에서 세션이 열려 있으면 바로 끝낸다.
     */
    fun setEnabledPackages(packages: Set<String>, atMs: Long): EngineOutput? {
        enabledPackages = packages.filter { it in adaptersByPackage }.toSet()
        val open = machine.sessionPackage ?: return null
        return if (open !in enabledPackages) forceEnd(atMs) else null
    }

    /** @return 기록했으면 페이지 넘김 여부, 대상 앱이 아니라 버렸으면 null */
    fun onScroll(signal: ScrollSignal): Boolean? =
        if (signal.packageName in enabledPackages) scrollTracker.record(signal) else null

    fun onSnapshot(atMs: Long, snapshot: ScreenSnapshot): EngineOutput =
        onObservation(evaluate(atMs, snapshot))

    fun onRootUnavailable(atMs: Long): EngineOutput = onObservation(Observation.unavailable(atMs))

    fun onForeignWindow(atMs: Long, packageName: String?): EngineOutput =
        onObservation(Observation.foreign(atMs, packageName))

    /**
     * 관찰 단계에서 예외가 났을 때. 모르는 화면으로 취급해 세션을 바로 끊지 않는다.
     * [packageName] 은 열린 세션의 패키지를 넘긴다(다른 값이면 앱 전환으로 오해한다).
     */
    fun onObservationError(atMs: Long, packageName: String?): EngineOutput =
        onObservation(
            if (packageName == null) Observation.unavailable(atMs)
            else Observation(atMs, WindowStatus.TARGET, packageName, AdapterVerdict.unknown(ReasonCode.ADAPTER_ERROR))
        )

    fun onNotificationPosted(atMs: Long) = gate.markNotified(atMs)

    fun forceEnd(atMs: Long): EngineOutput {
        val previous = machine.state
        val summary = machine.forceEnd(atMs)
        scrollTracker.clear()
        val step = Step(previous, machine.state, endedSession = summary)
        return EngineOutput(Observation.unavailable(atMs), step, cancelNotification = summary != null)
    }

    /** 스냅샷을 어댑터로 해석한다. 어댑터 예외는 여기서 격리한다. */
    fun evaluate(atMs: Long, snapshot: ScreenSnapshot): Observation {
        val adapter = adapterFor(snapshot.packageName)
            ?: return Observation.foreign(atMs, snapshot.packageName)
        val recent = RecentActivity(
            pageFlips = scrollTracker.pageFlipsWithin(adapter.packageName, atMs, tuning.pagingWindowMs)
        )
        val adapterVerdict = try {
            adapter.evaluate(snapshot, recent)
        } catch (e: RuntimeException) {
            AdapterVerdict.unknown(ReasonCode.ADAPTER_ERROR)
        }
        // 어댑터가 숏폼 근거를 전혀 못 찾았을 때만 "작은 창 = 대화상자" 규칙을 쓴다.
        // (PiP 처럼 작은 창 안에 플레이어가 있으면 어댑터가 먼저 판단한다)
        val verdict = if (adapterVerdict.reason == ReasonCode.NO_SHORTFORM_SIGNAL) {
            overlayVerdict(snapshot) ?: adapterVerdict
        } else adapterVerdict
        return Observation(atMs, WindowStatus.TARGET, adapter.packageName, verdict, adapter.ruleVersion)
    }

    /**
     * 활성 창 root 가 화면보다 훨씬 작으면 대상 앱의 대화상자/팝업 창이다(앱 무관 공통 규칙).
     * 에뮬레이터(YouTube 21.18.164)에서 로그인 대화상자가 별도 창으로 떠 root 면적비가 약 0.09 였다.
     */
    private fun overlayVerdict(snapshot: ScreenSnapshot): AdapterVerdict? {
        val root = snapshot.root ?: return null
        if (snapshot.screenArea <= 0L || root.area <= 0L) return null
        return if (snapshot.areaRatio(root) < tuning.overlayMaxRootAreaRatio) {
            AdapterVerdict(VerdictKind.OVERLAY, Confidence.LOW, ReasonCode.OVERLAY_WINDOW)
        } else null
    }

    private fun onObservation(obs: Observation): EngineOutput {
        val step = machine.onObservation(obs)
        val started = step.startedSession
        val canNotify = started != null && gate.canNotify(obs.atMs)
        return EngineOutput(
            observation = obs,
            step = step,
            notifySession = if (canNotify) started else null,
            suppressedByCooldown = started != null && !canNotify,
            cancelNotification = step.endedSession != null
        )
    }
}
