package com.example.shortsdetector.data

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import com.example.shortsdetector.DetectionConfig
import com.example.shortsdetector.data.goal.Goal
import com.example.shortsdetector.data.goal.GoalPreferences
import com.example.shortsdetector.intervention.DemoMode
import com.example.shortsdetector.intervention.InterventionPolicy
import com.example.shortsdetector.intervention.InterventionPolicyBounds
import com.example.shortsdetector.intervention.InterventionPolicyCodec
import com.example.shortsdetector.intervention.LevelStreaks
import com.example.shortsdetector.intervention.ResolvedPolicy

/**
 * 개입 정책 저장. 개입 기준 시간의 유일한 저장소다. 클라우드 백업에서 제외한다.
 *
 * 시연 모드의 단계·연속 횟수는 별도 칸에 둔다. 시연 값을 정책에 쓰면 껐을 때 원래 정책으로 돌아갈 수 없다.
 * 판정에는 [resolve] 결과를 쓴다.
 */
object InterventionPolicyPreferences {

    private const val FILE = "intervention_policy"
    private const val KEY_POLICY = "policy"
    private const val KEY_SKIP_STREAK = "skip_streak"
    private const val KEY_STOP_STREAK = "stop_streak"
    private const val KEY_DEMO_MODE = "demo_mode"

    /** 값이 바뀌면 서비스가 하루 개입 카운터를 초기화한다. */
    const val KEY_RECORDS_CLEARED_AT = "records_cleared_at"
    private const val KEY_DEMO_RUN_ID = "demo_run_id"
    private const val KEY_DEMO_LEVEL = "demo_level"
    private const val KEY_DEMO_SKIP_STREAK = "demo_skip_streak"
    private const val KEY_DEMO_STOP_STREAK = "demo_stop_streak"

    fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    /** 저장된 정책. 시연 모드와 무관하다. */
    fun load(context: Context): InterventionPolicy {
        migrate(context)
        return read(prefs(context))
    }

    fun save(context: Context, policy: InterventionPolicy) = write(prefs(context), policy)

    /** 판정에 쓸 값: 시연 모드와 PER_SESSION 목표를 반영한다. */
    fun resolve(context: Context): ResolvedPolicy {
        migrate(context)
        return resolve(prefs(context), GoalPreferences.load(context))
    }

    fun isDemoMode(context: Context): Boolean = prefs(context).getBoolean(KEY_DEMO_MODE, false)

    fun setDemoMode(context: Context, enabled: Boolean) = writeDemoMode(prefs(context), enabled)

    /** 기록 전체 삭제를 서비스에 알린다. 어느 스레드에서 불러도 된다. */
    fun markRecordsCleared(context: Context) = writeRecordsCleared(prefs(context), System.currentTimeMillis())

    internal fun writeRecordsCleared(p: SharedPreferences, nowMs: Long) =
        p.edit { putLong(KEY_RECORDS_CLEARED_AT, nowMs) }

    /** 시연 준비 초기화: 저장 정책의 단계를 Lv.1 로, 연속 횟수와 시연 단계를 처음으로 되돌린다. 기준 시간 등 다른 값은 둔다. */
    fun resetForDemo(context: Context) {
        migrate(context)
        resetForDemo(prefs(context), System.currentTimeMillis())
    }

    internal fun resetForDemo(p: SharedPreferences, nowMs: Long) {
        write(p, read(p).copy(level = InterventionPolicyBounds.MIN_LEVEL))
        p.edit {
            remove(KEY_SKIP_STREAK)
            remove(KEY_STOP_STREAK)
            remove(KEY_DEMO_LEVEL)
            remove(KEY_DEMO_SKIP_STREAK)
            remove(KEY_DEMO_STOP_STREAK)
            putLong(KEY_RECORDS_CLEARED_AT, nowMs)
        }
    }

    private fun migrate(context: Context) {
        migrateLegacyThreshold(prefs(context), DetectionPreferences.prefs(context))
    }

