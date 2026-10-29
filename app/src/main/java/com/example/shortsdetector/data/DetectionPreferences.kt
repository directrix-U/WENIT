package com.example.shortsdetector.data

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import com.example.shortsdetector.detection.adapters.instagram.InstagramReelsAdapter
import com.example.shortsdetector.detection.adapters.youtube.YouTubeViewIds

/**
 * 사용자 감지 설정. 지금은 대상 앱 on/off 만 있다.
 *
 * YouTube 는 기존 동작과 같게 기본 켜짐, Instagram 은 규칙이 실기기 미검증이라 기본 꺼짐.
 */
object DetectionPreferences {

    private const val FILE = "detection_settings"
    const val KEY_YOUTUBE_ENABLED = "youtube_enabled"
    const val KEY_INSTAGRAM_ENABLED = "instagram_enabled"

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

    fun enabledPackages(context: Context): Set<String> = buildSet {
        if (isYouTubeEnabled(context)) add(YouTubeViewIds.PACKAGE)
        if (isInstagramEnabled(context)) add(InstagramReelsAdapter.PACKAGE)
    }
}
