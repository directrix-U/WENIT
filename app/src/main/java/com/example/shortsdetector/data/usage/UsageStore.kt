package com.example.shortsdetector.data.usage

/**
 * 사용 기록 저장소. 구현은 기기 내 SQLite([UsageDatabase]) 하나이고, 테스트는 [InMemoryUsageStore] 를 쓴다.
 * 네트워크 전송 기능은 없다.
 *
 * 호출 스레드: 구현은 동기식이다. 메인 스레드에서 부르지 않는다([BackgroundUsageStore] 참고).
 */
interface UsageStore {
    /** 같은 id 가 있으면 덮어쓴다. */
    fun upsertSession(record: SessionRecord)

    fun insertIntervention(record: InterventionRecord)

    /** 이미 반응이 기록된 개입은 바꾸지 않는다(첫 반응만 남김). */
    fun setInterventionAction(id: String, action: InterventionAction, atMs: Long)

    /** 이미 결과가 기록된 개입은 바꾸지 않는다(첫 결과만 남김). */
    fun setInterventionOutcome(id: String, outcome: InterventionOutcome, reason: InterventionUserReason?)

    /**
     * 보여 줬지만 결과가 없는 개입을 IGNORED 로 닫는다(프로세스 종료 등으로 끝맺지 못한 경우). 닫은 개수를 돌려준다.
     * 쿨다운·상한으로 생략했거나 실패한 기록, 단계 개념이 없던 DB 버전 1 기록은 건드리지 않는다.
     */
    fun ignoreUnansweredInterventions(): Int

    /** 같은 id 가 있으면 무시한다. */
    fun insertPolicyChange(record: PolicyChangeRecord)

    fun setPolicyChangeStatus(id: String, status: PolicyChangeStatus)

    /** OPEN 으로 남은 세션을 ABORTED 로 닫는다. 닫은 개수를 돌려준다. */
    fun abortOpenSessions(): Int

    /** [cutoffMs] 이전에 끝난 세션과 그 이전의 개입·정책 변경 기록을 지운다(보관 기간). 지운 세션 수. */
    fun deleteBefore(cutoffMs: Long): Int

    /** 발표용 시드 기록만 지운다. */
    fun deleteSeedRecords()

    /** 발표용 시드 기록이 하나라도 있으면 true */
    fun hasSeedRecords(): Boolean

    /** 모든 사용 기록을 지운다(사용자 요청). 설정값은 지우지 않는다. */
    fun deleteAll()

    /** [fromMs, toMs) 와 겹치는 세션. 시작 시각 순. */
    fun sessionsOverlapping(fromMs: Long, toMs: Long): List<SessionRecord>

    fun interventionsBetween(fromMs: Long, toMs: Long): List<InterventionRecord>

    /** 최근 정책 변경. 새것부터. */
    fun recentPolicyChanges(limit: Int): List<PolicyChangeRecord>

    fun sessionCount(): Int
}

/** JVM 테스트용·폴백용 메모리 저장소. 스레드 안전. */
class InMemoryUsageStore : UsageStore {
    private val sessions = LinkedHashMap<String, SessionRecord>()
    private val interventions = LinkedHashMap<String, InterventionRecord>()
    private val policyChanges = LinkedHashMap<String, PolicyChangeRecord>()

    @Synchronized override fun upsertSession(record: SessionRecord) { sessions[record.id] = record }

    @Synchronized override fun insertIntervention(record: InterventionRecord) {
        interventions.putIfAbsent(record.id, record)
    }

    @Synchronized override fun setInterventionAction(id: String, action: InterventionAction, atMs: Long) {
        val r = interventions[id] ?: return
        if (r.userAction == null) interventions[id] = r.copy(userAction = action, actionAtMs = atMs)
    }

    @Synchronized override fun setInterventionOutcome(
        id: String,
        outcome: InterventionOutcome,
        reason: InterventionUserReason?
    ) {
        val r = interventions[id] ?: return
        if (r.outcome == null) interventions[id] = r.copy(outcome = outcome, userReason = reason)
    }

    @Synchronized override fun ignoreUnansweredInterventions(): Int {
        val shown = setOf(InterventionMethod.POPUP, InterventionMethod.NOTIFICATION)
        val open = interventions.values.filter { it.outcome == null && it.level != null && it.method in shown }
        open.forEach { interventions[it.id] = it.copy(outcome = InterventionOutcome.IGNORED) }
        return open.size
    }

    @Synchronized override fun insertPolicyChange(record: PolicyChangeRecord) {
        policyChanges.putIfAbsent(record.id, record)
    }

    @Synchronized override fun setPolicyChangeStatus(id: String, status: PolicyChangeStatus) {
        policyChanges[id]?.let { policyChanges[id] = it.copy(status = status) }
    }

    @Synchronized override fun abortOpenSessions(): Int {
        val open = sessions.values.filter { it.status == SessionStatus.OPEN }
        open.forEach { sessions[it.id] = it.copy(status = SessionStatus.ABORTED) }
        return open.size
    }

    @Synchronized override fun deleteBefore(cutoffMs: Long): Int {
        val old = sessions.values.filter { it.status != SessionStatus.OPEN && it.endedAtMs < cutoffMs }
        old.forEach { sessions.remove(it.id) }
        interventions.values.removeAll { it.occurredAtMs < cutoffMs }
        policyChanges.values.removeAll { it.occurredAtMs < cutoffMs }
        return old.size
    }

    @Synchronized override fun deleteSeedRecords() {
        sessions.values.removeAll { it.isSeed }
        interventions.values.removeAll { it.isSeed }
    }

    @Synchronized override fun hasSeedRecords() =
        sessions.values.any { it.isSeed } || interventions.values.any { it.isSeed }

    @Synchronized override fun deleteAll() { sessions.clear(); interventions.clear(); policyChanges.clear() }

    @Synchronized override fun sessionsOverlapping(fromMs: Long, toMs: Long): List<SessionRecord> =
        sessions.values.filter { it.startedAtMs < toMs && it.endedAtMs >= fromMs }.sortedBy { it.startedAtMs }

    @Synchronized override fun interventionsBetween(fromMs: Long, toMs: Long): List<InterventionRecord> =
        interventions.values.filter { it.occurredAtMs in fromMs until toMs }.sortedBy { it.occurredAtMs }

    @Synchronized override fun recentPolicyChanges(limit: Int): List<PolicyChangeRecord> =
        policyChanges.values.sortedByDescending { it.occurredAtMs }.take(limit)

    @Synchronized override fun sessionCount(): Int = sessions.size

    @Synchronized fun session(id: String): SessionRecord? = sessions[id]

    @Synchronized fun allSessions(): List<SessionRecord> = sessions.values.toList()

    @Synchronized fun allInterventions(): List<InterventionRecord> = interventions.values.toList()
}
