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
        assertEquals(
            "语音或文字",
            localizeUiText("Voice or text", "zh"),
        )
    }

    @Test
    fun `non-Chinese locale keeps English UI text`() {
        assertEquals("Settings", localizeUiText("Settings", "en"))
        assertEquals("Settings", localizeUiText("Settings", "fr"))
    }

    @Test
    fun `Mi Home extension tools follow app language`() {
        assertEquals("扩展", localizeUiText("Extensions", "zh"))
        assertEquals(
            "获取最新摄像头事件图片",
            localizeUiText("Get Latest Camera Event Image", "zh"),
        )
        assertEquals(
            "从一个已选择的摄像头获取最新可用的移动或门铃事件图片。",
            localizeUiText(
                "Retrieve the newest available motion or doorbell event " +
                    "image from one selected camera.",
                "zh",
            ),
        )
        assertEquals(
            "Xiaomi 1234 · 2 个家庭 · 3 个设备",
            localizeUiText(
                "Xiaomi 1234 · 2 homes · 3 devices",
                "zh",
            ),
        )
        assertEquals(
            "7 个已选设备",
            localizeUiText("7 selected devices", "zh"),
        )
        assertEquals(
            "最新事件 · 非实时画面",
            localizeUiText("LATEST EVENT · NOT LIVE", "zh"),
        )
        assertEquals(
            "事件：motion",
            localizeUiText("Event: motion", "zh"),
        )
        assertEquals(
            "已开启 Mochi 图像分析 · 仅限当前会话",
            localizeUiText(
                "Mochi image analysis on · current run only",
                "zh",
            ),
        )
        assertEquals(
            "Get Latest Camera Event Image",
            localizeUiText("Get Latest Camera Event Image", "en"),
        )
    }

    @Test
    fun `Skill Tool groups localize as aggregate prerequisites`() {
        assertEquals(
            "请先启用所需的工具组：腾讯文档 MCP、代理浏览器",
            localizeUiText(
                "Enable required Tool groups first: " +
                    "Tencent Docs MCP, Agent Browser",
                "zh",
            ),
        )
    }
}
