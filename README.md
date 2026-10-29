# WENIT! 숏폼 감지 (실험 앱)

YouTube Shorts(그리고 실험적으로 Instagram Reels) 재생 화면을 접근성 서비스로 **추정**해서 **로컬 알림만** 띄운다.
차단, 사용시간 저장, 통계, AI 코칭은 아직 범위 밖이다. 감지는 추정이며 100% 정확하다고 주장하지 않는다.

- 제품 명세: `docs/WENIT_PROJECT_SPEC.md`
- 감지 구조와 상태 모델: `docs/DETECTION_ARCHITECTURE.md`
- 실기기 검증 절차: `docs/DEVICE_TEST_GUIDE.md`
- 검증 기록(자동화/에뮬레이터/실기기 구분): `docs/VERIFICATION_LOG.md`
- 리팩터링 이전 상태: Git 태그 `baseline-v0` (이전 README 포함)

## 구성

| 위치 | 역할 |
|---|---|
| `ShortsDetectionService.kt` | 접근성 이벤트 수신, 스로틀, 감시 타이머, 엔진 결과대로 알림 게시/취소 |
| `detection/DetectionEngine.kt` | 스냅샷 → 어댑터 → 상태 머신 → 알림 쿨다운 (순수 Kotlin) |
| `detection/adapters/youtube/` | `YouTubeShortsAdapter`, 기존 판정 규칙 `LegacyShortsPlayerRule`, 관찰된 ID `YouTubeViewIds` |
| `detection/adapters/instagram/` | `InstagramReelsAdapter` (실험적, 실기기 미검증, 기본 꺼짐) |
| `detection/state/` | `ShortformStateMachine`, 조정값 `DetectionTuning` |
| `detection/features/` | 노드 → 구조 스냅샷(`AccessibilitySnapshotter`, 원문 미복사), 스크롤 패턴 추적 |
| `intervention/NotificationGate.kt` | 재알림 쿨다운 |
| `data/DetectionPreferences.kt` | 감지 대상 앱 on/off |
| `DetectionConfig.kt` | 임계값 상수 (`TODO: VERIFY ON DEVICE` 표시 포함) |
| `ShortsNotifier.kt`, `MainActivity.kt` | 알림 / 권한·상태·대상 앱 화면 |
| `app/src/test/` | JVM 단위 테스트와 구조 전용 fixture |
| `tools/sanitize_uiautomator_dump.py` | UI 덤프에서 원문을 지우고 구조만 남겨 fixture 로 변환 |

## 빌드·테스트

```bash
./gradlew assembleDebug          # APK
./gradlew testDebugUnitTest      # 감지 엔진 단위 테스트
./gradlew lintDebug
```

환경: minSdk 26 / compileSdk 37 / targetSdk 37, AGP 9.3.2(Kotlin 내장), Gradle 9.5.0, JUnit 4.13.2(테스트 전용).
AGP 9 는 Kotlin 지원이 내장되어 있어 `org.jetbrains.kotlin.android` 플러그인을 따로 적용하지 않는다.

## 실행

1. `./gradlew installDebug` 또는 Android Studio 에서 실행
2. 앱에서 **접근성 설정 열기** → "쇼츠 감지 프로토타입" 켜기
3. Android 13 이상은 **알림 권한 요청**
4. 유튜브에서 쇼츠 재생 → "숏폼 시청 추정" 알림 확인
5. `adb logcat -s ShortsDetector` 로 상태 전이와 근거 코드 확인 (자세한 내용은 `docs/DEVICE_TEST_GUIDE.md`)

## 개인정보

- 화면의 글자·댓글·메시지 원문은 읽어 저장하지 않는다. 로그에는 상태, 근거 코드, 면적비만 남는다.
- 네트워크 전송은 없다. 감지 결과도 아직 저장하지 않는다.
- UI 덤프 원본은 커밋하지 않는다(`.gitignore`). 테스트에는 원문을 지운 구조 fixture 만 쓴다.
- Google Play 배포 전에 Accessibility API 선언·고지·동의 요건을 별도로 검토해야 한다. 이 앱은 `isAccessibilityTool` 을 설정하지 않는다.
