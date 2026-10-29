package com.example.shortsdetector

import android.graphics.Rect
import android.os.Build
import android.view.accessibility.AccessibilityNodeInfo
import java.util.Locale

/**
 * 한 번의 판정 결과.
 *
 * 노드 참조를 들고 있지 않고 숫자만 복사해 둔다.
 * 그래야 판정이 끝나는 즉시 [AccessibilityNodeInfo] 를 안전하게 recycle 할 수 있다.
 */
data class DetectionResult(
    /** ID 가 정확히 일치하는 노드 수 (가시성/면적 무관) */
    val matchedCount: Int,
    /** 일치 노드 중 화면에 보이는(isVisibleToUser, 면적 > 0) 노드가 있는지. 감지 유지 판정에 쓴다. */
    val playerVisible: Boolean,
    /** 일치 노드 중 화면에 보이는 노드들의 최대 면적비 (0f..1f). 보이는 노드가 없으면 0 */
    val maxAreaRatio: Float,
    /** 신규 진입 판정: 플레이어가 보이고 면적비가 기준 이상인지 */
    val isShortsPlaying: Boolean
) {
    val found: Boolean get() = matchedCount > 0

    override fun toString(): String = String.format(
        Locale.US,
        "%s 발견=%b(%d개) 보임=%b 면적비=%.3f 판정=%s",
        DetectionConfig.SHORTS_PLAYER_VIEW_ID.substringAfterLast('/'),
        found, matchedCount, playerVisible, maxAreaRatio,
        if (isShortsPlaying) "쇼츠" else "아님"
    )
}

/**
 * 쇼츠 플레이어 뷰([DetectionConfig.SHORTS_PLAYER_VIEW_ID])의 존재로 쇼츠 재생 화면을 판정한다.
 *
 * 다음을 모두 만족하는 노드가 하나라도 있으면 "쇼츠 재생 중":
 *  1) viewIdResourceName 이 SHORTS_PLAYER_VIEW_ID 와 **전체 문자열 정확히 일치**
 *  2) isVisibleToUser == true
 *  3) 화면 면적 대비 bounds 면적 >= [DetectionConfig.MIN_PLAYER_AREA_RATIO]
 *
 * 위 조건은 **신규 진입** 판정용이다. 이미 감지된 상태에서는 서비스가 [DetectionResult.playerVisible]
 * 만으로 유지를 판단한다. 쇼츠 댓글창을 열면 플레이어가 화면 위쪽으로 줄어들어
 * (실측 면적비 0.863 → 0.292) 면적 조건이 깨지기 때문이다.
 *
 * 이전의 "전체화면 노드 + 자손 수/depth" 휴리스틱은 폐기했다. 화면 전체 크기 컨테이너는
 * 어느 화면에나 있고, 쇼츠 화면 트리도 깊고 자식이 많아 두 조건 모두 판별력이 없었다.
 */
object ShortsPlayerDetector {

    fun detect(
        root: AccessibilityNodeInfo,
        screenWidth: Int,
        screenHeight: Int
    ): DetectionResult {
        val screenArea = screenWidth.toLong() * screenHeight.toLong()
        val nodes = root.findAccessibilityNodeInfosByViewId(DetectionConfig.SHORTS_PLAYER_VIEW_ID)
            .orEmpty()

        var matchedCount = 0
        var playerVisible = false
        var maxAreaRatio = 0f
        val bounds = Rect()

        for (node in nodes) {
            try {
                // findAccessibilityNodeInfosByViewId 는 ID 로 찾지만, 부분 일치 오탐
                // (예: reel_time_bar 는 일반 영상에도 있음)을 원천 차단하려고 다시 정확히 비교한다.
                if (node.viewIdResourceName != DetectionConfig.SHORTS_PLAYER_VIEW_ID) continue
                matchedCount++

                if (!node.isVisibleToUser || screenArea <= 0L) continue

                node.getBoundsInScreen(bounds)
                val area = bounds.width().toLong() * bounds.height().toLong()
                if (area <= 0L) continue
                playerVisible = true

                val ratio = (area.toDouble() / screenArea.toDouble()).toFloat()
                if (ratio > maxAreaRatio) maxAreaRatio = ratio
            } finally {
                // recycle() 은 API 33 에서 deprecated 되었고 그 이후로는 no-op 이지만,
                // minSdk 26 을 지원하므로 구버전에서는 명시적으로 해제해 준다.
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                    @Suppress("DEPRECATION")
                    node.recycle()
                }
            }
        }

        return DetectionResult(
            matchedCount = matchedCount,
            playerVisible = playerVisible,
            maxAreaRatio = maxAreaRatio,
            isShortsPlaying = maxAreaRatio >= DetectionConfig.MIN_PLAYER_AREA_RATIO
        )
    }
}
