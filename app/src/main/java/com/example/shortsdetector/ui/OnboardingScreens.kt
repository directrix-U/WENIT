package com.example.shortsdetector.ui

import android.Manifest
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.example.shortsdetector.R
import com.example.shortsdetector.data.goal.GoalRules
import com.example.shortsdetector.data.goal.GoalType
import com.example.shortsdetector.data.profile.AgeBand
import com.example.shortsdetector.data.profile.Gender
import com.example.shortsdetector.data.profile.ShortformApp
import com.example.shortsdetector.data.profile.UsualDailyWatch
import com.example.shortsdetector.data.profile.WatchTimeSlot
import com.example.shortsdetector.ui.theme.WenitTokens
import java.time.DayOfWeek

@Composable
fun SurveyScreen(vm: SurveyViewModel, onDone: () -> Unit) {
    val s by vm.state.collectAsState()
    LaunchedEffect(s.finished) { if (s.finished) onDone() }
    Column(Modifier.fillMaxSize().padding(WenitTokens.SpaceL), verticalArrangement = Arrangement.spacedBy(WenitTokens.SpaceM)) {
        LinearProgressIndicator(
            progress = { (s.stepIndex + 1f) / SurveyStep.entries.size },
            modifier = Modifier.fillMaxWidth().height(WenitTokens.ProgressHeight)
        )
        Caption(stringResource(R.string.survey_progress, s.stepIndex + 1, SurveyStep.entries.size))
        Text(stringResource(surveyQuestion(s.step)), style = MaterialTheme.typography.headlineMedium)
        ScreenColumn(Modifier.weight(1f)) {
            when (s.step) {
                SurveyStep.AGE -> AgeBand.entries.forEach {
                    SingleChoiceRow(stringResource(ageLabel(it)), s.ageBand == it) { vm.selectAge(it) }
                }
                SurveyStep.GENDER -> Gender.entries.forEach {
                    SingleChoiceRow(stringResource(genderLabel(it)), s.gender == it) { vm.selectGender(it) }
                }
                SurveyStep.APPS -> ShortformApp.entries.forEach {
                    MultiChoiceRow(stringResource(appLabel(it)), it in s.apps) { vm.toggleApp(it) }
                }
                SurveyStep.TIME_SLOTS -> WatchTimeSlot.entries.forEach {
                    MultiChoiceRow(stringResource(slotLabel(it)), it in s.slots) { vm.toggleSlot(it) }
                }
                SurveyStep.DAYS -> DayOfWeek.entries.forEach {
                    MultiChoiceRow(stringResource(dayLabel(it)), it in s.days) { vm.toggleDay(it) }
                }
                SurveyStep.USUAL_WATCH -> UsualDailyWatch.entries.forEach {
                    SingleChoiceRow(stringResource(usualLabel(it)), s.usualWatch == it) { vm.selectUsualWatch(it) }
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(WenitTokens.SpaceS)) {
            if (!s.isFirst) SecondaryButton(stringResource(R.string.action_back), Modifier.weight(1f)) { vm.back() }
            PrimaryButton(stringResource(R.string.action_next), Modifier.weight(1f), s.canGoNext) { vm.next() }
        }
    }
}

private fun surveyQuestion(step: SurveyStep) = when (step) {
    SurveyStep.AGE -> R.string.survey_age
    SurveyStep.GENDER -> R.string.survey_gender
    SurveyStep.APPS -> R.string.survey_apps
    SurveyStep.TIME_SLOTS -> R.string.survey_slots
    SurveyStep.DAYS -> R.string.survey_days
    SurveyStep.USUAL_WATCH -> R.string.survey_usual
}

@Composable
fun ConsentScreen(vm: ConsentViewModel, onDone: () -> Unit) {
    ScreenColumn(Modifier.fillMaxSize()) {
        Text(stringResource(R.string.consent_title), style = MaterialTheme.typography.headlineMedium)
        Caption(stringResource(R.string.consent_body))
        WenitCard {
            Text(stringResource(R.string.consent_sent_title), style = MaterialTheme.typography.titleMedium)
            Text(stringResource(R.string.consent_sent_list), style = MaterialTheme.typography.bodyLarge)
        }
        WenitCard {
            Text(stringResource(R.string.consent_not_sent_title), style = MaterialTheme.typography.titleMedium)
            Text(stringResource(R.string.consent_not_sent_list), style = MaterialTheme.typography.bodyLarge)
        }
        Caption(stringResource(R.string.consent_auto_adjust))
        Caption(stringResource(R.string.consent_off_anytime))
        PrimaryButton(stringResource(R.string.consent_agree)) { vm.decide(true); onDone() }
        SecondaryButton(stringResource(R.string.consent_decline)) { vm.decide(false); onDone() }
    }
}

@Composable
fun PermissionsScreen(vm: PermissionsViewModel, onDone: () -> Unit) {
    val s by vm.state.collectAsState()
    OnResume(vm::refresh)
    ScreenColumn(Modifier.fillMaxSize()) {
        Text(stringResource(R.string.permissions_title), style = MaterialTheme.typography.headlineMedium)
        Caption(stringResource(R.string.permissions_body))
        PermissionCards(s, onChanged = vm::refresh)
        WenitCard {
            Text(stringResource(R.string.collect_title), style = MaterialTheme.typography.titleMedium)
            Caption(stringResource(R.string.collect_body))
        }
        PrimaryButton(stringResource(if (s.accessibility) R.string.action_next else R.string.action_later), onClick = onDone)
    }
}

/** 접근성·알림 상태와 켜기 버튼. 온보딩과 설정에서 같이 쓴다. */
@Composable
fun PermissionCards(s: PermissionStatus, onChanged: () -> Unit) {
    val context = LocalContext.current
    val requestNotification = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { onChanged() }
    WenitCard {
        Text(stringResource(R.string.permission_accessibility), style = MaterialTheme.typography.titleMedium)
        Caption(stringResource(if (s.accessibility) R.string.permission_on else R.string.permission_off))
        if (!s.accessibility) {
            SecondaryButton(stringResource(R.string.action_turn_on)) {
                context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }
        }
    }
    WenitCard {
        Text(stringResource(R.string.permission_notification), style = MaterialTheme.typography.titleMedium)
        Caption(stringResource(if (s.notifications) R.string.state_granted else R.string.state_denied))
        if (!s.notifications && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            SecondaryButton(stringResource(R.string.action_turn_on)) {
                requestNotification.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }
}

@Composable
fun GoalPickScreen(vm: GoalPickViewModel, onCustom: () -> Unit, onDone: () -> Unit) {
    val s by vm.state.collectAsState()
    LaunchedEffect(Unit) { vm.load() }
    OnResume(vm::refreshSaved)
    LaunchedEffect(s.finished) { if (s.finished) onDone() }
    if (s.loading) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(WenitTokens.SpaceM)) {
                CircularProgressIndicator()
                Caption(stringResource(R.string.goal_loading))
            }
        }
        return
    }
    ScreenColumn(Modifier.fillMaxSize()) {
        Text(stringResource(R.string.goal_title), style = MaterialTheme.typography.headlineMedium)
        Caption(stringResource(R.string.goal_body, GoalRules.MAX_GOALS))
        failureNotice(s.failure)?.let { Caption(stringResource(it)) }
        s.cards.forEach { goal ->
            val checked = goal.type in s.selected
            WenitCard {
                MultiChoiceRow(goal.label, checked, checked || s.canSelectMore) { vm.toggle(goal.type) }
                goal.reason?.let { Caption(it) }
                Caption(stringResource(sourceLabel(goal.source)))
            }
        }
        SecondaryButton(stringResource(R.string.goal_custom), onClick = onCustom)
        PrimaryButton(stringResource(R.string.goal_finish)) { vm.save() }
    }
}

@Composable
fun CustomGoalScreen(vm: CustomGoalViewModel, onDone: () -> Unit) {
    val s by vm.state.collectAsState()
    LaunchedEffect(s.saved) { if (s.saved) onDone() }
    ScreenColumn(Modifier.fillMaxSize()) {
        Text(stringResource(R.string.custom_title), style = MaterialTheme.typography.headlineMedium)
        GoalType.entries.forEach {
            SingleChoiceRow(stringResource(goalTypeLabel(it)), s.type == it) { vm.selectType(it) }
        }
        val type = s.type
        if (type != null) {
            if (type == GoalType.TIME_WINDOW) {
                Row(horizontalArrangement = Arrangement.spacedBy(WenitTokens.SpaceS)) {
                    NumberField(s.hour, stringResource(R.string.custom_hour), Modifier.weight(1f), vm::setHour)
                    NumberField(s.minute, stringResource(R.string.custom_minute), Modifier.weight(1f), vm::setMinute)
                }
            } else {
                NumberField(s.number, stringResource(unitLabel(type)), Modifier.fillMaxWidth(), vm::setNumber)
            }
            Caption(rangeText(type))
        }
        if (s.full) Caption(stringResource(R.string.custom_full, GoalRules.MAX_GOALS), MaterialTheme.colorScheme.error)
        PrimaryButton(stringResource(R.string.action_save), enabled = s.isValid) { vm.save() }
    }
}

@Composable
private fun NumberField(value: String, label: String, modifier: Modifier, onChange: (String) -> Unit) {
    OutlinedTextField(
        value, onChange, modifier, label = { Text(label) }, singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
    )
}

private fun unitLabel(type: GoalType) = when (type) {
    GoalType.DAILY_TOTAL, GoalType.PER_SESSION -> R.string.custom_unit_minutes
    else -> R.string.custom_unit_count
}

@Composable
private fun rangeText(type: GoalType): String {
    val r = GoalRules.rangeOf(type)
    return if (type == GoalType.TIME_WINDOW) {
        stringResource(R.string.custom_range_time, GoalRules.formatTimeWindow(r.first), GoalRules.formatTimeWindow(r.last))
    } else {
        stringResource(R.string.custom_range, r.first, r.last, stringResource(unitLabel(type)))
    }
}

@Composable
fun BackRow(onBack: () -> Unit) {
    TextButton(onBack, Modifier.padding(start = 4.dp)) { Text(stringResource(R.string.action_back)) }
}
