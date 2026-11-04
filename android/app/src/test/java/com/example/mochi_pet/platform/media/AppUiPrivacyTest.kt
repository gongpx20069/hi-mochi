package com.example.mochi_pet.platform.media

import android.app.Application
import android.view.accessibility.AccessibilityNodeInfo
import com.example.mochi_pet.core.apps.AppUiRole
import com.example.mochi_pet.core.apps.AppUiAction
import com.example.mochi_pet.core.media.MediaFailure
import com.example.mochi_pet.core.agent.tool.ToolErrorCode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class AppUiPrivacyTest {
    @Suppress("DEPRECATION")
    @Test fun `focused application root overrides stale active root without reading behind a foreign focused window`() {
        val controller = Robolectric.buildService(AppAccessibilityService::class.java).create()
        val service = controller.get()
        org.robolectric.util.ReflectionHelpers.callInstanceMethod<Unit>(service, "onServiceConnected")
        val selected = node("Search")
        val foreign = node("Other").apply { packageName = "fixture.other" }
        val window = android.view.accessibility.AccessibilityWindowInfo.obtain()
        org.robolectric.util.ReflectionHelpers.callInstanceMethod<Unit>(window, "setType",
            org.robolectric.util.ReflectionHelpers.ClassParameter.from(Int::class.javaPrimitiveType,
                android.view.accessibility.AccessibilityWindowInfo.TYPE_APPLICATION))
        org.robolectric.util.ReflectionHelpers.callInstanceMethod<Unit>(window, "setFocused",
            org.robolectric.util.ReflectionHelpers.ClassParameter.from(Boolean::class.javaPrimitiveType, true))
        shadowOf(window).setRoot(selected)
        shadowOf(service).setWindows(listOf(window))
        shadowOf(service).setRootInActiveWindow(foreign)
        try {
            assertEquals(selected, service.rootFor("fixture.music"))
            shadowOf(service).setRootInActiveWindow(selected)
            shadowOf(window).setRoot(foreign)
            assertEquals(ToolErrorCode.CONFLICT, assertThrows(MediaFailure::class.java) {
                service.rootFor("fixture.music")
            }.code)
            shadowOf(window).setRoot(null)
            assertEquals(ToolErrorCode.CONFLICT, assertThrows(MediaFailure::class.java) {
                service.rootFor("fixture.music")
            }.code)
        } finally {
            controller.destroy()
        }
    }

    @Test fun `return does not need a readable external window but still requires an unlocked connected service`() {
        val controller = Robolectric.buildService(AppAccessibilityService::class.java).create()
        val service = controller.get()
        org.robolectric.util.ReflectionHelpers.callInstanceMethod<Unit>(service, "onServiceConnected")
        try {
            assertNull(service.execute("fixture.music", AppUiAction.RETURN, null, null, "owner"))
            shadowOf(service.getSystemService(android.app.KeyguardManager::class.java)).setKeyguardLocked(true)
            val error = assertThrows(MediaFailure::class.java) {
                service.execute("fixture.music", AppUiAction.RETURN, null, null, "owner")
            }
            assertEquals(ToolErrorCode.PERMISSION_DENIED, error.code)
        } finally {
            controller.destroy()
        }
        assertThrows(MediaFailure::class.java) { service.execute("fixture.music", AppUiAction.RETURN, null, null, "owner") }
    }

    @Test fun `explicit dismiss labels and native close icons are actionable without enabling ad destinations`() {
        for (label in listOf("关闭按钮", "关闭广告", "取消", "稍后再说", "×", "Close ad", "Not now")) {
            val control = AppUiSemantics.describe(node(label))!!
            assertEquals(label, AppUiRole.DISMISS, control.role)
            assertEquals(listOf("click"), control.actions)
        }
        for (id in listOf("close", "iv_close", "iv_real_close_area", "ad_skip_button")) {
            val control = AppUiSemantics.describe(node(id = id))!!
            assertEquals(id, AppUiRole.DISMISS, control.role)
            assertEquals(listOf("click"), control.actions)
        }
        for (label in listOf("关闭并购买", "取消订阅", "注销账号", "观看广告", "同意", "允许", "Confirm payment")) {
            assertNull(label, AppUiSemantics.describe(node(label, id = "close")))
        }
        for (id in listOf("skip", "close_account", "close_and_subscribe", "disclose", "ad_banner")) {
            assertNull(id, AppUiSemantics.describe(node(id = id)))
        }
        assertNull(AppUiSemantics.describe(node(id = "close").apply { isEnabled = false }))
    }

    @Test fun `a close child only labels its single noninteractive wrapper not the entire ad container`() {
        val wrapper = node()
        shadowOf(wrapper).addChild(node("关闭").apply { isClickable = false })
        assertEquals(AppUiRole.DISMISS, AppUiSemantics.describe(wrapper)!!.role)
        shadowOf(wrapper).addChild(node().apply { isClickable = false })
        assertNull(AppUiSemantics.describe(wrapper))

        val banner = node()
        shadowOf(banner).addChild(node("关闭"))
        assertNull(AppUiSemantics.describe(banner))
    }

    @Test fun `unlabelled search entry inherits its bounded native search container semantics`() {
        val container = node(id = "search_box").apply { isClickable = false }
        val entry = node()
        shadowOf(container).addChild(entry)
        val search = AppUiSemantics.describe(entry)!!
        assertEquals(AppUiRole.SEARCH, search.role)
        assertEquals(listOf("click"), search.actions)
        val field = node(id = "et_searchbar").apply { isEditable = true }
        assertTrue(AppUiSemantics.describe(field)!!.actions.contains("input"))
        assertNull(AppUiSemantics.describe(node("密码", id = "search_box").apply { isEditable = true }))
    }

    @Test fun `search entry behind Compose wrappers is recognized without relabeling adjacent recognition buttons`() {
        val toolbar = node(id = "app_bar_layout_search").apply { isClickable = false }
        val row = node().apply { isClickable = false }
        val box = node(id = "search_box").apply { isClickable = false }
        val compose = node().apply { isClickable = false; className = "androidx.compose.ui.platform.ComposeView" }
        val wrapper = node().apply { isClickable = false }
        val entry = node()
        val recognition = node("Recognize music", "recognize_music")
        shadowOf(toolbar).addChild(row)
        shadowOf(row).addChild(box)
        shadowOf(row).addChild(recognition)
        shadowOf(box).addChild(compose)
        shadowOf(compose).addChild(wrapper)
        shadowOf(wrapper).addChild(entry)
        assertEquals(AppUiRole.SEARCH, AppUiSemantics.describe(entry)!!.role)
        assertEquals(listOf("click"), AppUiSemantics.describe(entry)!!.actions)
        assertNull(AppUiSemantics.describe(recognition)!!.role)
        val nestedAction = node()
        shadowOf(entry).addChild(nestedAction)
        assertNull(AppUiSemantics.describe(nestedAction))
    }

    @Test fun `rotating search suggestions do not change the entry but sensitive descendants remain blocked`() {
        val container = node(id = "search_box").apply { isClickable = false }
        val entry = node()
        val suggestion = node("Trending song A").apply { isClickable = false }
        shadowOf(container).addChild(entry)
        shadowOf(entry).addChild(suggestion)
        val initial = AppUiSemantics.describe(entry)!!
        assertEquals(AppUiRole.SEARCH, initial.role)
        assertEquals(listOf("click"), initial.actions)
        suggestion.text = "Trending song B"
        assertEquals(initial, AppUiSemantics.describe(entry))
        suggestion.text = "购买"
        assertNull(AppUiSemantics.describe(entry))

        val result = node("Requested song A", "search_key")
        val original = AppUiSemantics.describe(result)
        result.text = "Requested song B"
        assertFalse(original == AppUiSemantics.describe(result))
    }

    @Suppress("DEPRECATION")
    private fun node(label: String? = null, id: String? = null): AccessibilityNodeInfo =
        AccessibilityNodeInfo.obtain().apply {
            packageName = "fixture.music"
            className = "android.view.View"
            text = label
            viewIdResourceName = id?.let { "fixture.music:id/$it" }
            isVisibleToUser = true
            isEnabled = true
            isClickable = true
        }

    @Test fun `sensitive controls are not offered as actions but music controls remain available`() {
        for (label in listOf("登录", "确认付款", "观看广告", "发送", "Delete account", "Allow access")) {
            assertTrue(label, AppUiPrivacy.blocked(label))
        }
        for (label in listOf("搜索", "播放", "晴天 周杰伦", "Pause")) assertFalse(label, AppUiPrivacy.blocked(label))
    }

    @Test fun `private screen markers block snapshots and identifiable strings are redacted`() {
        assertTrue(AppUiPrivacy.privateScreen("账号与安全"))
        assertTrue(AppUiPrivacy.privateScreen("Enter password"))
        val text = AppUiPrivacy.redact("fixture@example.com https://example.com/private?q=fixture")
        assertFalse(text.contains("fixture@example.com"))
        assertFalse(text.contains("?q="))
        assertTrue(text.contains("[redacted]"))
    }
}
