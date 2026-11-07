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
    /** 세션의 첫 개입이 쿨다운으로 미뤄졌다(로그용). 세션마다 한 번만 true 다. 반복 개입이 잠깐 기다리는 것은 알리지 않는다 */
    val suppressedByCooldown: Boolean = false,
    /** 세션이 끝났다 → 떠 있는 감지 알림을 지워라 */
    val cancelNotification: Boolean = false,
    /** 이번 관찰에서 이탈 신호가 처음 잡혔다 → 세션 종료 판정과 별개로 개입 오버레이를 닫아라 */
    val exitSignalStarted: Boolean = false
) {
    val endedSession: SessionSummary? get() = step.endedSession

    /** 숏폼 세션이 시작되거나 이어졌다 */
    val enteredShortform: Boolean get() = step.startedSession != null || step.resumedSession != null

    /** 대상 앱이 앞으로 왔지만 숏폼 화면은 아니다 */
    val openedTargetApp: Boolean
        get() = step.current == DetectionState.TARGET_FOREGROUND && step.previous != DetectionState.TARGET_FOREGROUND
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
    watchThresholdMs: Long = 0L
) {
    private val adaptersByPackage = adapters.associateBy { it.packageName }
    private val scrollTracker = ScrollSignalTracker()
    private val machine = ShortformStateMachine(tuning)
    private val gate = NotificationGate(renotifyIntervalMs)
    private val thresholdPolicy = WatchThresholdPolicy(watchThresholdMs)

    /** 앱이 오버레이를 닫아 답을 못 받은 세션. 이어지면 다시 개입한다. */
    private var retrySession: Int? = null

    /** 개입 화면이 떠 있다. 답할 때까지 다음 개입을 판정하지 않는다. */
    private var interventionOpen = false

    /** 쿨다운으로 미뤘다고 이미 알린 세션 */
    private var suppressedSession: Int? = null

    /** 개입 임계값. 설정이 바뀌면 바로 반영한다(열린 세션에도 적용). */
    var watchThresholdMs: Long
        get() = thresholdPolicy.thresholdMs
        set(value) { thresholdPolicy.thresholdMs = value }

    /** 정책 값을 반영한다. 다음 판정부터 적용한다(열린 세션 포함). 개입 빈도는 쿨다운으로만 조절한다. */
    fun applyPolicy(thresholdMs: Long, cooldownMs: Long) {
        thresholdPolicy.thresholdMs = thresholdMs
        gate.minIntervalMs = cooldownMs
    }

    /**
     * 개입 화면이 떠 있는지 알려 준다. 사라지면 쿨다운을 그때부터 다시 잰다. 팝업을 오래 띄워 둔 뒤 답하자마자
     * 다음 개입이 뜨면 안 되기 때문이다.
     */
    fun setInterventionOpen(open: Boolean, atMs: Long) {
        val closed = interventionOpen && !open
        interventionOpen = open
        if (!closed) return
        gate.markNotified(atMs)
        machine.sessionNumber?.let { thresholdPolicy.delayUntil(it, machine.sessionWatchMs + gate.minIntervalMs) }
    }

    /**
     * 이탈 신호로 닫힌 개입을 다시 시도한다. 같은 세션이 이어져 숏폼 화면이고 기준을 넘었으면 쿨다운 뒤에 개입한다.
     * 쿨다운 동안은 기회를 쓰지 않고 기다린다. 다른 세션이 시작되면 버린다.
     */
    fun retryIntervention(sessionNumber: Int) {
        retrySession = sessionNumber
    }

    private fun retryDue(session: Int?, watchMs: Long, state: DetectionState, atMs: Long): Boolean {
        val target = retrySession ?: return false
        if (session != null && session != target) {
            retrySession = null
            return false
        }
        val ready = session != null && state == DetectionState.SHORTFORM_ESTIMATED &&
            watchMs >= thresholdPolicy.thresholdMs && gate.canNotify(atMs)
        if (ready) retrySession = null
        return ready
    }

    /** 켜진 동안 열린 세션의 시간을 시청시간이 아니라 가린 시간으로 센다. */
    fun setWatchHold(hold: Boolean) = machine.setWatchHold(hold)

    /** "5분만 더" 처럼 시간을 약속했으면 다음 개입을 [atWatchMs] 시청 시점까지 미룬다. */
    fun delayIntervention(sessionNumber: Int, atWatchMs: Long) = thresholdPolicy.delayUntil(sessionNumber, atWatchMs)

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

    /**
     * 활성 창이 대상 앱이 아니다.
     * @param userPresent 화면이 켜져 있고 잠겨 있지 않다. 잠금 화면도 시스템 UI 창이라 이 값으로 알림창과 구분한다.
     */
    fun onForeignWindow(atMs: Long, packageName: String?, userPresent: Boolean = false): EngineOutput =
        onObservation(
            if (userPresent && packageName == SYSTEM_UI_PACKAGE) Observation.systemPanel(atMs, packageName)
            else Observation.foreign(atMs, packageName)
        )

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
        } else {
            miniatureWindowVerdict(snapshot, adapterVerdict) ?: adapterVerdict
        }
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

    /**
     * 활성 창 자체가 작은데 플레이어가 보이면 작은 창(PiP)이다(앱 무관 공통 규칙). 시청시간에 넣지 않는다.
     * 면적비만 보는 어댑터는 사용자가 PiP 를 키우면 PLAYER_PARTIAL 로 판단할 수 있어 여기서 막는다.
     */
    private fun miniatureWindowVerdict(snapshot: ScreenSnapshot, verdict: AdapterVerdict): AdapterVerdict? {
        if (verdict.kind != VerdictKind.SHORTFORM && verdict.kind != VerdictKind.SHORTFORM_PARTIAL) return null
        val root = snapshot.root ?: return null
        if (snapshot.screenArea <= 0L || root.area <= 0L) return null
        if (snapshot.areaRatio(root) >= tuning.pipMaxRootAreaRatio) return null
        return verdict.copy(kind = VerdictKind.NOT_SHORTFORM, confidence = Confidence.LOW, reason = ReasonCode.MINIATURE_WINDOW)
    }

    /** 두 번째 개입부터는 숏폼이 온전히 보일 때만 띄운다. 댓글을 쓰는 중에 끼어들지 않는다. */
    private fun isDue(session: Int?, watchMs: Long, state: DetectionState): Boolean =
        thresholdPolicy.isDue(session, watchMs) &&
            (thresholdPolicy.isFirst(session) || state == DetectionState.SHORTFORM_ESTIMATED)

    private fun onObservation(obs: Observation): EngineOutput {
        val step = machine.onObservation(obs)
        val session = machine.sessionNumber
        val watchMs = machine.sessionWatchMs
        val due = !interventionOpen && (isDue(session, watchMs, step.current) || retryDue(session, watchMs, step.current, obs.atMs))
        val canNotify = due && gate.canNotify(obs.atMs)
        val first = thresholdPolicy.isFirst(session)
        if (canNotify) thresholdPolicy.markDecided(session!!, watchMs, gate.minIntervalMs)
        val suppressed = due && !canNotify && first && suppressedSession != session
        if (due) suppressedSession = if (canNotify) null else session
        return EngineOutput(
            observation = obs,
            step = step,
            notifySession = if (canNotify) session else null,
            notifyWatchMs = if (canNotify) watchMs else null,
            suppressedByCooldown = suppressed,
            cancelNotification = step.endedSession != null,
            exitSignalStarted = step.current == DetectionState.EXIT_PENDING &&
                step.previous != DetectionState.EXIT_PENDING
        )
    }

    companion object {
        /** TODO: VERIFY ON DEVICE — 다른 제조사 */
        const val SYSTEM_UI_PACKAGE = "com.android.systemui"
    }
}
