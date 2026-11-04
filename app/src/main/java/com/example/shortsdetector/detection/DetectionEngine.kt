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
import com.example.shortsdetector.detection.model.SessionProgress
import com.example.shortsdetector.detection.model.SessionSummary
import com.example.shortsdetector.detection.model.WindowStatus
import com.example.shortsdetector.detection.state.DetectionTuning
import com.example.shortsdetector.detection.state.ShortformStateMachine
import com.example.shortsdetector.detection.state.Step
import com.example.shortsdetector.intervention.DailyInterventionLimit
import com.example.shortsdetector.intervention.NotificationGate
import com.example.shortsdetector.intervention.WatchThresholdPolicy

/** 엔진이 서비스에 요청하는 동작. 서비스는 이것만 실행한다(알림 게시/취소). */
data class EngineOutput(
    val observation: Observation,
    val step: Step,
    /** 열린 세션의 확정 시청시간이 임계값에 닿았고 쿨다운도 지났다 → 개입(팝업/알림)을 띄워라 */
    val notifySession: Int? = null,
    /** [notifySession] 일 때 그 세션의 확정 시청시간(ms). 문구 표시용. */
    val notifyWatchMs: Long? = null,
    /** 임계값에 닿았지만 쿨다운 때문에 개입을 생략했다(로그용) */
    val suppressedByCooldown: Boolean = false,
    /** 임계값에 닿았지만 오늘 개입 수가 상한에 닿아 생략했다 */
    val suppressedByDailyLimit: Boolean = false,
    /** 세션이 끝났다 → 떠 있는 감지 알림을 지워라 */
    val cancelNotification: Boolean = false,
    /** 이번 관찰에서 이탈 신호가 처음 잡혔다 → 세션 종료 판정과 별개로 개입 오버레이를 닫아라 */
    val exitSignalStarted: Boolean = false
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
    renotifyIntervalMs: Long,
    /** 세션 안에서 이만큼 본 뒤에 개입한다. 0 이면 세션 시작 즉시(기존 동작). */
    watchThresholdMs: Long = 0L,
    maxDailyInterventions: Int = Int.MAX_VALUE,
    /** 지금의 현지 날짜(epoch day). 엔진 시각은 elapsed 라 날짜를 알 수 없어 주입받는다. */
    private val currentDay: () -> Long = { 0L }
) {
    private val adaptersByPackage = adapters.associateBy { it.packageName }
    private val scrollTracker = ScrollSignalTracker()
    private val machine = ShortformStateMachine(tuning)
    private val gate = NotificationGate(renotifyIntervalMs)
    private val thresholdPolicy = WatchThresholdPolicy(watchThresholdMs)
    private val dailyLimit = DailyInterventionLimit(maxDailyInterventions)
    private val demoLimit = DailyInterventionLimit(maxDailyInterventions)
    private var demoRunId: Long? = null

    /** 시연 모드면 시연 전용 카운터를 쓴다. 시연 개입이 실제 하루 상한을 소모하지 않는다. */
    private val activeLimit get() = if (demoRunId != null) demoLimit else dailyLimit

    /** 개입 임계값. 설정이 바뀌면 바로 반영한다(열린 세션에도 적용). */
    var watchThresholdMs: Long
        get() = thresholdPolicy.thresholdMs
        set(value) { thresholdPolicy.thresholdMs = value }

    /** 정책 값을 반영한다. 다음 판정부터 적용한다(열린 세션 포함). */
    fun applyPolicy(thresholdMs: Long, cooldownMs: Long, maxDailyInterventions: Int, demoRunId: Long? = null) {
        thresholdPolicy.thresholdMs = thresholdMs
        gate.minIntervalMs = cooldownMs
        dailyLimit.maxPerDay = maxDailyInterventions
        demoLimit.maxPerDay = maxDailyInterventions
        if (demoRunId != this.demoRunId) demoLimit.reset()
        this.demoRunId = demoRunId
    }

    /** 오늘 이미 보여 준 개입 수를 알려 준다(서비스 재시작 뒤 저장 기록에서 복구). */
    fun seedDailyInterventions(count: Int, demoCount: Int = 0) {
        dailyLimit.seed(currentDay(), count)
        demoLimit.seed(currentDay(), demoCount)
    }

    /** 기록을 전부 지웠을 때. 다음 개입부터 0 부터 다시 센다. */
    fun resetDailyInterventions() {
        dailyLimit.reset()
        demoLimit.reset()
    }

    /** 켜진 동안 열린 세션의 시간을 시청시간이 아니라 가린 시간으로 센다. */
    fun setWatchHold(hold: Boolean) = machine.setWatchHold(hold)

    /** 더 보기를 고른 세션을 [atWatchMs] 시청 시점에 한 번 더 판정한다. 쿨다운·하루 상한은 그대로 적용된다. */
    fun rearmIntervention(sessionNumber: Int, atWatchMs: Long) = thresholdPolicy.rearm(sessionNumber, atWatchMs)

    /** 현재 감지를 켠 패키지. 어댑터가 있어도 여기에 없으면 "다른 창"으로 본다. */
    var enabledPackages: Set<String> = adaptersByPackage.keys
        private set

    val state: DetectionState get() = machine.state

    /** 열린 세션의 패키지. 없으면 null. */
    val sessionPackage: String? get() = machine.sessionPackage

    /** 열린 세션의 현재 누적값(중간 저장용). 없으면 null. */
    fun sessionProgress(): SessionProgress? = machine.progress()

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

    fun onNotificationPosted(atMs: Long) {
        gate.markNotified(atMs)
        activeLimit.record(currentDay())
    }

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
        val session = machine.sessionNumber
        val watchMs = machine.sessionWatchMs
        val due = thresholdPolicy.shouldDecide(session, watchMs)
        val overLimit = due && !activeLimit.canIntervene(currentDay())
        val canNotify = due && !overLimit && gate.canNotify(obs.atMs)
        return EngineOutput(
            observation = obs,
            step = step,
            notifySession = if (canNotify) session else null,
            notifyWatchMs = if (canNotify) watchMs else null,
            suppressedByCooldown = due && !overLimit && !canNotify,
            suppressedByDailyLimit = overLimit,
            cancelNotification = step.endedSession != null,
            exitSignalStarted = step.current == DetectionState.EXIT_PENDING &&
                step.previous != DetectionState.EXIT_PENDING
        )
    }
}
