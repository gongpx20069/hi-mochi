package com.example.mochi_pet.platform.media

import android.view.accessibility.AccessibilityNodeInfo
import com.example.mochi_pet.core.apps.AppUiRole
import java.util.Locale

internal data class AppUiControl(val label: String, val actions: List<String>, val role: AppUiRole?)

internal object AppUiSemantics {
    private val dismissText = Regex(
        "关闭(?:按钮|广告|弹窗|浮窗|浮层|窗口|提示)?|取消|暂不|暂不开启|暂不打开|稍后|稍后再说|以后再说|" +
            "跳过(?:广告)?|[×✕✖]|close(?: (?:ad|advertisement|popup|dialog|window|button))?|dismiss|cancel|not now|maybe later|skip ad",
        RegexOption.IGNORE_CASE,
    )
    private val dismissTokens = setOf("close", "dismiss", "cancel", "skip")
    private val iconTokens = dismissTokens + setOf(
        "iv", "img", "image", "btn", "button", "icon", "view", "tv", "layout", "container", "area", "real",
        "ad", "ads", "popup", "dialog", "splash", "interstitial", "tt", "ks", "gdt",
    )
    private val search = Regex("search|搜索|搜歌|查找", RegexOption.IGNORE_CASE)
    private val searchContainer = Regex("(?:^|_)search_?(?:box|bar|field|input|container|entry)$", RegexOption.IGNORE_CASE)

    fun ownLabel(node: AccessibilityNodeInfo): String =
        listOfNotNull(node.text?.toString(), node.contentDescription?.toString()).distinct().joinToString(" ").take(500)

    fun describe(node: AccessibilityNodeInfo): AppUiControl? {
        val raw = label(node)
        val tokens = node.viewIdResourceName.orEmpty().substringAfterLast('/').replace(Regex("([a-z])([A-Z])"), "$1_$2")
            .lowercase(Locale.ROOT).split('_')
        val ownLabels = listOfNotNull(node.text?.toString(), node.contentDescription?.toString()).filter { it.isNotBlank() }
        val dismissal = (ownLabels.isNotEmpty() && ownLabels.all { dismissText.matches(it.trim()) }) ||
            (ownLabels.isEmpty() && singleChildLabel(node)?.let { dismissText.matches(it.trim()) } == true) ||
            (raw.isBlank() && node.childCount <= 1 &&
                tokens.any { it in dismissTokens } && tokens.all { it in iconTokens } &&
                ("skip" !in tokens || tokens.any { it in setOf("ad", "ads", "popup", "dialog", "splash", "interstitial") }))
        val role = when {
            dismissal -> AppUiRole.DISMISS
            isSearch(node) -> AppUiRole.SEARCH
            else -> null
        }
        if (role != AppUiRole.DISMISS && dismissText.matches(raw.trim())) return null
        if (role != AppUiRole.DISMISS && AppUiPrivacy.blocked(raw)) return null
        val actions = buildList {
            if (node.isEnabled && node.isClickable && (raw.isNotBlank() || role != null)) add("click")
            if (node.isEnabled && node.isEditable && role == AppUiRole.SEARCH) add("input")
            if (node.isEnabled && node.isScrollable) {
                add("scroll_forward")
                add("scroll_backward")
            }
        }
        if (raw.isBlank() && actions.isEmpty()) return null
        val display = when (role) {
            AppUiRole.DISMISS -> raw.ifBlank { "Close" }
            AppUiRole.SEARCH -> if (!node.isEditable && ownLabel(node).isBlank() && inheritsSearch(node)) {
                "Search"
            } else {
                "Search: ${raw.ifBlank { node.hintText?.toString().orEmpty() }}"
            }
            null -> raw.ifBlank { "Scrollable content" }
        }
        return AppUiControl(AppUiPrivacy.redact(display), actions, role)
    }

    private fun singleChildLabel(node: AccessibilityNodeInfo): String? {
        var current = node
        repeat(3) {
            if (current.childCount != 1) return null
            val child = current.getChild(0) ?: return null
            if (child.packageName != node.packageName || !child.isVisibleToUser || child.isPassword ||
                child.isClickable || child.className?.toString() == "android.webkit.WebView") return null
            ownLabel(child).takeIf(String::isNotBlank)?.let { return it }
            current = child
        }
        return null
    }
    private fun isSearch(node: AccessibilityNodeInfo): Boolean {
        if (search.containsMatchIn("${node.viewIdResourceName} ${node.hintText} ${ownLabel(node)}")) return true
        return inheritsSearch(node)
    }

    private fun inheritsSearch(node: AccessibilityNodeInfo): Boolean {
        if (!node.isClickable && !node.isEditable) return false
        if (!node.isEditable && ownLabel(node).isNotBlank()) return false
        var parent = node.parent
        repeat(4) {
            val current = parent ?: return false
            if (current.packageName != node.packageName || !current.isVisibleToUser || current.isClickable) return false
            if (searchContainer.containsMatchIn(current.viewIdResourceName.orEmpty().substringAfterLast('/'))) return true
            parent = current.parent
        }
        return false
    }

    private fun label(node: AccessibilityNodeInfo): String {
        val own = ownLabel(node)
        if (own.isNotBlank() || node.isEditable || !node.isClickable) return own
        val children = ArrayDeque<AccessibilityNodeInfo>()
        for (i in 0 until node.childCount) node.getChild(i)?.let(children::addLast)
        val labels = mutableListOf<String>()
        var count = 0
        while (children.isNotEmpty() && count++ < 20) {
            val child = children.removeFirst()
            if (child.packageName != node.packageName || !child.isVisibleToUser || child.isPassword ||
                child.className?.toString() == "android.webkit.WebView") continue
            ownLabel(child).takeIf(String::isNotBlank)?.let(labels::add)
            for (i in 0 until child.childCount) child.getChild(i)?.let(children::addLast)
        }
        return labels.distinct().joinToString(" ").take(500)
    }
}
