package com.example.shortsdetector.ai

import com.example.shortsdetector.data.goal.Goal
import com.example.shortsdetector.data.stats.AgentInput
import org.json.JSONArray
import org.json.JSONObject

/**
 * [AgentInput] 을 외부로 보낼 JSON 으로 바꾼다. 네트워크로 나가는 사용자 데이터는 이 결과뿐이다.
 * 허용 목록 방식이다: 여기에 적은 필드만 나간다(집계 숫자, 설문 응답, 목표, 정책).
 * 기록 id, 동의 값, 시각 원본은 보내지 않는다.
 */
object AgentInputJson {

    fun toJson(input: AgentInput): JSONObject {
        val root = JSONObject()
        input.profile?.let { p ->
            root.put(
                "survey",
                JSONObject()
                    .put("ageBand", p.ageBand.name)
                    .put("gender", p.gender.name)
                    .put("shortforms", names(p.shortformApps))
                    .put("watchTimeSlots", names(p.watchTimeSlots))
                    .put("watchDays", names(p.watchDays))
                    .put("usualDailyWatch", p.usualDailyWatch?.name ?: JSONObject.NULL)
            )
        }
        root.put("currentGoals", JSONArray().apply { input.goals.forEach { put(goal(it)) } })
        root.put(
            "policy",
            JSONObject()
                .put("thresholdMs", input.policy.thresholdMs)
                .put("level", input.policy.level)
                .put("maxDailyInterventions", input.policy.maxDailyInterventions)
        )
        root.put(
            "today",
            JSONObject()
                .put("date", input.today.date.toString())
                .put("estimatedWatchMs", input.today.estimatedWatchMs)
                .put("sessionCount", input.today.sessionCount)
                .put("longestSessionMs", input.today.longestSessionMs)
                .put("interventionCount", input.today.interventionCount)
                .put("selfStopCount", input.today.selfStopCount)
        )
        root.put(
            "week",
            JSONObject()
                .put("dailyWatchMs", JSONArray().apply { input.week.days.forEach { put(it.watchMs) } })
                .put("dailyAverageMs", input.week.dailyAverageMs)
                .put("changeRatio", input.week.changeRatio ?: JSONObject.NULL)
                .put(
                    "levelStopRates",
                    JSONArray().apply {
                        input.week.levelStopRates.forEach {
                            put(JSONObject().put("level", it.level).put("answered", it.answered).put("stopped", it.stopped))
                        }
                    }
                )
        )
        return root
    }

    private fun goal(g: Goal) = JSONObject().put("type", g.type.name).put("value", g.value)

    private fun names(values: Set<Enum<*>>) = JSONArray().apply { values.map { it.name }.sorted().forEach { put(it) } }
}
