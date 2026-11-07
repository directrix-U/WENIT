package com.example.shortsdetector.intervention

import com.example.shortsdetector.data.usage.SessionRecord
import com.example.shortsdetector.data.usage.SessionStatus

/**
 * 서비스가 개입 판단에 쓸 기록을 고른다. 시연 기록과 발표용 시드는 실제로 일어난 일이 아니므로 뺀다.
 * 시드를 실제 기록처럼 읽는 쪽은 통계 화면과 에이전트 입력뿐이다(명세의 is_seed).
 */
object LiveRecordFilter {

    /** 오늘 앞선 세션 중 "목표까지 M분" 계산에 넣을 세션 */
    fun finishedRealSessions(sessions: List<SessionRecord>): List<SessionRecord> =
        sessions.filter { it.status != SessionStatus.OPEN && !it.isDemo && !it.isSeed }
}
