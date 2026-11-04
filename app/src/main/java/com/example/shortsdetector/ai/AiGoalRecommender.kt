package com.example.shortsdetector.ai

import com.example.shortsdetector.data.goal.Goal
import com.example.shortsdetector.data.goal.GoalRules
import com.example.shortsdetector.data.goal.GoalSource
import com.example.shortsdetector.data.goal.GoalType
import com.example.shortsdetector.data.goal.PresetGoalRecommender
import com.example.shortsdetector.data.profile.AgeBand
import com.example.shortsdetector.data.profile.ShortformApp
import com.example.shortsdetector.data.profile.UserProfile
import com.example.shortsdetector.data.profile.UsualDailyWatch
import com.example.shortsdetector.data.profile.WatchTimeSlot
import com.example.shortsdetector.data.stats.AgentInput
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.time.DayOfWeek

/**
 * @param failure AI 를 쓰지 못했으면 그 이유. 이때 [goals] 는 전부 PRESET 이다.
 * @param goals 항상 3개
 */
data class GoalRecommendation(
    val goals: List<Goal>,
    val failure: AiFailureKind?,
    val httpStatus: Int?,
    val elapsedMs: Long
)

/**
 * AI 목표 추천. 어떤 실패든 [PresetGoalRecommender] 로 대체하므로 호출 측은 항상 목표 3개를 받는다.
 *
 * 동의 게이트: 프로필의 AI 추천 동의가 true 가 아니면 네트워크를 쓰지 않는다. 호출 스레드에서 동기로 동작한다.
 * 구조화 출력은 개수·길이·숫자 범위 제약을 지원하지 않으므로 그 값은 프롬프트로 요청하고 여기서 강제한다.
 */
