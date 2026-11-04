package com.example.shortsdetector.intervention

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import com.example.shortsdetector.MainActivity
import com.example.shortsdetector.R
import com.example.shortsdetector.ShortsDetectionService
import com.example.shortsdetector.data.usage.InterventionOutcome
import com.example.shortsdetector.data.usage.InterventionUserReason

/**
 * 단계별 개입 팝업. 접근성 서비스의 TYPE_ACCESSIBILITY_OVERLAY 창으로 띄운다
 * (SYSTEM_ALERT_WINDOW 권한 불필요. 이 창 타입은 접근성 서비스 Context 의 WindowManager 로만 추가할 수 있다).
 *
 *  - Lv.1 가벼운 알림 / Lv.2 성찰 질문: 화면 중앙 카드. 바깥 터치는 아래 앱으로 전달한다.
 *  - Lv.3 숨 고르기: 화면 전체를 덮는다. 시간 흐름은 [BreathingTimeline] 이 정한다. 카운트다운 동안은 버튼이 없고
 *    터치를 막으며, 끝나면 버튼을 보여 주고, 답이 없으면 스스로 닫는다.
 *    홈·뒤로 같은 시스템 탐색은 막지 않으며, 이탈 신호가 잡히면 서비스가 바로 닫는다. 사용자가 끌 수 없는 상태는 없다.
 *  - 포커스를 가져가지 않는다(FLAG_NOT_FOCUSABLE). 키보드·뒤로가기 등은 아래 앱이 계속 받는다.
 *  - 멈춤을 고르면 앱 화면으로 이동한다(사용자 탭이 있을 때만 Activity 를 연다).
 *
 * 메인 스레드에서만 호출한다. 예외는 여기서 격리하고 실패하면 false 를 돌려준다(호출 측이 알림으로 대체).
 * TODO: VERIFY ON DEVICE — 제조사 ROM 에서 전체 화면 오버레이 표시와 시스템 탐색 제스처.
 */
class InterventionPopup(private val service: AccessibilityService) {

    interface Listener {
        fun onReasonChosen(reason: InterventionUserReason)

        /** 사용자가 버튼을 눌렀을 때 한 번 호출한다. */
        fun onResponse(outcome: InterventionOutcome)

        /** Lv.3 카운트다운 시작. 이 시간은 시청시간에서 뺀다. */
        fun onBreathingStarted()

        /** Lv.3 카운트다운이 끝나 버튼이 보일 때 */
        fun onBreathingFinished()

        /** Lv.3 이 답 없이 스스로 닫혔을 때. */
        fun onTimedOut()
    }

    private val handler = Handler(Looper.getMainLooper())
    private var view: View? = null
    private var timeline: BreathingTimeline? = null

    val isShowing: Boolean get() = view != null

    /** @return 실제로 창을 붙였으면 true */
    fun show(level: Int, copy: InterventionCopy, listener: Listener): Boolean {
        dismiss()
        val wm = service.getSystemService(WindowManager::class.java) ?: return false
        return try {
            val layout = when (level) {
                1 -> R.layout.overlay_intervention_lv1
                2 -> R.layout.overlay_intervention_lv2
                else -> R.layout.overlay_intervention_lv3
            }
            val v = LayoutInflater.from(service).inflate(layout, null)
            v.findViewById<TextView>(R.id.popupTitle).text = copy.title
            v.findViewById<TextView?>(R.id.popupBody)?.text = copy.body.orEmpty()
            bindButtons(v, listener)
            if (level == 2) bindReasons(v, listener)

            val fullScreen = level >= InterventionPolicyBounds.MAX_LEVEL
            wm.addView(v, if (fullScreen) fullScreenParams() else cardParams())
            view = v
            if (fullScreen) startBreathing(v, listener)
            true
        } catch (e: RuntimeException) {
            // BadTokenException, InflateException 등. 원문 없이 예외 종류만 남긴다.
            Log.w(ShortsDetectionService.TAG, "개입 팝업 표시 실패: ${e.javaClass.simpleName}")
            dismiss()
            false
        }
    }

