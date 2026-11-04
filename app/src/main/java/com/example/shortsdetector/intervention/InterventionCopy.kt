package com.example.shortsdetector.intervention

import android.content.Context
import com.example.shortsdetector.R
import com.example.shortsdetector.ShortsNotifier
import com.example.shortsdetector.data.goal.Goal
import com.example.shortsdetector.data.goal.GoalType

/** @param goalRemainingMinutes 목표까지 남은 분. 목표가 없으면 null, 이미 닿았으면 0. */
data class InterventionCopyInput(val level: Int, val watchMs: Long, val goalRemainingMinutes: Int?)

data class InterventionCopy(val title: String, val body: String? = null)

/**
 * 팝업·이유 문구 공급자. 지금은 고정 문구([ResourceInterventionCopyProvider])뿐이고,
 * 나중에 AI 문구 구현을 넣을 때 실패하면 고정 문구로 돌아가게 한다. 판정에는 관여하지 않는다.
 */
interface InterventionCopyProvider {
    fun copyFor(input: InterventionCopyInput): InterventionCopy

    /** 단계가 [from] → [to] 로 바뀐 이유. 사용자에게 보이고 정책 변경 기록에 남는다. */
    fun levelChangeReason(from: Int, to: Int, policy: InterventionPolicy): String
}

class ResourceInterventionCopyProvider(private val context: Context) : InterventionCopyProvider {

    override fun copyFor(input: InterventionCopyInput): InterventionCopy {
        val watch = ShortsNotifier.formatWatch(context, input.watchMs)
        return when (input.level) {
            1 -> InterventionCopy(
                when (val left = input.goalRemainingMinutes) {
                    null -> context.getString(R.string.popup_lv1_title, watch)
                    0 -> context.getString(R.string.popup_lv1_title_goal_reached, watch)
                    else -> context.getString(R.string.popup_lv1_title_goal, watch, left)
                }
            )
            2 -> InterventionCopy(context.getString(R.string.popup_lv2_title))
            else -> InterventionCopy(
                context.getString(R.string.popup_lv3_title),
                context.getString(R.string.popup_lv3_body, watch)
            )
        }
    }

    override fun levelChangeReason(from: Int, to: Int, policy: InterventionPolicy): String = when {
        to < from -> context.getString(R.string.level_down, policy.deescalateAfterStops)
        to == 2 -> context.getString(R.string.level_up_to_2, policy.escalateAfterSkips)
        else -> context.getString(R.string.level_up_to_3)
    }
}

object GoalRemaining {

    private const val MS_PER_MINUTE = 60_000L

    /**
     * 팝업에 보여 줄 "목표까지 남은 분"(올림). 하루 목표를 우선하고, 없으면 한 번에 보는 시간 목표를 쓴다.
     * @param todayBeforeSessionMs 이 세션 전에 오늘 본 시간
     */
    fun minutes(goals: List<Goal>, todayBeforeSessionMs: Long, sessionWatchMs: Long): Int? {
        val daily = goals.firstOrNull { it.type == GoalType.DAILY_TOTAL }
        val session = goals.firstOrNull { it.type == GoalType.PER_SESSION }
        val leftMs = when {
            daily != null -> daily.value * MS_PER_MINUTE - todayBeforeSessionMs - sessionWatchMs
            session != null -> session.value * MS_PER_MINUTE - sessionWatchMs
            else -> return null
        }
        return ((leftMs.coerceAtLeast(0L) + MS_PER_MINUTE - 1) / MS_PER_MINUTE).toInt()
    }
}
