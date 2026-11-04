# 감지 아키텍처와 상태 모델 (yt-2 / ig-0-experimental)

- 작성일: 2026-10-30
- 기준선: Git 태그 `baseline-v0` (리팩터링 전 실험 앱 그대로)
- 이 문서의 "확인됨"은 **코드·저장소 덤프·단위 테스트로 확인한 것**만 뜻한다. 실기기 검증 여부는 별도 표기한다.

## 1. 기존 실험 앱(baseline-v0) 요약

| 항목 | 내용 |
|---|---|
| 대상 | YouTube만 (`packageNames=com.google.android.youtube`) |
| 이벤트 | `TYPE_WINDOW_STATE_CHANGED`, `TYPE_WINDOW_CONTENT_CHANGED`, OS 디바운스 300ms + 앱 스로틀 250ms |
| 판정 | `findAccessibilityNodeInfosByViewId("…:id/reel_watch_player")` → ID 전체 일치 + `isVisibleToUser` + 면적비 ≥ 0.70 |
| 진입 | 위 조건 3회 연속 → 래치 + 알림(재알림 쿨다운 30초) |
| 유지 | 플레이어가 보이기만 하면 유지(댓글창으로 0.292까지 줄어도 유지) |
| 해제 | 플레이어 안 보임 / root null / 다른 패키지 → **즉시** 해제 + 알림 취소 |
| 타이머 | 확인 중·래치 중에만 1초 감시 타이머, 60초 stale 안전장치 |
| 저장 | 없음(logcat 만) |
| 테스트 | 없음 |
| UsageStatsManager | 사용 안 함 |

실기기에서 사용자가 확인했다고 기록된 것: 댓글창에서도 감지 유지, 정지 상태 시청 시 타이머로 확정, 홈 이동 시 약 1초 내 해제.
알림창(알림 셰이드) 처리에 대한 별도 코드는 없었다. root 가 null 이 되면 해제됐다가 재진입하고, 30초 쿨다운이 중복 알림만 막았을 가능성이 있다(추측, 미확인).

## 2. 새 구조

```
platform (Android)                        detection (순수 Kotlin, JVM 테스트 대상)
──────────────────                        ─────────────────────────────────────────
ShortsDetectionService ── 스냅샷 ──▶ DetectionEngine (+ 공통 OVERLAY 규칙)
  · 이벤트 수신/스로틀/타이머                 ├─ adapters/youtube/YouTubeShortsAdapter
  · AccessibilitySnapshotter                 │     └─ LegacyShortsPlayerRule (기존 규칙 그대로)
    (노드 → ScreenSnapshot, 원문 미복사)      ├─ adapters/instagram/InstagramReelsAdapter (실험적)
  · ScrollEventMapper                        ├─ features/ScrollSignalTracker
  · ShortsNotifier (알림 게시/취소)           ├─ state/ShortformStateMachine
  · DetectionStatus (화면 표시용)            └─ intervention/NotificationGate
data/DetectionPreferences (대상 앱 on/off)
```

- **스냅샷 수집 방식**: YouTube 는 전체 트리를 순회하지 않는다. root 노드 1개와 판정에 쓰는 ID 7개를 `findAccessibilityNodeInfosByViewId` 로 검색한 결과만 담는다(기존 프로토타입과 같은 API). 이유는 두 가지다(에뮬레이터 실측).
  1. 트리 순회(`getChild`)는 "접근성상 중요하지 않은" 뷰를 건너뛴다. 그래서 `reel_watch_player` 가 순회에서는 안 보이고 ID 검색에서만 보였다. 리팩터링 초안이 이 때문에 쇼츠를 놓쳤다.
  2. 순회는 비싸다. 스냅샷 1회 중앙값 62ms, 최대 724ms 였고, ID 검색으로 바꾼 뒤 중앙값 15ms, 최대 402ms 가 됐다.
  Instagram 은 className 으로 판단해야 해서 상한(1,500 노드)이 있는 순회를 쓴다.
- 서비스·Activity 의 패키지/클래스 이름은 바꾸지 않았다. 바꾸면 사용자가 켜 둔 접근성 설정이 풀린다. CLAUDE.md 의 권장 구조(`ui/` 등)와 다른 이유다.
- 어댑터 예외는 `DetectionEngine.evaluate` 에서, 노드 순회 예외는 서비스의 `analyzeCurrentScreen` 과 `onAccessibilityEvent` 에서 격리한다. 어느 경우에도 서비스가 죽지 않는다.

