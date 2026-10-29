# WENIT! — 제품·기술 기획 명세서

- 버전: 1.0
- 작성 기준일: 2026-10-30
- 대상: Android 우선, YouTube Shorts 및 Instagram Reels
- 개발 방식: Claude Code를 이용한 단계별 구현 및 테스트
- 문서 목적: 제품 목표, 감지 엔진, 데이터 구조, 검증 기준, 향후 AI 코치 기능을 하나의 기준 문서로 고정한다.

---

## 0. 가장 중요한 결정

**WENIT!의 첫 번째 자산은 LLM이 아니라, 숏폼 이용 상태를 추정하는 감지 엔진과 감지·개입 결과를 검증할 수 있는 로그다.**

기존 실험 앱에서 댓글·알림창 등의 방해 요소가 있어도 감지가 작동했다는 사용자 검증 결과를 초기 기준선으로 삼는다. 이번 작업은 무조건 전체를 갈아엎는 프로젝트가 아니다. 먼저 기존 프로젝트의 코드·감지 규칙·테스트 방법을 조사하고, 유지할 수 있는 공통 기능은 보존한다. 필요한 경우 앱 전체가 아니라 감지 어댑터만 재작성한다.

제품의 핵심 원칙은 다음과 같다.

1. 앱 사용 상태, UI 트리의 구조적 단서, 스크롤·화면 전환 이벤트를 결합해 숏폼 세션을 **추정**한다. 숏폼을 100% 확정적으로 식별한다고 주장하지 않는다.
2. 공통 감지 엔진과 앱별(YouTube/Instagram) 규칙을 분리한다. 대상 앱 UI가 바뀌어도 공통 엔진을 재작성하지 않는다.
3. 감지와 개입 시점 판정은 재현 가능한 규칙으로 처리한다. LLM은 감지 판정이나 백그라운드 자동 행동의 권한을 갖지 않는다.
4. 1차 버전은 영상·오디오·스크린샷·원본 UI 텍스트를 저장하거나 외부로 전송하지 않는다. UI 값은 필요할 때 기기 내에서 잠깐 분석하고 폐기하며, 저장하는 것은 최소한의 구조적 특징과 이벤트 메타데이터다.
5. 불확실하면 공격적으로 개입하지 않는다. 불확실 상태에서는 관찰을 계속하거나 사용자가 직접 표시할 수 있게 한다.
6. 첫 개입은 사용자에게 허용된 알림을 기본으로 하고, 앱이 강제로 다른 앱 위에 화면을 덮거나 자동으로 Activity를 실행하지 않는다. 직접 오버레이는 정책·플랫폼·사용성 검토를 통과한 뒤 별도 결정한다.
7. 감지 품질과 사용자 경험을 측정하지 않은 채 개인화·AI 에이전트를 먼저 추가하지 않는다.

## 1. 제품 정의

### 1.1 문제

사용자는 YouTube Shorts나 Instagram Reels에 들어갈 의도로 앱을 실행하지 않았더라도, 숏폼 피드에 진입한 뒤 의도보다 오래 머물 수 있다. 기존 스크린타임 도구는 앱 전체를 대상으로 하는 경우가 있어, 긴 영상 시청·메시지 확인·게시물 탐색과 숏폼 시청을 세밀하게 구분하기 어렵다.

WENIT!은 특정 앱 전체를 무조건 차단하는 대신 숏폼 시청으로 추정되는 구간을 찾아 사용자가 스스로 멈추거나 원래 하려던 일로 돌아가도록 부드럽게 돕는다.

### 1.2 대상 사용자

- Android 기기에서 YouTube Shorts 또는 Instagram Reels를 사용하며, 이용 시간을 스스로 조절하고 싶어 하는 사용자.
- 사용자 통제권을 우선한다. 알림 빈도, 감지 대상 앱, 개입 임계값, 조용한 시간, 데이터 삭제를 직접 설정할 수 있어야 한다.
- 초기 검증에서는 성인/미성년 사용자를 섞어 모집하기보다 동의 절차와 연구 범위를 명확히 정한다. 미성년자를 포함할 경우 출시 전에 관련 개인정보·동의 요건을 별도 검토한다.

### 1.3 제품 가치 제안

“숏폼을 보는 앱을 통째로 막는 대신, 내가 의도한 사용 범위를 벗어났을 가능성이 높은 순간에 알아차리고 선택할 수 있게 돕는다.”

