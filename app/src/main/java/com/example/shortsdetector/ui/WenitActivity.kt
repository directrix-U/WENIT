package com.example.shortsdetector.ui

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.example.shortsdetector.BuildConfig
import com.example.shortsdetector.R
import com.example.shortsdetector.ShortsNotifier
import com.example.shortsdetector.ui.theme.WenitTheme
import kotlinx.coroutines.flow.MutableStateFlow

/** 앱의 유일한 화면 진입점. 첫 실행은 설문 → 동의 → 권한 → 목표, 이후는 홈. */
class WenitActivity : ComponentActivity() {

    private lateinit var repo: AppRepository
    private val tasks = BackgroundTaskRunner()

    /** 개입에서 넘어올 때마다 값이 바뀌어 홈으로 이동한다 */
    private val arrivals = MutableStateFlow(0)
    private val stopped = MutableStateFlow<Long?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        ShortsNotifier.ensureChannel(this)
        repo = AndroidAppRepository(this)
        handleIntervention(intent)
        val factory = WenitViewModelFactory(repo, tasks, BuildConfig.DEBUG)
        setContent {
            WenitTheme {
                Surface(color = MaterialTheme.colorScheme.background) {
                    WenitApp(repo.isOnboardingDone(), factory, arrivals, stopped)
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntervention(intent)
    }

    /** 팝업의 "멈출게" 와 알림 탭이 여기로 온다. 알림 탭은 그 개입에 "앱 열기" 반응을 기록한다. */
    private fun handleIntervention(intent: Intent?) {
        if (intent?.getBooleanExtra(EXTRA_FROM_INTERVENTION, false) != true) return
        intent.removeExtra(EXTRA_FROM_INTERVENTION) // 화면 회전 등으로 다시 처리하지 않게
        intent.getStringExtra(EXTRA_INTERVENTION_ID)?.let { id ->
            intent.removeExtra(EXTRA_INTERVENTION_ID)
            tasks.run(work = { repo.recordNotificationTap(id) }) {}
        }
        if (intent.hasExtra(EXTRA_STOPPED_WATCH_MS)) {
            stopped.value = intent.getLongExtra(EXTRA_STOPPED_WATCH_MS, 0L)
            intent.removeExtra(EXTRA_STOPPED_WATCH_MS)
        }
        arrivals.value += 1
    }

    companion object {
        const val EXTRA_FROM_INTERVENTION = "from_intervention"

        /** 개입 알림을 눌렀을 때 반응을 기록할 개입 기록 id */
        const val EXTRA_INTERVENTION_ID = "intervention_id"

        /** 팝업에서 멈췄을 때의 시청시간. 홈의 "N분에서 멈췄어요" 카드에 쓴다. */
        const val EXTRA_STOPPED_WATCH_MS = "stopped_watch_ms"
    }
}

private object Routes {
    const val SURVEY = "survey"
    const val CONSENT = "consent"
    const val PERMISSIONS = "permissions"
    const val GOAL_PICK = "goal_pick"
    const val CUSTOM_GOAL = "custom_goal"
    const val HOME = "home"
    const val RECORDS = "records"
    const val SETTINGS = "settings"
    const val DEVELOPER = "developer"
}

private class Tab(val route: String, val label: Int, val icon: Int)

private val tabs = listOf(
    Tab(Routes.HOME, R.string.tab_home, R.drawable.ic_tab_home),
    Tab(Routes.RECORDS, R.string.tab_records, R.drawable.ic_tab_records),
    Tab(Routes.SETTINGS, R.string.tab_settings, R.drawable.ic_tab_settings)
)

private fun NavHostController.goToTab(route: String) = navigate(route) {
    popUpTo(graph.findStartDestination().id) { saveState = true }
    launchSingleTop = true
    restoreState = true
}

@Composable
private fun WenitApp(
    onboardingDone: Boolean,
    factory: WenitViewModelFactory,
    arrivals: MutableStateFlow<Int>,
    stopped: MutableStateFlow<Long?>
) {
    val nav = rememberNavController()
    val entry by nav.currentBackStackEntryAsState()
    val route = entry?.destination?.route
    val start = remember { if (onboardingDone) Routes.HOME else Routes.SURVEY }
    val arrival by arrivals.collectAsState()
    val stoppedMs by stopped.collectAsState()

    // 온보딩 중에는 건너뛰지 않는다.
    LaunchedEffect(arrival) {
        if (arrival > 0 && (route == null || tabs.any { it.route == route } || route == Routes.DEVELOPER)) {
            nav.goToTab(Routes.HOME)
        }
    }

    fun finishOnboarding() = nav.navigate(Routes.HOME) {
        popUpTo(nav.graph.id) { inclusive = true }
        launchSingleTop = true
    }

    Scaffold(
        bottomBar = {
            if (tabs.any { it.route == route }) {
                NavigationBar {
                    tabs.forEach { tab ->
                        NavigationBarItem(
                            selected = route == tab.route,
                            onClick = { nav.goToTab(tab.route) },
                            icon = { Icon(painterResource(tab.icon), contentDescription = null) },
                            label = { Text(stringResource(tab.label)) }
                        )
                    }
                }
            }
        }
    ) { padding ->
        Box(Modifier.padding(padding)) {
            NavHost(nav, startDestination = start) {
                composable(Routes.SURVEY) {
                    SurveyScreen(viewModel(factory = factory)) { nav.navigate(Routes.CONSENT) }
                }
                composable(Routes.CONSENT) {
                    ConsentScreen(viewModel(factory = factory)) { nav.navigate(Routes.PERMISSIONS) }
                }
                composable(Routes.PERMISSIONS) {
                    PermissionsScreen(viewModel(factory = factory)) { nav.navigate(Routes.GOAL_PICK) }
                }
                composable(Routes.GOAL_PICK) {
                    GoalPickScreen(
                        viewModel(factory = factory),
                        onCustom = { nav.navigate(Routes.CUSTOM_GOAL) }
                    ) {
                        if (nav.previousBackStackEntry?.destination?.route == Routes.SETTINGS) nav.popBackStack() else finishOnboarding()
                    }
                }
                composable(Routes.CUSTOM_GOAL) {
                    CustomGoalScreen(viewModel(factory = factory)) { nav.popBackStack() }
                }
                composable(Routes.HOME) {
                    HomeScreen(
                        viewModel(factory = factory), stoppedMs,
                        onStoppedShown = { stopped.value = null },
                        onOpenSettings = { nav.goToTab(Routes.SETTINGS) }
                    )
                }
                composable(Routes.RECORDS) { RecordsScreen(viewModel(factory = factory)) }
                composable(Routes.SETTINGS) {
                    SettingsScreen(
                        viewModel(factory = factory),
                        onAddGoal = { nav.navigate(Routes.CUSTOM_GOAL) },
                        onRecommend = { nav.navigate(Routes.GOAL_PICK) },
                        onDeveloper = { nav.navigate(Routes.DEVELOPER) }
                    )
                }
                composable(Routes.DEVELOPER) {
                    DeveloperScreen(viewModel(factory = factory)) { nav.popBackStack() }
                }
            }
        }
    }
}