## 3. 상태 모델

| 상태 | 의미 | 감시 타이머 |
|---|---|---|
| `OUTSIDE_TARGET` | 활성 창이 대상 앱이 아니거나 root 없음(세션 없음) | 끔 |
| `TARGET_FOREGROUND` | 대상 앱이지만 숏폼 신호 없음/모름 | 끔 |
| `CANDIDATE` | 숏폼 신호 관찰 중(진입 확인 중, 또는 LOW 신뢰도) | 켬 |
| `SHORTFORM_ESTIMATED` | 숏폼 세션으로 추정 | 켬 |
| `TEMPORARILY_OBSCURED` | 세션 중 플레이어가 댓글/설명 패널 등에 일부 가려짐 | 켬 |
| `EXIT_PENDING` | 이탈 신호가 왔지만 grace 기간 대기 중 | 켬 |
| `ENDED` | 세션이 막 끝남(다음 관찰에서 대기 상태로) | 끔 |

### 전이 규칙

| 현재 | 입력 | 다음 |
|---|---|---|
| 대기·ENDED | SHORTFORM, 신뢰도 ≥ MEDIUM | CANDIDATE (hits=1) |
| 대기·ENDED | SHORTFORM, LOW | CANDIDATE (hits=0, 관찰만) |
| CANDIDATE | SHORTFORM ≥ MEDIUM, 같은 앱 | hits+1. hits ≥ 3 이면 **SHORTFORM_ESTIMATED (세션 시작, 알림)**. 단 직전 세션이 EXIT_CONFIRMED 로 끝난 지 10초(`SESSION_RESUME_WINDOW_MS`) 이내이고 같은 앱이면 **그 세션을 이어 감**(번호·누적 시간·개입 여부 유지, 끊긴 구간은 안 셈) |
| CANDIDATE | 그 밖의 입력(PARTIAL 포함) | TARGET_FOREGROUND / OUTSIDE_TARGET (hits 초기화) |
| ESTIMATED·OBSCURED·EXIT_PENDING | SHORTFORM (신뢰도 무관) | SHORTFORM_ESTIMATED |
| ESTIMATED·OBSCURED·EXIT_PENDING | SHORTFORM_PARTIAL 또는 OVERLAY | TEMPORARILY_OBSCURED (PARTIAL 은 시청시간에 넣음) |
| 대기 | SHORTFORM_PARTIAL 또는 OVERLAY | TARGET_FOREGROUND (진입 근거 아님) |
| ESTIMATED·OBSCURED | NOT_SHORTFORM / UNKNOWN / root 없음 / 다른 창 | EXIT_PENDING (시작 시각 기록) |
| EXIT_PENDING | 같은 이탈 신호가 2초(`EXIT_GRACE_MS`) 이상 지속 | **ENDED (알림 취소)** |
| 세션 중 | 다른 대상 앱의 화면 | 기존 세션 ENDED(APP_SWITCHED) 후 새로 판단 |
| 아무 상태 | 직전 관찰과 60초 넘게 공백 | 세션은 마지막 관찰 시각에 ENDED(STALE) |
| 아무 상태 | 같은 시각 이하의 관찰(중복/역순) | 무시 |

### 시간 누적

- `estimatedWatchMs`: SHORTFORM_ESTIMATED 시간 + 댓글창 등 SHORTFORM_PARTIAL 로 인한 TEMPORARILY_OBSCURED 시간
  (플레이어가 줄어든 채 계속 보이므로 시청으로 센다. `DetectionTuning.countPartialAsWatch`, 기본 true)
- `partialWatchMs`: 위 중 댓글창 등 부분 플레이어 시간(로그용, 이미 포함)
- `obscuredMs`: OVERLAY(대화상자)로 인한 TEMPORARILY_OBSCURED 시간(확정 시청시간에 넣지 않음)
- `uncertainMs`: EXIT_PENDING 시간(넣지 않음)
- 관찰 간격 1회당 최대 3초만 더한다(공백 부풀림 방지). 시각은 `SystemClock.elapsedRealtime()`.
- 아직 저장하지 않는다. 세션이 끝날 때 logcat 에 요약만 남긴다.

### 기존 대비 의도한 동작 변경