### 1.4 비목표(초기 버전에서 하지 않을 것)

- 영상 자체의 의미·주제·감정 분석
- 카메라, 마이크, 오디오, OCR, 스크린샷 캡처를 이용한 감지
- 콘텐츠의 개인정보를 서버나 LLM API로 전송
- LLM이 독자적으로 감지·개입·시스템 설정 변경 여부를 결정하는 구조
- 강제 앱 차단, 사용자가 끌 수 없는 서비스, 몰래 실행되는 감시 기능
- 첫 버전부터 구독·결제·랭킹·소셜 피드·캐릭터 성장 등 부가 기능을 모두 구현하는 것
- YouTube/Instagram 내부 구현을 공식 API처럼 가정하거나 비공개 API에 의존하는 것

## 2. 버전 범위

### MVP-A: 감지 엔진 검증

필수 산출물:
- 대상 앱 foreground 상태 추적
- AccessibilityService 기반 이벤트와 UI 트리에서 구조적 특징 추출
- 앱별 탐지 어댑터
- 후보/확정 추정/일시 가림/종료 상태 머신
- 이벤트 기록과 디버그 요약(원본 텍스트 제외)
- 수동 정답 라벨링 및 테스트 시나리오
- 기존 실험 앱과 새 버전의 비교 결과

완료 조건: 같은 테스트 시나리오에서 감지 여부와 이유를 재현할 수 있고, 오탐/미탐을 측정할 수 있다.

### MVP-B: 규칙 기반 개입

필수 산출물:
- 사용자 설정에 따른 누적 시청시간 또는 연속 세션 임계값
- 개입 쿨다운과 사용자 조용한 시간
- 개입 조건 충족 시 알림 표시
- 알림을 누르면 WENIT! 화면으로 이동
- 개입 직후 행동 및 주관적 피드백 기록
- 감지/알림/반응 로그를 보는 내부 진단 화면

완료 조건: 동일한 입력 이벤트에 대해 개입 판정이 재현 가능하고, 중복 알림·알림 폭주·세션 중복 집계가 테스트로 방지된다.

### MVP-C: 자기조절 경험

- 온보딩에서 사용자 목표 설정
- 하루/주간 숏폼 추정 시간, 세션 수, 개입 후 앱 이탈 비율 등 통계
- 개입 후 “도움 됨 / 별 영향 없음 / 방해됨” 피드백
- 간단한 대체 행동 또는 원래 하려던 일 체크리스트
- 죄책감이나 수치심을 자극하지 않는 문구

### 이후 버전: AI 코치와 개인화

- 검증된 문서셋 또는 RAG를 이용해 선택·검수된 행동과학 정보 제공
- LLM은 사용자 설정·최근 집계 통계를 바탕으로 짧은 코칭 문구와 질문을 생성
- 개입 유형 선택은 규칙 기반으로 먼저 구현하고, 충분한 표본이 쌓인 뒤에만 적응형 정책을 평가
- 모델의 조언이 앱 사용 판정·시스템 권한·데이터 보관 정책을 변경하지 못하도록 권한을 분리

## 3. 기술 아키텍처

### 3.1 계층

1. **Platform Signals**
   - `UsageStatsManager` / `UsageEvents`: 앱 사용 상태·시간의 보조 정보
   - `AccessibilityService`: 이벤트 종류, 패키지, 이벤트 시점, 필요 시 활성 창의 UI 노드 트리
   - 실제 기기/OS/앱 버전마다 제공 신호가 다를 수 있음을 전제로 한다.

2. **Feature Extractor**
   - 원본 `AccessibilityNodeInfo` 트리에서 최소 특징만 추출한다.
   - 예시 특징: 노드 클래스 분포, 스크롤 가능한 컨테이너 유무, 컨테이너의 상대적 크기·화면 비율, 세로 스크롤 이벤트 발생, 이벤트 간 간격, 화면 구조가 연속해서 바뀌는 양상, 필요한 경우 일시적으로 확인한 짧은 UI 단서의 존재 여부.
   - 실제 기기에서 얻을 수 없는 특징은 요구하지 않는다. 기능별로 `available / unavailable`을 표현한다.
   - 원본 UI 텍스트·콘텐츠 설명은 저장하거나 로그에 남기지 않는다. 텍스트 매칭을 실험하더라도 기기 내 일회성 처리 후 boolean 특징만 남긴다.

