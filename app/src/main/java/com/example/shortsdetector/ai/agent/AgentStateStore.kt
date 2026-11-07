package com.example.shortsdetector.ai.agent

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import com.example.shortsdetector.data.InterventionPolicyPreferences
import com.example.shortsdetector.intervention.InterventionPolicy

/** 에이전트가 만든 팝업 문구. 없으면 null 이고 고정 문구를 쓴다. */
data class AgentMessages(val lv1Message: String?, val lv2Question: String?)

/** 에이전트가 읽고 쓰는 상태. 저장된 정책을 다룬다(시연 모드 값이 아니다). */
interface AgentStateStore {
    fun isEnabled(): Boolean
    fun policy(): InterventionPolicy
    fun savePolicy(policy: InterventionPolicy)
    fun saveMessages(messages: AgentMessages)
    fun clearMessages()
    fun markRun(nowMs: Long)
    fun saveLastResult(summary: String)
}

/** 에이전트 상태 저장. 클라우드 백업에서 제외한다. */
object AgentPreferences {

    private const val FILE = "agent_state"
    private const val KEY_ENABLED = "enabled"
    private const val KEY_LAST_RUN_AT = "last_run_at"
    private const val KEY_LV1 = "lv1_message"
    private const val KEY_LV2 = "lv2_question"
    private const val KEY_LAST_RESULT = "last_result"

    fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun isEnabled(context: Context): Boolean = isEnabled(prefs(context))

    fun setEnabled(context: Context, enabled: Boolean) = prefs(context).edit { putBoolean(KEY_ENABLED, enabled) }

    fun lastRunAtMs(context: Context): Long? = prefs(context).let {
        if (it.contains(KEY_LAST_RUN_AT)) it.getLong(KEY_LAST_RUN_AT, 0L) else null
    }

    fun lastResult(context: Context): String? = prefs(context).getString(KEY_LAST_RESULT, null)

    /** 팝업에 쓸 문구. 에이전트 조절을 끄면 고정 문구로 돌아간다. */
    fun activeMessages(context: Context): AgentMessages? = activeMessages(prefs(context))

    /** 시연 준비 초기화: 실행 시각·문구·결과를 지운다. 끄기 스위치는 사용자의 설정이라 둔다. */
    fun clearRunState(context: Context) = clearRunState(prefs(context))

    internal fun isEnabled(p: SharedPreferences) = p.getBoolean(KEY_ENABLED, true)

    internal fun activeMessages(p: SharedPreferences): AgentMessages? {
        if (!isEnabled(p)) return null
        val lv1 = p.getString(KEY_LV1, null)
        val lv2 = p.getString(KEY_LV2, null)
        return if (lv1 == null && lv2 == null) null else AgentMessages(lv1, lv2)
    }

    internal fun writeMessages(p: SharedPreferences, m: AgentMessages) = p.edit {
        putString(KEY_LV1, m.lv1Message)
        putString(KEY_LV2, m.lv2Question)
    }

    internal fun clearMessages(p: SharedPreferences) = p.edit {
        remove(KEY_LV1)
        remove(KEY_LV2)
    }

    internal fun writeLastRun(p: SharedPreferences, nowMs: Long) = p.edit { putLong(KEY_LAST_RUN_AT, nowMs) }

    internal fun writeLastResult(p: SharedPreferences, summary: String) = p.edit { putString(KEY_LAST_RESULT, summary) }

    /** 시드를 지울 때. 마지막 실행 시각은 남겨 앱을 열자마자 다시 호출되지 않게 한다. */
    internal fun clearSeedResults(p: SharedPreferences) = p.edit {
        remove(KEY_LV1)
        remove(KEY_LV2)
        remove(KEY_LAST_RESULT)
    }

    internal fun clearRunState(p: SharedPreferences) = p.edit {
        remove(KEY_LV1)
        remove(KEY_LV2)
        remove(KEY_LAST_RUN_AT)
        remove(KEY_LAST_RESULT)
    }
}

class PreferencesAgentStore(
    private val policyPrefs: SharedPreferences,
    private val agentPrefs: SharedPreferences
) : AgentStateStore {
    override fun isEnabled() = AgentPreferences.isEnabled(agentPrefs)
    override fun policy() = InterventionPolicyPreferences.read(policyPrefs)
    override fun savePolicy(policy: InterventionPolicy) = InterventionPolicyPreferences.writeAgentPolicy(policyPrefs, policy)
    override fun saveMessages(messages: AgentMessages) = AgentPreferences.writeMessages(agentPrefs, messages)
    override fun clearMessages() = AgentPreferences.clearMessages(agentPrefs)
    override fun markRun(nowMs: Long) = AgentPreferences.writeLastRun(agentPrefs, nowMs)
    override fun saveLastResult(summary: String) = AgentPreferences.writeLastResult(agentPrefs, summary)
}