1. **이탈 grace 2초**: 기존에는 root null 한 번으로 즉시 해제했다. 이제 홈으로 나가면 알림이 최대 약 3초(grace + 타이머 1회) 뒤에 사라진다. 대신 알림창을 잠깐 내리거나 root 가 순간적으로 null 일 때 세션이 끊겼다 다시 시작되지 않는다. (TODO: VERIFY ON DEVICE)
2. **일시 가림 분리**: 댓글창 유지 동작은 같다. 그 시간을 따로 집계할 뿐이다.
3. **같은 세션 복귀 시 알림 없음**: grace 안에 돌아오면 같은 세션으로 이어진다. grace 를 넘겨 끝났어도 10초 안에 같은 앱에서 다시 진입하면 같은 세션으로 이어진다(2026-11-01 추가). 세션 요약은 같은 번호로 다시 나오며 나중 것이 누적값이다(`resumeCount`).
6. **댓글창 시간은 시청시간**(2026-11-01): 플레이어가 줄어든 채 계속 보이므로 센다. 대화상자(OVERLAY)는 계속 뺀다.
4. **아주 작은 플레이어는 유지하지 않음**: 면적비 0.15 미만(PiP/미니플레이어)은 `PLAYER_MINIATURE` 로 이탈 처리한다. 기존 규칙이라면 홈으로 나가 PiP 가 되어도 세션을 계속 유지했을 것이다(에뮬레이터 실측 0.077).
5. **대상 앱 대화상자는 가림**: 활성 창 root 가 화면의 50% 미만이고 어댑터가 숏폼 근거를 못 찾으면 `OVERLAY` 로 본다(에뮬레이터 로그인 대화상자 실측 약 0.09). 기존에는 해제됐을 상황이다.

## 4. 신호와 근거

### YouTube (`yt-2`)

| 신호 | 출처 | 근거 수준 |
|---|---|---|
| `reel_watch_player` 전체화면(≥ 0.70) | 기존 규칙 | 실측 덤프 + 이전 실기기 검증 + **에뮬레이터 YouTube 21.18.164 에서 0.863 확인**. 실기기 최신 버전은 미확인 |
| `reel_watch_player` 0.15 ~ 0.70 | 기존 규칙(유지 조건) | **에뮬레이터 댓글창에서 0.292 확인**(기존 주석 값과 같음) |
| `reel_watch_player` < 0.15 | 새 규칙(PLAYER_MINIATURE) | 에뮬레이터 PiP 에서 0.077 |
| `reel_watch_player` < 0.15 + 컨테이너 2종 이상 전체화면 | 새 규칙(PLAYER_COLLAPSED_UNDER_PANEL) | **에뮬레이터 댓글창 끝까지 펼침**: 플레이어 0.014/0.004(사용자 로그 0.001), `reel_recycler`·`reel_watch_fragment_root`·`reel_player_page_container` 0.863 유지, `reel_player_overlay_root` 는 플레이어와 함께 줄어듦. PiP 상태 컨테이너는 재현 실패로 미확인(TODO: VERIFY ON DEVICE) |
| `reel_recycler`, `reel_watch_fragment_root`, `reel_player_page_container`, `reel_player_overlay_root` 전체화면 | 새 보조 신호 | 덤프 1쌍 + 에뮬레이터 쇼츠 화면에서 확인 |
| 세로 한 페이지 넘김 스크롤 이벤트 | 새 보조 신호 | 에뮬레이터 쇼츠 `reel_recycler` 이벤트는 **인덱스 없이(-1) dy 만** 준다. 화면 높이의 40% 이상 이동을 넘김으로 센다(관찰값 1개, dy=1162). 스와이프 자동화가 불안정해 반복 확인은 못 함 |
| `watch_player` / `player_view` | "일반 영상" 근거 | 광고 덤프 + **에뮬레이터 일반 영상에서 REGULAR_PLAYER 확인** |

판정:

