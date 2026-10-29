# 실기기 검증 가이드

자동화 테스트(`./gradlew testDebugUnitTest`)는 순수 Kotlin 엔진만 검증한다. 아래 항목은 **실기기에서 직접 확인해야** 하며, 확인 전까지는 "미검증"으로 둔다.

## 1. 설치와 설정

```bash
./gradlew installDebug                    # 또는 Android Studio 에서 Run
adb logcat -c && adb logcat -s ShortsDetector
```

1. 앱 실행 → **접근성 설정 열기** → "쇼츠 감지 프로토타입" 켜기
2. Android 13 이상은 **알림 권한 요청** 버튼으로 허용
3. "감지 대상 앱"에서 YouTube 는 기본 켜짐, Instagram 은 실험을 원할 때만 켠다
4. 화면의 "현재 감지 상태"는 서비스가 연결된 경우에만 표시된다(onResume 시점 값)

## 2. 먼저 기록할 것

| 항목 | 명령 |
|---|---|
| 기기 / Android | `adb shell getprop ro.product.model`, `adb shell getprop ro.build.version.release` |
| YouTube 버전 | `adb shell dumpsys package com.google.android.youtube \| grep versionName` |
| Instagram 버전 | `adb shell dumpsys package com.instagram.android \| grep versionName` |
| 규칙 버전 | 서비스 연결 로그의 `yt=…, ig=…` |

## 3. 로그 읽는 법

```
D ShortsDetector: 측정 nodes=131 / 6ms
D ShortsDetector: [이벤트] CANDIDATE→CANDIDATE hits=2/3 SHORTFORM/HIGH/PLAYER_FULLSCREEN 면적비=0.863 [...]
I ShortsDetector: [타이머] ★ 숏폼 감지 #1 pkg=com.google.android.youtube 신뢰도=HIGH
D ShortsDetector: [타이머] SHORTFORM_ESTIMATED→TEMPORARILY_OBSCURED SHORTFORM_PARTIAL/MEDIUM/PLAYER_PARTIAL 면적비=0.292 [...]
I ShortsDetector: 세션 #1 종료: EXIT_CONFIRMED(ROOT_UNAVAILABLE) pkg=… 추정시청=…ms 가림=…ms 불확실=…ms
```

로그에는 화면 원문(제목, 댓글, 채널명)이 나오지 않는다. 원문이 보이면 버그이므로 보고한다.

## 4. 시나리오 체크리스트 (기기·앱 버전마다 반복)

| # | 시나리오 | 기대 | 확인할 로그 | 결과 |
|---|---|---|---|---|
| Y1 | Shorts 진입 후 가만히 보기 | 약 0.75~3초 안에 알림 1회 | `★ 숏폼 감지 #1`, `PLAYER_FULLSCREEN` | 미검증 |
| Y2 | Shorts 연속 스와이프 20회 | 알림 1회, 세션 유지 | `VERTICAL_PAGING` 신호가 찍히는지(가설 검증) | 미검증 |
| Y3 | 댓글창 열기 → 30초 → 닫기 | 세션 유지, 알림 유지 | `TEMPORARILY_OBSCURED`, 면적비 값 | 미검증 |
| Y4 | 설명 패널 열기/닫기 | Y3 과 같음 | 면적비, `PLAYER_PARTIAL` 인지 | 미검증 |
| Y5 | 알림창 내렸다 올리기(2초 미만) | 세션 유지 | `EXIT_PENDING` 뒤 `SHORTFORM_ESTIMATED` | 미검증 |
| Y6 | 알림창 5초 이상 | 세션 종료 가능(정상) | 종료 원인 | 미검증 |
| Y7 | Clear Screen 모드 | 감지 유지 또는 한계 기록 | 면적비, 신호 플래그 | 미검증 |
| Y8 | 홈 버튼 | 약 3초 안에 알림 사라짐 | `EXIT_CONFIRMED(ROOT_UNAVAILABLE)` | 미검증 |
| Y9 | 최근 앱으로 다른 앱 전환 | Y8 과 같음 | 종료 원인 | 미검증 |
| Y10 | 화면 끄기 | 종료 또는 STALE | 종료 원인 | 미검증 |
| Y11 | YouTube 홈/검색/구독 | 알림 없음 | `NO_SHORTFORM_SIGNAL` | 미검증 |
| Y12 | 일반 긴 영상(광고 없음), 전체화면 포함 | 알림 없음 | `REGULAR_PLAYER` 여부 | 미검증 |
| Y13 | 홈 피드 쇼츠 선반 미리보기 | 알림 없음 | `PLAYER_PARTIAL` 이 대기 상태에서 무시되는지 | 미검증 |
| Y14 | 숏폼 종료 후 30초 안에 재진입 | 새 세션, 알림 생략 | `쿨다운 — 알림 생략` | 미검증 |
| Y15 | 접근성 서비스 OFF | 감지 중단, 화면에 꺼짐 표시 | `onUnbind` | 미검증 |
| Y16 | 알림 권한 OFF | 감지 로그는 남고 알림만 실패 | `POST_NOTIFICATIONS 권한이 없어` | 미검증 |
| I1 | (Instagram 켬) Reels 탭 스와이프 | 2~3회 넘긴 뒤 알림 | `PAGER_WITH_PAGING` | 미검증 |
| I2 | 홈 피드 스크롤 | 알림 없음 | `PAGER_ONLY` 로 남는지 | 미검증 |
| I3 | DM, 스토리 | 알림 없음 | 가로 넘김이 세지 않는지 | 미검증 |
| I4 | Reels 댓글 시트 | 세션 유지 | 상태 | 미검증 |