3. **App Detector Adapter**
   - `YouTubeShortsDetector`와 `InstagramReelsDetector` 같은 별도 구현.
   - 패키지별 특징 해석과 규칙 버전을 분리한다.
   - UI 문자열이나 특정 `viewId` 하나에만 의존하지 않는다. 구조·상태·이벤트 패턴을 조합한다.
   - UI 규칙은 원격 서버에서 임의로 내려받지 않는다. 우선 앱 버전과 함께 앱에 포함해 검토·릴리스하고, 별도 원격 규칙을 도입할 경우 보안·검증 설계를 추가한다.

4. **Detection State Machine**
   - 상태는 다음을 기본으로 한다.
     - `OUTSIDE_TARGET`: 대상 앱이 전면에 있지 않음
     - `TARGET_FOREGROUND`: 대상 앱이 전면에 있으나 숏폼 여부 불명
     - `CANDIDATE`: 숏폼일 가능성이 있어 관찰 중
     - `SHORTFORM_ESTIMATED`: 복수 신호가 조건을 만족해 숏폼 세션으로 추정
     - `TEMPORARILY_OBSCURED`: 댓글 창·알림 셰이드·시스템 대화상자 등으로 신호가 일시적으로 가려짐
     - `EXIT_PENDING`: 이탈 신호가 나타났지만 재확인을 기다림
     - `ENDED`: 세션 종료 확정
   - 전환 규칙과 grace period는 상수 하나에 박아두지 말고 설정값으로 관리한다. 기본값은 기기 테스트에서 조정한다.
   - 일시 가림은 즉시 종료로 취급하지 않는다. 다만 가려진 시간 동안 숏폼 사용시간을 무조건 누적하지도 않는다. 상태와 시간을 분리해 기록한다.
   - `TEMPORARILY_OBSCURED` 중 숏폼 화면 증거가 부족하면 세션은 보류하고, 불확실한 시간을 확정 시청시간으로 합산하지 않는다.
   - 이벤트 폭주를 막기 위해 디바운스·스로틀을 적용하고, 이벤트 객체나 노드 객체를 수명이 끝난 뒤 그대로 보관하지 않는다.

5. **Policy / Intervention Engine**
   - 감지 엔진이 제공한 상태와 사용자 설정을 받아 개입 필요 여부를 결정한다.
   - LLM 호출 없이 동작해야 한다.
   - 쿨다운, 조용한 시간, 알림 권한, 사용자 일시중지, 중복 알림 방지, 하루 최대 빈도를 처리한다.
   - 초기 기본값은 보수적으로 설정하고, 앱 설정에서 사용자가 이해할 수 있도록 설명한다.

6. **Local Storage / Analytics**
   - Room 등 로컬 저장소를 사용하되 기존 프로젝트의 스택과 호환성을 우선한다.
   - 기본은 로컬 저장. 사용자가 명시적으로 허용하지 않는 한 로그를 서버에 업로드하지 않는다.
   - 시간 저장은 epoch milliseconds로 통일하고 타임존 및 일별 집계 경계를 별도로 처리한다.

7. **UI**
   - 설정, 상태 확인, 통계, 개입 피드백, 디버그 진단을 담당한다.
   - 서비스가 꺼져 있거나 권한이 없을 때 “작동 중”이라고 표시하지 않는다.

### 3.2 감지 규칙 설계 원칙

- **단일 신호로 확정하지 않는다.** “YouTube 패키지가 foreground”만으로는 Shorts가 아니다. “세로 스크롤 이벤트가 발생함”만으로도 Shorts가 아니다.
- **다중 신호를 결합한다.** 대상 패키지, UI 트리 특징, 세로 페이지 전환 양상, 이벤트 사이 간격, 상태의 지속성을 함께 사용한다.
- **증거를 설명 가능하게 남긴다.** 세션마다 `detector_version`, `signal_flags`, `confidence_bucket`(예: low/medium/high), `decision_reason_code`를 기록할 수 있다. 이 값은 원본 화면 내용이 아니라 구조적 특징이어야 한다.
- **불확실성은 데이터다.** UI 트리를 얻지 못한 상태와 “숏폼이 아님”은 다르다. `unknown`을 명시적으로 표현한다.
- **앱별 버전 드리프트를 견딘다.** 규칙별 유효 앱 버전/테스트 날짜/통과 여부를 기록하고, UI 변경이 의심되면 대상 앱 버전별 smoke test를 수행한다.
- **비용이 큰 탐색은 제한한다.** 모든 접근성 이벤트마다 전체 트리를 무제한 순회하지 않는다. 이벤트 종류와 시간 제한에 따라 요약을 갱신한다.
- **절대적 정확도를 주장하지 않는다.** 릴리스 노트와 앱 내 설명에서 “숏폼 시청 추정”이라고 표현한다.

