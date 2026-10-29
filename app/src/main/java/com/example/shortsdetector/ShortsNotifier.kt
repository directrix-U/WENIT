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
import com.example.shortsdetector.detection.model.Confidence

/** 감지 결과를 로컬 알림으로 띄우는 책임만 갖는다. */
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
     * @param confidence 진입 시 신뢰도. 디버그 확인용으로 본문에 표시한다.
     * @return 실제로 알림을 띄웠으면 true. 권한이 없으면 false.
     */
    fun notifyShortformDetected(
        context: Context,
        sessionNumber: Int,
        packageName: String?,
        confidence: Confidence?
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
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val appLabel = when (packageName) {
            YouTubeViewIds.PACKAGE -> context.getString(R.string.app_label_youtube)
            InstagramReelsAdapter.PACKAGE -> context.getString(R.string.app_label_instagram)
            else -> packageName.orEmpty()
        }
        val body = context.getString(
            R.string.notification_body,
            sessionNumber,
            appLabel,
            confidence?.name ?: "-"
        )

        val notification: Notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_shorts_detected)
            .setContentTitle(context.getString(R.string.notification_title))
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
