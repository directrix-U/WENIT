package com.example.shortsdetector

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.example.shortsdetector.detection.adapters.instagram.InstagramReelsAdapter
import com.example.shortsdetector.detection.adapters.youtube.YouTubeViewIds

/**
 * 개입을 시스템 알림으로 띄우는 책임만 갖는다.
 * 기본 개입은 화면 중앙 팝업([com.example.shortsdetector.intervention.InterventionPopup])이고,
 * 팝업을 끄거나 띄우지 못했을 때 이 알림을 쓴다.
 */
object ShortsNotifier {

    private const val CHANNEL_ID = "shorts_detection"
    private const val NOTIFICATION_ID = 1001

    /** 앱/서비스 시작 시 한 번만 불러 주면 된다. 같은 ID 로 다시 만들어도 안전하다. */
    fun ensureChannel(context: Context) {
        val channel = NotificationChannel(
            CHANNEL_ID,
            context.getString(R.string.channel_name),
            NotificationManager.IMPORTANCE_DEFAULT
        ).apply {
            description = context.getString(R.string.channel_description)
            setShowBadge(true)
        }
        context.getSystemService(NotificationManager::class.java)
            ?.createNotificationChannel(channel)
    }

    /**
     * @param sessionNumber 서비스가 켜진 뒤 몇 번째 감지 세션인지 (본문에 찍어 두면 재알림 확인이 쉽다)
     * @param packageName 감지된 앱 패키지. 앱 이름 표시에만 쓴다.
     * @param watchMs 이 세션의 확정 시청시간
     * @return 실제로 알림을 띄웠으면 true. 권한이 없으면 false.
     */
    fun notifyWatchThreshold(
        context: Context,
        sessionNumber: Int,
        packageName: String?,
        watchMs: Long,
        /** 알림을 눌렀을 때 반응을 기록할 개입 기록 id(없으면 기록 안 함) */
        interventionId: String? = null
    ): Boolean {
        if (!hasPostPermission(context)) {
            Log.w(
                ShortsDetectionService.TAG,
                "감지했지만 POST_NOTIFICATIONS 권한이 없어 알림을 띄우지 못함"
            )
            return false
        }

        val contentIntent = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                .putExtra(MainActivity.EXTRA_FROM_INTERVENTION, true)
                .putExtra(MainActivity.EXTRA_INTERVENTION_ID, interventionId),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val body = context.getString(
            R.string.notification_body,
            appLabel(context, packageName),
            formatWatch(context, watchMs),
            sessionNumber
        )

        val notification: Notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_shorts_detected)
            .setContentTitle(context.getString(R.string.popup_title, formatWatch(context, watchMs)))
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setContentIntent(contentIntent)
            .setAutoCancel(true)
            // 조건이 깨졌다가 다시 충족되면 매번 새로 알려야 하므로 false 로 둔다.
            .setOnlyAlertOnce(false)
            .build()

        context.getSystemService(NotificationManager::class.java)
            ?.notify(NOTIFICATION_ID, notification)
        return true
    }

    fun appLabel(context: Context, packageName: String?): String = when (packageName) {
        YouTubeViewIds.PACKAGE -> context.getString(R.string.app_label_youtube)
        InstagramReelsAdapter.PACKAGE -> context.getString(R.string.app_label_instagram)
        else -> packageName.orEmpty()
    }

    /** 1분 미만은 초, 이상은 분(내림)으로. 예: 45초, 3분 */
    fun formatWatch(context: Context, ms: Long): String {
        val sec = (ms / 1000L).coerceAtLeast(0L)
        return if (sec < 60) context.getString(R.string.duration_seconds, sec.toInt())
        else context.getString(R.string.duration_minutes, (sec / 60).toInt())
    }

    /** 쇼츠를 벗어나면 부른다. 떠 있는 감지 알림을 지운다(없으면 아무 일도 없음). */
    fun cancel(context: Context) {
        context.getSystemService(NotificationManager::class.java)?.cancel(NOTIFICATION_ID)
    }

    /** Android 13+ 에서만 런타임 권한이 필요하다. */
    fun hasPostPermission(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return true
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED
    }
}
