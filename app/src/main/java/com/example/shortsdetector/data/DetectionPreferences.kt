package com.example.shortsdetector.data

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import com.example.shortsdetector.DetectionConfig
import com.example.shortsdetector.detection.adapters.instagram.InstagramReelsAdapter
import com.example.shortsdetector.detection.adapters.youtube.YouTubeViewIds

/**
 * 사용자 감지 설정: 대상 앱 on/off, 개입 임계값, 화면 중앙 팝업 사용 여부.
 *
 * YouTube 는 기존 동작과 같게 기본 켜짐, Instagram 은 규칙이 실기기 미검증이라 기본 꺼짐.
 */
object DetectionPreferences {

    private const val FILE = "detection_settings"
    const val KEY_YOUTUBE_ENABLED = "youtube_enabled"
    const val KEY_INSTAGRAM_ENABLED = "instagram_enabled"
    const val KEY_WATCH_THRESHOLD_MS = "watch_threshold_ms"
    const val KEY_POPUP_ENABLED = "popup_enabled"

    fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun isYouTubeEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_YOUTUBE_ENABLED, true)

    fun isInstagramEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_INSTAGRAM_ENABLED, false)

    fun setYouTubeEnabled(context: Context, enabled: Boolean) =
        prefs(context).edit { putBoolean(KEY_YOUTUBE_ENABLED, enabled) }

    fun setInstagramEnabled(context: Context, enabled: Boolean) =
        prefs(context).edit { putBoolean(KEY_INSTAGRAM_ENABLED, enabled) }

    /** 저장값이 선택지에 없으면(구버전·손상) 기본값으로 돌린다. */
    fun watchThresholdMs(context: Context): Long {
        val v = prefs(context).getLong(KEY_WATCH_THRESHOLD_MS, DetectionConfig.DEFAULT_WATCH_THRESHOLD_MS)
        return if (v in DetectionConfig.WATCH_THRESHOLD_CHOICES_MS) v else DetectionConfig.DEFAULT_WATCH_THRESHOLD_MS
    }

    fun setWatchThresholdMs(context: Context, ms: Long) =
        prefs(context).edit { putLong(KEY_WATCH_THRESHOLD_MS, ms) }

    /** 끄면 팝업 없이 시스템 알림만 쓴다. */
    fun isPopupEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_POPUP_ENABLED, true)

    fun setPopupEnabled(context: Context, enabled: Boolean) =
        prefs(context).edit { putBoolean(KEY_POPUP_ENABLED, enabled) }

    fun enabledPackages(context: Context): Set<String> = buildSet {
        if (isYouTubeEnabled(context)) add(YouTubeViewIds.PACKAGE)
        if (isInstagramEnabled(context)) add(InstagramReelsAdapter.PACKAGE)
    }
}