### 3.3 UI 트리 변경 대응

YouTube는 2026년 6월 Shorts 플레이어의 `Clear Screen` 모드, 재생 속도·음소거 관련 조정, 아이콘 변경 등 UI 업데이트를 공식 발표했다. 따라서 다음을 적용한다.

- 특정 텍스트·아이콘·리소스 ID만을 핵심 판별 조건으로 쓰지 않는다.
- 화면 구조를 정규화해 요약한 특징(예: 전체 노드 수 범위, 스크롤 가능 영역의 상대적 크기, 주요 노드 클래스 분포)을 보조 신호로 사용한다.
- 화면을 가리는 댓글 시트/알림 셰이드/시스템 UI에서는 `TEMPORARILY_OBSCURED` 경로를 사용한다.
- Clear Screen처럼 텍스트·아이콘이 사라져도 다른 신호가 남는지 실제 기기에서 검증한다. 남지 않는 경우에는 해당 상태의 감지 한계를 문서화하고 오탐 방지 우선의 graceful degradation을 사용한다.
- 규칙 하나를 수정할 때 기존 회귀 테스트도 실행하고, 신규 UI에 대한 테스트만 통과했다고 기존 상황을 회귀시키지 않는다.

참고: https://blog.youtube/intl/ko-kr/news-and-events/youtube-shorts-experience-updates-features/

## 4. 데이터 모델 초안

구체적인 스키마는 기존 코드 구조와 저장소를 확인한 다음 확정한다. 아래는 논리 모델이다.

### `DetectionSession`
- `session_id`: UUID
- `package_name`: 대상 앱 패키지
- `started_at_ms`, `ended_at_ms`
- `final_state`: estimated / uncertain / ended / aborted
- `detector_version`
- `estimated_watch_ms`: 확정 추정 시청시간
- `obscured_ms`: 일시 가림으로 별도 처리한 시간
- `max_confidence_bucket`
- `end_reason_code`

### `DetectionObservation`
- `observation_id`: UUID
- `session_id`: FK
- `occurred_at_ms`
- `event_type`: scroll / window_changed / content_changed / foreground_changed / etc.
- `feature_flags`: 저장 가능한 구조 특징
- `decision_reason_code`
- 원본 UI 텍스트, 댓글 내용, 알림 내용, 스크린샷은 저장하지 않는다.

### `InterventionEvent`
- `intervention_id`: UUID
- `session_id`: FK nullable
- `occurred_at_ms`
- `intervention_type`
- `trigger_reason_code`
- `notification_posted`: boolean
- `opened_by_user`: boolean
- `cooldown_suppressed`: boolean

### `FeedbackEvent`
- `feedback_id`: UUID
- `intervention_id`: FK
- `occurred_at_ms`
- `subjective_feedback`: helpful / neutral / disruptive / skipped
- `behavioral_outcome`: app_left / returned_quickly / still_active / unknown

### `UserSettings`
- 대상 앱 활성화 여부
- 개입 임계값(초/분) 및 적용 단위(세션 또는 일 누적)
- 쿨다운 및 조용한 시간
- 알림 표시 여부
- 데이터 보관 기간과 전체 삭제 기능
- 설정 변경 기록은 필요한 경우에만 최소한으로 보관한다.

### 시간 규칙

- 원본 시각은 epoch milliseconds로 저장한다.
- 일별 통계의 시간대는 사용자 기기 현지 시간 기준으로 계산한다.
- 앱을 자주 재시작해도 중복·겹치는 세션이 생성되지 않게 세션 복구 규칙을 테스트한다.
- `UsageEvents`의 기록 보존 범위에 의존해 영구적인 원장처럼 쓰지 않는다. 실시간 상태는 접근성 이벤트와 앱 내부 상태를 사용하고, UsageStats는 보조 검증과 집계에 쓴다.

## 5. 개입 UX

### 5.1 첫 개입

