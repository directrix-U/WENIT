package com.example.shortsdetector.data.usage

import com.example.shortsdetector.intervention.InterventionPolicy

/**
 * 로컬에 저장하는 사용 기록. 명세서 4장(DetectionSession / InterventionEvent)의 논리 모델을 따른다.
 *
 * 저장하는 것: 패키지 이름, 시각(epoch ms), 시간 누적값, 상태·이유 코드.
 * 저장하지 않는 것: 화면 원문, 댓글·알림 내용, 스크린샷, 노드 트리, 영상 정보.
 */
enum class SessionStatus {
    /** 진행 중(중간 저장). 서비스가 비정상 종료되면 다음 연결 때 [ABORTED] 로 닫는다. */
    OPEN,
    CLOSED,
    /** 프로세스 종료 등으로 정상 종료 기록을 못 남김. 끝 시각은 마지막 중간 저장 시각이다. */
    ABORTED
}

data class SessionRecord(
    /** UUID. 세션이 이어지면(재진입 유예) 같은 id 의 행을 갱신한다 — 합산 중복 없음. */
    val id: String,
    val packageName: String,
    val ruleVersion: String?,
    val startedAtMs: Long,
    /** 끝 시각. OPEN 이면 마지막으로 관찰한 시각. */
    val endedAtMs: Long,
    val status: SessionStatus,
    /** 확정 추정 시청시간(댓글창 시간 포함) */
    val estimatedWatchMs: Long,
    /** 위 중 댓글창 등 부분 플레이어 시간 */
    val partialWatchMs: Long,
    /** 대화상자 등으로 가려진 시간(시청시간에 미포함) */
    val obscuredMs: Long,
    /** 이탈 대기 시간(시청시간에 미포함) */
    val uncertainMs: Long,
    /** LOW / MEDIUM / HIGH */
    val maxConfidence: String,
    /** EndReason 이름. OPEN 이면 null. */
    val endReason: String?,
    /** ExitCause 이름. EXIT_CONFIRMED 가 아니면 null. */
    val exitCause: String?,
    val resumeCount: Int
)

enum class InterventionMethod { POPUP, NOTIFICATION, FAILED, SUPPRESSED_BY_COOLDOWN, SUPPRESSED_BY_DAILY_LIMIT }

enum class InterventionAction { OPEN_APP, DISMISS }

/** 개입 뒤 사용자 행동. [InterventionAction](버튼 반응)과 별개로, 단계 규칙과 멈춤 비율 통계의 입력이다. */
enum class InterventionOutcome { STOPPED, CONTINUED, IGNORED }

/** 사용자가 고른 이유. 정해진 선택지만 저장한다(자유 입력 없음). */
enum class InterventionUserReason {
    /** 재밌어서 */
    FUN,
    /** 심심해서 */
    BORED,
    /** 피곤해서 */
    TIRED,
    /** 할 일 미루는 중 */
    PROCRASTINATING
}

data class InterventionRecord(
    val id: String,
    val sessionId: String?,
    val occurredAtMs: Long,
    val method: InterventionMethod,
    /** 개입 시점의 세션 확정 시청시간 */
    val watchMs: Long,
    /** 개입 시점의 임계값 설정 */
    val thresholdMs: Long,
    /** 사용자 반응. 반응 없이 팝업이 사라지면 null. */
    val userAction: InterventionAction? = null,
    val actionAtMs: Long? = null,
    /** 개입 시점의 단계(1~3). DB 버전 1 에서 올라온 기록은 단계를 모르므로 null. */
    val level: Int? = null,
    val userReason: InterventionUserReason? = null,
    /** 아직 결과가 없으면 null */
    val outcome: InterventionOutcome? = null,
    /** 시연 모드의 개입. 통계와 AI 입력에서 기본으로 뺀다. */
    val isDemo: Boolean = false
)

/** [RULE]: 사람이나 에이전트가 아니라 결정론적 단계 규칙이 바꿨다. */
enum class PolicyChangeSource { AGENT, USER, DEFAULT, RULE }

enum class PolicyChangeStatus { APPLIED, REJECTED_OUT_OF_RANGE, PENDING_APPROVAL, REVERTED_BY_USER }

/** 개입 정책 변경 기록(명세 4장 PolicyChange). */
data class PolicyChangeRecord(
    val id: String,
    val occurredAtMs: Long,
    val source: PolicyChangeSource,
    /** 최초 기본 정책처럼 이전 값이 없으면 null */
    val previousPolicy: InterventionPolicy?,
    /** 범위 밖이라 거절된 제안은 잘리기 전 값을 그대로 남긴다. */
    val newPolicy: InterventionPolicy,
    /** 사용자에게 보여 줄 짧은 이유. 원본 이용 데이터를 넣지 않는다. */
    val reasonText: String,
    /** 에이전트가 본 집계 범위(기간·지표 이름). 원문 없음. */
    val inputSummary: String?,
    val status: PolicyChangeStatus,
    /** 에이전트 변경일 때만 */
    val modelId: String? = null,
    /** 시연 모드의 변경. AI 입력에서 뺀다. */
    val isDemo: Boolean = false
)
