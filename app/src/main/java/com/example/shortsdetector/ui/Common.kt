package com.example.shortsdetector.ui

import androidx.annotation.StringRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.example.shortsdetector.R
import com.example.shortsdetector.ai.AiFailureKind
import com.example.shortsdetector.data.goal.GoalSource
import com.example.shortsdetector.data.goal.GoalStatus
import com.example.shortsdetector.data.goal.GoalType
import com.example.shortsdetector.data.profile.AgeBand
import com.example.shortsdetector.data.profile.Gender
import com.example.shortsdetector.data.profile.ShortformApp
import com.example.shortsdetector.data.profile.UsualDailyWatch
import com.example.shortsdetector.data.profile.WatchTimeSlot
import com.example.shortsdetector.data.usage.InterventionOutcome
import com.example.shortsdetector.data.usage.PolicyChangeSource
import com.example.shortsdetector.ui.theme.WenitTokens
import java.time.DayOfWeek

/** 화면으로 돌아올 때마다 상태를 다시 읽는다(권한 화면에서 돌아온 경우 등). */
@Composable
fun OnResume(action: () -> Unit) {
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) action() }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }
}

@Composable
fun ScreenColumn(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(WenitTokens.SpaceL),
        verticalArrangement = Arrangement.spacedBy(WenitTokens.SpaceM),
        content = content
    )
}

@Composable
fun SectionTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = WenitTokens.SpaceS))
}

@Composable
fun Caption(text: String, color: Color = MaterialTheme.colorScheme.onSurfaceVariant) {
    Text(text, style = MaterialTheme.typography.bodyMedium, color = color)
}

@Composable
fun WenitCard(
    modifier: Modifier = Modifier,
    containerColor: Color = MaterialTheme.colorScheme.surface,
    content: @Composable ColumnScope.() -> Unit
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = containerColor)
    ) {
        Column(
            modifier = Modifier.padding(WenitTokens.SpaceL),
            verticalArrangement = Arrangement.spacedBy(WenitTokens.SpaceS),
            content = content
        )
    }
}

@Composable
fun PrimaryButton(text: String, modifier: Modifier = Modifier, enabled: Boolean = true, onClick: () -> Unit) {
    Button(onClick, modifier.fillMaxWidth().height(WenitTokens.ButtonHeight), enabled, MaterialTheme.shapes.medium) {
        Text(text)
    }
}

@Composable
fun SecondaryButton(text: String, modifier: Modifier = Modifier, enabled: Boolean = true, onClick: () -> Unit) {
    OutlinedButton(onClick, modifier.fillMaxWidth().height(WenitTokens.ButtonHeight), enabled, MaterialTheme.shapes.medium) {
        Text(text)
    }
}

/** 디자인이 나오면 이 이미지만 바꾼다. */
@Composable
fun CharacterImage(modifier: Modifier = Modifier) {
    Image(
        painterResource(R.drawable.ic_character_placeholder),
        contentDescription = stringResource(R.string.popup_character_description),
        modifier = modifier.size(WenitTokens.CharacterSize)
    )
}

@Composable
fun StatusBadge(status: GoalStatus) {
    val c = MaterialTheme.colorScheme
    val (back, front) = when (status) {
        GoalStatus.ON_TRACK -> c.primaryContainer to c.onPrimaryContainer
        GoalStatus.ACHIEVED -> c.tertiaryContainer to c.onTertiaryContainer
        GoalStatus.EXCEEDED -> c.errorContainer to c.onErrorContainer
        GoalStatus.MISSED -> c.surfaceVariant to c.onSurfaceVariant
    }
    Text(
        stringResource(statusLabel(status)),
        style = MaterialTheme.typography.labelMedium,
        color = front,
        modifier = Modifier
            .background(back, MaterialTheme.shapes.small)
            .padding(horizontal = WenitTokens.SpaceS, vertical = WenitTokens.SpaceXs)
    )
}

