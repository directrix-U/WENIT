package com.example.shortsdetector.detection.state

import com.example.shortsdetector.detection.model.Confidence
import com.example.shortsdetector.detection.model.DetectionState
import com.example.shortsdetector.detection.model.EndReason
import com.example.shortsdetector.detection.model.ExitCause
import com.example.shortsdetector.detection.model.Observation
import com.example.shortsdetector.detection.model.SessionSummary
import com.example.shortsdetector.detection.model.VerdictKind
import com.example.shortsdetector.detection.model.WindowStatus

/** 관찰 1건을 처리한 결과. */
data class Step(
    val previous: DetectionState,
    val current: DetectionState,
    /** 이번 관찰로 새 세션이 시작됐으면 그 세션 번호 */
    val startedSession: Int? = null,
    val startedConfidence: Confidence? = null,
    /** 이번 관찰로 세션이 끝났으면 그 요약 */
    val endedSession: SessionSummary? = null,
    /** 중복/역순 관찰이라 무시했는지 */
    val ignored: Boolean = false,
    /** CANDIDATE 의 연속 충족 횟수(로그용) */
    val candidateHits: Int = 0
) {
    val changed: Boolean get() = previous != current
}

/**
 * 공통 감지 상태 머신. 앱별 UI 규칙은 모르고 [Observation] 만 본다. Android 의존성 없음.
 *
 * 기존 프로토타입의 래치 규칙을 보존한다.
 *  - 진입: 신뢰도 >= minEntryConfidence 인 SHORTFORM 이 confirmationCount 회 **연속** (기존 3회)
 *  - 유지: SHORTFORM(신뢰도 무관) 또는 SHORTFORM_PARTIAL(댓글창 등으로 줄어든 플레이어),
 *          OVERLAY(대상 앱의 대화상자가 위에 뜸)
 *  - 확인 중에 다른 판정이 오면 처음부터 다시 센다
 *
 * 기존과 달라진 점(의도한 변경):
 *  - SHORTFORM_PARTIAL 은 TEMPORARILY_OBSCURED 로 따로 표시하고, 그 시간은 확정 시청시간에 넣지 않는다.
 *  - 이탈 신호(root 없음, 다른 창, 숏폼 신호 없음)가 와도 바로 끝내지 않고 EXIT_PENDING 에서
 *    exitGraceMs 동안 기다린다. 알림창을 잠깐 내리거나 root 가 순간적으로 null 인 경우에 세션이
 *    끊겼다가 다시 시작되던 문제(기존에는 재알림 쿨다운으로만 가렸음)를 막는다.
 *  - 중복/역순 관찰(같은 시각 이하)은 무시한다.
 */
class ShortformStateMachine(private val tuning: DetectionTuning = DetectionTuning()) {

    private class OpenSession(
        val number: Int,
        val packageName: String,
        val ruleVersion: String?,
        val startedAtMs: Long,
        var maxConfidence: Confidence
    ) {
        var watchMs = 0L
        var obscuredMs = 0L
        var uncertainMs = 0L
    }

    var state: DetectionState = DetectionState.OUTSIDE_TARGET
        private set

    private var lastObservationAt: Long? = null
    private var hits = 0
    private var candidatePackage: String? = null
    private var session: OpenSession? = null
    private var sessionCounter = 0
    private var exitSince = 0L

    val sessionPackage: String? get() = session?.packageName

    fun onObservation(obs: Observation): Step {
        val last = lastObservationAt
        if (last != null && obs.atMs <= last) {
            return Step(state, state, ignored = true, candidateHits = hits)
        }

        val previous = state
        var ended: SessionSummary? = null

        if (last != null) {
            val gap = obs.atMs - last
            if (gap > tuning.staleMs) {
                // 공백 동안 무슨 일이 있었는지 모른다. 세션은 마지막 관찰 시각에 끝난 것으로 본다.
                if (session != null) ended = endSession(last, EndReason.STALE, null)
                resetCandidate()
            } else {
                credit(gap)
            }
        }
        lastObservationAt = obs.atMs

        var started: Int? = null
        var startedConfidence: Confidence? = null
        val kind = if (obs.window == WindowStatus.TARGET) obs.verdict?.kind ?: VerdictKind.UNKNOWN else null

        val open = session
        if (open != null) {
            if (obs.window == WindowStatus.TARGET && obs.packageName != open.packageName) {
                ended = endSession(obs.atMs, EndReason.APP_SWITCHED, null)
            } else {
                ended = ended ?: handleActive(obs, kind, open)
                return Step(previous, state, endedSession = ended, candidateHits = hits)
            }
        }

        // 세션 없음: 대기 / 확인 중
        val verdict = obs.verdict
        val pkg = obs.packageName
        when {
            kind == VerdictKind.SHORTFORM && verdict != null &&
                verdict.confidence >= tuning.minEntryConfidence -> {
                hits = if (state == DetectionState.CANDIDATE && candidatePackage == pkg) hits + 1 else 1
                candidatePackage = pkg
                state = DetectionState.CANDIDATE
                if (hits >= tuning.confirmationCount) {
                    started = startSession(obs, verdict.confidence)
                    startedConfidence = verdict.confidence
                }
            }
            kind == VerdictKind.SHORTFORM -> {
                // 신뢰도가 낮은 숏폼 신호: 관찰만 하고 진입 카운트는 쌓지 않는다.
                hits = 0
                candidatePackage = pkg
                state = DetectionState.CANDIDATE
            }
            obs.window == WindowStatus.TARGET -> {
                resetCandidate()
                state = DetectionState.TARGET_FOREGROUND
            }
            else -> {
                resetCandidate()
                state = DetectionState.OUTSIDE_TARGET
            }
        }

        return Step(previous, state, started, startedConfidence, ended, candidateHits = hits)
    }

