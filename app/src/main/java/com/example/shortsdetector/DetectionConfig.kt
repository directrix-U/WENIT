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

    /*
     * 감시 대상 패키지와 쇼츠 플레이어 ID 는 앱별 어댑터로 옮겼다.
     *  - YouTube: detection/adapters/youtube/YouTubeViewIds.kt  (reel_watch_player 등)
     *  - Instagram: detection/adapters/instagram/InstagramReelsAdapter.kt
     *
     * 뷰 ID 는 **전체 문자열 정확히 일치로만 비교한다.**
     * (부분 일치 금지 — 예: "reel_time_bar" 는 일반 영상 화면에도 존재한다.)
     * accessibility_service_config.xml 에 flagReportViewIds 가 없으면 뷰 ID 가 null 로 와서
     * 검색이 동작하지 않는다.
     */

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

    // --- 여기부터 리팩터링(yt-2)에서 추가한 값. 모두 TODO: VERIFY ON DEVICE ---

    /**
     * 이탈 신호(root 없음 / 다른 창 / 숏폼 신호 없음)가 이 시간 동안 이어져야 세션을 끝낸다.
     *
     * 기존에는 즉시 해제해서 홈 이동 후 약 1초 만에 알림이 지워졌다. 이제는 최대 약
     * (EXIT_GRACE_MS + WATCHDOG_INTERVAL_MS) 걸리는 대신, 알림창을 잠깐 내리거나 root 가 순간적으로
     * null 일 때 세션이 끊기지 않는다. TODO: VERIFY ON DEVICE
     */
    const val EXIT_GRACE_MS = 2_000L

    /**
     * 세션 유지에 필요한 최소 플레이어 면적비. 댓글창 실측 0.292 는 유지하고,
     * 에뮬레이터에서 홈 버튼 후 관찰된 0.077(PiP/미니플레이어로 추정)은 유지하지 않는다. TODO: VERIFY ON DEVICE
     */
    const val MIN_HOLD_AREA_RATIO = 0.15f

    /**
     * 활성 창 root 가 화면의 이 비율보다 작으면 대상 앱의 대화상자/팝업으로 보고 "일시 가림" 처리한다.
     * 에뮬레이터 로그인 대화상자 실측 약 0.09. TODO: VERIFY ON DEVICE
     */
    const val OVERLAY_MAX_ROOT_AREA_RATIO = 0.50f

    /** reel_* 보조 컨테이너를 "전체화면"으로 볼 면적비. 덤프 실측 0.863. TODO: VERIFY ON DEVICE */
    const val MIN_CONTAINER_AREA_RATIO = 0.70f

    /** 관찰 간격 1회당 시청시간 누적 상한(타이머 1초 간격 + 여유). */
    const val MAX_CREDIT_PER_STEP_MS = 3_000L

    /** 세로 페이지 넘김 스크롤을 "최근"으로 볼 범위. TODO: VERIFY ON DEVICE */
    const val PAGING_WINDOW_MS = 15_000L

    /** 트리 순회 상한. 덤프 기준 쇼츠 화면은 약 130 노드. */
    const val MAX_NODES_VISITED = 1_500

    /** Instagram(실험적): pager 형 컨테이너로 볼 면적비. TODO: VERIFY ON DEVICE */
    const val INSTAGRAM_PAGER_AREA_RATIO = 0.80f

    /** Instagram(실험적): 새 진입에 필요한 최근 페이지 넘김 수. TODO: VERIFY ON DEVICE */
    const val INSTAGRAM_MIN_PAGE_FLIPS = 2
}
