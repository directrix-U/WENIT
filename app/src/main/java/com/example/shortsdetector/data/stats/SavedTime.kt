package com.example.shortsdetector.data.stats

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import com.example.shortsdetector.data.profile.UsualDailyWatch
import com.example.shortsdetector.data.usage.DailyUsageCalculator
import com.example.shortsdetector.data.usage.SessionRecord
import java.time.LocalDate
import java.time.ZoneId

enum class SavedTimeBaseline { SURVEY, FIRST_WEEK }

/**
 * 아낀 시간 = 기준값 - 오늘 시청시간(0 미만은 0).
 * @param isEstimate 화면에 추정치임을 표시할지. 개입 효과를 증명하는 값이 아니므로 지금 정의에서는 항상 true.
 */
data class SavedTime(
    val savedMs: Long,
    val baselineMs: Long,
    val baseline: SavedTimeBaseline,
    val isEstimate: Boolean = true
)

object SavedTimeCalculator {

    const val FIRST_WEEK_DAYS = 7L
    private const val MS_PER_MINUTE = 60_000L

    /**
     * 첫 기록일부터 7일의 하루 평균. 그 7일이 다 지나기 전에는 null. 시청이 없던 날은 0 으로 평균에 들어간다.
     * @param sessions 첫 기록을 포함한 기록
     */
    fun firstWeekAverageMs(sessions: List<SessionRecord>, zone: ZoneId, today: LocalDate): Long? {
        val first = sessions.minOfOrNull { it.startedAtMs }?.let { DailyUsageCalculator.dateOf(it, zone) } ?: return null
        if (today.isBefore(first.plusDays(FIRST_WEEK_DAYS))) return null
        val days = DailyUsageCalculator.aggregate(sessions, zone, first, first.plusDays(FIRST_WEEK_DAYS - 1))
        return days.sumOf { it.estimatedWatchMs } / FIRST_WEEK_DAYS
    }

    /** 첫 7일 평균이 있으면 그것을, 없으면 설문값을 기준으로 쓴다. 둘 다 없으면 계산할 수 없어 null. */
    fun estimate(firstWeekAverageMs: Long?, usual: UsualDailyWatch?, todayWatchMs: Long): SavedTime? {
        val baseline = firstWeekAverageMs ?: usual?.let { it.estimateMinutes * MS_PER_MINUTE } ?: return null
        val source = if (firstWeekAverageMs != null) SavedTimeBaseline.FIRST_WEEK else SavedTimeBaseline.SURVEY
        return SavedTime((baseline - todayWatchMs).coerceAtLeast(0L), baseline, source)
    }
}

/**
 * 계산한 첫 7일 평균을 고정해 둔다. 기록은 보관 기간이 지나면 지워지므로 매번 다시 계산할 수 없다.
 * 사용자가 기록을 전체 삭제하면 함께 지운다.
 */
object SavedTimeBaselinePreferences {

    private const val FILE = "saved_time_baseline"
    private const val KEY_FIRST_WEEK_AVERAGE_MS = "first_week_average_ms"

    fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun clear(context: Context) = prefs(context).edit { clear() }

    /** 고정된 값이 있으면 돌려주고, 없으면 [compute] 결과를 고정한다. 아직 계산할 수 없으면 null. */
    internal fun resolve(p: SharedPreferences, compute: () -> Long?): Long? {
        if (p.contains(KEY_FIRST_WEEK_AVERAGE_MS)) return p.getLong(KEY_FIRST_WEEK_AVERAGE_MS, 0L)
        return compute()?.also { p.edit { putLong(KEY_FIRST_WEEK_AVERAGE_MS, it) } }
    }

    fun resolve(context: Context, compute: () -> Long?): Long? = resolve(prefs(context), compute)
}
