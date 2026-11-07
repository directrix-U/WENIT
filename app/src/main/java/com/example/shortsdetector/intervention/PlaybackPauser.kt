package com.example.shortsdetector.intervention

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.util.Log
import android.view.KeyEvent
import com.example.shortsdetector.ShortsDetectionService

/** 일시정지에 쓰는 플랫폼 동작. 실패해도 예외를 던지지 않는다. */
interface PlaybackControls {
    fun sendPauseKey()
    fun isPlaying(): Boolean

    /** @return 오디오 포커스를 얻었으면 true */
    fun requestFocus(): Boolean
    fun releaseFocus()
}

enum class PauseResult {
    /** 처음부터 재생 중이 아니었다 */
    NOT_PLAYING,

    /** 미디어 키로 멈췄다 */
    KEY,

    /** 오디오 포커스로 멈췄다 */
    FOCUS,

    /** 멈추지 못했다 */
    FAILED
}

/**
 * 재생 중인 영상을 멈춘다. 미디어 키를 먼저 보내고, 소리가 이어지면 오디오 포커스를 가져온다.
 *
 * 포커스는 임시가 아닌 AUDIOFOCUS_GAIN 으로 요청한다. 임시 포커스를 돌려주면 플레이어가 스스로 재생을 다시 시작할 수 있어
 * "멈춘 상태 유지, 다시 재생은 사용자가" 를 지킬 수 없다. 멈춘 뒤에는 포커스를 오래 들고 있지 않고 곧 돌려준다.
 *
 * 메인 스레드에서만 호출한다. 멈추는 중에 다시 부르면 진행 중인 시도의 결과를 함께 받는다.
 *
 * 실기기(SM-S906N, YouTube 21.44.534): 쇼츠의 MediaSession 이 STOPPED 라 미디어 키가 듣지 않았다.
 * TODO: VERIFY ON DEVICE — 다른 YouTube 버전과 Instagram Reels.
 */
class PlaybackPauser(
    private val controls: PlaybackControls,
    private val post: (delayMs: Long, action: () -> Unit) -> Unit
) {
    private val waiting = ArrayList<(PauseResult) -> Unit>()
    private var running = false
    private var focusHeld = false

    /**
     * 앱이 영상을 멈췄고 아직 소리가 돌아오지 않았다. 세션이 끝나도 유지한다. 멈춘 영상을 그대로 두고 YouTube 로
     * 돌아오면 새 세션이 시작되는데, 이때도 시청으로 세지 않기 위해서다.
     */
    var pausedByApp = false
        private set

    /** 앱이 멈춘 영상을 사용자가 다시 재생했으면 true 를 한 번 돌려준다. */
    fun checkResumed(): Boolean {
        if (!pausedByApp || !controls.isPlaying()) return false
        pausedByApp = false
        return true
    }

    fun pause(onDone: (PauseResult) -> Unit = {}) {
        waiting += onDone
        if (running) return
        if (!controls.isPlaying()) {
            finish(PauseResult.NOT_PLAYING)
            return
        }
        running = true
        controls.sendPauseKey()
        post(KEY_WAIT_MS) {
            if (!controls.isPlaying()) finish(PauseResult.KEY) else takeFocus()
        }
    }

    private fun takeFocus() {
        if (!controls.requestFocus()) {
            finish(PauseResult.FAILED)
            return
        }
        focusHeld = true
        waitForSilence(FOCUS_WAIT_MS)
    }

    private fun waitForSilence(leftMs: Long) {
        when {
            !controls.isPlaying() -> {
                finish(PauseResult.FOCUS)
                post(FOCUS_HOLD_MS, ::release)
            }
            leftMs <= 0L -> {
                release()
                finish(PauseResult.FAILED)
            }
            else -> post(POLL_MS) { waitForSilence(leftMs - POLL_MS) }
        }
    }

    /** 들고 있는 포커스를 돌려준다. 서비스가 끝날 때도 부른다. */
    fun release() {
        if (!focusHeld) return
        focusHeld = false
        controls.releaseFocus()
    }

    private fun finish(result: PauseResult) {
        running = false
        if (result == PauseResult.KEY || result == PauseResult.FOCUS) pausedByApp = true
        val callbacks = ArrayList(waiting)
        waiting.clear()
        callbacks.forEach { it(result) }
    }

    companion object {
        /** 미디어 키가 들었는지 보기까지 기다리는 시간. 포커스 시도가 그만큼 늦어진다. */
        const val KEY_WAIT_MS = 250L
        const val POLL_MS = 50L

        /** 포커스를 얻은 뒤 소리가 멎기를 기다리는 시간 */
        const val FOCUS_WAIT_MS = 800L

        /** 멈춘 뒤에도 포커스를 잠시 들고 있어 플레이어가 멈춘 상태로 자리 잡게 한다. */
        const val FOCUS_HOLD_MS = 800L
    }
}

/** [PlaybackControls] 의 [AudioManager] 구현. */
class AudioPlaybackControls(context: Context) : PlaybackControls {

    private val audio = context.getSystemService(AudioManager::class.java)
    private val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE)
                .build()
        )
        .setOnAudioFocusChangeListener { }
        .build()

    override fun sendPauseKey() {
        guarded {
            audio?.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PAUSE))
            audio?.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MEDIA_PAUSE))
        }
    }

    override fun isPlaying(): Boolean = audio?.isMusicActive == true

    override fun requestFocus(): Boolean = try {
        audio?.requestAudioFocus(request) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
    } catch (e: RuntimeException) {
        Log.w(ShortsDetectionService.TAG, "오디오 포커스 요청 실패: ${e.javaClass.simpleName}")
        false
    }

    override fun releaseFocus() {
        guarded { audio?.abandonAudioFocusRequest(request) }
    }

    private inline fun guarded(block: () -> Unit) {
        try {
            block()
        } catch (e: RuntimeException) {
            Log.w(ShortsDetectionService.TAG, "재생 제어 실패: ${e.javaClass.simpleName}")
        }
    }
}
