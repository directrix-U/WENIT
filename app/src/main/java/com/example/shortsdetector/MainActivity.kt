package com.example.shortsdetector

import android.Manifest
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.ComponentName
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.view.accessibility.AccessibilityManager
import android.widget.RadioButton
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.example.shortsdetector.data.DetectionPreferences
import com.example.shortsdetector.data.InterventionPolicyPreferences
import com.example.shortsdetector.data.stats.SavedTimeBaselinePreferences
import com.example.shortsdetector.data.usage.DailyUsageCalculator
import com.example.shortsdetector.data.usage.InterventionAction
import com.example.shortsdetector.data.usage.UsageDatabase
import com.example.shortsdetector.intervention.InterventionCoordinator
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID
import java.util.concurrent.Executors
import com.example.shortsdetector.databinding.ActivityMainBinding
import com.example.shortsdetector.platform.DetectionStatus

/**
 * 프로토타입용 최소 화면.
 *  - 접근성 서비스 활성화 상태 표시 + 설정 화면으로 이동
 *  - Android 13+ 알림 권한 요청
 *  - 감지 대상 앱 선택(YouTube 기본 켜짐, Instagram 실험적·기본 꺼짐)
 *  - 개입 기준 시간(정책 값)과 화면 중앙 팝업 사용 여부, 임시 시연 모드 스위치
 *  - 개입 팝업을 누르면 이 화면으로 온다([EXTRA_FROM_INTERVENTION])
 *  - 서비스가 연결된 경우에만 현재 감지 상태 표시
 *  - 사용 기록: 오늘 집계 한 줄 + 전체 삭제(확인 대화상자). 통계 화면은 다음 단계.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val io = Executors.newSingleThreadExecutor()

    private val requestNotificationPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        Toast.makeText(
            this,
            if (granted) R.string.toast_permission_granted else R.string.toast_permission_denied,
            Toast.LENGTH_SHORT
        ).show()
        refreshStatus()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        ShortsNotifier.ensureChannel(this)

        binding.buttonOpenAccessibilitySettings.setOnClickListener {
            openAccessibilitySettings()
        }
        binding.buttonRequestNotification.setOnClickListener {
            requestNotificationPermissionIfNeeded()
        }

        // 감지 대상 앱 선택. 서비스가 SharedPreferences 변경을 듣고 바로 반영한다.
        binding.switchYouTube.isChecked = DetectionPreferences.isYouTubeEnabled(this)
        binding.switchInstagram.isChecked = DetectionPreferences.isInstagramEnabled(this)
        binding.switchYouTube.setOnCheckedChangeListener { _, checked ->
            DetectionPreferences.setYouTubeEnabled(this, checked)
        }
        binding.switchInstagram.setOnCheckedChangeListener { _, checked ->
            DetectionPreferences.setInstagramEnabled(this, checked)
        }

        setupInterventionSettings()
        binding.buttonDeleteRecords.setOnClickListener { confirmDeleteRecords() }
        recordNotificationTap(intent)

        // 첫 실행이면 알림 권한을 미리 물어본다. 없으면 감지해도 알림이 안 뜬다.
        requestNotificationPermissionIfNeeded(silentIfGranted = true)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        recordNotificationTap(intent)
    }

    override fun onDestroy() {
        io.shutdown()
        super.onDestroy()
    }

    /** 개입 알림을 눌러서 왔으면 그 개입에 "앱 열기" 반응을 기록한다(팝업 반응은 서비스가 기록). */
    private fun recordNotificationTap(intent: Intent?) {
        val id = intent?.getStringExtra(EXTRA_INTERVENTION_ID) ?: return
        intent.removeExtra(EXTRA_INTERVENTION_ID) // 화면 회전 등으로 다시 기록하지 않게
        runIo {
            UsageDatabase.get(this).setInterventionAction(id, InterventionAction.OPEN_APP, System.currentTimeMillis())
        }
    }

    /** 오늘(기기 현지 시간) 집계와 저장된 세션 수를 보여 준다. */
    private fun refreshRecords() {
        runIo {
            val db = UsageDatabase.get(this)
            val zone = ZoneId.systemDefault()
            val today = LocalDate.now(zone)
            val from = today.atStartOfDay(zone).toInstant().toEpochMilli()
            val to = today.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
            val day = DailyUsageCalculator.aggregate(db.sessionsOverlapping(from, to), zone, today, today).single()
            val total = db.sessionCount()
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                binding.textRecords.text = getString(
                    R.string.records_summary,
                    ShortsNotifier.formatWatch(this, day.estimatedWatchMs),
                    day.sessionCount,
                    total,
                    DetectionConfig.USAGE_RETENTION_DAYS.toInt()
                )
            }
        }
    }

    private fun confirmDeleteRecords() {
        AlertDialog.Builder(this)
            .setTitle(R.string.dialog_delete_records_title)
            .setMessage(R.string.dialog_delete_records_body)
            .setNegativeButton(R.string.dialog_cancel, null)
            .setPositiveButton(R.string.dialog_delete) { _, _ ->
                runIo {
                    UsageDatabase.get(this).deleteAll()
                    SavedTimeBaselinePreferences.clear(this)
                    InterventionPolicyPreferences.markRecordsCleared(this)
                    runOnUiThread {
                        if (isDestroyed) return@runOnUiThread
                        Toast.makeText(this, R.string.toast_records_deleted, Toast.LENGTH_SHORT).show()
                        refreshRecords()
                    }
                }
            }
            .show()
    }

    /** DB 작업은 메인 스레드 밖에서. 실패는 화면에 "못 읽음"으로만 표시하고 원문을 남기지 않는다. */
    private fun runIo(block: () -> Unit) {
        try {
            io.execute {
                try {
                    block()
                } catch (e: RuntimeException) {
                    Log.w(ShortsDetectionService.TAG, "기록 화면 작업 실패: ${e.javaClass.simpleName}")
                    runOnUiThread {
                        if (!isDestroyed) binding.textRecords.setText(R.string.records_unavailable)
                    }
                }
            }
        } catch (e: RuntimeException) {
            Log.w(ShortsDetectionService.TAG, "기록 화면 작업 예약 실패: ${e.javaClass.simpleName}")
        }
    }

    private fun setupInterventionSettings() {
        val current = InterventionPolicyPreferences.load(this).thresholdMs
        DetectionConfig.WATCH_THRESHOLD_CHOICES_MS.forEach { ms ->
            val label = ShortsNotifier.formatWatch(this, ms)
            val button = RadioButton(this).apply {
                id = android.view.View.generateViewId()
                text = if (ms < DetectionConfig.TEST_THRESHOLD_BELOW_MS) {
                    getString(R.string.threshold_test_suffix, label)
                } else {
                    label
                }
                isChecked = ms == current
                setOnCheckedChangeListener { _, checked ->
                    if (checked) changeThreshold(ms)
                }
            }
            binding.groupThreshold.addView(button)
        }
        binding.switchPopup.isChecked = DetectionPreferences.isPopupEnabled(this)
        binding.switchPopup.setOnCheckedChangeListener { _, checked ->
            DetectionPreferences.setPopupEnabled(this, checked)
            refreshStatus()
        }
        // 정식 설정 화면은 Compose 단계에서 만든다. 켤 때마다 시연용 단계가 Lv.1 부터 시작한다.
        binding.switchDemoMode.isChecked = InterventionPolicyPreferences.isDemoMode(this)
        binding.switchDemoMode.setOnCheckedChangeListener { _, checked ->
            InterventionPolicyPreferences.setDemoMode(this, checked)
            refreshStatus()
        }
    }

    /** 사용자가 바꾼 기준 시간은 정책에 저장하고 source=USER 로 기록한다. */
    private fun changeThreshold(ms: Long) {
        val before = InterventionPolicyPreferences.load(this)
        val change = InterventionCoordinator.userThresholdChange(
            before, ms, UUID.randomUUID().toString(), System.currentTimeMillis(),
            getString(R.string.policy_reason_user_threshold)
        ) ?: return
        InterventionPolicyPreferences.save(this, change.newPolicy)
        runIo { UsageDatabase.get(this).insertPolicyChange(change) }
    }

    override fun onResume() {
        super.onResume()
        // 설정 화면에서 돌아왔을 때 상태를 다시 읽는다.
        refreshStatus()
        refreshRecords()
    }

    private fun refreshStatus() {
        val accessibilityOn = isAccessibilityServiceEnabled()
        val notificationOn = ShortsNotifier.hasPostPermission(this)

        binding.textAccessibilityStatus.text = getString(
            R.string.status_accessibility,
            getString(if (accessibilityOn) R.string.state_on else R.string.state_off)
        )
        binding.textNotificationStatus.text = getString(
            R.string.status_notification,
            getString(if (notificationOn) R.string.state_granted else R.string.state_denied)
        )
        binding.buttonRequestNotification.isEnabled = !notificationOn

        // 서비스가 실제로 연결돼 있을 때만 상태를 보여 준다. 꺼져 있는데 "작동 중"처럼 보이면 안 된다.
        val status = DetectionStatus.current
        binding.textDetectionStatus.text = when {
            !accessibilityOn || !status.connected -> getString(R.string.status_detection_inactive)
            status.reason != null ->
                getString(R.string.status_detection_detail, status.state.name, status.reason.name)
            else -> getString(R.string.status_detection, status.state.name)
        }

        val policy = InterventionPolicyPreferences.resolve(this)
        binding.textPolicyStatus.text = getString(
            if (policy.demoMode) R.string.status_policy_demo else R.string.status_policy,
            policy.policy.level,
            ShortsNotifier.formatWatch(this, policy.thresholdMs)
        )

        binding.textHint.text = when {
            !accessibilityOn -> getString(R.string.hint_enable_accessibility)
            !notificationOn && !DetectionPreferences.isPopupEnabled(this) ->
                getString(R.string.hint_enable_notification)
            else -> getString(R.string.hint_ready)
        }
    }

    private fun openAccessibilitySettings() {
        try {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            Toast.makeText(this, R.string.toast_find_service, Toast.LENGTH_LONG).show()
        } catch (e: android.content.ActivityNotFoundException) {
            Toast.makeText(this, R.string.toast_settings_unavailable, Toast.LENGTH_SHORT).show()
        }
    }

    private fun requestNotificationPermissionIfNeeded(silentIfGranted: Boolean = false) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        if (ShortsNotifier.hasPostPermission(this)) {
            if (!silentIfGranted) {
                Toast.makeText(this, R.string.toast_permission_granted, Toast.LENGTH_SHORT).show()
            }
            return
        }
        requestNotificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    companion object {
        /** 개입 팝업에서 열렸음을 표시. 지금은 화면 구분 없이 같은 화면을 연다(개입 선택 화면은 다음 단계). */
        const val EXTRA_FROM_INTERVENTION = "from_intervention"

        /** 개입 알림을 눌렀을 때 반응을 기록할 개입 기록 id */
        const val EXTRA_INTERVENTION_ID = "intervention_id"
    }

    /** 설정 > 접근성에서 우리 서비스가 켜져 있는지 확인 */
    private fun isAccessibilityServiceEnabled(): Boolean {
        val target = ComponentName(this, ShortsDetectionService::class.java)

        val manager = getSystemService(AccessibilityManager::class.java)
        if (manager != null) {
            val enabled = manager.getEnabledAccessibilityServiceList(
                AccessibilityServiceInfo.FEEDBACK_ALL_MASK
            )
            if (enabled.any { ComponentName.unflattenFromString(it.id) == target }) return true
        }

        // 일부 기기에서 위 목록 갱신이 늦어, Secure 설정 문자열도 함께 확인한다.
        val setting = Settings.Secure.getString(
            contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false

        return setting.split(':').any {
            ComponentName.unflattenFromString(it.trim()) == target
        }
    }
}
