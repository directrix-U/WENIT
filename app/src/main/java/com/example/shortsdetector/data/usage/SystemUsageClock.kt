package com.example.shortsdetector.data.usage

import android.os.SystemClock

/** 실제 기기 시계. 감지 엔진은 elapsedRealtime 을 쓰고, 저장은 epoch ms 로 한다(명세 4장 시간 규칙). */
object SystemUsageClock : UsageClock {
    override fun epochNowMs(): Long = System.currentTimeMillis()
    override fun elapsedNowMs(): Long = SystemClock.elapsedRealtime()
}
