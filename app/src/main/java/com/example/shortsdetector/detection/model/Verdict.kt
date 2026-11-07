package com.example.shortsdetector.detection.model

/** 어댑터가 한 화면을 보고 내린 해석. "숏폼 아님"과 "모름"을 구분한다. */
enum class VerdictKind {
    /** 숏폼 재생 화면으로 보인다(신뢰도는 [Confidence] 로 따로 표시). */
    SHORTFORM,

    /** 숏폼 플레이어는 보이지만 댓글/설명 패널 등에 일부 가려졌다. 새 진입 근거로는 쓰지 않는다. */
    SHORTFORM_PARTIAL,

    /**
     * 대상 앱의 작은 창(대화상자·팝업)이 활성 창이라 그 아래 화면을 볼 수 없다.
     * 세션 중이면 일시 가림, 세션이 없으면 진입 근거가 아니다. 앱 UI 와 무관한 공통 규칙이 내린다.
     */
    OVERLAY,

    /** 대상 앱 화면이지만 숏폼 신호가 없다(홈, 검색, 일반 영상 등). */
    NOT_SHORTFORM,

    /** 트리가 비었거나 잘렸거나 어댑터 오류 등으로 판단할 수 없다. */
    UNKNOWN
}

enum class Confidence { LOW, MEDIUM, HIGH }

/** 판정 근거 코드. 로그·향후 저장에 원문 대신 이 값만 남긴다. */
enum class ReasonCode {
    PLAYER_FULLSCREEN,
    PLAYER_PARTIAL,
    PLAYER_MINIATURE,
    /** 활성 창 자체가 작다(PiP). 플레이어가 보여도 시청으로 세지 않는다 */
    MINIATURE_WINDOW,
    /** 플레이어는 아주 작지만 숏폼 컨테이너가 화면을 덮고 있다(댓글창을 끝까지 펼침) */
    PLAYER_COLLAPSED_UNDER_PANEL,
    OVERLAY_WINDOW,
    REEL_CONTAINERS_WITH_PAGING,
    REEL_CONTAINERS_ONLY,
    REGULAR_PLAYER,
    PAGER_WITH_PAGING,
    PAGER_ONLY,
    NO_SHORTFORM_SIGNAL,
    TREE_EMPTY,
    TREE_TRUNCATED,
    ADAPTER_ERROR
}

/** 어댑터가 본 개별 구조 신호. 조합 결과를 설명 가능하게 남기기 위한 플래그. */
enum class Signal {
    SHORTS_PLAYER_VISIBLE,
    SHORTS_PLAYER_FULLSCREEN,
    REEL_CONTAINER_VISIBLE,
    REGULAR_PLAYER_VISIBLE,
    FULLSCREEN_PAGER,
    VERTICAL_PAGING
}

data class AdapterVerdict(
    val kind: VerdictKind,
    val confidence: Confidence,
    val reason: ReasonCode,
    val signals: Set<Signal> = emptySet(),
    /** 숏폼 플레이어 노드의 최대 면적비. 해당 없으면 0. */
    val playerAreaRatio: Float = 0f
) {
    companion object {
        fun unknown(reason: ReasonCode) = AdapterVerdict(VerdictKind.UNKNOWN, Confidence.LOW, reason)
    }
}
