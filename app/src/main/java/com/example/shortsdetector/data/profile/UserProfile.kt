package com.example.shortsdetector.data.profile

import java.time.DayOfWeek

enum class AgeBand { TEENS, TWENTIES, THIRTIES, FORTIES, FIFTIES_PLUS }

enum class Gender { FEMALE, MALE, OTHER, NO_ANSWER }

/** 설문 선택지. 감지 대상 앱 설정이 아니다([OTHER] 는 지금 감지하지 않는 앱도 답할 수 있게 둔 값). */
enum class ShortformApp { YOUTUBE_SHORTS, INSTAGRAM_REELS, OTHER }

enum class WatchTimeSlot { MORNING, DAYTIME, EVENING, LATE_NIGHT }

/**
 * 온보딩 설문 응답. 설문을 마치지 않았으면 프로필 자체가 없다(null, [UserProfilePreferences] 참고).
 * 복수 선택 항목은 비어 있을 수 있다.
 */
data class UserProfile(
    val ageBand: AgeBand,
    val gender: Gender,
    val shortformApps: Set<ShortformApp> = emptySet(),
    val watchTimeSlots: Set<WatchTimeSlot> = emptySet(),
    val watchDays: Set<DayOfWeek> = emptySet()
)
