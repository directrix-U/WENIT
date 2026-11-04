package com.example.shortsdetector.data.stats

import com.example.shortsdetector.data.goal.GoalRules
import com.example.shortsdetector.data.goal.GoalType
import com.example.shortsdetector.data.profile.AgeBand
import com.example.shortsdetector.data.profile.Gender
import com.example.shortsdetector.data.profile.UserProfile
import com.example.shortsdetector.data.usage.InterventionMethod
import com.example.shortsdetector.data.usage.InterventionRecord
import com.example.shortsdetector.data.usage.PolicyChangeRecord
import com.example.shortsdetector.data.usage.PolicyChangeSource
import com.example.shortsdetector.data.usage.PolicyChangeStatus
import com.example.shortsdetector.data.usage.SessionRecord
import com.example.shortsdetector.data.usage.SessionStatus
import com.example.shortsdetector.intervention.InterventionPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Modifier
import java.lang.reflect.ParameterizedType
import java.lang.reflect.Type
import java.lang.reflect.WildcardType
import java.time.LocalDate
import java.time.ZoneId

class AgentInputPrivacyTest {

    private val zone = ZoneId.of("Asia/Seoul")
    private val now = LocalDate.of(2026, 11, 5).atTime(20, 0).atZone(zone).toInstant().toEpochMilli()

    private val forbiddenField = Regex("(?i)node|screen|comment|caption|title|packagename|viewid|video|sessionid")
    private val leafTypes = setOf<Type>(
        java.lang.Long.TYPE, Integer.TYPE, java.lang.Double.TYPE, java.lang.Boolean.TYPE,
        java.lang.Long::class.java, Integer::class.java, java.lang.Double::class.java, java.lang.Boolean::class.java,
        String::class.java, LocalDate::class.java
    )

    /** AgentInput 에서 닿는 모든 필드를 돌며 "클래스.필드" 와 타입 이름을 모은다. 앱 패키지 밖 타입은 허용 목록만 통과. */
    private fun walk(type: Type, seen: MutableSet<Type>, fields: MutableList<String>, unknown: MutableList<String>) {
        when (type) {
            is ParameterizedType -> type.actualTypeArguments.forEach { walk(it, seen, fields, unknown) }
            is WildcardType -> type.upperBounds.forEach { walk(it, seen, fields, unknown) }
            is Class<*> -> when {
                type in leafTypes || type.isEnum -> Unit
                !seen.add(type) -> Unit
                type.name.startsWith("com.example.shortsdetector.") ->
                    type.declaredFields.filterNot { Modifier.isStatic(it.modifiers) || it.isSynthetic }.forEach {
                        fields += "${type.simpleName}.${it.name}"
                        walk(it.genericType, seen, fields, unknown)
                    }
                else -> unknown += type.name
            }
            else -> unknown += type.typeName
        }
    }

    @Test
    fun `agent input reaches only allowed types and no raw screen fields`() {
        val fields = ArrayList<String>()
        val unknown = ArrayList<String>()
        walk(AgentInput::class.java, HashSet(), fields, unknown)
        assertTrue("허용되지 않은 타입: $unknown", unknown.isEmpty())
        assertTrue(fields.size > 20)                    // 실제로 내려갔는지 확인
        assertEquals(emptyList<String>(), fields.filter { forbiddenField.containsMatchIn(it.substringAfter('.')) })
    }

    @Test
    fun `raw records are not part of the input`() {
        val fields = ArrayList<String>()
        walk(AgentInput::class.java, HashSet(), fields, ArrayList())
        assertFalse(fields.any { it.startsWith("SessionRecord.") || it.startsWith("InterventionRecord.") })
    }

    @Test
    fun `builder carries profile, goals, policy, stats and changes without package names`() {
        val profile = UserProfile(AgeBand.TWENTIES, Gender.NO_ANSWER)
        val goals = listOf(GoalRules.userGoal(GoalType.DAILY_TOTAL, 30))
        val session = SessionRecord(
            "secret-id", "com.google.android.youtube", "yt-2", now - 600_000, now - 300_000,
            SessionStatus.CLOSED, 300_000, 0, 0, 0, "HIGH", null, null, 0
        )
        val intervention = InterventionRecord("i", "secret-id", now - 400_000, InterventionMethod.POPUP, 1, 1, level = 1)
        val change = PolicyChangeRecord(
            "c", now - 1, PolicyChangeSource.DEFAULT, null, InterventionPolicy(), "기본값", null, PolicyChangeStatus.APPLIED
        )
        val input = AgentInputBuilder.build(
            profile, goals, InterventionPolicy(), listOf(session), listOf(intervention), listOf(change), zone, now
        )
        assertEquals(profile, input.profile)
        assertEquals(300_000, input.today.estimatedWatchMs)
        assertEquals(1, input.today.interventionCount)
        assertEquals(1, input.today.goals.size)
        assertEquals(listOf(change), input.recentPolicyChanges)
        assertFalse("com.google" in input.toString())
        assertFalse("secret-id" in input.toString())
    }

    @Test
    fun `incomplete survey is represented as a null profile`() {
        val input = AgentInputBuilder.build(
            null, emptyList(), InterventionPolicy(), emptyList(), emptyList(), emptyList(), zone, now
        )
        assertNull(input.profile)
    }
}
