package com.example.shortsdetector.data.usage

/**
 * 사용 기록 DB 스키마와 마이그레이션 SQL. Android 의존성 없음.
 *
 * 버전 2: interventions 에 level/user_reason/outcome 추가, policy_changes 테이블 추가.
 * 새 컬럼은 전부 nullable 이다. 버전 1 기록은 단계·결과를 모르므로 값을 꾸며 넣지 않고 null 로 둔다.
 *
 * 버전 3: interventions·policy_changes 에 is_demo 추가. 버전 2 의 시연 정책 변경은 input_summary 로 알 수 있어 옮긴다.
 * 버전 2 의 시연 개입 기록은 구분할 표시가 없어 0 으로 남는다.
 *
 * 버전 4: sessions 에 is_demo 추가. 기존 세션은 구분할 수 없어 0 이다.
 *
 * 버전 5: sessions·interventions 에 is_seed 추가. 발표용 시드 기록 표시이고 기존 기록은 0 이다.
 */
object UsageSchema {

    const val VERSION = 5

    val CREATE_V1: List<String> = listOf(
        """
        CREATE TABLE sessions (
            id TEXT PRIMARY KEY NOT NULL,
            package_name TEXT NOT NULL,
            rule_version TEXT,
            started_at_ms INTEGER NOT NULL,
            ended_at_ms INTEGER NOT NULL,
            status TEXT NOT NULL,
            estimated_watch_ms INTEGER NOT NULL,
            partial_watch_ms INTEGER NOT NULL,
            obscured_ms INTEGER NOT NULL,
            uncertain_ms INTEGER NOT NULL,
            max_confidence TEXT NOT NULL,
            end_reason TEXT,
            exit_cause TEXT,
            resume_count INTEGER NOT NULL
        )
        """.trimIndent(),
        "CREATE INDEX idx_sessions_started ON sessions(started_at_ms)",
        "CREATE INDEX idx_sessions_ended ON sessions(ended_at_ms)",
        """
        CREATE TABLE interventions (
            id TEXT PRIMARY KEY NOT NULL,
            session_id TEXT,
            occurred_at_ms INTEGER NOT NULL,
            method TEXT NOT NULL,
            watch_ms INTEGER NOT NULL,
            threshold_ms INTEGER NOT NULL,
            user_action TEXT,
            action_at_ms INTEGER
        )
        """.trimIndent(),
        "CREATE INDEX idx_interventions_at ON interventions(occurred_at_ms)"
    )

    val MIGRATE_1_TO_2: List<String> = listOf(
        "ALTER TABLE interventions ADD COLUMN level INTEGER",
        "ALTER TABLE interventions ADD COLUMN user_reason TEXT",
        "ALTER TABLE interventions ADD COLUMN outcome TEXT",
        """
        CREATE TABLE policy_changes (
            id TEXT PRIMARY KEY NOT NULL,
            occurred_at_ms INTEGER NOT NULL,
            source TEXT NOT NULL,
            previous_policy TEXT,
            new_policy TEXT NOT NULL,
            reason_text TEXT NOT NULL,
            input_summary TEXT,
            status TEXT NOT NULL,
            model_id TEXT
        )
        """.trimIndent(),
        "CREATE INDEX idx_policy_changes_at ON policy_changes(occurred_at_ms)"
    )

    val MIGRATE_2_TO_3: List<String> = listOf(
        "ALTER TABLE interventions ADD COLUMN is_demo INTEGER NOT NULL DEFAULT 0",
        "ALTER TABLE policy_changes ADD COLUMN is_demo INTEGER NOT NULL DEFAULT 0",
        "UPDATE policy_changes SET is_demo = 1 WHERE input_summary = 'demo_mode'"
    )

    val MIGRATE_3_TO_4: List<String> = listOf(
        "ALTER TABLE sessions ADD COLUMN is_demo INTEGER NOT NULL DEFAULT 0"
    )

    val MIGRATE_4_TO_5: List<String> = listOf(
        "ALTER TABLE sessions ADD COLUMN is_seed INTEGER NOT NULL DEFAULT 0",
        "ALTER TABLE interventions ADD COLUMN is_seed INTEGER NOT NULL DEFAULT 0"
    )
}
