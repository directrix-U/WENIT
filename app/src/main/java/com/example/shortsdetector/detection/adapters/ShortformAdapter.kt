package com.example.shortsdetector.detection.adapters

import com.example.shortsdetector.detection.model.AdapterVerdict
import com.example.shortsdetector.detection.model.ScreenSnapshot

/** 어댑터에 함께 넘기는 최근 이벤트 요약. 트리만으로는 알 수 없는 시간 패턴. */
data class RecentActivity(
    /** 최근 pagingWindowMs 안의 세로 페이지 넘김 수 */
    val pageFlips: Int = 0
)

/**
 * 앱별 숏폼 판정 규칙. 공통 상태 머신은 이 인터페이스만 알고, 앱 UI 의 세부 사항은 모른다.
 *
 * 구현은 Android 프레임워크에 의존하지 않는 순수 Kotlin 이어야 한다(JVM 단위 테스트 대상).
 */
interface ShortformAdapter {
    val packageName: String

    /** 규칙 버전. 규칙을 바꿀 때마다 올리고 로그에 남긴다. */
    val ruleVersion: String

    /**
     * 트리 순회와 별도로 `findAccessibilityNodeInfosByViewId` 로 직접 찾아야 하는 ID.
     *
     * 트리 순회(getChild)는 "접근성상 중요하지 않은" 뷰를 건너뛰지만 ID 검색은 찾아 준다.
     * 실제로 에뮬레이터(YouTube 21.18.164)에서 reel_watch_player 가 순회에는 안 나오고
     * ID 검색(기존 프로토타입 방식)에는 나왔다. 핵심 신호는 여기에 넣어 기존 조회 방식을 유지한다.
     */
    val lookupViewIds: Set<String> get() = emptySet()

    /**
     * 전체 트리 순회가 필요한지. false 면 root 와 [lookupViewIds] 검색 결과만 스냅샷에 담는다.
     *
     * 순회는 prefetch 범위를 넘는 노드마다 IPC 가 생겨 비싸다(에뮬레이터 실측 중앙값 62ms, 최대 724ms).
     * 필요한 노드를 ID 로 다 지정할 수 있는 어댑터는 false 로 둔다.
     */
    val needsFullTree: Boolean get() = true

    fun evaluate(snapshot: ScreenSnapshot, recent: RecentActivity): AdapterVerdict
}
