package com.agentphone.accessibility

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import kotlin.math.max

/**
 * Optional advanced capability. This service is only active after the user explicitly enables it
 * in Android Settings > Accessibility. It is intentionally kept in a separate Gradle module so the
 * rest of the app can be built and shipped without it, and so its Play policy implications are
 * isolated and documented.
 */
class AgentAccessibilityService : AccessibilityService() {

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    override fun onInterrupt() = Unit

    override fun onDestroy() {
        if (instance === this) instance = null
        super.onDestroy()
    }

    fun snapshot(maxNodes: Int = 300): ScreenSnapshot {
        val root = rootInActiveWindow ?: return ScreenSnapshot("", emptyList(), text = "")
        val nodes = mutableListOf<ScreenNode>()
        val visited = HashSet<String>()
        var truncated = false

        fun walk(node: AccessibilityNodeInfo?, depth: Int) {
            if (node == null || nodes.size >= maxNodes || depth > 40) {
                truncated = truncated || nodes.size >= maxNodes
                return
            }
            val key = nodeKey(node)
            if (!visited.add(key)) return
            val text = listOfNotNull(node.text?.toString(), node.contentDescription?.toString())
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .distinct()
                .joinToString(" | ")
            if (text.isNotEmpty()) {
                val rect = Rect()
                node.getBoundsInScreen(rect)
                nodes += ScreenNode(
                    text = text.take(500),
                    className = node.className?.toString().orEmpty(),
                    clickable = node.isClickable,
                    editable = node.isEditable,
                    enabled = node.isEnabled,
                    left = rect.left,
                    top = rect.top,
                    right = rect.right,
                    bottom = rect.bottom,
                )
            }
            for (i in 0 until node.childCount) walk(node.getChild(i), depth + 1)
        }
        walk(root, 0)
        val packageName = root.packageName?.toString().orEmpty()
        return ScreenSnapshot(
            packageName = packageName,
            nodes = nodes,
            truncated = truncated,
            text = nodes.joinToString("\n") { it.text },
        )
    }

    fun clickText(needle: String): Boolean {
        val root = rootInActiveWindow ?: return false
        val target = findNode(root) { node ->
            val text = listOfNotNull(node.text, node.contentDescription).joinToString(" ")
            text.contains(needle, ignoreCase = true) && node.isVisibleToUser
        } ?: return false
        var candidate: AccessibilityNodeInfo? = target
        while (candidate != null) {
            if (candidate.isClickable &&
                candidate.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            ) {
                return true
            }
            candidate = candidate.parent
        }
        val rect = Rect()
        target.getBoundsInScreen(rect)
        return tap(rect.exactCenterX(), rect.exactCenterY())
    }

    fun clickQuickSetting(vararg labels: String): Boolean {
        val root = rootInActiveWindow ?: return false
        fun matches(node: AccessibilityNodeInfo): Boolean {
            val text = listOfNotNull(node.text, node.contentDescription).joinToString(" ")
            return labels.any { text.contains(it, ignoreCase = true) } && node.isVisibleToUser
        }
        val switch = findNode(root) {
            matches(it) && it.className?.contains("Switch", ignoreCase = true) == true
        }
        val target = switch ?: findNode(root, ::matches) ?: return false
        if (target.isClickable && target.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
            return true
        }
        var candidate: AccessibilityNodeInfo? = target
        while (candidate != null) {
            if (candidate.isClickable &&
                candidate.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            ) {
                return true
            }
            candidate = candidate.parent
        }
        val rect = Rect()
        target.getBoundsInScreen(rect)
        return tap(rect.exactCenterX(), rect.exactCenterY())
    }

    fun activePackage(): String? =
        rootInActiveWindow?.packageName?.toString()

    fun tap(x: Float, y: Float): Boolean {
        val path = Path().apply { moveTo(max(0f, x), max(0f, y)) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 80))
            .build()
        return dispatchGesture(gesture, null, null)
    }

    fun swipe(fromX: Float, fromY: Float, toX: Float, toY: Float, durationMs: Long = 300): Boolean {
        val path = Path().apply {
            moveTo(fromX, fromY)
            lineTo(toX, toY)
        }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, durationMs))
            .build()
        return dispatchGesture(gesture, null, null)
    }

    fun scroll(forward: Boolean): Boolean {
        val root = rootInActiveWindow ?: return false
        val scrollable = findNode(root) { it.isScrollable }
        if (scrollable != null) {
            val action = if (forward) {
                AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
            } else {
                AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
            }
            if (scrollable.performAction(action)) return true
        }
        val metrics = resources.displayMetrics
        val cx = metrics.widthPixels / 2f
        return if (forward) {
            swipe(cx, metrics.heightPixels * 0.75f, cx, metrics.heightPixels * 0.25f)
        } else {
            swipe(cx, metrics.heightPixels * 0.25f, cx, metrics.heightPixels * 0.75f)
        }
    }

    fun typeText(text: String): Boolean {
        val root = rootInActiveWindow ?: return false
        val editable = findNode(root) { it.isEditable && it.isFocused }
            ?: findNode(root) { it.isEditable }
            ?: return false
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        return editable.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
    }

    fun pressBack(): Boolean =
        performGlobalAction(GLOBAL_ACTION_BACK)

    fun pressHome(): Boolean =
        performGlobalAction(GLOBAL_ACTION_HOME)

    fun pressRecents(): Boolean =
        performGlobalAction(GLOBAL_ACTION_RECENTS)

    fun openNotificationShade(): Boolean =
        performGlobalAction(GLOBAL_ACTION_NOTIFICATIONS)

    fun openQuickSettings(): Boolean =
        performGlobalAction(GLOBAL_ACTION_QUICK_SETTINGS)

    fun toggleSplitScreen(): Boolean =
        performGlobalAction(GLOBAL_ACTION_TOGGLE_SPLIT_SCREEN)

    fun lockScreen(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            performGlobalAction(GLOBAL_ACTION_LOCK_SCREEN)
        } else {
            false
        }

    fun takeScreenshot(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            performGlobalAction(GLOBAL_ACTION_TAKE_SCREENSHOT)
        } else {
            false
        }

    fun openPowerMenu(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            performGlobalAction(GLOBAL_ACTION_POWER_DIALOG)
        } else {
            false
        }

    private fun findNode(
        root: AccessibilityNodeInfo,
        predicate: (AccessibilityNodeInfo) -> Boolean,
    ): AccessibilityNodeInfo? {
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        val seen = HashSet<String>()
        queue.add(root)
        while (queue.isNotEmpty()) {
            val node = queue.removeFirst()
            val key = nodeKey(node)
            if (!seen.add(key)) continue
            if (predicate(node)) return node
            for (i in 0 until node.childCount) node.getChild(i)?.let(queue::addLast)
        }
        return null
    }

    private fun nodeKey(node: AccessibilityNodeInfo): String =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            node.uniqueId ?: node.hashCode().toString()
        } else {
            node.hashCode().toString()
        }

    companion object {
        @Volatile
        var instance: AgentAccessibilityService? = null
            private set
    }
}

data class ScreenNode(
    val text: String,
    val className: String,
    val clickable: Boolean,
    val editable: Boolean,
    val enabled: Boolean,
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
)

data class ScreenSnapshot(
    val packageName: String,
    val nodes: List<ScreenNode>,
    val text: String,
    val truncated: Boolean = false,
)