- 기본: 사용자가 허용한 알림을 표시한다.
- 알림 문구는 비난·수치심·의학적 진단을 피한다. 예: “지금 계속 볼지, 잠깐 멈출지 선택해 볼래?”
- 알림을 누르면 앱 내의 짧은 선택 화면으로 이동한다.
- 알림 권한이 꺼져 있으면 실패를 명시하고, 권한을 다시 켜는 안내를 제공한다. 권한 없이 다른 방식으로 몰래 우회하지 않는다.
- 같은 세션·쿨다운 구간에서 중복 알림을 보내지 않는다.
- 사용자가 일시중지하거나 규칙을 조정할 수 있게 한다.

### 5.2 개입 후 화면

첫 버전에서는 다음 중 1~2개만 선택한다.
- “계속 보기 / 여기서 멈추기”라는 비판단적 선택
- 사용자가 미리 입력한 현재 목표/체크리스트 한 항목
- 10초 동안 멈춰서 다음 행동을 선택하는 간단한 프롬프트
- 도움 됨/무관심/방해됨 피드백

캐릭터 성장, 복수의 애니메이션, 사용자 지정 이미지, 블랙홀 화면, 화면 흑백 처리 등은 감지·알림·로그의 신뢰성이 입증된 뒤 우선순위를 재평가한다. 화면 색상/시스템 설정 변경은 권한·정책·사용성 부담이 있으므로 MVP에서 제외한다.

### 5.3 행동 성과의 의미

앱 이탈은 행동의 한 가지 관측치일 뿐 성공의 완전한 증거가 아니다. 다음을 분리해 측정한다.

1. 시스템 관측 행동: 감지 세션이 종료되었는지, 짧은 시간 안에 다시 돌아왔는지
2. 개입 참여: 알림을 열었는지, 선택 화면을 완료했는지
3. 사용자 평가: 실제로 도움이 되었는지

세 지표를 하나의 “성공률”로 섞지 않는다.

## 6. 검증·실험 계획

### 6.1 기준선 구축

기존 실험 앱과 새 버전을 비교할 수 있도록 다음을 기록한다.
- 테스트 기기, Android OS 버전, YouTube/Instagram 앱 버전
- 감지 시작·종료에 대한 사람이 기록한 정답 라벨
- 감지 결과, state transition, reason code
- 오탐·미탐 및 UI 트리를 읽을 수 없었던 비율

원본 화면이나 댓글 내용은 연구 기록으로 저장하지 않는다. 필요하면 화면 녹화 대신 테스터의 이벤트 시점 체크리스트와 앱 내부의 익명 구조 로그를 사용한다.

### 6.2 최소 테스트 매트릭스

각 시나리오는 기기/앱 버전마다 반복한다.

| 범주 | 시나리오 | 기대 동작 |
|---|---|---|
| Shorts 정상 | Shorts 진입 후 여러 영상으로 연속 스와이프 | 숏폼 추정 상태로 전환 |
| 일반 YouTube | 홈, 검색, 댓글, 구독, 일반 긴 영상 | 숏폼으로 성급하게 확정하지 않음 |
| 방해 요소 | 댓글 창 열기/닫기, 알림 셰이드 열기/닫기, 시스템 대화상자 | 일시 가림 처리, 즉시 종료로 오판하지 않음 |
| 상호작용 변화 | 일시정지, 탭, 좋아요/피드백 패널, Clear Screen 모드 | 지원되는 신호만 사용하고 불확실성을 보존 |
| 전환/종료 | 홈 이동, 앱 전환, 화면 꺼짐, 뒤로 가기 | 세션 종료 또는 pause 규칙에 맞게 처리 |
| 장애 | UI 트리 null/empty, 노드 stale, 이벤트 폭주, 서비스 재시작 | 크래시 없이 unknown/복구 처리 |
| Instagram | Reels 피드, 댓글/공유 시트, 일반 피드·DM 화면 | 릴스 추정과 일반 화면을 구분 |
| 설정/권한 | 접근성 서비스 OFF, 사용 정보 접근 OFF, 알림 권한 OFF | 동작 불가 상태를 사용자에게 정확히 표시 |

### 6.3 지표

- Precision = 감지한 숏폼 중 실제 정답 라벨이 숏폼인 비율
- Recall = 실제 숏폼 구간 중 감지한 비율
- False positives per hour = 숏폼이 아닌 상태에서 발생한 잘못된 확정/개입 빈도
- Detection latency = 실제 진입 후 추정 상태로 전환될 때까지의 시간
- State recovery rate = 댓글/알림 셰이드 등 방해 후 정상 상태로 복귀한 비율
- Crash/ANR 및 서비스 재연결 실패 빈도
- 사용자 지표: 알림 해제·방해됨 피드백·일시중지 비율

