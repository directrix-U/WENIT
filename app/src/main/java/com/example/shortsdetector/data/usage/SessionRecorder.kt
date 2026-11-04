package com.example.shortsdetector.data.usage

import com.example.shortsdetector.detection.EngineOutput
import com.example.shortsdetector.detection.model.EndReason
import com.example.shortsdetector.detection.model.SessionProgress
import com.example.shortsdetector.detection.model.SessionSummary
import java.util.UUID

/** 관찰 시계(elapsedRealtime)와 벽시계(epoch)를 함께 읽는다. 테스트에서 고정값을 넣는다. */
interface UsageClock {
    fun epochNowMs(): Long
    fun elapsedNowMs(): Long
}

/**
 * 엔진 출력 → 저장 기록 변환. Android 의존성이 없고 메인 스레드에서 호출한다(저장소 쓰기는 저장소 구현이 넘긴다).
 *
 * 시각 변환: 세션 시작 때 한 번만 elapsed → epoch 로 바꾸고, 이후 끝 시각은 시작 epoch + 경과 시간으로 계산한다.
 * 세션 도중 사용자가 시계를 바꿔도 세션 길이가 틀어지지 않는다.
 *
 * 세션 이어 가기: 엔진 세션 번호 → 기록 id 를 기억해 두고, 이어진 세션은 같은 id 행을 갱신한다.
 * 세션 번호는 서비스가 다시 시작되면 1 부터 다시 매겨지므로 저장하지 않는다(기록 id 는 UUID).
 *
 * 중간 저장: 열린 세션은 [checkpointIntervalMs] 마다 OPEN 으로 덮어쓴다. 프로세스가 죽으면 마지막 중간
 * 저장까지만 남고, 다음 연결 때 [UsageStore.abortOpenSessions] 로 ABORTED 처리한다.
 */
class SessionRecorder(
    private val store: UsageStore,
    private val clock: UsageClock,
    private val checkpointIntervalMs: Long = DEFAULT_CHECKPOINT_INTERVAL_MS,
    private val newId: () -> String = { UUID.randomUUID().toString() }
) {
    private class Tracked(val id: String, val startEpochMs: Long, val startElapsedMs: Long)

    /** 열린 세션 또는 이어질 수 있는 직전 세션. 많아야 2개. */
    private val tracked = HashMap<Int, Tracked>()
    private var lastCheckpointAt: Long? = null

    /** 서비스 연결 시 호출. 이전 실행에서 OPEN 으로 남은 기록을 닫고 보관 기간이 지난 기록을 지운다. */
    fun onServiceStarted(retentionMs: Long) {
        tracked.clear()
        lastCheckpointAt = null
        store.abortOpenSessions()
        store.deleteBefore(clock.epochNowMs() - retentionMs)
    }

    /** [EngineOutput] 하나를 반영한다. [progress] 는 같은 시점의 엔진 [SessionProgress]. */
    fun onOutput(output: EngineOutput, progress: SessionProgress?) {
        output.endedSession?.let(::onEnded)
        val step = output.step
        step.startedSession?.let { n -> progress?.takeIf { it.sessionNumber == n }?.let(::onStarted) }
        step.resumedSession?.let { n -> progress?.takeIf { it.sessionNumber == n }?.let(::onResumed) }

        val last = lastCheckpointAt
        if (progress != null && (last == null || progress.lastObservedAtMs - last >= checkpointIntervalMs)) {
            writeOpen(progress)
        }
    }

    /** 개입 기록 id 를 미리 만든다(알림 Intent 에 넣어 탭 반응을 연결할 때). */
    fun newInterventionId(): String = newId()

    /** 개입(또는 쿨다운으로 생략) 기록. 기록 id 를 돌려준다(사용자 반응을 나중에 붙일 때 씀). */
    fun onIntervention(
        sessionNumber: Int?,
        method: InterventionMethod,
        watchMs: Long,
        thresholdMs: Long,
        level: Int? = null,
        id: String = newId()
    ): String {
        store.insertIntervention(
            InterventionRecord(
                id = id,
                sessionId = sessionNumber?.let { tracked[it]?.id },
                occurredAtMs = clock.epochNowMs(),
                method = method,
                watchMs = watchMs,
                thresholdMs = thresholdMs,
                level = level
            )
        )
        return id
    }

    fun onInterventionAction(interventionId: String, action: InterventionAction) {
        store.setInterventionAction(interventionId, action, clock.epochNowMs())
    }

    private fun onStarted(p: SessionProgress) {
        // 새 세션이 열리면 이전 세션은 더 이상 이어질 수 없다.
        tracked.clear()
        tracked[p.sessionNumber] = Tracked(newId(), toEpoch(p.startedAtMs), p.startedAtMs)
        writeOpen(p)
    }

    private fun onResumed(p: SessionProgress) {
        if (tracked[p.sessionNumber] == null) {
            tracked[p.sessionNumber] = Tracked(newId(), toEpoch(p.startedAtMs), p.startedAtMs)
        }
        writeOpen(p)
    }

    private fun onEnded(s: SessionSummary) {
        val t = tracked[s.sessionNumber]
            ?: Tracked(newId(), toEpoch(s.startedAtMs), s.startedAtMs).also { tracked[s.sessionNumber] = it }
        store.upsertSession(
            SessionRecord(
                id = t.id,
                packageName = s.packageName,
                ruleVersion = s.ruleVersion,
                startedAtMs = t.startEpochMs,
                endedAtMs = t.startEpochMs + (s.endedAtMs - t.startElapsedMs).coerceAtLeast(0L),
                status = SessionStatus.CLOSED,
                estimatedWatchMs = s.estimatedWatchMs,
                partialWatchMs = s.partialWatchMs,
                obscuredMs = s.obscuredMs,
                uncertainMs = s.uncertainMs,
                maxConfidence = s.maxConfidence.name,
                endReason = s.endReason.name,
                exitCause = s.exitCause?.name,
                resumeCount = s.resumeCount
            )
        )
        // EXIT_CONFIRMED 만 이어질 수 있다(상태 머신 규칙과 같음).
        if (s.endReason != EndReason.EXIT_CONFIRMED) tracked.remove(s.sessionNumber)
        lastCheckpointAt = null
    }

    private fun writeOpen(p: SessionProgress) {
        val t = tracked[p.sessionNumber] ?: return
        store.upsertSession(
            SessionRecord(
                id = t.id,
                packageName = p.packageName,
                ruleVersion = p.ruleVersion,
                startedAtMs = t.startEpochMs,
                endedAtMs = t.startEpochMs + (p.lastObservedAtMs - t.startElapsedMs).coerceAtLeast(0L),
                status = SessionStatus.OPEN,
                estimatedWatchMs = p.estimatedWatchMs,
                partialWatchMs = p.partialWatchMs,
                obscuredMs = p.obscuredMs,
                uncertainMs = p.uncertainMs,
                maxConfidence = p.maxConfidence.name,
                endReason = null,
                exitCause = null,
                resumeCount = p.resumeCount
            )
        )
        lastCheckpointAt = p.lastObservedAtMs
    }

    private fun toEpoch(elapsedMs: Long): Long = clock.epochNowMs() - (clock.elapsedNowMs() - elapsedMs)

    companion object {
        /** 열린 세션 중간 저장 간격. 비정상 종료 때 잃는 시청시간의 상한이기도 하다. */
        const val DEFAULT_CHECKPOINT_INTERVAL_MS = 15_000L
    }
}
