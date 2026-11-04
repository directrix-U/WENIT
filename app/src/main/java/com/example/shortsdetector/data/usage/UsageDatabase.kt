package com.example.shortsdetector.data.usage

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.example.shortsdetector.intervention.InterventionPolicyCodec

/**
 * 기기 내 SQLite 저장소. 플랫폼 기본 API(SQLiteOpenHelper)만 쓴다 — 새 라이브러리 없음.
 * (Room 은 주석 처리기(KSP) 플러그인이 추가로 필요하고, 테이블 몇 개에는 이점이 작아 쓰지 않았다.)
 *
 * 백업: res/xml/backup_rules.xml, data_extraction_rules.xml 에서 이 DB 를 클라우드 백업·기기 이전에서 뺀다.
 * 동의 없는 외부 전송을 막기 위함(명세 3.1-6).
 */
class UsageDatabase private constructor(context: Context) :
    SQLiteOpenHelper(context.applicationContext, NAME, null, UsageSchema.VERSION), UsageStore {

    override fun onCreate(db: SQLiteDatabase) {
        // 새 설치도 업그레이드와 같은 경로를 지나게 해 두 스키마가 갈라지지 않게 한다.
        UsageSchema.CREATE_V1.forEach(db::execSQL)
        UsageSchema.MIGRATE_1_TO_2.forEach(db::execSQL)
        UsageSchema.MIGRATE_2_TO_3.forEach(db::execSQL)
    }

    /** SQLiteOpenHelper 가 트랜잭션으로 감싸 호출한다. 기존 기록은 지우지 않는다. */
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) UsageSchema.MIGRATE_1_TO_2.forEach(db::execSQL)
        if (oldVersion < 3) UsageSchema.MIGRATE_2_TO_3.forEach(db::execSQL)
    }

    override fun upsertSession(record: SessionRecord) {
        val v = ContentValues().apply {
            put("id", record.id)
            put("package_name", record.packageName)
            put("rule_version", record.ruleVersion)
            put("started_at_ms", record.startedAtMs)
            put("ended_at_ms", record.endedAtMs)
            put("status", record.status.name)
            put("estimated_watch_ms", record.estimatedWatchMs)
            put("partial_watch_ms", record.partialWatchMs)
            put("obscured_ms", record.obscuredMs)
            put("uncertain_ms", record.uncertainMs)
            put("max_confidence", record.maxConfidence)
            put("end_reason", record.endReason)
            put("exit_cause", record.exitCause)
            put("resume_count", record.resumeCount)
        }
        writableDatabase.insertWithOnConflict("sessions", null, v, SQLiteDatabase.CONFLICT_REPLACE)
    }

    override fun insertIntervention(record: InterventionRecord) {
        val v = ContentValues().apply {
            put("id", record.id)
            put("session_id", record.sessionId)
            put("occurred_at_ms", record.occurredAtMs)
            put("method", record.method.name)
            put("watch_ms", record.watchMs)
            put("threshold_ms", record.thresholdMs)
            put("user_action", record.userAction?.name)
            put("action_at_ms", record.actionAtMs)
            put("level", record.level)
            put("user_reason", record.userReason?.name)
            put("outcome", record.outcome?.name)
            put("is_demo", if (record.isDemo) 1 else 0)
        }
        writableDatabase.insertWithOnConflict("interventions", null, v, SQLiteDatabase.CONFLICT_IGNORE)
    }

    override fun setInterventionAction(id: String, action: InterventionAction, atMs: Long) {
        val v = ContentValues().apply {
            put("user_action", action.name)
            put("action_at_ms", atMs)
        }
        writableDatabase.update("interventions", v, "id = ? AND user_action IS NULL", arrayOf(id))
    }

    override fun setInterventionOutcome(id: String, outcome: InterventionOutcome, reason: InterventionUserReason?) {
        val v = ContentValues().apply {
            put("outcome", outcome.name)
            put("user_reason", reason?.name)
        }
        writableDatabase.update("interventions", v, "id = ? AND outcome IS NULL", arrayOf(id))
    }

    override fun insertPolicyChange(record: PolicyChangeRecord) {
        val v = ContentValues().apply {
            put("id", record.id)
            put("occurred_at_ms", record.occurredAtMs)
            put("source", record.source.name)
            put("previous_policy", record.previousPolicy?.let(InterventionPolicyCodec::encode))
            put("new_policy", InterventionPolicyCodec.encode(record.newPolicy))
            put("reason_text", record.reasonText)
            put("input_summary", record.inputSummary)
            put("status", record.status.name)
            put("model_id", record.modelId)
            put("is_demo", if (record.isDemo) 1 else 0)
        }
        writableDatabase.insertWithOnConflict("policy_changes", null, v, SQLiteDatabase.CONFLICT_IGNORE)
    }

    override fun setPolicyChangeStatus(id: String, status: PolicyChangeStatus) {
        val v = ContentValues().apply { put("status", status.name) }
        writableDatabase.update("policy_changes", v, "id = ?", arrayOf(id))
    }

    override fun ignoreUnansweredInterventions(): Int {
        val v = ContentValues().apply { put("outcome", InterventionOutcome.IGNORED.name) }
        return writableDatabase.update(
            "interventions", v,
            "outcome IS NULL AND level IS NOT NULL AND method IN (?, ?)",
            arrayOf(InterventionMethod.POPUP.name, InterventionMethod.NOTIFICATION.name)
        )
    }

    override fun abortOpenSessions(): Int {
        val v = ContentValues().apply { put("status", SessionStatus.ABORTED.name) }
        return writableDatabase.update("sessions", v, "status = ?", arrayOf(SessionStatus.OPEN.name))
    }

    override fun deleteBefore(cutoffMs: Long): Int {
        val db = writableDatabase
        db.beginTransaction()
        try {
            val n = db.delete(
                "sessions", "status != ? AND ended_at_ms < ?",
                arrayOf(SessionStatus.OPEN.name, cutoffMs.toString())
            )
            db.delete("interventions", "occurred_at_ms < ?", arrayOf(cutoffMs.toString()))
            db.delete("policy_changes", "occurred_at_ms < ?", arrayOf(cutoffMs.toString()))
            db.setTransactionSuccessful()
            return n
        } finally {
            db.endTransaction()
        }
    }

    override fun deleteAll() {
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.delete("sessions", null, null)
            db.delete("interventions", null, null)
            db.delete("policy_changes", null, null)
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    override fun sessionsOverlapping(fromMs: Long, toMs: Long): List<SessionRecord> =
        readableDatabase.query(
            "sessions", null, "started_at_ms < ? AND ended_at_ms >= ?",
            arrayOf(toMs.toString(), fromMs.toString()), null, null, "started_at_ms ASC"
        ).use { c -> buildList { while (c.moveToNext()) add(c.toSession()) } }

    override fun interventionsBetween(fromMs: Long, toMs: Long): List<InterventionRecord> =
        readableDatabase.query(
            "interventions", null, "occurred_at_ms >= ? AND occurred_at_ms < ?",
            arrayOf(fromMs.toString(), toMs.toString()), null, null, "occurred_at_ms ASC"
        ).use { c -> buildList { while (c.moveToNext()) add(c.toIntervention()) } }

    override fun recentPolicyChanges(limit: Int): List<PolicyChangeRecord> =
        readableDatabase.query(
            "policy_changes", null, null, null, null, null, "occurred_at_ms DESC", limit.toString()
        ).use { c -> buildList { while (c.moveToNext()) c.toPolicyChange()?.let(::add) } }

    override fun sessionCount(): Int =
        readableDatabase.rawQuery("SELECT COUNT(*) FROM sessions", null).use { c ->
            if (c.moveToFirst()) c.getInt(0) else 0
        }

    private fun Cursor.str(col: String): String? = getColumnIndexOrThrow(col).let { if (isNull(it)) null else getString(it) }
    private fun Cursor.long(col: String): Long = getLong(getColumnIndexOrThrow(col))
    private fun Cursor.longOrNull(col: String): Long? = getColumnIndexOrThrow(col).let { if (isNull(it)) null else getLong(it) }

    private fun Cursor.toSession() = SessionRecord(
        id = str("id")!!,
        packageName = str("package_name")!!,
        ruleVersion = str("rule_version"),
        startedAtMs = long("started_at_ms"),
        endedAtMs = long("ended_at_ms"),
        status = enumOr(str("status"), SessionStatus.ABORTED),
        estimatedWatchMs = long("estimated_watch_ms"),
        partialWatchMs = long("partial_watch_ms"),
        obscuredMs = long("obscured_ms"),
        uncertainMs = long("uncertain_ms"),
        maxConfidence = str("max_confidence") ?: "LOW",
        endReason = str("end_reason"),
        exitCause = str("exit_cause"),
        resumeCount = long("resume_count").toInt()
    )

    private fun Cursor.toIntervention() = InterventionRecord(
        id = str("id")!!,
        sessionId = str("session_id"),
        occurredAtMs = long("occurred_at_ms"),
        method = enumOr(str("method"), InterventionMethod.FAILED),
        watchMs = long("watch_ms"),
        thresholdMs = long("threshold_ms"),
        userAction = str("user_action")?.let { a -> InterventionAction.entries.firstOrNull { it.name == a } },
        actionAtMs = longOrNull("action_at_ms"),
        level = longOrNull("level")?.toInt(),
        userReason = str("user_reason")?.let { r -> InterventionUserReason.entries.firstOrNull { it.name == r } },
        outcome = str("outcome")?.let { o -> InterventionOutcome.entries.firstOrNull { it.name == o } },
        isDemo = long("is_demo") != 0L
    )

    /** 새 정책 값을 읽을 수 없는 행은 건너뛴다. */
    private fun Cursor.toPolicyChange(): PolicyChangeRecord? {
        val newPolicy = str("new_policy")?.let(InterventionPolicyCodec::decode) ?: return null
        return PolicyChangeRecord(
            id = str("id")!!,
            occurredAtMs = long("occurred_at_ms"),
            source = enumOr(str("source"), PolicyChangeSource.DEFAULT),
            previousPolicy = str("previous_policy")?.let(InterventionPolicyCodec::decode),
            newPolicy = newPolicy,
            reasonText = str("reason_text").orEmpty(),
            inputSummary = str("input_summary"),
            status = enumOr(str("status"), PolicyChangeStatus.REJECTED_OUT_OF_RANGE),
            modelId = str("model_id"),
            isDemo = long("is_demo") != 0L
        )
    }

    private inline fun <reified E : Enum<E>> enumOr(name: String?, fallback: E): E =
        enumValues<E>().firstOrNull { it.name == name } ?: fallback

    companion object {
        const val NAME = "wenit_usage.db"

        @Volatile private var instance: UsageDatabase? = null

        /** 앱 프로세스 하나에 하나만 연다(서비스와 화면이 같은 연결을 공유). */
        fun get(context: Context): UsageDatabase =
            instance ?: synchronized(this) { instance ?: UsageDatabase(context).also { instance = it } }
    }
}