초기 출시 게이트의 제안값(실제 파일럿에서 조정할 **제품 목표**, 보장된 성능이 아님):
- 사전에 정한 대표 시나리오에서 precision 95% 이상을 목표로 한다.
- recall 목표는 85% 이상을 출발점으로 두되, precision과 함께 검토한다.
- 테스트되지 않은 UI 상태에서는 자동 개입을 공격적으로 수행하지 않는다.
- 댓글/알림 셰이드 등 주요 방해 시나리오 각각에 대해 충분한 반복 표본을 확보한다. 최소 30회 반복은 출발점이며, 기기 수와 앱 버전 수를 별도 보고한다.

결과는 전체 평균뿐 아니라 앱별·OS별·UI 시나리오별로 분리해 제시한다. 데이터가 적으면 그 한계를 명시한다.

### 6.4 회귀 테스트

- 감지 규칙은 버전 관리한다.
- 규칙 변경마다 기존 시나리오와 신규 UI 시나리오를 함께 재실행한다.
- 각 테스트에는 `expected_state`, 실제 상태, latency, reason code를 저장한다.
- 실기기에서 얻은 로그를 fixture로 만들 때 개인 데이터가 포함되지 않도록 구조 특징만 남긴다.
- 일반 YouTube/Instagram 화면에서 발생한 false positive 사례는 별도 회귀 세트로 유지한다.

## 7. 개인정보·플랫폼 정책

- 접근성 서비스 사용 목적, 수집·접근하는 정보, 사용 방법, 전송 여부를 앱 안에서 명확히 설명하고 적극적인 동의를 받는다.
- Google Play 배포를 목표로 한다면 Accessibility API declaration과 해당 정책을 출시 전에 재검토한다. 장애 지원이 주목적이 아닌 앱은 `isAccessibilityTool=true`로 선언하지 않는다.
- 접근성 API는 더 좁은 범위의 API로 목적을 달성할 수 있는지 검토한 뒤 사용한다.
- 정책이 허용하지 않는 자동 의사결정·시스템 제어·보안 제약 우회에 의존하지 않는다. WENIT!의 자동 감지 및 개입 방식은 출시 전 Play 정책에 대한 별도 검토 항목이다. 이 문서는 배포 승인에 대한 보증이 아니다.
- 백그라운드에서 Activity를 강제로 띄우는 방식에 의존하지 않는다. 사용자 탭으로 알림에서 앱으로 이동하도록 한다.
- 개인정보 최소 수집, 로컬 우선, 기록 보관 기간, 모든 로그 삭제, 서비스 중지·권한 해제 안내를 구현한다.
- Claude/기타 LLM API를 추가할 경우 감지용 원본 UI 정보는 보내지 않는다. 개인별 집계 정보와 사용자가 직접 입력한 목표를 외부 전송하기 전 별도 고지·동의와 삭제 정책을 설계한다.
- 접근성 이벤트·UI 트리 분석 기능은 사용자가 명시적으로 활성화한 상태에서만 동작한다. 기능을 끌 수 있어야 하고 해제 후에는 감지·기록이 중단되어야 한다.

정책 참고:
- Google Play — Accessibility API: https://support.google.com/googleplay/android-developer/answer/10964491?hl=en
- Google Play — Developer Program Policy: https://support.google.com/googleplay/android-developer/answer/18265346
- Android — AccessibilityService: https://developer.android.com/reference/android/accessibilityservice/AccessibilityService
- Android — Background activity launch restrictions: https://developer.android.com/guide/components/activities/secure-bal

## 8. 실패 처리

- 대상 앱이 foreground여도 UI 특징을 얻지 못하면 `TARGET_FOREGROUND` 또는 `CANDIDATE`에 머문다.
- detector adapter에서 예외가 발생하면 서비스 전체가 종료되지 않도록 격리하고, 오류 코드만 기록한다.
- 이벤트가 중복으로 도착해도 동일 시점/동일 상태 전이를 중복 반영하지 않는다.
- 재부팅 또는 프로세스 재시작 후 열린 세션은 복구 규칙에 따라 종료/재검증하고, 무한히 활성 상태로 남겨두지 않는다.
- 사용자 권한이 해제되면 기능 상태를 비활성으로 전환하고 명확한 안내를 표시한다.
- 대상 앱 버전 또는 UI 구조가 기존 규칙과 크게 다르면 confidence를 낮추거나 감지를 일시적으로 보수화한다.

