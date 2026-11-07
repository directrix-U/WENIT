package com.example.shortsdetector.detection.state

import com.example.shortsdetector.DetectionConfig
import com.example.shortsdetector.detection.model.Confidence

/**
 * 상태 머신·어댑터가 쓰는 조정값 묶음. 테스트에서 값만 바꿔 주입할 수 있게 상수와 분리했다.
 *
 * 기본값은 [DetectionConfig] 에서 가져온다. 기존 프로토타입에서 실기기로 맞춘 값은 그대로 유지했고,
 * 새로 추가한 값은 `TODO: VERIFY ON DEVICE` 이다.
 */
data class DetectionTuning(
    /** 새 진입에 필요한 연속 충족 횟수 (기존 값) */
    val confirmationCount: Int = DetectionConfig.CONFIRMATION_COUNT,
    /** 새 진입에 필요한 숏폼 플레이어 면적비 (기존 값) */
    val minPlayerAreaRatio: Float = DetectionConfig.MIN_PLAYER_AREA_RATIO,
    /** 이 신뢰도 이상인 SHORTFORM 판정만 진입 카운트에 쓴다 */
    val minEntryConfidence: Confidence = Confidence.MEDIUM,
    /** 이보다 작은 플레이어는 PiP/미니플레이어로 보고 세션 유지 근거로 쓰지 않는다. TODO: VERIFY ON DEVICE */
    val minHoldAreaRatio: Float = DetectionConfig.MIN_HOLD_AREA_RATIO,
    /** 활성 창 root 면적비가 이보다 작으면 대화상자/팝업(OVERLAY)으로 본다. TODO: VERIFY ON DEVICE */
    val overlayMaxRootAreaRatio: Float = DetectionConfig.OVERLAY_MAX_ROOT_AREA_RATIO,
    /** 플레이어가 보이는 활성 창이 이보다 작으면 작은 창(PiP)으로 본다. 분할 화면은 걸리지 않게 둔다. TODO: VERIFY ON DEVICE */
    val pipMaxRootAreaRatio: Float = DetectionConfig.PIP_MAX_ROOT_AREA_RATIO,
    /** 보조 컨테이너(reel_*)를 "전체화면"으로 볼 면적비. TODO: VERIFY ON DEVICE */
    val minContainerAreaRatio: Float = DetectionConfig.MIN_CONTAINER_AREA_RATIO,
    /** 이탈 신호 후 세션을 끝내기까지 기다리는 시간. TODO: VERIFY ON DEVICE */
    val exitGraceMs: Long = DetectionConfig.EXIT_GRACE_MS,
    /** 이탈 종료 뒤 이 시간 안에 같은 앱에서 재진입하면 같은 세션으로 이어 간다. 0 이면 끔. TODO: VERIFY ON DEVICE */
    val sessionResumeWindowMs: Long = DetectionConfig.SESSION_RESUME_WINDOW_MS,
    /** 관찰 공백이 이보다 길면 세션을 끝낸다 (기존 STATE_STALE_MS) */
    val staleMs: Long = DetectionConfig.STATE_STALE_MS,
    /** 관찰 간격 1회당 시간 누적 상한. 공백 시간을 시청시간으로 부풀리지 않기 위함 */
    val maxCreditPerStepMs: Long = DetectionConfig.MAX_CREDIT_PER_STEP_MS,
    /**
     * 댓글/설명 패널로 줄어든 플레이어(SHORTFORM_PARTIAL) 시간을 확정 시청시간에 넣을지.
     * 패널이 열려 있어도 영상은 계속 재생·표시되므로 기본은 넣는다. 대화상자(OVERLAY)는 여기에 해당하지 않는다.
     */
    val countPartialAsWatch: Boolean = true,
    /** 세로 페이지 전환 신호를 "최근"으로 볼 범위. TODO: VERIFY ON DEVICE */
    val pagingWindowMs: Long = DetectionConfig.PAGING_WINDOW_MS,
    /** Instagram: 세로 pager 로 볼 컨테이너 면적비. TODO: VERIFY ON DEVICE */
    val instagramPagerAreaRatio: Float = DetectionConfig.INSTAGRAM_PAGER_AREA_RATIO,
    /** Instagram: 진입에 필요한 최근 페이지 전환 수. TODO: VERIFY ON DEVICE */
    val instagramMinPageFlips: Int = DetectionConfig.INSTAGRAM_MIN_PAGE_FLIPS
)
