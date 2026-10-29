package com.example.shortsdetector.detection.adapters.youtube

/**
 * YouTube UI 리소스 ID. **모두 저장소의 실제 덤프(2026-09-27, YouTube 버전 미기록)에서 관찰된 값만** 둔다.
 * 관찰 근거: app/src/test/resources/fixtures/youtube 의 TSV fixture
 *
 * YouTube 업데이트로 ID 가 바뀌면 여기만 고친다. 확인 방법은 docs/DEVICE_TEST_GUIDE.md 참고.
 */
object YouTubeViewIds {

    const val PACKAGE = "com.google.android.youtube"

    /** 쇼츠 플레이어. 기존 프로토타입의 유일한 판정 근거(1차 신호). */
    const val SHORTS_PLAYER = "$PACKAGE:id/reel_watch_player"

    /**
     * 쇼츠 화면의 보조 컨테이너(2차 신호). 쇼츠 덤프에서 플레이어와 같은 전체화면 bounds 로 관찰됐고,
     * 일반 시청 화면(광고 재생 중) 덤프에는 없었다.
     *
     * 주의: 덤프에서 reel_recycler 는 scrollable=false 였다. "스크롤 가능 여부"는 근거로 쓰지 않는다.
     */
    val REEL_CONTAINERS: Set<String> = setOf(
        "$PACKAGE:id/reel_recycler",
        "$PACKAGE:id/reel_watch_fragment_root",
        "$PACKAGE:id/reel_player_page_container",
        "$PACKAGE:id/reel_player_overlay_root"
    )

    /**
     * 일반 영상 플레이어. 일반 시청 화면(광고 재생 중) 덤프에서 관찰됐다(면적비 약 0.57).
     * 숏폼 신호가 없을 때 "일반 영상"이라는 근거로만 쓴다. 숏폼 판정을 뒤집는 데는 쓰지 않는다
     * (미니플레이어가 쇼츠 위에 떠 있을 수 있기 때문 — 미확인).
     */
    val REGULAR_PLAYERS: Set<String> = setOf(
        "$PACKAGE:id/watch_player",
        "$PACKAGE:id/player_view"
    )
}
