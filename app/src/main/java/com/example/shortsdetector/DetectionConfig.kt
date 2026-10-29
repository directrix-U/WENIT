package com.example.shortsdetector

/**
 * 쇼츠 감지 기준값 모음.
 *
 * 판정은 쇼츠 플레이어 뷰 ID([SHORTS_PLAYER_VIEW_ID])로 한다. 실제 유튜브 앱 UI 덤프를
 * 비교한 결과, 이전의 "화면 80% 이상 노드 + 자손 수/depth" 휴리스틱은 일반 영상 재생 화면에서도
 * 충족돼 오탐이 났다. 화면 전체 크기의 컨테이너는 어느 화면에나 있고, 쇼츠 화면 트리도
 * 깊고 자식이 많아서 두 조건 모두 판별력이 없었다.
 *
 * 유튜브 업데이트로 ID 가 바뀌면 아래 상수만 교체하면 된다. 확인 방법:
 *
 *   adb logcat -s ShortsDetector
 *   쇼츠 재생 화면에서 "발견=false" 가 찍히면 ID 가 바뀐 것이므로,
 *   uiautomator dump 등으로 쇼츠 플레이어의 resource-id 를 다시 확인한다.
 */
object DetectionConfig {

    /** 감시 대상 패키지. accessibility_service_config.xml 의 packageNames 와 동일해야 한다. */
    const val TARGET_PACKAGE = "com.google.android.youtube"

    /**
     * 쇼츠 플레이어 뷰의 전체 리소스 ID. **전체 문자열 정확히 일치로만 비교한다.**
     * (부분 일치 금지 — 예: "reel_time_bar" 는 일반 영상 화면에도 존재한다.)
     *
     * accessibility_service_config.xml 에 flagReportViewIds 가 없으면 뷰 ID 가 null 로 와서
     * 검색이 동작하지 않는다.
     */
    const val SHORTS_PLAYER_VIEW_ID = "com.google.android.youtube:id/reel_watch_player"

    /**
     * 쇼츠 플레이어 노드가 화면 면적의 이 비율 이상을 차지해야 "쇼츠 재생 중"으로 **새로 판정**한다.
     * 이미 감지된 뒤에는 적용하지 않는다(댓글창을 열면 0.29 까지 줄어듦). 노드가 보이기만 하면 유지.
     */
    const val MIN_PLAYER_AREA_RATIO = 0.70f

    /**
     * 조건이 이만큼 **연속으로** 충족돼야 알림을 띄운다.
     *
     * 화면 전환 순간의 1회성 오탐을 거른다.
     * MIN_ANALYSIS_INTERVAL_MS 와 곱해 대략 (3 x 250ms = 750ms) 동안
     * 조건이 유지돼야 "진짜 쇼츠 화면"으로 인정한다.
     * 이벤트가 끊겨도 감시 타이머(WATCHDOG_INTERVAL_MS)가 나머지를 채우므로 최대 약 2초.
     */
    const val CONFIRMATION_COUNT = 3

    /**
     * 판정 자체의 최소 간격(ms).
     * OS 레벨 디바운스(notificationTimeout)에 더해 앱 쪽에서도 한 번 더 막아 준다.
     */
    const val MIN_ANALYSIS_INTERVAL_MS = 250L

    /**
     * 감지 확인 중이거나 감지 상태인 동안, 이벤트 없이도 이 간격으로 화면을 직접 확인한다.
     *
     * 유튜브를 벗어나면 이벤트가 오지 않아 이탈(→ 알림 제거)이 몇 분씩 늦어졌고,
     * 쇼츠를 가만히 보면 이벤트가 끊겨 CONFIRMATION_COUNT 확정이 1분 넘게 걸렸다.
     * 한 번 확인에 수 ms 라서 1초 간격이어도 부담이 작고, 쇼츠가 아닐 때는 돌지 않는다.
     */
    const val WATCHDOG_INTERVAL_MS = 1_000L

    /**
     * 마지막 유효 분석 이후 이 시간이 지나면 "조건 충족 래치"를 강제로 해제한다.
     *
     * packageNames 로 OS 레벨 필터를 걸었기 때문에 유튜브를 완전히 벗어나면
     * 이벤트가 아예 오지 않는다. 그래서 "조건이 깨졌다"는 사실을 이벤트로 알 수 없고,
     * 이 staleness 타임아웃이 유일한 단서다.
     *
     * 다만 침묵은 "이탈했다"는 증거가 아니다. 쇼츠 한 편을 스크롤 없이 계속 보고 있어도
     * 이벤트가 끊긴다. 15초로 뒀더니 그 상황에서 래치가 풀렸다가 곧바로 재감지돼
     * 중복 알림이 떴다. 그래서 값을 넉넉히 늘리고, 아래 재알림 쿨다운을 함께 둔다.
     */
    const val STATE_STALE_MS = 60_000L

    /**
     * 직전 알림 이후 이 시간 안에는 다시 알리지 않는다.
     *
     * 래치가 어떤 이유로 흔들리더라도 알림이 연달아 뜨는 것만은 막는 최후 방어선.
     */
    const val MIN_RENOTIFY_INTERVAL_MS = 30_000L
}
