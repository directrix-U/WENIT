package com.example.shortsdetector.ai.agent

import com.example.shortsdetector.ai.AgentInputJson
import com.example.shortsdetector.ai.AiFailureKind
import com.example.shortsdetector.ai.AiResult
import com.example.shortsdetector.ai.ClaudeMessagesClient
import com.example.shortsdetector.data.stats.AgentInput
import com.example.shortsdetector.data.usage.PolicyChangeRecord
import com.example.shortsdetector.data.usage.PolicyChangeSource
import com.example.shortsdetector.data.usage.PolicyChangeStatus
import com.example.shortsdetector.data.usage.UsageStore
import com.example.shortsdetector.intervention.InterventionPolicy
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.util.UUID

enum class AgentRunStatus { DISABLED, NO_CONSENT, NOT_ENOUGH_DATA, FAILED, KEPT, CHANGED }

data class AgentRunResult(
    val status: AgentRunStatus,
    val before: InterventionPolicy,
    val after: InterventionPolicy,
    val elapsedMs: Long,
    val failure: AiFailureKind? = null,
    val httpStatus: Int? = null,
    val reason: String? = null
) {
    /** 임시 화면과 마지막 결과 저장용 한 줄 */
    fun summary(): String = buildString {
        append(status.name)
        append(" · ${elapsedMs}ms")
        failure?.let { append(" · $it") }
        httpStatus?.let { append(" · HTTP $it") }
        if (status == AgentRunStatus.CHANGED) append(" · ${AgentPolicyRules.describeChange(before, after)}")
        reason?.let { append(" · $it") }
    }
}

/**
 * 개입 정책 조절 에이전트(명세 2장 MVP-D, 3.1-8). 정해진 때 한 번 호출되는 함수이며, 매 순간의 개입 판정은 하지 않는다.
 *
 * 순서: 조절 끄기 → 동의 → 데이터 충분성 → 호출 → 형식 검증 → 범위·변경 폭 적용 → 저장 → 기록.
 * 어떤 실패든 저장하지 않으므로 현재 정책이 그대로 유지된다. 감지·권한·시스템 설정·전송 권한은 없다.
 * 호출 스레드에서 동기로 동작한다. 메인 스레드에서 부르지 않는다.
 */
