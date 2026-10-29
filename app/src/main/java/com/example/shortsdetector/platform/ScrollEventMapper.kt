package com.example.shortsdetector.platform

import android.os.Build
import android.view.accessibility.AccessibilityEvent
import com.example.shortsdetector.detection.model.ScrollSignal

/**
 * TYPE_VIEW_SCROLLED 이벤트에서 구조 값만 꺼낸다. 이벤트 text 와 source 노드는 읽지 않는다
 * (getSource() 는 IPC 비용이 있고, 필요한 값은 이벤트 필드에 이미 있다).
 */
object ScrollEventMapper {

    fun toSignal(event: AccessibilityEvent, atMs: Long, screenHeight: Int): ScrollSignal? {
        if (event.eventType != AccessibilityEvent.TYPE_VIEW_SCROLLED) return null
        val pkg = event.packageName?.toString() ?: return null
        val hasDelta = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P
        return ScrollSignal(
            atMs = atMs,
            packageName = pkg,
            className = event.className?.toString(),
            fromIndex = event.fromIndex,
            toIndex = event.toIndex,
            itemCount = event.itemCount,
            deltaX = if (hasDelta) event.scrollDeltaX else null,
            deltaY = if (hasDelta) event.scrollDeltaY else null,
            screenHeight = screenHeight
        )
    }
}
