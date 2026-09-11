package com.example.mochi_pet.feature.home

import com.example.mochi_pet.core.agent.llm.ProviderConfigurationException
import com.example.mochi_pet.core.agent.llm.ProviderHttpException
import com.example.mochi_pet.core.agent.llm.ProviderNetworkException
import com.example.mochi_pet.core.agent.llm.ProviderProtocolException
import com.example.mochi_pet.core.agent.llm.ProviderResponseTooLargeException
import java.io.InterruptedIOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class ConversationErrorMessageTest {
    @Test
    fun `network timeouts are distinct from connection failures`() {
        for (cause in listOf(InterruptedIOException(), SocketTimeoutException())) {
            assertEquals(
                "AI 服务响应超时，请检查网络和 AI 服务状态。",
                localizeUiText(
                    conversationErrorMessage(ProviderNetworkException("private", cause)),
                    "zh",
                ),
            )
        }
        assertEquals(
            "无法连接 AI 服务，请检查网络、代理和服务地址。",
            localizeUiText(
                conversationErrorMessage(
                    ProviderNetworkException("private", UnknownHostException()),
                ),
                "zh",
            ),
        )
    }

    @Test
    fun `provider failures have localized safe actionable messages`() {
        val cases = listOf(
            ProviderHttpException(401, "private") to
                "AI 服务拒绝访问，请在设置中检查 API Key 和模型权限。",
            ProviderHttpException(403, "private") to
                "AI 服务拒绝访问，请在设置中检查 API Key 和模型权限。",
            ProviderHttpException(429, "private") to
                "AI 服务请求受限或额度不足，请检查额度或稍后重试。",
            ProviderHttpException(503, "private") to
                "AI 服务暂时不可用，请稍后重试。",
            ProviderHttpException(504, "private") to
                "AI 服务响应超时，请检查网络和 AI 服务状态。",
            ProviderHttpException(408, "private") to
                "AI 服务响应超时，请检查网络和 AI 服务状态。",
            ProviderHttpException(400, "private") to
                "AI 服务拒绝了此请求，请检查提供商和模型设置。",
            ProviderConfigurationException("private") to
                "AI 提供商配置无效，请在设置中检查服务地址和模型。",
            ProviderProtocolException("private") to
                "AI 服务返回了不支持的响应，请检查模型兼容性。",
            ProviderResponseTooLargeException("private") to
                "AI 服务响应超出大小限制，请缩小请求范围。",
            IllegalStateException("private") to "Mochi 无法完成此请求",
        )
        for ((error, expected) in cases) {
            val message = conversationErrorMessage(error)
            assertEquals(expected, localizeUiText(message, "zh"))
            assertEquals(message, localizeUiText(message, "en"))
            assertFalse(message.contains("private"))
        }
    }
}
