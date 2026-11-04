package com.example.shortsdetector.data.stats

import com.example.shortsdetector.data.goal.Goal
import com.example.shortsdetector.data.profile.UserProfile
import com.example.shortsdetector.data.usage.InterventionRecord
import com.example.shortsdetector.data.usage.PolicyChangeRecord
import com.example.shortsdetector.data.usage.SessionRecord
import com.example.shortsdetector.intervention.InterventionPolicy
import java.time.ZoneId

/**
 * 나중에 AI 입력으로 직렬화할 묶음. 집계값·설문 응답·목표·정책만 담는다.
 *
 * 세션/개입 원본 기록, 패키지 이름, 화면 원문·노드·영상 정보는 넣지 않는다.
 * 새 필드를 추가하면 AgentInputPrivacyTest 의 허용 타입 검사를 통과해야 한다.
 * 프로필은 설문 미완료면 null 이다. 외부로 보내기 전에 명세 7장의 고지·동의가 필요하다.
 */
data class AgentInput(
    val generatedAtMs: Long,
    val profile: UserProfile?,
    val goals: List<Goal>,
    val policy: InterventionPolicy,
    val today: TodayStats,
    val week: WeekStats,
    /** 새것부터 */
    val recentPolicyChanges: List<PolicyChangeRecord>
)

object AgentInputBuilder {

    /** [sessions], [interventions] 는 [UsageStatsCalculator.dataWindow] 범위의 기록. */
    fun build(
        profile: UserProfile?,
        goals: List<Goal>,
        policy: InterventionPolicy,
        sessions: List<SessionRecord>,
        interventions: List<InterventionRecord>,
        recentPolicyChanges: List<PolicyChangeRecord>,
        zone: ZoneId,
        nowMs: Long
    ) = AgentInput(
        generatedAtMs = nowMs,
        profile = profile,
        goals = goals,
        policy = policy,
        today = UsageStatsCalculator.today(sessions, interventions, goals, zone, nowMs),
        week = UsageStatsCalculator.week(sessions, interventions, zone, nowMs),
        recentPolicyChanges = recentPolicyChanges
    )
}
