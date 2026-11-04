package com.example.shortsdetector.ai

/** AI 문구를 화면 길이에 맞춘다. Android 의존성 없음. */
object TextFit {

    private const val ELLIPSIS = "…"
    private val TRAILING_PUNCTUATION = charArrayOf(' ', ',', '·', '-', '(')

    /**
     * [max] 자를 넘으면 마지막 띄어쓰기 위치에서 자르고 "…" 을 붙인다. 결과는 "…" 을 포함해 [max] 자 이내다.
     * 띄어쓰기가 없는 한 덩어리 문구만 글자 단위로 자른다.
     */
    fun fit(text: String, max: Int): String {
        val clean = text.trim()
        if (clean.length <= max) return clean
        val room = clean.take(max - ELLIPSIS.length)
        val space = room.lastIndexOf(' ')
        val head = if (space > 0) room.substring(0, space) else room
        return head.trimEnd(*TRAILING_PUNCTUATION) + ELLIPSIS
    }
}
