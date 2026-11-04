package com.example.shortsdetector.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import com.example.shortsdetector.BuildConfig
import com.example.shortsdetector.DetectionConfig
import com.example.shortsdetector.R
import com.example.shortsdetector.ai.agent.AgentPolicyRules
import com.example.shortsdetector.data.stats.DailyWatch
import com.example.shortsdetector.data.stats.WeekStats
import com.example.shortsdetector.data.usage.DemoSeedData
import com.example.shortsdetector.intervention.DemoMode
import com.example.shortsdetector.ui.theme.WenitTokens
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.roundToInt

// --- f. 홈 ---

@Composable
fun HomeScreen(vm: HomeViewModel, stoppedAtMs: Long?, onStoppedShown: () -> Unit, onOpenSettings: () -> Unit) {
    val s by vm.state.collectAsState()
    OnResume(vm::refresh)
    LaunchedEffect(stoppedAtMs) {
        if (stoppedAtMs != null) {
            vm.showStopped(stoppedAtMs)
            onStoppedShown()
        }
    }
    ScreenColumn(Modifier.fillMaxSize()) {
        s.stoppedAtMs?.let { ms ->
            WenitCard(containerColor = MaterialTheme.colorScheme.tertiaryContainer) {
                Text(stringResource(R.string.home_stopped, durationText(ms)), style = MaterialTheme.typography.titleMedium)
                TextButton(vm::dismissStopped) { Text(stringResource(R.string.action_close)) }
            }
        }
        if (!s.permissions.detecting && !s.loading) {
            WenitCard(containerColor = MaterialTheme.colorScheme.errorContainer) {
                Text(stringResource(R.string.home_not_detecting), style = MaterialTheme.typography.titleMedium)
                Caption(stringResource(R.string.home_not_detecting_body), MaterialTheme.colorScheme.onErrorContainer)
                TextButton(onOpenSettings) { Text(stringResource(R.string.home_open_settings)) }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(WenitTokens.SpaceM)) {
            CharacterImage()
            androidx.compose.foundation.layout.Column {
                Caption(stringResource(if (s.permissions.detecting) R.string.home_detecting else R.string.home_paused))
                Caption(stringResource(R.string.home_today))
                Text(durationText(s.watchMs), style = MaterialTheme.typography.headlineMedium)
                Caption(stringResource(R.string.home_estimate))
            }
        }
        if (!s.loading && !s.hasRecords) {
            WenitCard {
                Text(stringResource(R.string.home_empty_title), style = MaterialTheme.typography.titleMedium)
                Caption(stringResource(R.string.home_empty_body))
            }
        }
        if (s.goals.isNotEmpty()) {
            WenitCard {
                Text(stringResource(R.string.home_goals), style = MaterialTheme.typography.titleMedium)
                s.goals.forEach {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(it.goal.label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                        StatusBadge(it.status)
                    }
                }
            }
        }
        WenitCard {
            Text(
                stringResource(if (s.demoMode) R.string.home_level_demo else R.string.home_level, s.level),
                style = MaterialTheme.typography.titleMedium
            )
            s.lastChangeReason?.let { Caption(it) }
        }
        s.yesterday?.let {
            WenitCard { Text(stringResource(R.string.home_yesterday, it.total, it.achieved), style = MaterialTheme.typography.bodyLarge) }
        }
        s.savedTime?.let {
            WenitCard {
                Text(stringResource(R.string.home_saved, durationText(it.savedMs)), style = MaterialTheme.typography.titleMedium)
                if (it.isEstimate) Caption(stringResource(R.string.home_saved_estimate))
            }
        }
    }
}

// --- g. 기록 ---

@Composable
fun RecordsScreen(vm: RecordsViewModel) {
    val s by vm.state.collectAsState()
    OnResume(vm::refresh)
    val zone = remember { ZoneId.systemDefault() }
    val format = remember { DateTimeFormatter.ofPattern("MM-dd HH:mm") }
    fun time(ms: Long) = format.format(Instant.ofEpochMilli(ms).atZone(zone))

    ScreenColumn(Modifier.fillMaxSize()) {
        Text(stringResource(R.string.tab_records), style = MaterialTheme.typography.headlineMedium)
        if (s.loading) {
            CircularProgressIndicator()
            return@ScreenColumn
        }
        if (s.isEmpty) {
            WenitCard {
                Text(stringResource(R.string.records_empty_title), style = MaterialTheme.typography.titleMedium)
                Caption(stringResource(R.string.records_empty_body))
            }
        }
        s.week?.let { WeekCard(it) }
        s.week?.let { week ->
            WenitCard {
                Text(stringResource(R.string.records_stop_rate), style = MaterialTheme.typography.titleMedium)
                week.levelStopRates.forEach { r ->
                    Caption(
                        r.stopRate?.let { rate ->
                            stringResource(R.string.records_stop_rate_row, r.level, (rate * 100).roundToInt(), r.stopped, r.answered)
                        } ?: stringResource(R.string.records_stop_rate_none, r.level)
                    )
                }
            }
        }
        SectionTitle(stringResource(R.string.records_changes))
        if (s.changes.isEmpty()) Caption(stringResource(R.string.records_changes_empty))
        s.changes.forEach { row ->
            val c = row.record
            WenitCard {
                Caption("${time(c.occurredAtMs)} · ${stringResource(changeSourceLabel(c.source))}")
                Text(AgentPolicyRules.describeChange(c.previousPolicy, c.newPolicy), style = MaterialTheme.typography.bodyLarge)
                Caption(c.reasonText)
                if (row.canRevert) TextButton(vm::revert) { Text(stringResource(R.string.records_revert)) }
            }
        }
        SectionTitle(stringResource(R.string.records_interventions))
        if (s.interventions.isEmpty()) Caption(stringResource(R.string.records_interventions_empty))
        s.interventions.forEach {
            Caption(
                stringResource(
                    R.string.records_intervention_row, time(it.occurredAtMs), it.level ?: 1,
                    durationText(it.watchMs), stringResource(outcomeLabel(it.outcome))
                )
            )
        }
    }
}

@Composable
private fun WeekCard(week: WeekStats) {
    WenitCard {
        Text(stringResource(R.string.records_week), style = MaterialTheme.typography.titleMedium)
        WeekBars(week.days)
        Caption(stringResource(R.string.records_average, durationText(week.dailyAverageMs)))
        val ratio = week.changeRatio
        Caption(
            when {
                ratio == null || !week.canCompare -> stringResource(R.string.records_change_none)
                ratio <= 0 -> stringResource(R.string.records_change_down, (-ratio * 100).roundToInt())
                else -> stringResource(R.string.records_change_up, (ratio * 100).roundToInt())
            }
        )
    }
}

/** 월~일 일곱 칸. 아직 오지 않은 요일은 비워 둔다. */
@Composable
private fun WeekBars(days: List<DailyWatch>) {
    val color = MaterialTheme.colorScheme.primary
    val track = MaterialTheme.colorScheme.surfaceVariant
    val radius = with(LocalDensity.current) { WenitTokens.ChartBarRadius.toPx() }
    val max = (days.maxOfOrNull { it.watchMs } ?: 0L).coerceAtLeast(1L)
    val description = stringResource(R.string.records_chart_description)
    Canvas(Modifier.fillMaxWidth().height(WenitTokens.ChartHeight).semantics { contentDescription = description }) {
        val slot = size.width / WEEK_DAYS
        val barWidth = slot * BAR_RATIO
        repeat(WEEK_DAYS) { i ->
            val x = slot * i + (slot - barWidth) / 2
            drawRoundRect(track, Offset(x, 0f), Size(barWidth, size.height), CornerRadius(radius))
            days.getOrNull(i)?.let { day ->
                val h = size.height * day.watchMs / max
                drawRoundRect(color, Offset(x, size.height - h), Size(barWidth, h), CornerRadius(radius))
            }
        }
    }
    Row(Modifier.fillMaxWidth()) {
        java.time.DayOfWeek.entries.forEach {
            Text(
                stringResource(dayShortLabel(it)), style = MaterialTheme.typography.labelMedium,
                textAlign = TextAlign.Center, modifier = Modifier.weight(1f)
            )
        }
    }
}

private const val WEEK_DAYS = 7
private const val BAR_RATIO = 0.6f

private fun dayShortLabel(d: java.time.DayOfWeek) = when (d) {
    java.time.DayOfWeek.MONDAY -> R.string.day_short_mon
    java.time.DayOfWeek.TUESDAY -> R.string.day_short_tue
    java.time.DayOfWeek.WEDNESDAY -> R.string.day_short_wed
    java.time.DayOfWeek.THURSDAY -> R.string.day_short_thu
    java.time.DayOfWeek.FRIDAY -> R.string.day_short_fri
    java.time.DayOfWeek.SATURDAY -> R.string.day_short_sat
    java.time.DayOfWeek.SUNDAY -> R.string.day_short_sun
}

// --- h. 설정 ---

@Composable
fun SettingsScreen(vm: SettingsViewModel, onAddGoal: () -> Unit, onRecommend: () -> Unit, onDeveloper: () -> Unit) {
    val s by vm.state.collectAsState()
    var confirmDelete by remember { mutableStateOf(false) }
    OnResume(vm::refresh)
    LaunchedEffect(s.openDeveloper) {
        if (s.openDeveloper) {
            vm.consumeOpenDeveloper()
            onDeveloper()
        }
    }
    ScreenColumn(Modifier.fillMaxSize()) {
        Text(stringResource(R.string.tab_settings), style = MaterialTheme.typography.headlineMedium)

        SectionTitle(stringResource(R.string.settings_goals))
        if (s.goals.isEmpty()) Caption(stringResource(R.string.settings_goals_empty))
        s.goals.forEach { goal ->
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(goal.label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                TextButton({ vm.removeGoal(goal.type) }) { Text(stringResource(R.string.action_remove)) }
            }
        }
        SecondaryButton(stringResource(R.string.settings_add_goal), onClick = onAddGoal)
        SecondaryButton(stringResource(R.string.settings_recommend_goal), onClick = onRecommend)

        SectionTitle(stringResource(R.string.settings_max_level))
        SingleChoiceRow(stringResource(R.string.settings_max_level_2), s.maxLevel == 2) { vm.setMaxLevel(2) }
        SingleChoiceRow(stringResource(R.string.settings_max_level_3), s.maxLevel == 3) { vm.setMaxLevel(3) }

        SectionTitle(stringResource(R.string.settings_ai))
        SwitchRow(stringResource(R.string.settings_ai_consent), s.aiConsent, onChange = vm::setAiConsent)
        SwitchRow(stringResource(R.string.settings_agent_enabled), s.agentEnabled, onChange = vm::setAgentEnabled)
        SecondaryButton(
            stringResource(if (s.analyzing) R.string.settings_analyzing else R.string.settings_analyze_now),
            enabled = !s.analyzing && s.agentEnabled && s.aiConsent,
            onClick = vm::analyzeNow
        )
        s.analysisNotice?.let { notice ->
            Caption(
                stringResource(
                    when (notice) {
                        AnalysisNotice.POLICY_KEPT -> R.string.settings_analysis_failed
                        AnalysisNotice.NOT_ENOUGH_DATA -> R.string.settings_analysis_no_data
                    }
                )
            )
        }
        s.lastResult?.let { Caption(stringResource(R.string.settings_last_result, it)) }

        SectionTitle(stringResource(R.string.settings_targets))
        SwitchRow(stringResource(R.string.app_label_youtube), s.youtube, onChange = vm::setYouTube)
        SwitchRow(stringResource(R.string.settings_instagram), s.instagram, onChange = vm::setInstagram)

        SectionTitle(stringResource(R.string.settings_permissions))
        PermissionCards(s.permissions, onChanged = vm::refresh)

        SectionTitle(stringResource(R.string.settings_records))
        SecondaryButton(stringResource(R.string.settings_delete_all)) { confirmDelete = true }
        if (s.deleted) Caption(stringResource(R.string.settings_deleted))

        SwitchRow(stringResource(R.string.settings_demo_mode), s.demoMode, onChange = vm::setDemoMode)
        Text(
            stringResource(R.string.settings_version, BuildConfig.VERSION_NAME),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.clickable(onClick = vm::tapVersion)
        )
    }
    if (confirmDelete) {
        ConfirmDialog(
            stringResource(R.string.dialog_delete_records_title), stringResource(R.string.dialog_delete_records_body),
            stringResource(R.string.dialog_delete), { confirmDelete = false }
        ) {
            confirmDelete = false
            vm.deleteAll()
        }
    }
}

@Composable
fun ConfirmDialog(title: String, body: String, confirm: String, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(body) },
        confirmButton = { Button(onConfirm) { Text(confirm) } },
        dismissButton = { TextButton(onDismiss) { Text(stringResource(R.string.dialog_cancel)) } }
    )
}

