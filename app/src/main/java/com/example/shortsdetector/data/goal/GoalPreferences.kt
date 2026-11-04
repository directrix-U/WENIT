package com.example.shortsdetector.data.goal

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit

/**
 * 사용자 목표 저장. 칸 번호 키(goal_0_type …)로 나눠 저장한다.
 * 읽을 때도 [GoalRules.normalize] 를 지나므로 손상된 값이나 바뀐 범위가 화면·판정으로 새지 않는다.
 * 클라우드 백업에서 제외한다(res/xml/backup_rules.xml).
 */
object GoalPreferences {

    private const val FILE = "user_goals"
    private const val KEY_COUNT = "goal_count"

    fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun load(context: Context): List<Goal> = read(prefs(context))

    /** 목록 전체를 바꾼다. */
    fun save(context: Context, goals: List<Goal>) = write(prefs(context), goals)

    /** 추가 또는 같은 유형 수정. 이미 최대 개수라 넣을 수 없으면 false. */
    fun put(context: Context, goal: Goal): Boolean = put(prefs(context), goal)

    /** [oldType] 목표 수정(유형 변경 포함). 넣을 수 없으면 false. */
    fun replace(context: Context, oldType: GoalType, goal: Goal): Boolean = replace(prefs(context), oldType, goal)

    fun remove(context: Context, type: GoalType) = remove(prefs(context), type)

    fun clear(context: Context) = prefs(context).edit { clear() }

    internal fun put(p: SharedPreferences, goal: Goal): Boolean =
        GoalRules.put(read(p), goal)?.also { write(p, it) } != null

    internal fun replace(p: SharedPreferences, oldType: GoalType, goal: Goal): Boolean =
        GoalRules.replace(read(p), oldType, goal)?.also { write(p, it) } != null

    internal fun remove(p: SharedPreferences, type: GoalType) = write(p, GoalRules.remove(read(p), type))

    internal fun read(p: SharedPreferences): List<Goal> {
        val count = p.getInt(KEY_COUNT, 0).coerceIn(0, GoalRules.MAX_GOALS)
        val goals = (0 until count).mapNotNull { i ->
            val type = GoalType.entries.firstOrNull { it.name == p.getString(key(i, "type"), null) }
                ?: return@mapNotNull null
            Goal(
                type = type,
                value = p.getInt(key(i, "value"), 0),
                label = p.getString(key(i, "label"), null).orEmpty(),
                reason = p.getString(key(i, "reason"), null),
                source = GoalSource.entries.firstOrNull { it.name == p.getString(key(i, "source"), null) }
                    ?: GoalSource.USER
            )
        }
        return GoalRules.normalize(goals)
    }

    internal fun write(p: SharedPreferences, goals: List<Goal>) {
        val clean = GoalRules.normalize(goals)
        p.edit {
            clear()
            putInt(KEY_COUNT, clean.size)
            clean.forEachIndexed { i, g ->
                putString(key(i, "type"), g.type.name)
                putInt(key(i, "value"), g.value)
                putString(key(i, "label"), g.label)
                putString(key(i, "reason"), g.reason)
                putString(key(i, "source"), g.source.name)
            }
        }
    }

    private fun key(index: Int, field: String) = "goal_${index}_$field"
}
