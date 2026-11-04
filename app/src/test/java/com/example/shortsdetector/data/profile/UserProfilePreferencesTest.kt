package com.example.shortsdetector.data.profile

import com.example.shortsdetector.testutil.FakeSharedPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.DayOfWeek

class UserProfilePreferencesTest {

    private val p = FakeSharedPreferences()
    private val profile = UserProfile(
        AgeBand.TWENTIES, Gender.NO_ANSWER,
        setOf(ShortformApp.YOUTUBE_SHORTS, ShortformApp.INSTAGRAM_REELS),
        setOf(WatchTimeSlot.LATE_NIGHT),
        setOf(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY)
    )

    @Test
    fun `nothing stored means the survey is not completed`() {
        assertNull(UserProfilePreferences.read(p))
    }

    @Test
    fun `answers survive a round trip including no-answer gender`() {
        UserProfilePreferences.write(p, profile)
        assertEquals(profile, UserProfilePreferences.read(p))
    }

    @Test
    fun `empty multi-select answers are valid`() {
        val empty = UserProfile(AgeBand.FORTIES, Gender.FEMALE)
        UserProfilePreferences.write(p, empty)
        assertEquals(empty, UserProfilePreferences.read(p))
    }

    @Test
    fun `answers without the completion flag stay incomplete`() {
        p.values["age_band"] = "TWENTIES"
        p.values["gender"] = "MALE"
        assertNull(UserProfilePreferences.read(p))
    }

    @Test
    fun `unreadable required answer falls back to incomplete`() {
        UserProfilePreferences.write(p, profile)
        p.values["age_band"] = "UNKNOWN_BAND"
        assertNull(UserProfilePreferences.read(p))
    }

    @Test
    fun `unknown multi-select values are skipped`() {
        UserProfilePreferences.write(p, profile)
        p.values["watch_time_slots"] = hashSetOf("LATE_NIGHT", "REMOVED_SLOT")
        assertEquals(setOf(WatchTimeSlot.LATE_NIGHT), UserProfilePreferences.read(p)!!.watchTimeSlots)
    }
}
