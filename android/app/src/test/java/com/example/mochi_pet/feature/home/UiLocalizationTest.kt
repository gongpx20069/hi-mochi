package com.example.mochi_pet.feature.home

import org.junit.Assert.assertEquals
import org.junit.Test

class UiLocalizationTest {
    @Test
    fun `Chinese locale translates known UI text`() {
        assertEquals("设置", localizeUiText("Settings", "zh"))
        assertEquals(
            "Mochi 正在思考",
            localizeUiText("Mochi is Thinking", "zh"),
        )
        assertEquals(
            "移除 Calendar？",
            localizeUiText("Remove Calendar?", "zh"),
        )
        assertEquals(
            "1200 次总安装 · 增长中",
            localizeUiText("1200 total installs · Growing", "zh"),
        )
        assertEquals(
            "共享 Providers",
            localizeUiText("Share Providers", "zh"),
        )
        assertEquals(
            "接收 Providers",
            localizeUiText("Receive Providers", "zh"),
        )
    }

    @Test
    fun `non-Chinese locale keeps English UI text`() {
        assertEquals("Settings", localizeUiText("Settings", "en"))
        assertEquals("Settings", localizeUiText("Settings", "fr"))
    }
}
