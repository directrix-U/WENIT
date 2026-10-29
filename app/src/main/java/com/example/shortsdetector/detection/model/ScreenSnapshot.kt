package com.example.shortsdetector.detection.model

/**
 * 접근성 노드 하나에서 판정에 필요한 구조 정보만 복사한 값.
 *
 * text / contentDescription 같은 화면 원문은 **의도적으로 담지 않는다.**
 * Android 프레임워크 타입을 쓰지 않으므로 JVM 단위 테스트에서 그대로 만들 수 있다.
 */
data class UiNode(
    val viewId: String?,
    val className: String?,
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
    val visibleToUser: Boolean,
    val scrollable: Boolean = false
) {
    val width: Int get() = right - left
    val height: Int get() = bottom - top

    /** 음수/뒤집힌 bounds 는 0 으로 본다. */
    val area: Long
        get() = if (width <= 0 || height <= 0) 0L else width.toLong() * height.toLong()
}

/**
 * 한 시점의 활성 창 구조 요약.
 *
 * @property truncated 노드 수 상한에 걸려 순회를 중간에 멈췄는지. true 면 "없음"을 확신할 수 없다.
 */
data class ScreenSnapshot(
    val packageName: String,
    val screenWidth: Int,
    val screenHeight: Int,
    val nodes: List<UiNode>,
    val truncated: Boolean = false
) {
    val screenArea: Long
        get() = if (screenWidth <= 0 || screenHeight <= 0) 0L
        else screenWidth.toLong() * screenHeight.toLong()

    /** 화면 대비 면적비(0f..). 화면 크기를 모르면 0. */
    fun areaRatio(node: UiNode): Float {
        val screen = screenArea
        if (screen <= 0L) return 0f
        return (node.area.toDouble() / screen.toDouble()).toFloat()
    }

    /** 첫 노드는 활성 창의 root 다(스냅샷을 만드는 쪽이 지킨다). */
    val root: UiNode? get() = nodes.firstOrNull()

    /** viewId 가 **전체 문자열로 정확히** 일치하는 노드만 돌려준다(부분 일치 금지). */
    fun nodesWithId(viewId: String): List<UiNode> = nodes.filter { it.viewId == viewId }
}
