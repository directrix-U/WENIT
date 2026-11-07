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
import com.example.shortsdetector.R
import com.example.shortsdetector.ShortsDetectionService
import com.example.shortsdetector.data.usage.InterventionOutcome
import com.example.shortsdetector.data.usage.InterventionUserReason
import com.example.shortsdetector.ui.WenitActivity

/**
 * 단계별 개입 팝업. 접근성 서비스의 TYPE_ACCESSIBILITY_OVERLAY 창으로 띄운다
 * (SYSTEM_ALERT_WINDOW 권한 불필요. 이 창 타입은 접근성 서비스 Context 의 WindowManager 로만 추가할 수 있다).
 *
 * 단계별 레이아웃·창 모양은 [InterventionLevels] 가 정하고, 여기에는 종류별 화면 동작([Screen])만 있다.
 *
 *  - 가벼운 알림 / 성찰 질문: 화면 중앙 카드. 바깥 터치는 아래 앱으로 전달한다.
 *  - 숨 고르기: 화면 전체를 덮는다. 시간 흐름은 [BreathingTimeline] 이 정한다. 카운트다운 동안은 버튼이 없고
 *    터치를 막으며, 끝나면 버튼을 보여 주고, 답이 없으면 스스로 닫는다.
 *    홈·뒤로 같은 시스템 탐색은 막지 않으며, 이탈 신호가 잡히면 서비스가 바로 닫는다. 사용자가 끌 수 없는 상태는 없다.
 *  - 포커스를 가져가지 않는다(FLAG_NOT_FOCUSABLE). 키보드·뒤로가기 등은 아래 앱이 계속 받는다.
 *  - 멈춤을 고르면 영상을 멈춘 뒤 앱 화면으로 이동한다(사용자 탭이 있을 때만 Activity 를 연다).
 *    재생 중에 앱을 벗어나면 YouTube 가 작은 창(PiP)으로 넘어간다.
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

        /** 앱으로 이동하기 직전. 준비가 끝나면 [proceed] 를 부른다. */
        fun beforeLeaving(proceed: () -> Unit)
    }

    /**
     * 종류별 화면 동작. 새 [InterventionKind] 는 여기에 구현을 더한다.
     * 이미지·애니메이션은 레이아웃과 [attached] 에서 시작하고, 탭·끌기로 치우는 화면은 [Host] 를 쓴다.
     */
    private interface Screen {
        /** 창을 붙이기 전. 버튼을 연결한다. */
        fun bind(v: View, host: Host) = Unit

        /** 창을 붙인 뒤 */
        fun attached(v: View, host: Host) = Unit
    }

    /** 화면이 쓰는 창 조작 */
    private interface Host {
        val listener: Listener

        /** 사용자의 반응으로 개입을 끝낸다. 버튼이 없는 화면도 이것으로 끝낸다. */
        fun answer(outcome: InterventionOutcome)

        /** [OverlayWindow.FLOATING] 창을 옮긴다. 끌기에 쓴다. */
        fun moveWindowBy(dx: Int, dy: Int)
    }

    private val noticeScreen = object : Screen {}

    private val questionScreen = object : Screen {
        override fun bind(v: View, host: Host) = bindReasons(v, host.listener)
    }

    private val breathingScreen = object : Screen {
        override fun attached(v: View, host: Host) = startBreathing(v, host.listener)
    }

    private fun screenFor(kind: InterventionKind): Screen = when (kind) {
        InterventionKind.NOTICE -> noticeScreen
        InterventionKind.QUESTION -> questionScreen
        InterventionKind.BREATHING -> breathingScreen
    }

    private val handler = Handler(Looper.getMainLooper())
    private var view: View? = null
    private var timeline: BreathingTimeline? = null

    val isShowing: Boolean get() = view != null

    /**
     * @param watchMs 이 개입 시점의 시청시간. 멈춘 뒤 홈 화면에 보여 준다.
     * @return 실제로 창을 붙였으면 true
     */
    fun show(level: Int, copy: InterventionCopy, watchMs: Long, listener: Listener): Boolean {
        dismiss()
        val wm = service.getSystemService(WindowManager::class.java) ?: return false
        return try {
            val spec = InterventionLevels.of(level)
            val screen = screenFor(spec.kind)
            val v = LayoutInflater.from(service).inflate(spec.layout, null)
            v.findViewById<TextView>(R.id.popupTitle).text = copy.title
            v.findViewById<TextView?>(R.id.popupBody)?.apply {
                text = copy.body.orEmpty()
                visibility = if (copy.body.isNullOrEmpty()) View.GONE else View.VISIBLE
            }
            val params = paramsFor(spec.window)
            val host = hostFor(v, params, watchMs, listener)
            bindButtons(v, host)
            screen.bind(v, host)
            wm.addView(v, params)
            view = v
            screen.attached(v, host)
            true
        } catch (e: RuntimeException) {
            // BadTokenException, InflateException 등. 원문 없이 예외 종류만 남긴다.
            Log.w(ShortsDetectionService.TAG, "개입 팝업 표시 실패: ${e.javaClass.simpleName}")
            dismiss()
            false
        }
    }

    private fun hostFor(v: View, params: WindowManager.LayoutParams, watchMs: Long, listener: Listener): Host {
        val owner = listener
        return object : Host {
            private var answered = false
            override val listener get() = owner

            override fun answer(outcome: InterventionOutcome) {
                if (answered) return
                answered = true
                try {
                    owner.onResponse(outcome)
                } catch (e: RuntimeException) {
                    Log.w(ShortsDetectionService.TAG, "개입 반응 기록 실패: ${e.javaClass.simpleName}")
                }
                if (outcome == InterventionOutcome.STOPPED) owner.beforeLeaving { openApp(watchMs) } else dismiss()
            }

            override fun moveWindowBy(dx: Int, dy: Int) {
                params.x += dx
                params.y += dy
                try {
                    service.getSystemService(WindowManager::class.java)?.updateViewLayout(v, params)
                } catch (e: RuntimeException) {
                    Log.w(ShortsDetectionService.TAG, "개입 창 이동 실패: ${e.javaClass.simpleName}")
                }
            }
        }
    }

    /** 버튼이 없는 화면도 있으므로 있을 때만 연결한다. */
    private fun bindButtons(v: View, host: Host) {
        v.findViewById<View?>(R.id.popupContinue)?.setOnClickListener { host.answer(InterventionOutcome.CONTINUED) }
        v.findViewById<View?>(R.id.popupStop)?.setOnClickListener { host.answer(InterventionOutcome.STOPPED) }
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

    private fun paramsFor(window: OverlayWindow) = when (window) {
        OverlayWindow.CARD -> cardParams()
        OverlayWindow.FULL_SCREEN -> fullScreenParams()
        OverlayWindow.FLOATING -> floatingParams()
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

    /** FLAG_NOT_TOUCH_MODAL: 창 바깥 터치는 아래 앱으로 간다. 위치는 왼쪽 위 기준이라 끌기로 옮기기 쉽다. */
    private fun floatingParams() = WindowManager.LayoutParams(
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
        PixelFormat.TRANSLUCENT
    ).apply { gravity = Gravity.TOP or Gravity.START }

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

    private fun openApp(watchMs: Long) {
        dismiss()
        try {
            // 접근성 서비스가 바인딩된 앱은 백그라운드 Activity 시작 제한의 예외다. 그래도 사용자 탭일 때만 연다.
            service.startActivity(
                Intent(service, WenitActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                    .putExtra(WenitActivity.EXTRA_FROM_INTERVENTION, true)
                    .putExtra(WenitActivity.EXTRA_STOPPED_WATCH_MS, watchMs)
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
