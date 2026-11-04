package com.example.shortsdetector.data.usage

/**
 * 사용 기록 DB 스키마와 마이그레이션 SQL. Android 의존성 없음.
 *
 * 버전 2: interventions 에 level/user_reason/outcome 추가, policy_changes 테이블 추가.
 * 새 컬럼은 전부 nullable 이다. 버전 1 기록은 단계·결과를 모르므로 값을 꾸며 넣지 않고 null 로 둔다.
 */
object UsageSchema {

    const val VERSION = 2

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
}