    private fun bindButtons(v: View, listener: Listener) {
        var answered = false
        fun answer(outcome: InterventionOutcome) {
            if (answered) return
            answered = true
            try {
                listener.onResponse(outcome)
            } catch (e: RuntimeException) {
                Log.w(ShortsDetectionService.TAG, "개입 반응 기록 실패: ${e.javaClass.simpleName}")
            }
            if (outcome == InterventionOutcome.STOPPED) openApp() else dismiss()
        }
        v.findViewById<View>(R.id.popupContinue).setOnClickListener { answer(InterventionOutcome.CONTINUED) }
        v.findViewById<View>(R.id.popupStop).setOnClickListener { answer(InterventionOutcome.STOPPED) }
    }

    /** 이유를 하나 고르면 답 버튼이 켜진다. 팝업 바깥은 막지 않으므로 고르지 않아도 영상을 볼 수 있다. */
    private fun bindReasons(v: View, listener: Listener) {
        val options = listOf(
            R.id.reasonFun to InterventionUserReason.FUN,
            R.id.reasonBored to InterventionUserReason.BORED,
            R.id.reasonTired to InterventionUserReason.TIRED,
            R.id.reasonProcrastinating to InterventionUserReason.PROCRASTINATING
        ).map { (id, reason) -> v.findViewById<View>(id) to reason }
        val buttons = listOf(v.findViewById<View>(R.id.popupContinue), v.findViewById<View>(R.id.popupStop))
        buttons.forEach { it.isEnabled = false; it.alpha = DISABLED_ALPHA }
        for ((option, reason) in options) {
            option.setOnClickListener {
                options.forEach { (o, _) -> o.isSelected = o === option }
                buttons.forEach { it.isEnabled = true; it.alpha = 1f }
                listener.onReasonChosen(reason)
            }
        }
    }

    private fun startBreathing(v: View, listener: Listener) {
        val count = v.findViewById<TextView>(R.id.breathingCount)
        val buttons = v.findViewById<View>(R.id.popupButtons)
        timeline = BreathingTimeline(
            post = { delayMs, action -> handler.postDelayed(action, delayMs) },
            onCount = { left -> count.text = service.getString(R.string.popup_lv3_countdown, left) },
            onButtons = {
                count.visibility = View.GONE
                buttons.visibility = View.VISIBLE
                listener.onBreathingFinished()
            },
            onTimedOut = listener::onTimedOut
        ).also {
            listener.onBreathingStarted()
            it.start()
        }
    }

    private fun cardParams(): WindowManager.LayoutParams {
        val metrics = service.resources.displayMetrics
        val maxWidth = service.resources.getDimensionPixelSize(R.dimen.popup_max_width)
        val width = minOf((metrics.widthPixels * POPUP_WIDTH_RATIO).toInt(), maxWidth)
        return WindowManager.LayoutParams(
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
    }

    private fun fullScreenParams() = WindowManager.LayoutParams(
        WindowManager.LayoutParams.MATCH_PARENT,
        WindowManager.LayoutParams.MATCH_PARENT,
        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
        PixelFormat.TRANSLUCENT
    ).apply { windowAnimations = android.R.style.Animation_Dialog }

    fun dismiss() {
        timeline?.cancel()
        timeline = null
        handler.removeCallbacksAndMessages(null)
        val v = view ?: return
        view = null
        try {
            service.getSystemService(WindowManager::class.java)?.removeViewImmediate(v)
        } catch (e: RuntimeException) {
            Log.w(ShortsDetectionService.TAG, "개입 팝업 제거 실패: ${e.javaClass.simpleName}")
        }
    }

    private fun openApp() {
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

    private companion object {
        const val POPUP_WIDTH_RATIO = 0.86f
        const val DISABLED_ALPHA = 0.4f
    }
}
