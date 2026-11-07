package com.example.shortsdetector.ai.agent

import android.content.SharedPreferences
import com.example.shortsdetector.data.InterventionPolicyPreferences
import com.example.shortsdetector.data.usage.PolicyChangeRecord
import com.example.shortsdetector.data.usage.PolicyChangeSource
import com.example.shortsdetector.data.usage.PolicyChangeStatus
import com.example.shortsdetector.data.usage.UsageStore

/**
 * 시드와 시드에서 나온 결과를 지운다: 시드 기록, 시드 기반 정책 변경 이력, 에이전트 문구와 마지막 분석 결과.
 * 정책은 기본값으로 되돌린다. 실제 기록과 시드 없이 나온 변경 이력은 건드리지 않는다.
 *
 * 정책이 실제로 바뀌면 그 사실은 시드 기반이 아닌 새 이력으로 남긴다(명세: 정책 변경은 이유와 함께 기록).
 */
class SeedDataReset(
    private val records: UsageStore,
    private val policyPrefs: SharedPreferences,
    private val agentPrefs: SharedPreferences,
    private val nowMs: () -> Long,
    private val newId: () -> String
) {
    fun run(reason: String) {
        records.deleteSeedRecords()
        records.deleteSeedPolicyChanges()
        AgentPreferences.clearSeedResults(agentPrefs)
        val before = InterventionPolicyPreferences.resetToDefault(policyPrefs)
        val after = InterventionPolicyPreferences.read(policyPrefs)
        if (before == after) return
        records.insertPolicyChange(
            PolicyChangeRecord(
                newId(), nowMs(), PolicyChangeSource.DEFAULT, before, after, reason, null, PolicyChangeStatus.APPLIED
            )
        )
    }
}