결과를 기록할 때 오탐/미탐 횟수와 반복 수를 함께 적는다(명세 6.3). 2026-10-30 에뮬레이터 결과는 `docs/VERIFICATION_LOG.md` 에 있다(실기기 결과 아님).

에뮬레이터·adb 로 자동화할 때 주의할 점:
- 이 서비스가 "접근성 버튼" 단축키로 등록돼 있으면 화면 오른쪽 끝을 탭할 때 서비스가 꺼질 수 있다.
- Git Bash 에서 `adb shell ... /sdcard/...` 를 쓰면 경로가 바뀐다. `MSYS_NO_PATHCONV=1` 을 설정한다.
- 로그인하지 않은 YouTube 에서 싫어요 등 일부 버튼은 로그인 대화상자를 띄운다(`OVERLAY` 시나리오로는 쓸 수 있다).

## 5. UI 덤프 수집과 fixture 만들기

덤프에는 영상 제목·채널명·상품 정보 같은 **시청 기록**이 들어간다. 다음 규칙을 지킨다.

1. 덤프는 저장소의 `dumps/` 폴더에만 둔다. `.gitignore` 로 `/dumps/` 와 `*_dump.xml` 은 커밋되지 않는다.
   ```bash
   mkdir -p dumps
   adb shell uiautomator dump /sdcard/window_dump.xml
   adb pull /sdcard/window_dump.xml dumps/shorts_comments_2026-11-01.xml
   adb shell rm /sdcard/window_dump.xml
   ```
2. 원문을 제거한 구조 fixture 로 바꾼 뒤에만 테스트에 쓴다.
   ```bash
   python -I tools/sanitize_uiautomator_dump.py dumps/shorts_comments_2026-11-01.xml \
     app/src/test/resources/fixtures/youtube/shorts_comments_2026-11-01.tsv \
     "source: 2026-11-01, <기기>, Android <버전>, YouTube <버전>. Shorts with comments open."
   ```
3. 만든 TSV 에 text/content-desc 가 없는지 확인한 뒤 커밋한다.

### 기존 덤프 2개의 처리

- `shorts_dump.xml`, `video_dump.xml` (2026-09-27): 프로젝트 루트에 **원본 그대로** 두었다. Git 에는 포함하지 않는다.
- 원문을 제거한 버전이 `app/src/test/resources/fixtures/youtube/` 의 TSV 2개다.
- `video_dump.xml` 은 일반 영상이 아니라 **프리롤 광고 재생 중인 시청 화면**이다. 광고가 없는 일반 영상 덤프를 새로 받아 fixture 로 추가해야 한다.
- 원본이 더 필요 없으면 사용자가 직접 삭제하거나 저장소 밖 개인 폴더로 옮긴다.

## 6. ID 가 바뀌었다고 의심될 때

1. Shorts 화면에서 `REEL_CONTAINERS_ONLY` / `REEL_CONTAINERS_WITH_PAGING` 로그가 나오면 `reel_watch_player` 가 사라진 것이다.
2. 덤프를 받아 위 절차로 fixture 를 만들고, 새 ID 를 `YouTubeViewIds.kt` 에 반영한다.
3. **기존 fixture 테스트를 지우지 않고** 새 fixture 테스트를 추가한다. 그다음 `./gradlew testDebugUnitTest` 를 실행한다.
