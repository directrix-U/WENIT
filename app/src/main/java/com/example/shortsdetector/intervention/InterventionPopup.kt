package com.example.shortsdetector.intervention

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.graphics.PixelFormat
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import com.example.shortsdetector.MainActivity
import com.example.shortsdetector.R
import com.example.shortsdetector.ShortsDetectionService

/**
 * 화면 중앙 개입 팝업. 접근성 서비스의 TYPE_ACCESSIBILITY_OVERLAY 창으로 띄운다
 * (SYSTEM_ALERT_WINDOW 권한 불필요. 이 창 타입은 접근성 서비스 Context 의 WindowManager 로만 추가할 수 있다).
 *
 *  - 카드 또는 "앱 열기"를 누르면 앱 화면으로 이동한다(사용자 탭이 있을 때만 Activity 를 연다).
 *  - "닫기"는 팝업만 닫는다. 바깥 터치는 아래 앱으로 그대로 전달한다(FLAG_NOT_TOUCH_MODAL) — 막지 않는다.
 *  - 포커스를 가져가지 않는다(FLAG_NOT_FOCUSABLE). 키보드·뒤로가기 등은 아래 앱이 계속 받는다.
 *
 * 메인 스레드에서만 호출한다. 예외는 여기서 격리하고 실패하면 false 를 돌려준다(호출 측이 알림으로 대체).
 * TODO: VERIFY ON DEVICE — 팝업이 떠 있는 동안 rootInActiveWindow / 감지 상태가 흔들리지 않는지.
 */
class InterventionPopup(private val service: AccessibilityService) {

    private var view: View? = null

    val isShowing: Boolean get() = view != null

    enum class Action { OPEN_APP, DISMISS }

    /**
     * @param title 표시할 제목.
     * @param onAction 사용자가 버튼/카드를 눌렀을 때 한 번 호출(기록용). 반응 없이 사라지면 호출하지 않는다.
     * @return 실제로 창을 붙였으면 true
     */
    fun show(title: String, onAction: (Action) -> Unit = {}): Boolean {
        dismiss()
        val wm = service.getSystemService(WindowManager::class.java) ?: return false
        return try {
            val v = LayoutInflater.from(service).inflate(R.layout.overlay_intervention, null)
            var reported = false
            fun report(a: Action) {
                if (reported) return
                reported = true
                try { onAction(a) } catch (e: RuntimeException) {
                    Log.w(ShortsDetectionService.TAG, "개입 반응 기록 실패: ${e.javaClass.simpleName}")
                }
            }
            v.findViewById<TextView>(R.id.popupTitle).text = title
            v.findViewById<View>(R.id.popupCard).setOnClickListener { report(Action.OPEN_APP); openApp() }
            v.findViewById<View>(R.id.popupOpen).setOnClickListener { report(Action.OPEN_APP); openApp() }
            v.findViewById<View>(R.id.popupDismiss).setOnClickListener {
                Log.i(ShortsDetectionService.TAG, "개입 팝업: 닫기")
                report(Action.DISMISS)
                dismiss()
            }

            val metrics = service.resources.displayMetrics
            val maxWidth = (360 * metrics.density).toInt()
            val width = minOf((metrics.widthPixels * 0.86f).toInt(), maxWidth)

            val params = WindowManager.LayoutParams(
                width,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.CENTER
                windowAnimations = android.R.style.Animation_Dialog
            }
            wm.addView(v, params)
            view = v
            true
        } catch (e: RuntimeException) {
            // BadTokenException, InflateException 등. 원문 없이 예외 종류만 남긴다.
            Log.w(ShortsDetectionService.TAG, "개입 팝업 표시 실패: ${e.javaClass.simpleName}")
            view = null
            false
        }
    }

    fun dismiss() {
        val v = view ?: return
        view = null
        try {
            service.getSystemService(WindowManager::class.java)?.removeViewImmediate(v)
        } catch (e: RuntimeException) {
            Log.w(ShortsDetectionService.TAG, "개입 팝업 제거 실패: ${e.javaClass.simpleName}")
        }
    }

    private fun openApp() {
        Log.i(ShortsDetectionService.TAG, "개입 팝업: 앱 열기")
        dismiss()
        try {
            // 접근성 서비스가 바인딩된 앱은 백그라운드 Activity 시작 제한의 예외다. 그래도 사용자 탭일 때만 연다.
            service.startActivity(
                Intent(service, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                    .putExtra(MainActivity.EXTRA_FROM_INTERVENTION, true)
            )
        } catch (e: RuntimeException) {
            Log.w(ShortsDetectionService.TAG, "앱 열기 실패: ${e.javaClass.simpleName}")
        }
    }
}
