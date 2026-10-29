package com.example.shortsdetector.detection.model

/** 관찰 시점에 활성 창이 어떤 상태였는지. */
enum class WindowStatus {
    /** 감지 대상 패키지의 창이고 어댑터 판정이 있다. */
    TARGET,

    /** 활성 창이 다른 앱/시스템 UI 다(시스템 대화상자, 다른 앱 등). */
    FOREIGN,

    /** rootInActiveWindow 를 얻지 못했다(전환 중, 알림창, 홈 화면 등 — 원인은 구분 불가). */
    UNAVAILABLE
}

/**
 * 상태 머신에 들어가는 입력 1건.
 *
 * @property atMs 단조 증가 시계(SystemClock.elapsedRealtime) 기준 시각.
 */
data class Observation(
    val atMs: Long,
    val window: WindowStatus,
    val packageName: String? = null,
    val verdict: AdapterVerdict? = null,
    val ruleVersion: String? = null
) {
    companion object {
        fun unavailable(atMs: Long) = Observation(atMs, WindowStatus.UNAVAILABLE)
        fun foreign(atMs: Long, packageName: String?) =
            Observation(atMs, WindowStatus.FOREIGN, packageName)
    }
}

/**
 * 스크롤 이벤트에서 뽑은 구조 값. 이벤트의 text 는 담지 않는다.
 *
 * 인덱스/델타를 제공하지 않는 경우 -1 / null 이다(API 28 미만은 델타 없음).
 */
data class ScrollSignal(
    val atMs: Long,
    val packageName: String,
    val className: String?,
    val fromIndex: Int = -1,
    val toIndex: Int = -1,
    val itemCount: Int = -1,
    val deltaX: Int? = null,
    val deltaY: Int? = null,
    /** 이벤트 시점의 화면 높이(px). 인덱스가 없을 때 이동량을 화면 비율로 보기 위해 쓴다. 모르면 0 */
    val screenHeight: Int = 0
)
