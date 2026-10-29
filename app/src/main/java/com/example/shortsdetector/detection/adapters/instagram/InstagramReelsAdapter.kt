package com.example.shortsdetector.detection.adapters.instagram

import com.example.shortsdetector.detection.adapters.RecentActivity
import com.example.shortsdetector.detection.adapters.ShortformAdapter
import com.example.shortsdetector.detection.model.AdapterVerdict
import com.example.shortsdetector.detection.model.Confidence
import com.example.shortsdetector.detection.model.ReasonCode
import com.example.shortsdetector.detection.model.ScreenSnapshot
import com.example.shortsdetector.detection.model.Signal
import com.example.shortsdetector.detection.model.UiNode
import com.example.shortsdetector.detection.model.VerdictKind
import com.example.shortsdetector.detection.state.DetectionTuning

/**
 * Instagram Reels 어댑터 — **실험적, 실기기 미검증.**
 *
 * 이 저장소에는 Instagram UI 덤프가 없어서 Instagram 고유 view ID 를 하나도 확인하지 못했다.
 * 그래서 추측한 ID 를 규칙으로 쓰지 않고, 앱과 무관한 구조/이벤트 패턴만 쓴다.
 *  - 화면 대부분을 차지하는 pager 형 컨테이너(RecyclerView/ViewPager 계열 className)
 *  - 최근 세로 "한 페이지 넘김" 스크롤 이벤트 횟수 ([com.example.shortsdetector.detection.features.ScrollSignalTracker])
 *
 * 두 신호가 모두 있어야 MEDIUM(진입 가능). pager 만 있으면 LOW 라서 새 진입은 하지 않고, 이미 추정 중인
 * 세션을 유지하는 데만 쓰인다(한 릴스를 스와이프 없이 오래 볼 때).
 *
 * TODO: VERIFY ON DEVICE — Reels 피드/일반 피드/DM/스토리에서 className, bounds, 스크롤 이벤트의
 * fromIndex/toIndex 가 실제로 이 가설대로 나오는지 확인한 뒤 관찰된 ID 를 보조 신호로 추가한다.
 * 그 전까지는 설정에서 기본 꺼짐이다.
 */
class InstagramReelsAdapter(private val tuning: DetectionTuning = DetectionTuning()) : ShortformAdapter {

    override val packageName: String = PACKAGE
    override val ruleVersion: String = RULE_VERSION

    override fun evaluate(snapshot: ScreenSnapshot, recent: RecentActivity): AdapterVerdict {
        if (snapshot.nodes.isEmpty()) return AdapterVerdict.unknown(ReasonCode.TREE_EMPTY)

        val pager = snapshot.nodes.any {
            it.visibleToUser && isPagerLike(it) &&
                snapshot.areaRatio(it) >= tuning.instagramPagerAreaRatio
        }
        val flips = recent.pageFlips
        val signals = buildSet {
            if (pager) add(Signal.FULLSCREEN_PAGER)
            if (flips > 0) add(Signal.VERTICAL_PAGING)
        }

        return when {
            pager && flips >= tuning.instagramMinPageFlips ->
                AdapterVerdict(VerdictKind.SHORTFORM, Confidence.MEDIUM, ReasonCode.PAGER_WITH_PAGING, signals)
            pager ->
                AdapterVerdict(VerdictKind.SHORTFORM, Confidence.LOW, ReasonCode.PAGER_ONLY, signals)
            snapshot.truncated ->
                AdapterVerdict(VerdictKind.UNKNOWN, Confidence.LOW, ReasonCode.TREE_TRUNCATED, signals)
            else ->
                AdapterVerdict(VerdictKind.NOT_SHORTFORM, Confidence.LOW, ReasonCode.NO_SHORTFORM_SIGNAL, signals)
        }
    }

    private fun isPagerLike(node: UiNode): Boolean {
        val cls = node.className ?: return false
        return PAGER_CLASS_SUFFIXES.any { cls.endsWith(it) }
    }

    companion object {
        const val PACKAGE = "com.instagram.android"
        const val RULE_VERSION = "ig-0-experimental"

        private val PAGER_CLASS_SUFFIXES = listOf("RecyclerView", "ViewPager", "ViewPager2")
    }
}