@Composable
fun SingleChoiceRow(text: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = WenitTokens.SpaceXs),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioButton(selected, onClick)
        Text(text, style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
fun MultiChoiceRow(text: String, checked: Boolean, enabled: Boolean = true, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(enabled = enabled, onClick = onClick).padding(vertical = WenitTokens.SpaceXs),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Checkbox(checked, { onClick() }, enabled = enabled)
        Text(text, style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
fun SwitchRow(text: String, checked: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = WenitTokens.SpaceXs),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(text, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Switch(checked, onChange, enabled = enabled)
    }
}

/** 예: 1시간 5분, 12분, 45초 */
@Composable
fun durationText(ms: Long): String {
    val seconds = (ms / MS_PER_SECOND).coerceAtLeast(0)
    val hours = seconds / SECONDS_PER_HOUR
    val minutes = seconds % SECONDS_PER_HOUR / SECONDS_PER_MINUTE
    return when {
        hours > 0 -> stringResource(R.string.duration_hours_minutes, hours.toInt(), minutes.toInt())
        seconds >= SECONDS_PER_MINUTE -> stringResource(R.string.duration_minutes, minutes.toInt())
        else -> stringResource(R.string.duration_seconds, seconds.toInt())
    }
}

private const val MS_PER_SECOND = 1_000L
private const val SECONDS_PER_MINUTE = 60L
private const val SECONDS_PER_HOUR = 3_600L

@StringRes fun statusLabel(s: GoalStatus) = when (s) {
    GoalStatus.ON_TRACK -> R.string.status_on_track
    GoalStatus.EXCEEDED -> R.string.status_exceeded
    GoalStatus.ACHIEVED -> R.string.status_achieved
    GoalStatus.MISSED -> R.string.status_missed
}

@StringRes fun ageLabel(v: AgeBand) = when (v) {
    AgeBand.TEENS -> R.string.age_teens
    AgeBand.TWENTIES -> R.string.age_twenties
    AgeBand.THIRTIES -> R.string.age_thirties
    AgeBand.FORTIES -> R.string.age_forties
    AgeBand.FIFTIES_PLUS -> R.string.age_fifties
}

@StringRes fun genderLabel(v: Gender) = when (v) {
    Gender.FEMALE -> R.string.gender_female
    Gender.MALE -> R.string.gender_male
    Gender.OTHER -> R.string.gender_other
    Gender.NO_ANSWER -> R.string.gender_no_answer
}

@StringRes fun appLabel(v: ShortformApp) = when (v) {
    ShortformApp.YOUTUBE_SHORTS -> R.string.app_label_youtube
    ShortformApp.INSTAGRAM_REELS -> R.string.app_label_instagram
    ShortformApp.OTHER -> R.string.app_label_other
}

@StringRes fun slotLabel(v: WatchTimeSlot) = when (v) {
    WatchTimeSlot.MORNING -> R.string.slot_morning
    WatchTimeSlot.DAYTIME -> R.string.slot_daytime
    WatchTimeSlot.EVENING -> R.string.slot_evening
    WatchTimeSlot.LATE_NIGHT -> R.string.slot_late_night
}

@StringRes fun dayLabel(v: DayOfWeek) = when (v) {
    DayOfWeek.MONDAY -> R.string.day_mon
    DayOfWeek.TUESDAY -> R.string.day_tue
    DayOfWeek.WEDNESDAY -> R.string.day_wed
    DayOfWeek.THURSDAY -> R.string.day_thu
    DayOfWeek.FRIDAY -> R.string.day_fri
    DayOfWeek.SATURDAY -> R.string.day_sat
    DayOfWeek.SUNDAY -> R.string.day_sun
}

@StringRes fun usualLabel(v: UsualDailyWatch) = when (v) {
    UsualDailyWatch.UNDER_30_MIN -> R.string.usual_under_30
    UsualDailyWatch.MIN_30_TO_60 -> R.string.usual_30_60
    UsualDailyWatch.HOUR_1_TO_2 -> R.string.usual_1_2
    UsualDailyWatch.HOUR_2_TO_3 -> R.string.usual_2_3
    UsualDailyWatch.OVER_3_HOURS -> R.string.usual_over_3
}

@StringRes fun goalTypeLabel(v: GoalType) = when (v) {
    GoalType.DAILY_TOTAL -> R.string.goal_type_daily_total
    GoalType.PER_SESSION -> R.string.goal_type_per_session
    GoalType.TIME_WINDOW -> R.string.goal_type_time_window
    GoalType.DAILY_OPENS -> R.string.goal_type_daily_opens
    GoalType.SELF_STOPS -> R.string.goal_type_self_stops
}

@StringRes fun sourceLabel(v: GoalSource) = when (v) {
    GoalSource.AI -> R.string.goal_source_ai
    GoalSource.PRESET -> R.string.goal_source_preset
    GoalSource.USER -> R.string.goal_source_user
}

@StringRes fun outcomeLabel(v: InterventionOutcome?) = when (v) {
    InterventionOutcome.STOPPED -> R.string.outcome_stopped
    InterventionOutcome.CONTINUED -> R.string.outcome_continued
    InterventionOutcome.IGNORED -> R.string.outcome_ignored
    InterventionOutcome.INTERRUPTED -> R.string.outcome_interrupted
    null -> R.string.outcome_unknown
}

@StringRes fun changeSourceLabel(v: PolicyChangeSource) = when (v) {
    PolicyChangeSource.AGENT -> R.string.change_source_agent
    PolicyChangeSource.USER -> R.string.change_source_user
    PolicyChangeSource.DEFAULT -> R.string.change_source_default
    PolicyChangeSource.RULE -> R.string.change_source_rule
}

/** AI 를 못 쓴 이유를 사용자 말로 바꾼다. 동의하지 않은 것은 실패가 아니다. */
@StringRes fun failureNotice(v: AiFailureKind?) = when (v) {
    null -> null
    AiFailureKind.NO_CONSENT -> R.string.goal_notice_no_consent
    else -> R.string.goal_notice_ai_failed
}
