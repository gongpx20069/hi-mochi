package com.example.mochi_pet.platform.media

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.app.KeyguardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.os.Bundle
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityManager
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import android.util.Log
import com.example.mochi_pet.core.agent.tool.ToolErrorCode
import com.example.mochi_pet.core.apps.AppUiAction
import com.example.mochi_pet.core.apps.AppUiElement
import com.example.mochi_pet.core.apps.AppUiSnapshot
import com.example.mochi_pet.core.media.MediaFailure
import java.lang.ref.WeakReference
import java.util.UUID

internal val DEFAULT_MUSIC_APPS = setOf("com.miui.player", "com.tencent.qqmusic", "com.netease.cloudmusic")
private val protectedPackages = setOf("android", "com.android.settings", "com.android.permissioncontroller",
    "com.google.android.permissioncontroller", "com.android.packageinstaller", "com.google.android.packageinstaller",
    "com.miui.securitycenter")

internal fun appCanBeSelected(context: Context, packageName: String): Boolean =
    packageName != context.packageName && packageName !in protectedPackages

data class SelectableApp(val packageName: String, val label: String, val music: Boolean)

fun selectableApps(context: Context): List<SelectableApp> =
    context.packageManager.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
        .filter { it.activityInfo.enabled && it.activityInfo.exported && appCanBeSelected(context, it.activityInfo.packageName) }
        .distinctBy { it.activityInfo.packageName }
        .map { SelectableApp(it.activityInfo.packageName, it.loadLabel(context.packageManager).toString().take(100),
            it.activityInfo.packageName in DEFAULT_MUSIC_APPS || it.activityInfo.applicationInfo.category == ApplicationInfo.CATEGORY_AUDIO) }
        .sortedWith(compareByDescending<SelectableApp> { it.music }.thenBy { it.label })

fun appAssistanceGranted(context: Context): Boolean =
    context.getSystemService(AccessibilityManager::class.java)
        .getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
        .any { ComponentName(it.resolveInfo.serviceInfo.packageName, it.resolveInfo.serviceInfo.name) ==
            ComponentName(context, AppAccessibilityService::class.java) }

internal object AppUiPrivacy {
    private val sensitive = Regex(
        "登录|登陆|注册|验证码|密码|密钥|助记词|银行卡|收银|支付|付款|充值|购买|订购|订阅|注销|开通|续费|广告|领金币|赚钱|签到|下载|安装|卸载|删除|发送|发表|发布|授权|允许|同意|确认|确定|提交|" +
            "log.?in|sign.?in|password|secret|api.?key|token|payment|purchase|checkout|subscribe|advert|install|delete|send|publish|grant|allow|agree|confirm|submit",
        RegexOption.IGNORE_CASE,
    )
    private val privateScreen = Regex("支付密码|账号与安全|银行卡号|助记词|私钥|验证码|API.?Key|secret.?key|password|one.time.code", RegexOption.IGNORE_CASE)
    fun blocked(text: String): Boolean = sensitive.containsMatchIn(text)
    fun privateScreen(text: String): Boolean = privateScreen.containsMatchIn(text)
    fun redact(text: String): String = text
        .replace(Regex("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}"), "[redacted]")
        .replace(Regex("(?<!\\d)\\+?\\d[\\d -]{8,}\\d(?!\\d)"), "[redacted]")
        .replace(Regex("[A-Za-z0-9_+/=-]{24,}"), "[redacted]")
        .replace(Regex("https?://\\S+", RegexOption.IGNORE_CASE), "[link]")
        .take(200)
}

class AppAccessibilityService : AccessibilityService() {
    private data class Handle(
        val node: AccessibilityNodeInfo, val fingerprint: String, val actions: List<String>,
        val owner: String, val packageName: String, val created: Long,
    )
    private val handles = mutableMapOf<String, Handle>()

