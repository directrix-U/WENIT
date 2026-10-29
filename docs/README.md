# WENIT! Claude Code 계획 패키지

## 파일

- `WENIT_PROJECT_SPEC.md`: 제품·기술·검증의 기준 명세서
- `CLAUDE.md`: Claude Code가 저장소에서 항상 따라야 할 개발 규칙
- `CLAUDE_CODE_PROMPTS.md`: 단계별 작업 지시문(Phase 0부터 순서대로 사용)

## 사용 순서

1. 이 패키지를 기존 Android 프로젝트의 루트에 복사한다. 기존 앱을 먼저 백업하거나 Git commit을 만든다.
2. `CLAUDE.md`와 `WENIT_PROJECT_SPEC.md`를 읽게 한다.
3. `CLAUDE_CODE_PROMPTS.md`의 **Phase 0 — 저장소 감사(코드 수정 금지)** 지시문을 먼저 실행한다.
4. 감사 결과를 확인하고 Phase 1부터 한 단계씩 진행한다. Phase 0 결과도 보기 전에 전체 앱 재작성을 지시하지 않는다.
5. 매 단계마다 실제 기기에서 YouTube/Instagram 앱 버전, Android 버전, 감지 결과를 기록한다.

## 주의

이 패키지는 기획 및 구현 지침이다. 앱의 실제 감지 정확도나 Google Play 배포 승인을 보증하지 않는다. 접근성 API와 백그라운드 동작은 출시 전에 최신 Android 및 Google Play 정책을 다시 검토해야 한다.
