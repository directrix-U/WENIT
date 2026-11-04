package com.example.shortsdetector.data.usage

import android.util.Log
import com.example.shortsdetector.ShortsDetectionService
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * 쓰기를 전용 단일 스레드에서 순서대로 실행하는 래퍼. 서비스(메인 스레드)는 이것만 쓴다.
 *
 *  - 디스크 I/O 로 접근성 콜백이 막히지 않게 한다.
 *  - 단일 스레드라 쓰기 순서가 호출 순서와 같다(OPEN → CLOSED 가 뒤집히지 않음).
 *  - 저장 실패는 여기서 격리한다. 감지·개입은 저장 실패와 무관하게 계속 동작한다. 로그에는 예외 종류만 남긴다.
 *
 * 서비스 읽기는 [read] 를 쓴다. 화면은 자체 백그라운드 작업에서 [UsageDatabase] 를 직접 읽는다.
 */
class BackgroundUsageStore(private val delegate: UsageStore) : UsageStore by delegate {

    private val executor: ExecutorService = Executors.newSingleThreadExecutor { r ->
        Thread(r, "wenit-usage-writer").apply { isDaemon = true }
    }

    private fun write(what: String, block: () -> Unit) {
        try {
            executor.execute {
                try {
                    block()
                } catch (e: RuntimeException) {
                    Log.w(ShortsDetectionService.TAG, "기록 저장 실패($what): ${e.javaClass.simpleName}")
                }
            }
        } catch (e: RuntimeException) {
            // RejectedExecutionException(종료 후 호출) 등
            Log.w(ShortsDetectionService.TAG, "기록 저장 예약 실패($what): ${e.javaClass.simpleName}")
        }
    }

    override fun upsertSession(record: SessionRecord) = write("session") { delegate.upsertSession(record) }
    override fun insertIntervention(record: InterventionRecord) = write("intervention") { delegate.insertIntervention(record) }
    override fun setInterventionAction(id: String, action: InterventionAction, atMs: Long) =
        write("action") { delegate.setInterventionAction(id, action, atMs) }
    override fun setInterventionOutcome(id: String, outcome: InterventionOutcome, reason: InterventionUserReason?) =
        write("outcome") { delegate.setInterventionOutcome(id, outcome, reason) }
    override fun ignoreUnansweredInterventions(): Int { write("ignore") { delegate.ignoreUnansweredInterventions() }; return 0 }
    override fun insertPolicyChange(record: PolicyChangeRecord) = write("policyChange") { delegate.insertPolicyChange(record) }
    override fun setPolicyChangeStatus(id: String, status: PolicyChangeStatus) =
        write("policyStatus") { delegate.setPolicyChangeStatus(id, status) }
    override fun abortOpenSessions(): Int { write("abort") { delegate.abortOpenSessions() }; return 0 }
    override fun deleteBefore(cutoffMs: Long): Int { write("prune") { delegate.deleteBefore(cutoffMs) }; return 0 }
    override fun deleteSeedRecords() = write("deleteSeed") { delegate.deleteSeedRecords() }
    override fun deleteAll() = write("deleteAll") { delegate.deleteAll() }

    /** 쓰기와 같은 스레드에서 읽는다(앞선 쓰기가 끝난 뒤 실행). [block] 은 결과를 메인 스레드로 넘겨야 한다. */
    fun read(block: (UsageStore) -> Unit) = write("read") { block(delegate) }

    /** 남은 쓰기를 마저 실행하고 스레드를 닫는다. */
    fun shutdown() = executor.shutdown()
}
