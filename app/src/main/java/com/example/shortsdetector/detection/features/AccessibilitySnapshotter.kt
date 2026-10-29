package com.example.shortsdetector.detection.features

import android.graphics.Rect
import android.os.Build
import android.view.accessibility.AccessibilityNodeInfo
import com.example.shortsdetector.detection.model.ScreenSnapshot
import com.example.shortsdetector.detection.model.UiNode

/**
 * [AccessibilityNodeInfo] 트리를 [ScreenSnapshot] 으로 복사한다.
 *
 *  - 복사하는 것: viewId, className, bounds, isVisibleToUser, isScrollable
 *  - **복사하지 않는 것: text, contentDescription, hint 등 화면 원문**
 *  - 너비 우선, [maxNodes] 개에서 멈추고 truncated 로 표시한다(비정상적으로 큰 트리에서 ANR 방지).
 *  - 순회 도중 노드가 사라지면(stale) 그 노드만 건너뛴다.
 *  - 자식 노드는 복사 즉시 해제한다. root 의 해제는 호출한 쪽 책임이다.
 */
class AccessibilitySnapshotter(private val maxNodes: Int) {

    /**
     * @param lookupViewIds 순회와 별도로 ID 검색으로 찾아 합칠 ID. 순회는 "중요하지 않은" 뷰를 건너뛰므로
     *   핵심 신호는 기존 프로토타입처럼 [AccessibilityNodeInfo.findAccessibilityNodeInfosByViewId] 로 찾는다.
     */
    fun capture(
        root: AccessibilityNodeInfo,
        packageName: String,
        screenWidth: Int,
        screenHeight: Int,
        lookupViewIds: Set<String> = emptySet(),
        fullTree: Boolean = true
    ): ScreenSnapshot {
        // fullTree=false: root 한 개만 복사해 "트리를 읽을 수 있었다"는 사실을 남기고, 나머지는 ID 검색으로 찾는다.
        val traversed = if (fullTree) traverse(root) else Traversal(listOfNotNull(copy(root, Rect())), false)
        val looked = lookup(root, lookupViewIds)
        // 순회와 검색에서 같은 노드가 두 번 나오지 않게 한다(같은 ID + 같은 bounds).
        val merged = traversed.nodes + looked.filterNot { l ->
            traversed.nodes.any { it.viewId == l.viewId && it.left == l.left && it.top == l.top &&
                it.right == l.right && it.bottom == l.bottom }
        }
        return ScreenSnapshot(packageName, screenWidth, screenHeight, merged, traversed.truncated)
    }

    private class Traversal(val nodes: List<UiNode>, val truncated: Boolean)

    private fun lookup(root: AccessibilityNodeInfo, viewIds: Set<String>): List<UiNode> {
        if (viewIds.isEmpty()) return emptyList()
        val bounds = Rect()
        val found = ArrayList<UiNode>()
        for (id in viewIds) {
            val nodes = try {
                root.findAccessibilityNodeInfosByViewId(id).orEmpty()
            } catch (e: RuntimeException) {
                emptyList()
            }
            for (node in nodes) {
                try {
                    // 검색은 부분 일치할 수 있으므로 ID 를 다시 정확히 비교한다.
                    copy(node, bounds)?.takeIf { it.viewId == id }?.let(found::add)
                } finally {
                    recycle(node)
                }
            }
        }
        return found
    }

    private fun traverse(root: AccessibilityNodeInfo): Traversal {
        val nodes = ArrayList<UiNode>(INITIAL_CAPACITY)
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        val bounds = Rect()
        var truncated = false

        queue.addLast(root)
        try {
            while (queue.isNotEmpty()) {
                val node = queue.removeFirst()
                try {
                    if (nodes.size >= maxNodes) {
                        truncated = true
                        continue
                    }
                    copy(node, bounds)?.let(nodes::add)
                    val childCount = safeChildCount(node)
                    for (i in 0 until childCount) {
                        val child = try {
                            node.getChild(i)
                        } catch (e: RuntimeException) {
                            null
                        } ?: continue
                        queue.addLast(child)
                    }
                } finally {
                    if (node !== root) recycle(node)
                }
            }
        } finally {
            // 상한에 걸려 남은 노드도 해제한다.
            while (queue.isNotEmpty()) {
                val leftover = queue.removeFirst()
                if (leftover !== root) recycle(leftover)
            }
        }

        return Traversal(nodes, truncated)
    }

    private fun copy(node: AccessibilityNodeInfo, bounds: Rect): UiNode? = try {
        node.getBoundsInScreen(bounds)
        UiNode(
            viewId = node.viewIdResourceName,
            className = node.className?.toString(),
            left = bounds.left,
            top = bounds.top,
            right = bounds.right,
            bottom = bounds.bottom,
            visibleToUser = node.isVisibleToUser,
            scrollable = node.isScrollable
        )
    } catch (e: RuntimeException) {
        null
    }

    private fun safeChildCount(node: AccessibilityNodeInfo): Int = try {
        node.childCount
    } catch (e: RuntimeException) {
        0
    }

    private fun recycle(node: AccessibilityNodeInfo) {
        // recycle() 은 API 33 에서 deprecated 되었고 그 이후로는 no-op 이지만,
        // minSdk 26 을 지원하므로 구버전에서는 명시적으로 해제해 준다.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            @Suppress("DEPRECATION")
            node.recycle()
        }
    }

    private companion object {
        const val INITIAL_CAPACITY = 160
    }
}