class PolicyAgent(
    private val client: ClaudeMessagesClient,
    private val state: AgentStateStore,
    private val records: UsageStore,
    private val nowMs: () -> Long = System::currentTimeMillis,
    private val newId: () -> String = { UUID.randomUUID().toString() }
) {
    fun run(input: AgentInput): AgentRunResult {
        val started = nowMs()
        val current = state.policy()
        fun done(status: AgentRunStatus, failure: AiFailureKind? = null, http: Int? = null): AgentRunResult =
            AgentRunResult(status, current, current, nowMs() - started, failure, http).also { state.saveLastResult(it.summary()) }

        if (!state.isEnabled()) return done(AgentRunStatus.DISABLED)
        if (input.profile?.aiRecommendationConsent != true) return done(AgentRunStatus.NO_CONSENT)
        if (input.recent.deliveredInterventions < MIN_INTERVENTIONS) return done(AgentRunStatus.NOT_ENOUGH_DATA)
        if (!client.hasKey) return done(AgentRunStatus.FAILED, AiFailureKind.NO_KEY)

        state.markRun(nowMs())
        val result = client.requestJson(SYSTEM_PROMPT, userText(input), SCHEMA)
        if (result is AiResult.Failure) return done(AgentRunStatus.FAILED, result.kind, result.httpStatus)
        val proposal = parse((result as AiResult.Success).json)
            ?: return done(AgentRunStatus.FAILED, AiFailureKind.BAD_FORMAT)
        if (proposal.keep) {
            val reason = AgentText.clean(proposal.reason, AgentText.MAX_REASON_LENGTH)
            return AgentRunResult(AgentRunStatus.KEPT, current, current, nowMs() - started, reason = reason)
                .also { state.saveLastResult(it.summary()) }
        }

        state.saveMessages(
            AgentMessages(
                AgentText.clean(proposal.lv1Message, AgentText.MAX_MESSAGE_LENGTH),
                AgentText.clean(proposal.lv2Question, AgentText.MAX_MESSAGE_LENGTH)
            )
        )
        val next = AgentPolicyRules.apply(current, proposal)
        val reason = AgentText.clean(proposal.reason, AgentText.MAX_REASON_LENGTH) ?: FALLBACK_REASON
        if (next == current) {
            return AgentRunResult(AgentRunStatus.KEPT, current, current, nowMs() - started, reason = reason)
                .also { state.saveLastResult(it.summary()) }
        }
        state.savePolicy(next)
        records.insertPolicyChange(
            PolicyChangeRecord(
                id = newId(),
                occurredAtMs = nowMs(),
                source = PolicyChangeSource.AGENT,
                previousPolicy = current,
                newPolicy = next,
                reasonText = reason,
                inputSummary = inputSummary(input),
                status = PolicyChangeStatus.APPLIED,
                modelId = ClaudeMessagesClient.MODEL,
                isSeed = input.usedSeedData
            )
        )
        return AgentRunResult(AgentRunStatus.CHANGED, current, next, nowMs() - started, reason = reason)
            .also { state.saveLastResult(it.summary()) }
    }

    /**
     * 가장 최근 AGENT 변경 하나를 되돌린다. 이미 되돌린 변경이면 더 옛날 변경으로 넘어가지 않고 null 을 돌려준다.
     * 정책은 변경 전 값 전체로 복구하고, 에이전트 문구는 지워 고정 문구로 돌아간다.
     */
    fun revertLatest(reason: String): PolicyChangeRecord? {
        val latest = records.recentPolicyChanges(REVERT_LOOKBACK)
            .firstOrNull { it.source == PolicyChangeSource.AGENT && !it.isDemo } ?: return null
        val previous = latest.previousPolicy ?: return null
        if (latest.status != PolicyChangeStatus.APPLIED) return null

        val current = state.policy()
        state.savePolicy(previous)
        state.clearMessages()
        records.setPolicyChangeStatus(latest.id, PolicyChangeStatus.REVERTED_BY_USER)
        return PolicyChangeRecord(
            id = newId(),
            occurredAtMs = nowMs(),
            source = PolicyChangeSource.USER,
            previousPolicy = current,
            newPolicy = previous,
            reasonText = reason,
            inputSummary = null,
            status = PolicyChangeStatus.REVERTED_BY_USER,
            isSeed = latest.isSeed
        ).also(records::insertPolicyChange)
    }

    private fun userText(input: AgentInput) =
        "다음 JSON 은 이 사용자의 최근 이용 통계와 현재 정책입니다. 정책을 유지할지 바꿀지 판단해 주세요.\n" +
            AgentInputJson.toJson(input)

    private fun inputSummary(input: AgentInput) =
        "최근 ${input.recent.days.size}일, 개입 ${input.recent.deliveredInterventions}건, " +
            "지표: 일별 시청·시간대·세션·단계별 결과·이유·목표 상태"

    /** 필드가 빠졌거나 타입이 다르면 null. 일부만 쓰지 않고 전체를 버린다. */
    private fun parse(json: JSONObject): AgentProposal? = try {
        val keep = when (json.getString("decision")) {
            "KEEP" -> true
            "CHANGE" -> false
            else -> null
        }
        keep?.let {
            AgentProposal(
                keep = it,
                thresholdSec = whole(json, "thresholdSec"),
                cooldownSec = whole(json, "cooldownSec"),
                dailyMax = whole(json, "dailyMax"),
                startLevel = whole(json, "startLevel"),
                reason = json.getString("reason"),
                lv1Message = json.getString("lv1Message"),
                lv2Question = json.getString("lv2Question")
            )
        }
    } catch (e: JSONException) {
        null
    }

    private fun whole(json: JSONObject, key: String): Int {
        val value = json.get(key)
        if (value !is Number || value.toDouble() != Math.rint(value.toDouble())) throw JSONException(key)
        return value.toInt()
    }

    companion object {
        const val MIN_INTERVENTIONS = 3
        private const val REVERT_LOOKBACK = 50
        private const val FALLBACK_REASON = "최근 반응을 보고 조금 조정했어요"

        val SCHEMA: JSONObject = JSONObject()
            .put("type", "object")
            .put(
                "properties",
                JSONObject()
                    .put("decision", JSONObject().put("type", "string").put("enum", JSONArray(listOf("KEEP", "CHANGE"))))
                    .put("thresholdSec", JSONObject().put("type", "integer"))
                    .put("cooldownSec", JSONObject().put("type", "integer"))
                    .put("dailyMax", JSONObject().put("type", "integer"))
                    .put("startLevel", JSONObject().put("type", "integer"))
                    .put("reason", JSONObject().put("type", "string"))
                    .put("lv1Message", JSONObject().put("type", "string"))
                    .put("lv2Question", JSONObject().put("type", "string"))
            )
            .put(
                "required",
                JSONArray(
                    listOf(
                        "decision", "thresholdSec", "cooldownSec", "dailyMax", "startLevel",
                        "reason", "lv1Message", "lv2Question"
                    )
                )
            )
            .put("additionalProperties", false)

        val SYSTEM_PROMPT: String = """
            당신은 숏폼 시청을 스스로 조절하려는 사람을 돕는 앱의 개입 정책을 조절합니다.
            사용자가 알림에 어떻게 반응했는지를 보고, 다음 개입을 어떻게 할지 정합니다.

            개입 단계는 Lv.1 가벼운 알림, Lv.2 이유를 묻는 질문, Lv.3 10초 숨 고르기입니다.
            levelOutcomes 의 stopped 는 스스로 멈춤, continued 는 계속 보기를 고름, ignored 는 반응 없이 넘김입니다.

            판단 기준:
            - 넘김(continued, ignored)이 많으면 startLevel 을 올리고 thresholdSec 을 줄입니다.
            - 잘 멈추면(stopped 가 많으면) startLevel 을 내리고 thresholdSec 을 늘려 완화합니다.
            - 짧은 시간에 개입이 많거나 피로 신호가 있으면 dailyMax 를 줄입니다.
            - 근거가 약하면 큰 변화를 주지 말고 decision 을 KEEP 으로 합니다.

            값의 범위:
            - thresholdSec: 30~600, 현재 값의 ±50% 이내
            - cooldownSec: 30~600
            - dailyMax: 3~10, 현재 값의 ±3 이내
            - startLevel: 1~maxLevel, 현재 단계의 ±1 이내
            - decision 이 KEEP 이어도 모든 필드를 현재 값 기준으로 채웁니다.
            - policy.perSessionGoalMinutes 는 사용자가 정한 한 번 시청 목표입니다. 실제 기준은 thresholdSec 와 이 값 중 작은 쪽입니다.

            문구:
            - reason 은 실제 숫자를 근거로 한 문장, 40자 이내, 사용자에게 보여 줍니다. 예: "7일간 알림 12번 중 10번 넘겨서 조금 일찍 알려요"
            - lv1Message 는 30자 이내의 가벼운 알림 문구입니다. 이 사용자가 주로 보는 시간대(watchMsByTimeSlot)와 넘김 비율을 반영해 씁니다.
            - lv2Question 은 30자 이내의 질문입니다. lv2Reasons 에서 가장 많은 이유와 시간대를 반영해 씁니다.
            - 두 문구는 이 사용자의 데이터에서 나온 내용이어야 합니다. 누구에게나 맞는 일반적인 문장이나 이 안내문의 문장을 그대로 쓰지 않습니다.
            - 모두 "~해요" 체로 쓰고, 높임말(~시다, ~셔서, ~신다)은 쓰지 않습니다. 틀림: "자주 넘기셔서" → 맞음: "자주 넘겨서"
            - 비난하거나 죄책감을 주지 않습니다. 의학적·심리학적 진단이나 건강 효과를 말하지 않습니다.
        """.trimIndent()
    }
}