| 조건 | 결과 |
|---|---|
| 플레이어 전체화면 + (컨테이너 또는 페이징) | SHORTFORM HIGH |
| 플레이어 전체화면만 | SHORTFORM MEDIUM (기존과 같은 진입) |
| 플레이어가 보이지만 면적비 0.15 미만 + 컨테이너 2종 이상 전체화면 | SHORTFORM_PARTIAL (PLAYER_COLLAPSED_UNDER_PANEL, 댓글창 펼침) |
| 플레이어가 보이지만 면적비 0.15 미만 | NOT_SHORTFORM (PLAYER_MINIATURE) |
| 플레이어가 보이고 면적비 0.15 이상 0.70 미만 | SHORTFORM_PARTIAL |
| 플레이어 없음 + 컨테이너 2종 이상 + 페이징 | SHORTFORM MEDIUM (플레이어 ID 가 바뀐 경우 대비) |
| 플레이어 없음 + 컨테이너 2종 이상 | SHORTFORM LOW (진입 안 함, 로그로 ID 변경 의심 가능) |
| 트리 잘림 | UNKNOWN |
| 일반 플레이어 보임 | NOT_SHORTFORM (REGULAR_PLAYER) |
| 그 외 | NOT_SHORTFORM |

텍스트·contentDescription·아이콘·좌표 상수는 쓰지 않는다. `reel_recycler` 는 덤프에서 `scrollable=false` 였으므로 스크롤 가능 여부도 근거로 쓰지 않는다.

### Instagram (`ig-0-experimental`, 기본 꺼짐)

저장소에 Instagram 덤프가 없어 Instagram 고유 ID 를 하나도 쓰지 않는다. 앱과 무관한 두 신호만 쓴다.
- RecyclerView/ViewPager 계열 노드가 화면의 80% 이상을 차지함
- 최근 15초 안의 세로 한 페이지 넘김이 2회 이상

두 조건이 모두 맞아야 MEDIUM 이다. pager 만 있으면 LOW 이고 기존 세션 유지에만 쓰인다. **실기기에서 전부 미검증**이다. 홈 피드도 전체 높이 리스트이므로, 큰 게시물 하나가 화면을 채운 채 두 번 넘어가면 오탐이 날 수 있다(위험 요소).

## 5. UI 변경에 취약한 지점

| # | 의존 | 깨질 때 | 완화 |
|---|---|---|---|
| 1 | `reel_watch_player` ID | 진입 경로가 컨테이너+페이징 경로로 줄어든다 | 보조 경로(MEDIUM), LOW 후보 로그 |
| 2 | `reel_*` 컨테이너 ID | 신뢰도가 HIGH→MEDIUM 으로 떨어질 뿐, 기존 진입은 유지 | — |
| 3 | 면적비 0.70 | Clear Screen 등으로 bounds 가 바뀌면 진입이 실패할 수 있다 | 미확인. 기기에서 면적비 로그 확인 |
| 4 | `isVisibleToUser` | 시트가 덮을 때 값이 어떻게 나오는지 미확인 | — |
| 5 | `flagReportViewIds` | 없으면 모든 ID 가 null 이 된다 | XML 과 코드에서 모두 설정 |
| 6 | 알림창/홈의 `rootInActiveWindow` 동작 | 원인을 구분할 수 없다 | grace 로 흡수 |
| 7 | 스크롤 이벤트 index 필드 | 페이징 신호가 0 이 된다 | YouTube 는 보조 신호일 뿐. Instagram 은 감지 불가 |

## 6. 성능·메모리

- YouTube 는 ID 검색 7회로 스냅샷을 만든다. 기존 방식(ID 검색 1회)보다 IPC 가 많다. 에뮬레이터 측정치는 중앙값 15ms, p90 117ms, 최대 402ms 이고 실기기는 미측정이다. 더 줄여야 하면 플레이어가 전체화면일 때 나머지 검색을 건너뛰는 방법이 있다.
- Instagram 순회는 너비 우선이고 최대 1,500 노드에서 멈춘다. 이벤트 스로틀 250ms 와 타이머 1초는 그대로다.
- 소요 시간은 `측정 nodes=… / Nms` 로그로 확인한다.
- 자식 노드는 복사 즉시 해제한다(API 33 미만 recycle). 스냅샷에는 노드 참조를 남기지 않는다.
- 스크롤 신호는 최근 32개만 보관한다. Handler 콜백과 prefs 리스너는 unbind/destroy 에서 해제한다.

## 7. 개입: 시청시간 임계값 + 화면 중앙 팝업 (2026-10-31)

