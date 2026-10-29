package com.example.shortsdetector.intervention

/**
 * 재알림 쿨다운. 기존 프로토타입의 MIN_RENOTIFY_INTERVAL_MS 판정을 그대로 옮겼다.
 *
 * 상태가 어떤 이유로 흔들려도 알림이 연달아 뜨는 것만은 막는 최후 방어선.
 * 실제로 알림을 띄웠을 때만 [markNotified] 를 부른다(권한이 없어 실패하면 쿨다운을 걸지 않는다).
 */
class NotificationGate(private val minIntervalMs: Long) {

    private var lastNotifiedAt: Long? = null

    fun canNotify(nowMs: Long): Boolean {
        val last = lastNotifiedAt ?: return true
        return nowMs - last >= minIntervalMs
    }

    fun markNotified(nowMs: Long) {
        lastNotifiedAt = nowMs
    }

    /** 쿨다운이 남아 있으면 그 시간(ms), 없으면 null. 로그용. */
    fun sinceLast(nowMs: Long): Long? = lastNotifiedAt?.let { nowMs - it }
}
