package com.example.shortsdetector.detection.adapters.youtube

import com.example.shortsdetector.detection.model.ScreenSnapshot
import java.util.Locale

/**
 * 한 번의 판정 결과.
 *
 * 노드 참조를 들고 있지 않고 숫자만 복사해 둔다.
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
        YouTubeViewIds.SHORTS_PLAYER.substringAfterLast('/'),
        found, matchedCount, playerVisible, maxAreaRatio,
        if (isShortsPlaying) "쇼츠" else "아님"
    )
}

/**
 * 기존 프로토타입(baseline-v0 의 ShortsPlayerDetector)의 판정 규칙을 **그대로** 옮긴 것.
 *
 * 다음을 모두 만족하는 노드가 하나라도 있으면 "쇼츠 재생 중":
 *  1) viewIdResourceName 이 [YouTubeViewIds.SHORTS_PLAYER] 와 **전체 문자열 정확히 일치**
 *  2) isVisibleToUser == true
 *  3) 화면 면적 대비 bounds 면적 >= minPlayerAreaRatio
 *
 * 위 조건은 **신규 진입** 판정용이다. 이미 감지된 상태에서는 [DetectionResult.playerVisible]
 * 만으로 유지를 판단한다. 쇼츠 댓글창을 열면 플레이어가 화면 위쪽으로 줄어들어
 * (실측 면적비 0.863 → 0.292) 면적 조건이 깨지기 때문이다.
 *
 * 이전의 "전체화면 노드 + 자손 수/depth" 휴리스틱은 폐기했다. 화면 전체 크기 컨테이너는
 * 어느 화면에나 있고, 쇼츠 화면 트리도 깊고 자식이 많아 두 조건 모두 판별력이 없었다.
 *
 * 노드 탐색(findAccessibilityNodeInfosByViewId)과 recycle 은 이제 플랫폼 계층의
 * AccessibilitySnapshotter 가 맡고, 이 규칙은 [ScreenSnapshot] 만 본다.
 * [YouTubeShortsAdapter] 는 이 결과를 1차 신호로 쓰고, 회귀 테스트는 두 결과가 같은지 비교한다.
 */
object LegacyShortsPlayerRule {

    fun evaluate(snapshot: ScreenSnapshot, minPlayerAreaRatio: Float): DetectionResult {
        var matchedCount = 0
        var playerVisible = false
        var maxAreaRatio = 0f

        // 부분 일치 오탐(예: reel_time_bar 는 일반 영상에도 있음)을 막으려고 정확히 비교한다.
        for (node in snapshot.nodesWithId(YouTubeViewIds.SHORTS_PLAYER)) {
            matchedCount++

            if (!node.visibleToUser || snapshot.screenArea <= 0L) continue
            if (node.area <= 0L) continue
            playerVisible = true

            val ratio = snapshot.areaRatio(node)
            if (ratio > maxAreaRatio) maxAreaRatio = ratio
        }

        return DetectionResult(
            matchedCount = matchedCount,
            playerVisible = playerVisible,
            maxAreaRatio = maxAreaRatio,
            isShortsPlaying = maxAreaRatio >= minPlayerAreaRatio
        )
    }
}