- **시점**: 세션 시작 즉시가 아니라, 열린 세션의 확정 시청시간(`SHORTFORM_ESTIMATED` 누적)이 사용자 설정 임계값에 닿을 때 세션당 1회. 댓글창 시간은 센다. 대화상자(OVERLAY) 가림 시간과 `EXIT_PENDING` 시간은 세지 않는다. 선택지 30초(테스트용)/1/3/5/10분, 기본 3분(임시값). 재알림 쿨다운 30초는 그대로 둔다. 규칙: `intervention/WatchThresholdPolicy`. 임계값 0 이면 기존 동작(세션 시작 즉시)과 같아서 기존 회귀 테스트를 그대로 돌린다.
- **기준 단위**: 세션 단위만 있다. 이탈 grace(2초)보다 길게 나갔다 오면 새 세션이 시작되고 0부터 다시 센다. 일 누적 기준은 아직 없다.
- **방식**: 접근성 서비스의 `TYPE_ACCESSIBILITY_OVERLAY` 창으로 화면 중앙에 카드를 띄운다(`intervention/InterventionPopup`). 카드나 "앱 열기"를 누르면 앱 화면으로 이동하고, "닫기"를 누르면 팝업만 닫힌다. 바깥 터치는 아래 앱으로 그대로 전달되고 포커스를 가져가지 않는다. Activity 는 사용자가 탭했을 때만 연다. 세션이 끝나면 팝업도 지운다. 팝업을 끄거나 띄우지 못하면 기존 시스템 알림으로 대신하고, 둘 다 실패하면 쿨다운을 걸지 않는다.
- **명세와의 관계**: 명세 2-6항은 "직접 오버레이는 정책·플랫폼·사용성 검토를 통과한 뒤 별도 결정"이라고 한다. 이번 변경은 제품 소유자의 명시적 요청(2026-10-31)으로 넣었다. 사용자가 끌 수 있다(설정 스위치, 끄면 알림만 쓴다).
- **정책 리스크(미검토)**: 장애인 지원 목적이 아닌 앱이 접근성 API 로 다른 앱 위에 창을 띄우는 것은 Google Play 접근성 API 정책상 사용 선언·고지·심사 대상이다. 배포 전에 별도로 검토해야 한다. 대안은 `SYSTEM_ALERT_WINDOW`(사용자가 직접 허용) 또는 알림만 쓰는 방식이다.

## 사용 기록 저장 (2026-11-01)

| 항목 | 내용 |
|---|---|
| 저장소 | 기기 내 SQLite `wenit_usage.db` (`data/usage/UsageDatabase`, 플랫폼 `SQLiteOpenHelper` — 새 라이브러리 없음. Room 은 KSP 플러그인이 더 필요해 보류) |
| `sessions` | UUID, 패키지, 규칙 버전, 시작·끝(epoch ms), 상태(OPEN/CLOSED/ABORTED), 추정 시청·댓글창·가림·불확실 시간, 최대 신뢰도, 종료 이유·원인 코드, 이어짐 횟수 |
| `interventions` | UUID, 세션 id, 시각, 방식(POPUP/NOTIFICATION/FAILED/SUPPRESSED_BY_COOLDOWN), 그때 시청시간·임계값, 사용자 반응(OPEN_APP/DISMISS, 첫 반응만) |
| 저장 안 함 | 화면 원문, 댓글·알림 내용, 스크린샷, 노드 트리, 영상 정보 |
| 이어진 세션 | 같은 UUID 행을 갱신한다. 합산 중복 없음 |
| 중간 저장 | 열린 세션을 15초마다 OPEN 으로 덮어쓴다. 프로세스가 죽으면 다음 서비스 연결 때 ABORTED 로 닫는다(최대 약 15초 손실) |
| 시각 | 감지는 elapsedRealtime, 저장은 epoch ms. 세션 시작 때 한 번만 변환해 도중 시계 변경에 영향받지 않는다 |
| 일별 집계 | `DailyUsageCalculator`: 기기 현지 자정 기준. 자정을 넘긴 세션은 겹친 비율로 나눈다(근사). 세션 수는 시작한 날 |
| 스레드 | 서비스 쓰기는 전용 단일 스레드(`BackgroundUsageStore`), 화면 읽기는 화면의 백그라운드 스레드. 저장 실패는 감지·개입에 영향 없음 |
| 보관·삭제 | 90일 지난 기록은 서비스 연결 때 삭제(`USAGE_RETENTION_DAYS`, 임시값). 앱 화면 "기록 전체 삭제"(확인 대화상자) |
| 백업 | `data_extraction_rules.xml` / `backup_rules.xml` 로 이 DB 를 클라우드 백업·기기 이전에서 제외. 설정은 백업 허용 |
| 네트워크 | 없음 |
