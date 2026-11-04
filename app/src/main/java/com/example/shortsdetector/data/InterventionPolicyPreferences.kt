package com.example.shortsdetector.data

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import com.example.shortsdetector.intervention.InterventionPolicy
import com.example.shortsdetector.intervention.InterventionPolicyCodec
import com.example.shortsdetector.intervention.LevelStreaks

/**
 * 현재 개입 정책과 단계 연속 횟수. 항상 범위 안으로 잘린 값을 돌려준다. 클라우드 백업에서 제외한다.
 *
 * 저장된 정책이 없으면 기본 정책에 기존 임계값 설정([DetectionPreferences.watchThresholdMs])을 넣어 돌려준다.
 * 지금 서비스가 실제로 쓰는 임계값은 여전히 DetectionPreferences 쪽이다. 정책의 임계값·쿨다운·하루 상한은 아직 판정에 연결되지 않았다.
 */
object InterventionPolicyPreferences {

    private const val FILE = "intervention_policy"
    private const val KEY_POLICY = "policy"
    private const val KEY_SKIP_STREAK = "skip_streak"
    private const val KEY_STOP_STREAK = "stop_streak"

    fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun load(context: Context): InterventionPolicy =
        read(prefs(context), DetectionPreferences.watchThresholdMs(context))

    fun save(context: Context, policy: InterventionPolicy) = write(prefs(context), policy)

    fun loadStreaks(context: Context): LevelStreaks = readStreaks(prefs(context))

    fun saveStreaks(context: Context, streaks: LevelStreaks) = writeStreaks(prefs(context), streaks)

    internal fun read(p: SharedPreferences, fallbackThresholdMs: Long): InterventionPolicy {
        val stored = p.getString(KEY_POLICY, null)?.let(InterventionPolicyCodec::decode)
        return (stored ?: InterventionPolicy(thresholdMs = fallbackThresholdMs)).clamped()
    }

    internal fun write(p: SharedPreferences, policy: InterventionPolicy) =
        p.edit { putString(KEY_POLICY, InterventionPolicyCodec.encode(policy.clamped())) }

    internal fun readStreaks(p: SharedPreferences) = LevelStreaks(
        skips = p.getInt(KEY_SKIP_STREAK, 0).coerceAtLeast(0),
        stops = p.getInt(KEY_STOP_STREAK, 0).coerceAtLeast(0)
    )

    internal fun writeStreaks(p: SharedPreferences, streaks: LevelStreaks) = p.edit {
        putInt(KEY_SKIP_STREAK, streaks.skips)
        putInt(KEY_STOP_STREAK, streaks.stops)
    }
}