    override fun onServiceConnected() { connected = WeakReference(this) }
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED ||
            event?.eventType == AccessibilityEvent.TYPE_WINDOWS_CHANGED) handles.clear()
    }
    override fun onInterrupt() { handles.clear() }
    override fun onDestroy() {
        handles.clear()
        if (current() === this) connected = null
        super.onDestroy()
    }

    private fun requireAvailable() {
        if (current() !== this || getSystemService(KeyguardManager::class.java).isKeyguardLocked) {
            throw MediaFailure(ToolErrorCode.PERMISSION_DENIED, "App assistance is unavailable or the phone is locked.")
        }
    }

    internal fun rootFor(packageName: String): AccessibilityNodeInfo {
        requireAvailable()
        val focused = windows.singleOrNull {
            it.isFocused && it.type == AccessibilityWindowInfo.TYPE_APPLICATION
        }
        val root = if (focused != null) focused.root else rootInActiveWindow
        if (root == null) {
            Log.w("MochiAppUi", "foreground_window_unavailable")
            throw MediaFailure(ToolErrorCode.CONFLICT, "No readable active app window. Keep the selected app foreground while Mochi operates it; do not switch back to Mochi until the task finishes.")
        }
        if (root.packageName?.toString() != packageName) {
            Log.w("MochiAppUi", "foreground_window_not_selected_app")
            handles.clear()
            throw MediaFailure(ToolErrorCode.CONFLICT, "The selected app is not in the foreground. No other app was read or operated.")
        }
        return root
    }

    internal fun execute(packageName: String, action: AppUiAction, ref: String?, text: String?, owner: String): AppUiSnapshot? {
        if (action == AppUiAction.RETURN) {
            requireAvailable()
            handles.clear()
            return null
        }
        serviceInfo = serviceInfo.apply { packageNames = arrayOf(packageName) }
        val root = rootFor(packageName)
        if (action == AppUiAction.READ) return snapshot(root, packageName, owner)
        if (action == AppUiAction.BACK) {
            handles.clear()
            if (!performGlobalAction(GLOBAL_ACTION_BACK)) throw MediaFailure(ToolErrorCode.CONFLICT, "Android did not accept Back.")
            return null
        }
        val handle = handles[ref] ?: staleControl("reference_invalidated", "Read the app again; this control reference expired.")
        handles.clear()
        val node = handle.node
        if (handle.owner != owner || handle.packageName != packageName ||
            SystemClock.elapsedRealtime() - handle.created > 30_000) {
            staleControl("reference_owner_or_expiry", "The app control changed. Read again before choosing an action.")
        }
        if (!node.refresh() || node.windowId != root.windowId || !node.isVisibleToUser || node.isPassword ||
            node.packageName?.toString() != packageName) {
            staleControl("reference_node_unavailable", "The app control changed. Read again before choosing an action.")
        }
        if (fingerprint(node) != handle.fingerprint) {
            staleControl("reference_control_changed", "The app control changed. Read again before choosing an action.")
        }
        if (action.name.lowercase() !in handle.actions) throw MediaFailure(ToolErrorCode.PERMISSION_DENIED, "This control does not permit that action.")
        snapshot(root, packageName, owner)
        val stillAvailable = handles.values.any { it.node == node && it.fingerprint == handle.fingerprint && action.name.lowercase() in it.actions }
        handles.clear()
        if (!stillAvailable) staleControl("reference_not_in_snapshot", "The screen changed or a sensitive screen appeared. No action was taken.")
        val accepted = when (action) {
            AppUiAction.CLICK -> node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            AppUiAction.INPUT -> node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, requireNotNull(text))
            })
            AppUiAction.SCROLL_FORWARD -> node.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
            AppUiAction.SCROLL_BACKWARD -> node.performAction(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)
            else -> error("Action handled above")
        }
        if (!accepted) throw MediaFailure(ToolErrorCode.CONFLICT, "The app did not accept the action. Read current state; do not replay automatically.")
        return null
    }

    private fun snapshot(root: AccessibilityNodeInfo, packageName: String, owner: String): AppUiSnapshot {
        handles.clear()
        val queue = ArrayDeque<AccessibilityNodeInfo>().apply { add(root) }
        val nodes = mutableListOf<AccessibilityNodeInfo>()
        var visited = 0
        while (queue.isNotEmpty() && visited++ < 1_000) {
            val node = queue.removeFirst()
            if (node.packageName?.toString() != packageName || !node.isVisibleToUser) continue
            if (node.isPassword || AppUiPrivacy.privateScreen(AppUiSemantics.ownLabel(node))) {
                throw MediaFailure(ToolErrorCode.PERMISSION_DENIED, "This screen may contain private authentication or payment data. Handle it yourself.")
            }
            if (node.className?.toString() == "android.webkit.WebView") continue
            nodes += node
            for (i in 0 until node.childCount) node.getChild(i)?.let(queue::addLast)
        }
        if (queue.isNotEmpty()) {
            Log.w("MochiAppUi", "foreground_window_traversal_limit")
            throw MediaFailure(ToolErrorCode.CONFLICT, "The app screen is too complex to inspect safely.")
        }
        val elements = nodes.mapNotNull { node ->
            val control = AppUiSemantics.describe(node) ?: return@mapNotNull null
            val actions = control.actions
            val key = UUID.randomUUID().toString()
            if (actions.isNotEmpty()) handles[key] = Handle(node, fingerprint(node), actions, owner, packageName, SystemClock.elapsedRealtime())
            AppUiElement(key, control.label, actions, control.role)
        }
        val selected = elements.filter { it.actions.isNotEmpty() }.take(60) +
            elements.filter { it.actions.isEmpty() }.take(20)
        handles.keys.retainAll(selected.map { it.ref }.toSet())
        return AppUiSnapshot(selected, selected.size < elements.size)
    }

    private fun fingerprint(node: AccessibilityNodeInfo): String =
        "${node.viewIdResourceName}|${node.className}|${node.isEditable}|${node.isClickable}|${node.inputType}|${node.hintText}|${AppUiSemantics.describe(node)}"

    private fun staleControl(marker: String, message: String): Nothing {
        Log.w("MochiAppUi", marker)
        throw MediaFailure(ToolErrorCode.STALE_REF, message)
    }

    internal fun returnToMochi(): Boolean {
        requireAvailable()
        val launch = packageManager.getLaunchIntentForPackage(this.packageName) ?: return false
        startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP))
        return true
    }

    companion object {
        private var connected: WeakReference<AppAccessibilityService>? = null
        internal fun current(): AppAccessibilityService? = connected?.get()
    }
}