    /** 서비스 중단·설정 변경 등으로 강제 종료. 열린 세션이 없으면 null. */
    fun forceEnd(atMs: Long): SessionSummary? {
        val endAt = maxOf(atMs, lastObservationAt ?: atMs)
        val summary = if (session != null) endSession(endAt, EndReason.FORCED, null) else null
        resetCandidate()
        state = DetectionState.OUTSIDE_TARGET
        return summary
    }

    private fun handleActive(obs: Observation, kind: VerdictKind?, open: OpenSession): SessionSummary? {
        when (kind) {
            VerdictKind.SHORTFORM -> {
                val c = obs.verdict?.confidence ?: Confidence.LOW
                if (c > open.maxConfidence) open.maxConfidence = c
                state = DetectionState.SHORTFORM_ESTIMATED
            }
            VerdictKind.SHORTFORM_PARTIAL, VerdictKind.OVERLAY -> state = DetectionState.TEMPORARILY_OBSCURED
            else -> {
                if (state != DetectionState.EXIT_PENDING) {
                    state = DetectionState.EXIT_PENDING
                    exitSince = obs.atMs
                }
                if (obs.atMs - exitSince >= tuning.exitGraceMs) {
                    return endSession(obs.atMs, EndReason.EXIT_CONFIRMED, exitCauseOf(obs, kind))
                }
            }
        }
        return null
    }

    private fun exitCauseOf(obs: Observation, kind: VerdictKind?): ExitCause = when (obs.window) {
        WindowStatus.UNAVAILABLE -> ExitCause.ROOT_UNAVAILABLE
        WindowStatus.FOREIGN -> ExitCause.FOREIGN_WINDOW
        WindowStatus.TARGET ->
            if (kind == VerdictKind.NOT_SHORTFORM) ExitCause.NO_SHORTFORM_SIGNAL else ExitCause.UNKNOWN_SCREEN
    }

    /** 직전 상태에 머문 시간을 누적한다. 한 간격당 상한을 둬 공백을 부풀리지 않는다. */
    private fun credit(gap: Long) {
        val open = session ?: return
        val c = minOf(gap, tuning.maxCreditPerStepMs)
        when (state) {
            DetectionState.SHORTFORM_ESTIMATED -> open.watchMs += c
            DetectionState.TEMPORARILY_OBSCURED -> open.obscuredMs += c
            DetectionState.EXIT_PENDING -> open.uncertainMs += c
            else -> Unit
        }
    }

    private fun startSession(obs: Observation, confidence: Confidence): Int {
        sessionCounter++
        session = OpenSession(
            number = sessionCounter,
            packageName = obs.packageName.orEmpty(),
            ruleVersion = obs.ruleVersion,
            startedAtMs = obs.atMs,
            maxConfidence = confidence
        )
        resetCandidate()
        state = DetectionState.SHORTFORM_ESTIMATED
        return sessionCounter
    }

    private fun endSession(atMs: Long, reason: EndReason, cause: ExitCause?): SessionSummary {
        val open = checkNotNull(session)
        session = null
        resetCandidate()
        state = DetectionState.ENDED
        return SessionSummary(
            sessionNumber = open.number,
            packageName = open.packageName,
            ruleVersion = open.ruleVersion,
            startedAtMs = open.startedAtMs,
            endedAtMs = atMs,
            estimatedWatchMs = open.watchMs,
            obscuredMs = open.obscuredMs,
            uncertainMs = open.uncertainMs,
            maxConfidence = open.maxConfidence,
            endReason = reason,
            exitCause = cause
        )
    }

    private fun resetCandidate() {
        hits = 0
        candidatePackage = null
    }
}
