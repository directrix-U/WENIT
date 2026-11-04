package com.example.shortsdetector.intervention

import android.content.SharedPreferences
import com.example.shortsdetector.data.InterventionPolicyPreferences
import com.example.shortsdetector.data.usage.InterventionAction
import com.example.shortsdetector.data.usage.InterventionOutcome
import com.example.shortsdetector.data.usage.InterventionUserReason
import com.example.shortsdetector.data.usage.PolicyChangeRecord
import com.example.shortsdetector.data.usage.PolicyChangeSource
import com.example.shortsdetector.data.usage.PolicyChangeStatus
import com.example.shortsdetector.data.usage.UsageClock
import com.example.shortsdetector.data.usage.UsageStore
import java.util.UUID

/** 단계 규칙이 읽고 쓰는 상태. 시연 모드면 시연용 칸을 쓴다. */
interface LevelStateStore {
    fun resolved(): ResolvedPolicy
    fun streaks(): LevelStreaks
    fun save(level: Int, streaks: LevelStreaks)
}

class PreferencesPolicyStore(private val prefs: SharedPreferences) : LevelStateStore {
    override fun resolved() = InterventionPolicyPreferences.resolve(prefs, emptyList())
    override fun streaks() = InterventionPolicyPreferences.readLevelStreaks(prefs)
    override fun save(level: Int, streaks: LevelStreaks) =
        InterventionPolicyPreferences.writeLevelState(prefs, level, streaks)
}

/**
 * @param nextLevel 반응을 반영한 뒤의 단계
 * @param reinterveneAfterWatchMs 같은 세션에서 다시 개입할 시청시간 간격. 없으면 null.
 */
data class InterventionResult(
    val sessionNumber: Int,
    val level: Int,
    val nextLevel: Int,
    val outcome: InterventionOutcome,
    val reinterveneAfterWatchMs: Long?
)

/**
 * 개입 하나의 끝맺음: 반응 기록 → 단계 규칙 → 바뀌면 저장 + PolicyChange(RULE).
 * 개입 하나는 정확히 한 번만 끝난다. 버튼 뒤에 세션 종료가 와도 IGNORED 로 덮이지 않는다.
 * 메인 스레드에서만 호출한다. 저장소 쓰기는 저장소 구현이 넘긴다.
 */
class InterventionCoordinator(
    private val store: UsageStore,
    private val policyStore: LevelStateStore,
    private val copy: InterventionCopyProvider,
    private val clock: UsageClock,
    private val newId: () -> String = { UUID.randomUUID().toString() }
) {
    private class Pending(val id: String, val sessionNumber: Int, val level: Int) {
        var reason: InterventionUserReason? = null
    }

    private var pending: Pending? = null

    /** 개입을 보여 줬을 때. 앞선 개입이 끝나지 않았으면 IGNORED 로 끝낸다. */
    fun begin(interventionId: String, sessionNumber: Int, level: Int) {
        abandon()
        pending = Pending(interventionId, sessionNumber, level)
    }

    /** Lv.2 이유 선택. 답하기 전에 바꿀 수 있고, 답 없이 사라져도 고른 이유는 남는다. */
    fun chooseReason(reason: InterventionUserReason) {
        pending?.reason = reason
    }

    /** 반응 없이 사라졌을 때. 끝낼 개입이 없으면 아무 일도 없다. */
    fun abandon(): InterventionResult? = respond(InterventionOutcome.IGNORED)

    /**
     * 이탈 신호로 앱이 오버레이를 닫았을 때. 고른 이유가 있으면 남기고, 단계와 연속 횟수는 건드리지 않는다.
     * @return 끝낼 개입이 없으면 null
     */
    fun interrupt(): InterventionResult? {
        val p = pending ?: return null
        pending = null
        store.setInterventionOutcome(p.id, InterventionOutcome.INTERRUPTED, p.reason)
        return InterventionResult(p.sessionNumber, p.level, p.level, InterventionOutcome.INTERRUPTED, null)
    }

    /** @return 끝낼 개입이 없으면 null */
    fun respond(outcome: InterventionOutcome): InterventionResult? {
        val p = pending ?: return null
        pending = null
        store.setInterventionOutcome(p.id, outcome, p.reason)
        when (outcome) {
            InterventionOutcome.STOPPED -> store.setInterventionAction(p.id, InterventionAction.OPEN_APP, clock.epochNowMs())
            InterventionOutcome.CONTINUED -> store.setInterventionAction(p.id, InterventionAction.DISMISS, clock.epochNowMs())
            InterventionOutcome.IGNORED, InterventionOutcome.INTERRUPTED -> Unit
        }

        val before = policyStore.resolved()
        val step = InterventionLevelRules.next(before.policy, policyStore.streaks(), outcome)
        val nextLevel = step.policy.level
        policyStore.save(nextLevel, step.streaks)
        if (step.levelChanged) recordLevelChange(before, nextLevel)

        return InterventionResult(
            p.sessionNumber, p.level, nextLevel, outcome,
            ReinterventionRule.afterWatchMs(p.level, outcome, before.thresholdMs.takeIf { before.demoMode })
        )
    }

    private fun recordLevelChange(before: ResolvedPolicy, nextLevel: Int) {
        val from = before.policy.level
        store.insertPolicyChange(
            PolicyChangeRecord(
                id = newId(),
                occurredAtMs = clock.epochNowMs(),
                source = PolicyChangeSource.RULE,
                previousPolicy = before.policy,
                newPolicy = before.policy.copy(level = nextLevel),
                reasonText = copy.levelChangeReason(from, nextLevel, before.policy),
                inputSummary = null,
                status = PolicyChangeStatus.APPLIED,
                isDemo = before.demoMode
            )
        )
    }

    companion object {
        /** 시연 준비 초기화 기록 */
        fun demoResetChange(
            before: InterventionPolicy,
            after: InterventionPolicy,
            id: String,
            atMs: Long,
            reason: String
        ) = PolicyChangeRecord(id, atMs, PolicyChangeSource.DEFAULT, before, after, reason, null, PolicyChangeStatus.APPLIED)

        /** 사용자가 바꾼 기준 시간의 변경 기록. 값이 그대로면 null. 잘린 값이 [PolicyChangeRecord.newPolicy] 에 들어간다. */
        fun userThresholdChange(
            before: InterventionPolicy,
            thresholdMs: Long,
            id: String,
            atMs: Long,
            reason: String
        ): PolicyChangeRecord? {
            val after = before.copy(thresholdMs = thresholdMs).clamped()
            if (after == before) return null
            return PolicyChangeRecord(
                id, atMs, PolicyChangeSource.USER, before, after, reason, null, PolicyChangeStatus.APPLIED
            )
        }
    }
}