    /**
     * 예전 임계값 설정을 정책으로 한 번 옮기고 지운다. 정책이 이미 있으면 옮기지 않는다.
     * 예전 값은 선택지에 있을 때만 쓴다(기존 읽기 규칙과 같음).
     * @return 이번 호출에서 옮겼으면 true
     */
    internal fun migrateLegacyThreshold(p: SharedPreferences, legacy: SharedPreferences): Boolean {
        if (p.contains(KEY_POLICY)) return false
        val old = legacy.getLong(DetectionPreferences.LEGACY_KEY_WATCH_THRESHOLD_MS, DetectionConfig.DEFAULT_WATCH_THRESHOLD_MS)
        val threshold = if (old in DetectionConfig.WATCH_THRESHOLD_CHOICES_MS) old else DetectionConfig.DEFAULT_WATCH_THRESHOLD_MS
        write(p, InterventionPolicy(thresholdMs = threshold))
        legacy.edit { remove(DetectionPreferences.LEGACY_KEY_WATCH_THRESHOLD_MS) }
        return true
    }

    internal fun read(p: SharedPreferences): InterventionPolicy =
        (p.getString(KEY_POLICY, null)?.let(InterventionPolicyCodec::decode) ?: InterventionPolicy()).clamped()

    internal fun write(p: SharedPreferences, policy: InterventionPolicy) =
        p.edit { putString(KEY_POLICY, InterventionPolicyCodec.encode(policy.clamped())) }

    internal fun resolve(p: SharedPreferences, goals: List<Goal>): ResolvedPolicy {
        val saved = read(p)
        if (!p.getBoolean(KEY_DEMO_MODE, false)) return ResolvedPolicy(saved, saved.effectiveThresholdMs(goals), false)
        val demo = DemoMode.overlay(saved, p.getInt(KEY_DEMO_LEVEL, InterventionPolicyBounds.MIN_LEVEL))
        return ResolvedPolicy(demo, demo.thresholdMs, true, p.getLong(KEY_DEMO_RUN_ID, 0L))
    }

    /** 켤 때마다 시연용 단계를 Lv.1 부터, 시연 전용 하루 카운터를 0 부터 다시 시작한다. */
    internal fun writeDemoMode(p: SharedPreferences, enabled: Boolean, nowMs: Long = System.currentTimeMillis()) = p.edit {
        putBoolean(KEY_DEMO_MODE, enabled)
        if (enabled) putLong(KEY_DEMO_RUN_ID, nowMs) else remove(KEY_DEMO_RUN_ID)
        remove(KEY_DEMO_LEVEL)
        remove(KEY_DEMO_SKIP_STREAK)
        remove(KEY_DEMO_STOP_STREAK)
    }

    /** 에이전트·되돌리기가 정책을 바꿀 때. 단계가 바뀌면 연속 횟수를 처음부터 다시 센다. */
    internal fun writeAgentPolicy(p: SharedPreferences, policy: InterventionPolicy) {
        val levelChanged = read(p).level != policy.clamped().level
        write(p, policy)
        if (levelChanged) {
            p.edit {
                remove(KEY_SKIP_STREAK)
                remove(KEY_STOP_STREAK)
            }
        }
    }

    internal fun readLevelStreaks(p: SharedPreferences): LevelStreaks {
        val demo = p.getBoolean(KEY_DEMO_MODE, false)
        return LevelStreaks(
            skips = p.getInt(if (demo) KEY_DEMO_SKIP_STREAK else KEY_SKIP_STREAK, 0).coerceAtLeast(0),
            stops = p.getInt(if (demo) KEY_DEMO_STOP_STREAK else KEY_STOP_STREAK, 0).coerceAtLeast(0)
        )
    }

    /** 단계 규칙의 결과를 저장한다. 시연 모드면 정책을 건드리지 않는다. */
    internal fun writeLevelState(p: SharedPreferences, level: Int, streaks: LevelStreaks) {
        val demo = p.getBoolean(KEY_DEMO_MODE, false)
        if (!demo) write(p, read(p).copy(level = level))
        p.edit {
            if (demo) putInt(KEY_DEMO_LEVEL, level)
            putInt(if (demo) KEY_DEMO_SKIP_STREAK else KEY_SKIP_STREAK, streaks.skips)
            putInt(if (demo) KEY_DEMO_STOP_STREAK else KEY_STOP_STREAK, streaks.stops)
        }
    }
}
