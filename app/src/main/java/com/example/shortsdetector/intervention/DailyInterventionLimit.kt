package com.example.shortsdetector.intervention

/** 하루 최대 개입 수. 날짜가 바뀌면 0 부터 센다. 날짜는 호출하는 쪽이 현지 기준 값으로 넘긴다. */
class DailyInterventionLimit(var maxPerDay: Int) {

    private var day: Long? = null
    private var count = 0

    private fun countOn(today: Long) = if (day == today) count else 0

    fun canIntervene(today: Long): Boolean = countOn(today) < maxPerDay

    fun record(today: Long) {
        count = countOn(today) + 1
        day = today
    }

    fun reset() {
        day = null
        count = 0
    }

    /** 저장 기록에서 복구한 값. 복구 전에 이미 센 수가 더 크면 그 값을 유지한다. */
    fun seed(today: Long, shown: Int) {
        count = maxOf(countOn(today), shown)
        day = today
    }
}
