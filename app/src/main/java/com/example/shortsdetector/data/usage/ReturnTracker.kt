package com.example.shortsdetector.data.usage

/**
 * 스스로 멈춘 개입 뒤 다시 돌아왔는지 기록한다. 저장 스레드에서 호출한다. Android 의존성 없음.
 *
 *  - 쇼츠로 돌아옴: 다음 숏폼 세션이 시작되거나 이어지면 [InterventionReturn.SHORTS] 와 걸린 시간을 남긴다.
 *  - 앱만 엶: 대상 앱을 열었지만 숏폼은 보지 않은 경우. 걸린 시간만 남기고 계속 지켜본다.
 *  - [WINDOW_MS] 안에 쇼츠로 돌아오지 않으면 앱을 열었는지에 따라 [InterventionReturn.APP_ONLY] 나
 *    [InterventionReturn.NONE] 으로 끝낸다.
 *
 * 시각과 간격만 저장한다. 화면 내용은 다루지 않는다.
 */
class ReturnTracker(private val store: UsageStore) {

    fun onShortsReturned(nowMs: Long) {
        pending(nowMs).forEach {
            store.setInterventionReturn(it.id, InterventionReturn.SHORTS, gap(it, nowMs), it.appReturnMs)
        }
    }

    fun onTargetAppOpened(nowMs: Long) {
        pending(nowMs).filter { it.appReturnMs == null }.forEach {
            store.setInterventionReturn(it.id, null, null, gap(it, nowMs))
        }
    }

    /** 기다릴 시간이 지난 기록을 끝낸다. */
    fun sweep(nowMs: Long) {
        pending(nowMs)
    }

    /** 시간이 지난 기록은 끝내고 아직 기다리는 기록을 돌려준다. */
    private fun pending(nowMs: Long): List<InterventionRecord> {
        val (expired, open) = store.interventionsAwaitingReturn().partition { gap(it, nowMs) >= WINDOW_MS }
        expired.forEach {
            val status = if (it.appReturnMs != null) InterventionReturn.APP_ONLY else InterventionReturn.NONE
            store.setInterventionReturn(it.id, status, null, it.appReturnMs)
        }
        return open
    }

    private fun gap(record: InterventionRecord, nowMs: Long): Long =
        (nowMs - (record.actionAtMs ?: record.occurredAtMs)).coerceAtLeast(0L)

    companion object {
        /** "하루 동안 돌아오지 않음" 의 기준 */
        const val WINDOW_MS = 24L * 60 * 60 * 1000
    }
}
