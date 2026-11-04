package com.example.shortsdetector.data.profile

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import java.time.DayOfWeek

/**
 * 온보딩 설문 응답 저장. 나이대·성별이 들어 있어 클라우드 백업에서 제외한다(res/xml/backup_rules.xml).
 *
 * 설문 미완료 = [load] 가 null. 완료 표시가 없거나 필수 응답을 읽을 수 없으면(구버전·손상) 미완료로 본다.
 */
object UserProfilePreferences {

    private const val FILE = "user_profile"
    private const val KEY_COMPLETED = "survey_completed"
    private const val KEY_AGE_BAND = "age_band"
    private const val KEY_GENDER = "gender"
    private const val KEY_APPS = "shortform_apps"
    private const val KEY_TIME_SLOTS = "watch_time_slots"
    private const val KEY_DAYS = "watch_days"

    fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun load(context: Context): UserProfile? = read(prefs(context))

    fun isSurveyCompleted(context: Context): Boolean = load(context) != null

    fun save(context: Context, profile: UserProfile) = write(prefs(context), profile)

    /** 설문 응답을 지운다. 다시 미완료 상태가 된다. */
    fun clear(context: Context) = prefs(context).edit { clear() }

    internal fun read(p: SharedPreferences): UserProfile? {
        if (!p.getBoolean(KEY_COMPLETED, false)) return null
        return UserProfile(
            ageBand = enumOrNull<AgeBand>(p.getString(KEY_AGE_BAND, null)) ?: return null,
            gender = enumOrNull<Gender>(p.getString(KEY_GENDER, null)) ?: return null,
            shortformApps = enumSet(p, KEY_APPS),
            watchTimeSlots = enumSet(p, KEY_TIME_SLOTS),
            watchDays = enumSet<DayOfWeek>(p, KEY_DAYS)
        )
    }

    internal fun write(p: SharedPreferences, profile: UserProfile) = p.edit {
        putBoolean(KEY_COMPLETED, true)
        putString(KEY_AGE_BAND, profile.ageBand.name)
        putString(KEY_GENDER, profile.gender.name)
        putStringSet(KEY_APPS, profile.shortformApps.mapTo(HashSet()) { it.name })
        putStringSet(KEY_TIME_SLOTS, profile.watchTimeSlots.mapTo(HashSet()) { it.name })
        putStringSet(KEY_DAYS, profile.watchDays.mapTo(HashSet()) { it.name })
    }

    private inline fun <reified E : Enum<E>> enumOrNull(name: String?): E? =
        enumValues<E>().firstOrNull { it.name == name }

    /** 모르는 값은 건너뛴다. */
    private inline fun <reified E : Enum<E>> enumSet(p: SharedPreferences, key: String): Set<E> =
        p.getStringSet(key, null).orEmpty().mapNotNullTo(LinkedHashSet()) { enumOrNull<E>(it) }
}
