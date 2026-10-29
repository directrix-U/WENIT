# 쇼츠 감지 프로토타입 (1단계: 감지 + 알림)

유튜브 쇼츠 재생 화면을 접근성 서비스로 감지해서 **로컬 알림만** 띄우는 프로토타입.
차단, 스크롤 간격 측정, 사용시간 집계는 이번 범위가 아니다.

## 구성

| 파일 | 역할 |
|---|---|
| `DetectionConfig.kt` | 임계값 상수 모음. 튜닝은 여기만 고치면 된다 |
| `ShortsDetectionService.kt` | 접근성 이벤트 수신, 3단계 필터, rising-edge 래치 |
| `ImmersiveNodeAnalyzer.kt` | 트리 1회 순회(O(n))로 면적비 / 자손 수 / depth 측정 |
| `ShortsNotifier.kt` | 알림 채널 + 알림 발행, POST_NOTIFICATIONS 확인 |
| `MainActivity.kt` | 상태 표시 + 접근성 설정 이동 + 알림 권한 요청 |
| `res/xml/accessibility_service_config.xml` | 이벤트 타입, packageNames, notificationTimeout |

## 감지 로직 (3단계)

1. **패키지 필터** — `accessibility_service_config.xml` 의 `android:packageNames` 로
   `com.google.android.youtube` 이벤트만 OS 레벨에서 받는다.
   (`onServiceConnected()` 에서 코드로도 한 번 더 지정)
2. **화면 점유율** — `getRootInActiveWindow()` 로 얻은 트리를 순회하며 각 노드의
   `getBoundsInScreen()` 면적을 화면 면적과 비교, 80% 이상이면 후보로 수집.
3. **트리 단순성** — 후보 서브트리의 자손 노드 수와 depth 를 재고, 임계치 이하이면
   "단일 몰입 콘텐츠 = 쇼츠 재생 화면"으로 판정.

후보가 여러 개면(루트 컨테이너도 항상 면적 조건을 통과하므로) **자손 수가 가장 적은
= 가장 안쪽 전체화면 노드**를 대표로 골라 판정한다.

## 알림 발생 규칙

`shortsActive` 래치를 두고 **false → true 전환에서만** 알림을 1회 띄운다.

- 조건 유지 중: 알림 없음
- 조건 이탈(트리가 복잡해짐 / 후보 소멸 / 활성 창이 유튜브가 아님): 래치 해제
- 이탈 후 재충족: 다시 알림

**주의** — `packageNames` 로 OS 레벨 필터를 걸었기 때문에 유튜브를 완전히 벗어나면
이벤트 자체가 오지 않아 "조건이 깨졌다"는 걸 이벤트로는 알 수 없다. 그래서
`STATE_STALE_MS`(기본 15초) 동안 유효한 분석이 없으면 래치를 강제로 푼다.
이 값이 너무 짧으면 정지 화면에서 중복 알림이 뜰 수 있으니 실측 후 조정할 것.

## 디바운스

- OS 레벨: `notificationTimeout="300"` (config XML + 코드)
- 앱 레벨: `MIN_ANALYSIS_INTERVAL_MS = 250` — 트리 순회 자체의 최소 간격
- 안전장치: `MAX_NODES_VISITED = 1500` — 비정상적으로 큰 트리에서 ANR 방지

## 임계값 튜닝

임계값은 **실측 전이라 넉넉하게** 잡아 뒀다 (`descendants <= 60`, `depth <= 10`).

```bash
adb logcat -s ShortsDetector
```

```
D ShortsDetector: 측정 areaRatio=0.998 descendants=42 depth=7 viewId=... simple=true / 11ms
I ShortsDetector: ★ 쇼츠 감지 #1 areaRatio=0.998 descendants=42 depth=7 ...
I ShortsDetector: 감지 상태 해제: 트리 단순성 조건 이탈 (descendants=118, depth=13)
```

1. 쇼츠 재생 화면에서 찍히는 값 범위를 기록
2. 홈 / 검색 / 일반 영상 화면에서 찍히는 값 범위를 기록
3. 두 분포가 갈리는 지점으로 `MAX_DESCENDANT_COUNT` / `MAX_SUBTREE_DEPTH` 를 낮춘다

## 실행 방법

1. Android Studio 로 열고 실행 (`assembleDebug` 확인 완료)
2. 앱에서 **접근성 설정 열기** → 목록에서 "쇼츠 감지 프로토타입" 켜기
3. Android 13+ 는 **알림 권한 요청** 버튼으로 `POST_NOTIFICATIONS` 허용
4. 유튜브에서 쇼츠 재생 → "쇼츠 감지됨" 알림 확인

## 빌드 환경

`minSdk 26 / compileSdk 37 / targetSdk 37`, AGP 9.3.2, Gradle 9.5.0.
AGP 9 는 Kotlin 지원이 내장되어 있어 `org.jetbrains.kotlin.android` 플러그인을
따로 적용하지 않는다 (적용하면 `kotlin` 확장 중복으로 실패).

## 다음 단계 (이번 범위 밖)

- 스크롤 간격 측정 + 5회 연속 조건
- 차단 화면(오버레이)
- 인스타그램 릴스 대응
- UsageStatsManager 기반 사용시간 집계
