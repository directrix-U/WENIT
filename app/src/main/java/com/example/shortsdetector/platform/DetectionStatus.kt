package com.example.shortsdetector.platform

import com.example.shortsdetector.detection.model.DetectionState
import com.example.shortsdetector.detection.model.ReasonCode

/**
 * 서비스 → 화면으로 현재 감지 상태를 넘기는 프로세스 내 공유 값. 저장하지 않는다.
 *
 * 화면은 [connected] 가 false 면 "작동 중"으로 표시하지 않는다(명세 3.1-7).
 */
object DetectionStatus {

    data class Snapshot(
        val connected: Boolean,
        val state: DetectionState,
        val packageName: String?,
        val reason: ReasonCode?
    )

    @Volatile
    var current: Snapshot = Snapshot(false, DetectionState.OUTSIDE_TARGET, null, null)
        private set

    fun onConnected() {
        current = Snapshot(true, DetectionState.OUTSIDE_TARGET, null, null)
    }

    fun update(state: DetectionState, packageName: String?, reason: ReasonCode?) {
        current = Snapshot(true, state, packageName, reason)
    }

    fun onDisconnected() {
        current = Snapshot(false, DetectionState.OUTSIDE_TARGET, null, null)
    }
}