## 9. 개발 우선순위

### Phase 0 — 프로젝트 감사 및 기준선
- 기존 저장소/패키지 구조/빌드 설정을 읽는다.
- 기존 실험 앱에서 작동했던 감지 로직과 그 조건을 식별한다.
- 현재 지원하는 기기·OS·앱 버전과 재현 방법을 문서화한다.
- 기존 테스트·로그가 없다면 안전하게 추가한다.
- 이 단계에서는 대규모 리팩터링을 하지 않는다.

### Phase 1 — 공통 엔진과 앱별 어댑터 정리
- raw event → features → detector adapter → state machine을 분리한다.
- 단위 테스트로 상태 전이와 시간 계산을 검증한다.
- 기존 감지와 새 감지의 결과를 같은 테스트에 대해 비교한다.

### Phase 2 — 최신 YouTube UI 회귀
- 실제 기기에서 현재 YouTube Shorts의 정상 화면과 방해 시나리오를 관찰한다.
- UI 문자열/아이콘 고정 조건에 의존하는 코드를 찾아 구조적 특징과 시간 패턴을 보완한다.
- Clear Screen 및 댓글/알림 셰이드 등에서의 동작을 별도 검증한다.

### Phase 3 — 로그·검증 도구
- 구조적 이벤트 로그, reason code, 테스트 fixture, precision/recall 계산을 만든다.
- 데이터 품질이 확보되기 전에는 개입 기능을 확장하지 않는다.

### Phase 4 — 규칙 기반 알림 개입
- 사용자가 선택한 세션/일 누적 임계값, 쿨다운, 조용한 시간, 권한 검사
- 알림 → 사용자 탭 → WENIT! 개입 화면
- 중복 알림·알림 피로·앱 재진입 결과 기록

### Phase 5 — 통계·목표·피드백
- 목표 설정, 간단한 주간 통계, 반응 피드백
- 성과를 “사용시간 감소” 하나로만 판단하지 않고, 도움됨 평가와 재진입 등도 함께 확인

### Phase 6 — AI 코치(검증 후)
- 코칭 텍스트 생성만 담당하는 좁은 인터페이스
- 검수된 자료 인용 또는 고정 문구 기반
- LLM 장애 시 로컬 문구로 graceful fallback
- LLM은 감지·임계값·권한·자동 실행을 제어하지 않음

## 10. Definition of Done

각 Phase는 다음 조건을 모두 만족해야 완료다.

1. 프로젝트가 실제 기기 또는 에뮬레이터에서 빌드된다.
2. 변경된 로직에 대한 단위 테스트/회귀 테스트가 추가되고 통과한다.
3. 서비스 권한이 없거나 UI 신호가 없는 경우의 처리가 명시되어 있다.
4. 로그에 원본 댓글·알림·영상 텍스트나 스크린샷이 남지 않는다.
5. 사용자 설정과 권한을 무시한 개입이 발생하지 않는다.
6. 변경 파일, 실행한 명령, 테스트 결과, 알려진 한계, 다음 작업이 정리되어 있다.
7. 주요 동작이 검증되지 않았으면 완료했다고 주장하지 않는다.

---

## 참고 링크

- YouTube 공식 Shorts 업데이트(2026-06-26): https://blog.youtube/intl/ko-kr/news-and-events/youtube-shorts-experience-updates-features/
- Android AccessibilityService API: https://developer.android.com/reference/android/accessibilityservice/AccessibilityService
- Android AccessibilityEvent API: https://developer.android.com/reference/android/view/accessibility/AccessibilityEvent
- Android AccessibilityNodeInfo API: https://developer.android.com/reference/android/view/accessibility/AccessibilityNodeInfo
- Android UsageStatsManager API: https://developer.android.com/reference/android/app/usage/UsageStatsManager
- Google Play Accessibility API policy: https://support.google.com/googleplay/android-developer/answer/10964491?hl=en
- Google Play Developer Program Policy: https://support.google.com/googleplay/android-developer/answer/18265346
- Android background activity launch restrictions: https://developer.android.com/guide/components/activities/secure-bal
