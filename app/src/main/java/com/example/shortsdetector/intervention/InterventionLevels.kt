package com.example.shortsdetector.intervention

import androidx.annotation.LayoutRes
import androidx.annotation.StringRes
import com.example.shortsdetector.R

/** 개입 화면의 종류. 새 종류를 더하면 [InterventionPopup] 의 화면 구현과 문구 공급자가 컴파일 오류로 알려 준다. */
enum class InterventionKind {
    /** 가벼운 알림 */
    NOTICE,

    /** 이유를 묻는 질문 */
    QUESTION,

    /** 전체 화면 숨 고르기. 카운트다운 동안 시청시간을 멈춘다 */
    BREATHING
}

/**
 * 오버레이 창의 모양과 터치. 전부 TYPE_ACCESSIBILITY_OVERLAY 라 별도 권한이 필요 없다.
 * 창 바깥의 터치는 [CARD]·[FLOATING] 이면 아래 앱으로 간다. 한 창 안에서 일부만 통과시키는 방법은 없으므로,
 * 막을 영역을 창 크기로 정한다.
 */
enum class OverlayWindow {
    /** 화면 중앙 카드. 바깥 터치는 아래 앱으로 전달한다 */
    CARD,

    /** 화면 전체. 터치를 막는다 */
    FULL_SCREEN,

    /**
     * 내용 크기의 창을 원하는 곳에 띄운다. 창 위 터치만 받고 나머지는 아래 앱으로 전달하며, 끌어서 옮길 수 있다.
     * 캐릭터 단계를 위해 준비했고 아직 쓰는 단계가 없다. TODO: VERIFY ON DEVICE
     */
    FLOATING
}

/** 이 단계에서 다음 단계로 언제 올라가는가 */
enum class EscalationRule {
    /** 넘김(계속 보기, 무응답)이 정책의 escalateAfterSkips 번 연속이면 */
    AFTER_SKIPS,

    /** 계속 보기를 고르면 바로. 무응답은 고른 것이 아니므로 올리지 않는다 */
    ON_CONTINUE,

    /** 올라가지 않는다 */
    NONE
}

/**
 * 개입 단계 하나.
 * @param layout 팝업 레이아웃
 * @param maxLevelLabel 설정의 "최대 개입 단계" 선택지 문구. 최대 단계로 고를 수 없으면 null
 * @param description 에이전트 프롬프트에 넣는 한 줄 설명
 * @param promisesMoreTime 계속 보기 버튼이 "5분만 더" 처럼 시간을 약속한다. 그 시간이 지날 때까지 다시 개입하지 않는다
 */
data class InterventionLevelSpec(
    val number: Int,
    val kind: InterventionKind,
    val window: OverlayWindow,
    @LayoutRes val layout: Int,
    val escalation: EscalationRule,
    @StringRes val maxLevelLabel: Int?,
    val description: String,
    val promisesMoreTime: Boolean = false
)

/**
 * 개입 단계 목록. 단계에 관한 정보는 여기에만 둔다.
 *
 * 단계를 추가하려면 [ALL] 에 한 줄을 더하고, 새 [InterventionKind] 라면 [InterventionPopup] 의 화면 구현과
 * [InterventionCopyProvider] 의 문구를 더한다. 범위, 단계 규칙, 설정의 선택지, 통계, 에이전트 프롬프트는 이 목록을 따른다.
 */
object InterventionLevels {

    val ALL: List<InterventionLevelSpec> = listOf(
        InterventionLevelSpec(
            1, InterventionKind.NOTICE, OverlayWindow.CARD, R.layout.overlay_intervention_lv1,
            EscalationRule.AFTER_SKIPS, null, "Lv.1 가벼운 알림"
        ),
        InterventionLevelSpec(
            2, InterventionKind.QUESTION, OverlayWindow.CARD, R.layout.overlay_intervention_lv2,
            EscalationRule.ON_CONTINUE, R.string.settings_max_level_2, "Lv.2 이유를 묻는 질문"
        ),
        InterventionLevelSpec(
            3, InterventionKind.BREATHING, OverlayWindow.FULL_SCREEN, R.layout.overlay_intervention_lv3,
            EscalationRule.NONE, R.string.settings_max_level_3, "Lv.3 10초 숨 고르기",
            promisesMoreTime = true
        )
    )

    val MIN: Int = ALL.first().number
    val MAX: Int = ALL.last().number

    /** 범위 밖 번호는 가장 가까운 단계로 읽는다. 저장값이 손상돼도 화면을 띄울 수 있게 한다. */
    fun of(level: Int): InterventionLevelSpec = ALL[(level - MIN).coerceIn(ALL.indices)]

    /** 정책의 최대 단계로 고를 수 있는 단계 */
    val maxLevelChoices: List<InterventionLevelSpec> = ALL.filter { it.maxLevelLabel != null }
}
