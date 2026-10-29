package com.example.shortsdetector

import android.Manifest
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.ComponentName
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.accessibility.AccessibilityManager
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.example.shortsdetector.databinding.ActivityMainBinding

/**
 * 프로토타입용 최소 화면.
 *  - 접근성 서비스 활성화 상태 표시 + 설정 화면으로 이동
 *  - Android 13+ 알림 권한 요청
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

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

        // 첫 실행이면 알림 권한을 미리 물어본다. 없으면 감지해도 알림이 안 뜬다.
        requestNotificationPermissionIfNeeded(silentIfGranted = true)
    }

    override fun onResume() {
        super.onResume()
        // 설정 화면에서 돌아왔을 때 상태를 다시 읽는다.
        refreshStatus()
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
        binding.textHint.text = when {
            !accessibilityOn -> getString(R.string.hint_enable_accessibility)
            !notificationOn -> getString(R.string.hint_enable_notification)
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
