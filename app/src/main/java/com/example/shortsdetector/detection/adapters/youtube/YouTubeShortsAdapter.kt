package com.example.shortsdetector.detection.adapters.youtube

import com.example.shortsdetector.detection.adapters.RecentActivity
import com.example.shortsdetector.detection.adapters.ShortformAdapter
import com.example.shortsdetector.detection.model.AdapterVerdict
import com.example.shortsdetector.detection.model.Confidence
import com.example.shortsdetector.detection.model.ReasonCode
import com.example.shortsdetector.detection.model.ScreenSnapshot
import com.example.shortsdetector.detection.model.Signal
import com.example.shortsdetector.detection.model.VerdictKind
import com.example.shortsdetector.detection.state.DetectionTuning

/**
 * YouTube Shorts 어댑터.
 *
 * 1차 신호는 기존 프로토타입 규칙([LegacyShortsPlayerRule])을 그대로 쓴다. 기존에 실기기에서 통과한
 * 동작(전체화면 플레이어 → 진입, 댓글창으로 줄어든 플레이어 → 유지)을 회귀시키지 않기 위해서다.
 *
 * 여기에 서로 독립적인 보조 신호를 더해 신뢰도를 매긴다.
 *  - 보조 컨테이너([YouTubeViewIds.REEL_CONTAINERS])가 전체화면으로 보임
 *  - 최근 세로 페이지 넘김 스크롤 이벤트(트리와 무관한 시간 패턴)
 *
 * 플레이어 ID 가 업데이트로 사라져도 보조 신호가 남으면 완전히 무너지지 않게 했다. 다만 플레이어 없이
 * 컨테이너만 보이는 경우는 LOW 로 두어 새 진입을 하지 않고, 스크롤 패턴까지 겹칠 때만 MEDIUM 이다.
 * 텍스트·contentDescription·아이콘은 쓰지 않는다(Clear Screen 모드에서 사라질 수 있는 요소).
 */
class YouTubeShortsAdapter(private val tuning: DetectionTuning = DetectionTuning()) : ShortformAdapter {

    override val packageName: String = YouTubeViewIds.PACKAGE
    override val ruleVersion: String = RULE_VERSION
    /** 판정에 쓰는 노드는 모두 ID 로 정해져 있으므로 전체 순회 없이 ID 검색만 한다(기존 프로토타입 방식). */
    override val lookupViewIds: Set<String> =
        setOf(YouTubeViewIds.SHORTS_PLAYER) + YouTubeViewIds.REEL_CONTAINERS + YouTubeViewIds.REGULAR_PLAYERS
    override val needsFullTree: Boolean = false

    override fun evaluate(snapshot: ScreenSnapshot, recent: RecentActivity): AdapterVerdict {
        if (snapshot.nodes.isEmpty()) return AdapterVerdict.unknown(ReasonCode.TREE_EMPTY)

        val legacy = LegacyShortsPlayerRule.evaluate(snapshot, tuning.minPlayerAreaRatio)

        val containerIds = snapshot.nodes.asSequence()
            .filter { it.visibleToUser && it.viewId in YouTubeViewIds.REEL_CONTAINERS }
            .filter { snapshot.areaRatio(it) >= tuning.minContainerAreaRatio }
            .mapNotNull { it.viewId }
            .toSet()
        val paging = recent.pageFlips > 0
        val regularPlayer = snapshot.nodes.any {
            it.visibleToUser && it.area > 0 && it.viewId in YouTubeViewIds.REGULAR_PLAYERS
        }

        val signals = buildSet {
            if (legacy.playerVisible) add(Signal.SHORTS_PLAYER_VISIBLE)
            if (legacy.isShortsPlaying) add(Signal.SHORTS_PLAYER_FULLSCREEN)
            if (containerIds.isNotEmpty()) add(Signal.REEL_CONTAINER_VISIBLE)
            if (paging) add(Signal.VERTICAL_PAGING)
            if (regularPlayer) add(Signal.REGULAR_PLAYER_VISIBLE)
        }

        fun verdict(kind: VerdictKind, confidence: Confidence, reason: ReasonCode) =
            AdapterVerdict(kind, confidence, reason, signals, legacy.maxAreaRatio)

        return when {
            legacy.isShortsPlaying -> verdict(
                VerdictKind.SHORTFORM,
                if (containerIds.isNotEmpty() || paging) Confidence.HIGH else Confidence.MEDIUM,
                ReasonCode.PLAYER_FULLSCREEN
            )
            // 기존 규칙은 보이기만 하면 유지했지만, PiP/미니플레이어처럼 아주 작은 플레이어는 유지하지 않는다.
            legacy.playerVisible && legacy.maxAreaRatio < tuning.minHoldAreaRatio ->
                verdict(VerdictKind.NOT_SHORTFORM, Confidence.LOW, ReasonCode.PLAYER_MINIATURE)
            legacy.playerVisible ->
                verdict(VerdictKind.SHORTFORM_PARTIAL, Confidence.MEDIUM, ReasonCode.PLAYER_PARTIAL)
            containerIds.size >= MIN_CONTAINERS_WITHOUT_PLAYER && paging ->
                verdict(VerdictKind.SHORTFORM, Confidence.MEDIUM, ReasonCode.REEL_CONTAINERS_WITH_PAGING)
            containerIds.size >= MIN_CONTAINERS_WITHOUT_PLAYER ->
                verdict(VerdictKind.SHORTFORM, Confidence.LOW, ReasonCode.REEL_CONTAINERS_ONLY)
            snapshot.truncated ->
                verdict(VerdictKind.UNKNOWN, Confidence.LOW, ReasonCode.TREE_TRUNCATED)
            regularPlayer ->
                verdict(VerdictKind.NOT_SHORTFORM, Confidence.MEDIUM, ReasonCode.REGULAR_PLAYER)
            else ->
                verdict(VerdictKind.NOT_SHORTFORM, Confidence.LOW, ReasonCode.NO_SHORTFORM_SIGNAL)
        }
    }

    companion object {
        const val RULE_VERSION = "yt-2"

        /** 플레이어 없이 판단할 때 필요한 서로 다른 보조 컨테이너 수 */
        const val MIN_CONTAINERS_WITHOUT_PLAYER = 2
    }
}
