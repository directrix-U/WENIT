package com.example.shortsdetector.intervention

/**
 * 화면 → 서비스로 "개입 알림을 눌렀다" 를 넘기는 프로세스 내 통로. 저장하지 않는다.
 * 답을 기다리는 개입은 서비스의 [InterventionCoordinator] 가 들고 있어서, 눌렀다는 사실이 세션 종료보다 먼저 닿아야 한다.
 * 메인 스레드에서만 호출한다.
 */
object NotificationTaps {

    /** @return 서비스가 그 개입을 끝냈으면 true */
    var listener: ((interventionId: String) -> Boolean)? = null

    /** @return 서비스가 처리했으면 true. false 면 호출한 쪽이 기록을 남긴다. */
    fun deliver(interventionId: String): Boolean = listener?.invoke(interventionId) == true
}