class AiGoalRecommender(
    private val client: ClaudeMessagesClient,
    private val nowMs: () -> Long = System::currentTimeMillis
) {
    fun recommend(input: AgentInput): GoalRecommendation {
        val started = nowMs()
        fun finish(goals: List<Goal>, failure: AiFailureKind?, status: Int? = null) =
            GoalRecommendation(goals, failure, status, nowMs() - started)

        val preset = PresetGoalRecommender.recommend(input.profile)
        if (input.profile?.aiRecommendationConsent != true) return finish(preset, AiFailureKind.NO_CONSENT)
        if (!client.hasKey) return finish(preset, AiFailureKind.NO_KEY)

        return when (val result = client.requestJson(SYSTEM_PROMPT, userText(input), SCHEMA)) {
            is AiResult.Failure -> finish(preset, result.kind, result.httpStatus)
            is AiResult.Success -> {
                val goals = parseGoals(result.json)
                if (goals.isEmpty()) {
                    finish(preset, AiFailureKind.BAD_FORMAT)
                } else {
                    finish(fillFromPreset(goals, preset), null)
                }
            }
        }
    }

    private fun userText(input: AgentInput) =
        "다음 JSON 은 사용자의 설문 응답과 집계 통계입니다. 이 사용자에게 맞는 목표 3개를 추천해 주세요.\n" +
            AgentInputJson.toJson(input)

    companion object {
        const val GOAL_COUNT = 3
        const val MAX_LABEL_LENGTH = 25
        const val MAX_REASON_LENGTH = 40

        /** 형식이 맞지 않는 항목은 건너뛴다. 값은 기존 검증으로 자르고, 같은 유형은 앞의 것만 남긴다. */
        internal fun parseGoals(json: JSONObject): List<Goal> {
            val items = json.optJSONArray("goals") ?: return emptyList()
            val goals = (0 until items.length()).mapNotNull { i -> parseGoal(items.optJSONObject(i)) }
            return GoalRules.normalize(goals).map(::fitLengths)
        }

        private fun parseGoal(item: JSONObject?): Goal? {
            if (item == null) return null
            return try {
                val type = GoalType.entries.firstOrNull { it.name == item.getString("type") } ?: return null
                Goal(type, item.getInt("value"), item.getString("label"), item.getString("reason"), GoalSource.AI)
            } catch (e: JSONException) {
                null
            }
        }

        /** 화면에 들어가도록 줄인다. 라벨은 자르면 뜻이 바뀌므로 기본 문구로 바꾼다. */
        private fun fitLengths(goal: Goal) = goal.copy(
            label = if (goal.label.length > MAX_LABEL_LENGTH) GoalRules.defaultLabel(goal.type, goal.value) else goal.label,
            reason = goal.reason?.take(MAX_REASON_LENGTH)
        )

        private fun fillFromPreset(goals: List<Goal>, preset: List<Goal>): List<Goal> {
            val missing = preset.filter { p -> goals.none { it.type == p.type } }
            return (goals + missing).take(GOAL_COUNT)
        }

        val SCHEMA: JSONObject = JSONObject()
            .put("type", "object")
            .put(
                "properties",
                JSONObject().put(
                    "goals",
                    JSONObject()
                        .put("type", "array")
                        .put(
                            "items",
                            JSONObject()
                                .put("type", "object")
                                .put(
                                    "properties",
                                    JSONObject()
                                        .put(
                                            "type",
                                            JSONObject()
                                                .put("type", "string")
                                                .put("enum", JSONArray(GoalType.entries.map { it.name }))
                                        )
                                        .put("value", JSONObject().put("type", "integer"))
                                        .put("label", JSONObject().put("type", "string"))
                                        .put("reason", JSONObject().put("type", "string"))
                                )
                                .put("required", JSONArray(listOf("type", "value", "label", "reason")))
                                .put("additionalProperties", false)
                        )
                )
            )
            .put("required", JSONArray(listOf("goals")))
            .put("additionalProperties", false)

        private fun range(type: GoalType) = GoalRules.rangeOf(type).let { "${it.first}~${it.last}" }

        val SYSTEM_PROMPT: String = """
            당신은 숏폼 시청을 스스로 조절하려는 사람을 돕는 코치입니다. 설문 응답과 통계를 보고 지킬 만한 목표를 추천합니다.

            지켜야 할 것:
            - 친근하고 비난하지 않는 말투를 씁니다. 수치심이나 죄책감을 주는 표현을 쓰지 않습니다.
            - 의학적·심리학적 진단이나 건강 효과를 주장하지 않습니다.
            - 목표는 정확히 ${GOAL_COUNT}개이고 유형이 모두 달라야 합니다.
            - 아래 유형만 씁니다. value 는 정수이고 범위 안이어야 합니다.
              - DAILY_TOTAL: 하루 총 시청 분 이하 (${range(GoalType.DAILY_TOTAL)})
              - PER_SESSION: 한 번에 보는 분 이하 (${range(GoalType.PER_SESSION)})
              - TIME_WINDOW: 이 시각 이후 시청 안 함. 값은 자정을 넘겨 이어지는 분입니다. 23:00 은 1380, 새벽 1:00 은 1500 (${range(GoalType.TIME_WINDOW)})
              - DAILY_OPENS: 하루 시청 세션 수 이하 (${range(GoalType.DAILY_OPENS)})
              - SELF_STOPS: 하루 스스로 멈춘 횟수 이상 (${range(GoalType.SELF_STOPS)})
            - 지나치게 엄격한 목표를 내지 않습니다. 평소 시청 시간에서 조금 줄이는 정도로 시작합니다.
            - label 은 한국어 ${MAX_LABEL_LENGTH}자 이내로, 화면에 보여 줄 목표 문구입니다.
            - reason 은 한국어 ${MAX_REASON_LENGTH}자 이내로, 설문 응답을 근거로 왜 이 목표인지 씁니다.
            - 나이대와 성별로 사람을 단정하지 않습니다.
        """.trimIndent()
    }
}

/** 설문 화면이 생기기 전 디버그 빌드에서 쓰는 고정 응답. */
object TestSurvey {
    val profile = UserProfile(
        ageBand = AgeBand.TWENTIES,
        shortformApps = setOf(ShortformApp.YOUTUBE_SHORTS),
        watchTimeSlots = setOf(WatchTimeSlot.EVENING, WatchTimeSlot.LATE_NIGHT),
        watchDays = setOf(DayOfWeek.FRIDAY, DayOfWeek.SATURDAY, DayOfWeek.SUNDAY),
        usualDailyWatch = UsualDailyWatch.HOUR_1_TO_2
    )
}