// --- i. 개발자 화면 ---

@Composable
fun DeveloperScreen(vm: DeveloperViewModel, onBack: () -> Unit, onRestarted: () -> Unit) {
    val s by vm.state.collectAsState()
    var confirmReset by remember { mutableStateOf(false) }
    var confirmRestart by remember { mutableStateOf(false) }
    OnResume(vm::refresh)
    LaunchedEffect(s.restarted) { if (s.restarted) onRestarted() }
    ScreenColumn(Modifier.fillMaxSize()) {
        BackRow(onBack)
        Text(stringResource(R.string.dev_title), style = MaterialTheme.typography.headlineMedium)

        SectionTitle(stringResource(R.string.dev_threshold))
        DetectionConfig.WATCH_THRESHOLD_CHOICES_MS.forEach { ms ->
            SingleChoiceRow(durationText(ms), s.thresholdMs == ms) { vm.setThreshold(ms) }
        }
        SwitchRow(stringResource(R.string.switch_popup), s.popupEnabled, onChange = vm::setPopup)

        SectionTitle(stringResource(R.string.dev_demo_threshold))
        DemoMode.THRESHOLD_CHOICES_MS.forEach { ms ->
            SingleChoiceRow(durationText(ms), s.demoThresholdMs == ms) { vm.setDemoThreshold(ms) }
        }

        SectionTitle(stringResource(R.string.dev_scenarios))
        SecondaryButton(stringResource(R.string.button_seed_a), enabled = !s.working) { vm.seed(DemoSeedData.Scenario.LATE_NIGHT_SKIPPER) }
        SecondaryButton(stringResource(R.string.button_seed_b), enabled = !s.working) { vm.seed(DemoSeedData.Scenario.EASY_STOPPER) }
        s.seeded?.let { Caption(stringResource(R.string.dev_seed_done, it)) }

        SecondaryButton(stringResource(R.string.button_goal_eval), enabled = !s.working, onClick = vm::runGoalEval)
        s.evalRows?.let { Caption(stringResource(R.string.ai_goal_eval_done, it)) }

        SecondaryButton(stringResource(R.string.button_reset_demo), enabled = !s.working) { confirmReset = true }
        if (s.resetDone) Caption(stringResource(R.string.toast_reset_demo_done))
        SecondaryButton(stringResource(R.string.dev_restart_onboarding), enabled = !s.working) { confirmRestart = true }
    }
    if (confirmRestart) {
        ConfirmDialog(
            stringResource(R.string.dev_restart_title), stringResource(R.string.dev_restart_body),
            stringResource(R.string.dialog_reset_demo_confirm), { confirmRestart = false }
        ) {
            confirmRestart = false
            vm.restartOnboarding()
        }
    }
    if (confirmReset) {
        ConfirmDialog(
            stringResource(R.string.dialog_reset_demo_title), stringResource(R.string.dialog_reset_demo_body),
            stringResource(R.string.dialog_reset_demo_confirm), { confirmReset = false }
        ) {
            confirmReset = false
            vm.resetForDemo()
        }
    }
}
