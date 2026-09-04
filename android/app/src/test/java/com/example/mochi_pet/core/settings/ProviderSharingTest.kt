package com.example.mochi_pet.core.settings

import com.example.mochi_pet.core.agent.llm.ProviderType
import com.example.mochi_pet.core.tools.SharedManualMcpServer
import com.example.mochi_pet.core.tools.SharedTencentDocsProvider
import com.example.mochi_pet.core.tools.SharedToolProviders
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ProviderSharingTest {
    private val bundle = SharedProviderBundle(
        llm = SharedLlmProvider(
            providerType = ProviderType.AZURE_OPENAI,
            endpoint = "https://example.openai.azure.com",
            model = "mochi",
            apiVersion = "2025-04-01-preview",
            timeoutSeconds = 60,
            maxResponseBytes = 2_097_152,
            apiKey = "llm-secret",
        ),
        speech = SharedSpeechProvider(
            provider = SpeechProvider.IFLYTEK,
            iFlytekAppId = "app-id",
            iFlytekApiKey = "speech-key",
            iFlytekApiSecret = "speech-secret",
        ),
        tools = SharedToolProviders(
            tencentDocs = SharedTencentDocsProvider(
                token = "tool-secret",
                enabledToolNames = setOf("get_content"),
            ),
        ),
    )

    @Test
    fun `round trip keeps provider secrets out of visible link`() {
        val link = ProviderShareCodec.encode(bundle)

        assertTrue(link.startsWith("mochi://provider/import#v2."))
        assertEquals(bundle, ProviderShareCodec.decode(link))
        assertTrue("llm-secret" !in link)
        assertTrue("speech-secret" !in link)
        assertTrue("tool-secret" !in link)
    }

    @Test
    fun `default selection includes Providers but not Tool credentials`() {
        val selection = ProviderShareSelection()

        assertTrue(selection.includeLlm)
        assertTrue(selection.includeSpeech)
        assertFalse(selection.tools.includeAmap)
        assertFalse(selection.tools.includeTencentDocs)
        assertTrue(selection.tools.manualMcpServerIds.isEmpty())
    }

    @Test
    fun `same bundle uses a fresh embedded key`() {
        assertNotEquals(
            ProviderShareCodec.encode(bundle),
            ProviderShareCodec.encode(bundle),
        )
    }

    @Test
    fun `modified link is rejected`() {
        val link = ProviderShareCodec.encode(bundle)
        val index = link.length / 2
        val replacement = if (link[index] == 'A') 'B' else 'A'

        assertThrows(ProviderShareException::class.java) {
            ProviderShareCodec.decode(
                link.substring(0, index) +
                    replacement +
                    link.substring(index + 1),
            )
        }
    }

    @Test
    fun `legacy v1 links are rejected`() {
        val link = ProviderShareCodec.encode(bundle)

        assertThrows(IllegalArgumentException::class.java) {
            ProviderShareCodec.decode(
                link.replace(
                    "mochi://provider/import#v2.",
                    "mochi://provider/import#v1.",
                ),
            )
        }
    }

    @Test
    fun `oversized selections are rejected before sharing`() {
        val oversized = bundle.copy(
            tools = SharedToolProviders(
                manualMcpServers = List(8) { index ->
                    SharedManualMcpServer(
                        name = "Server $index",
                        endpoint = "https://example$index.com/mcp",
                        bearerToken = "x".repeat(4_096),
                        enabledToolNames = setOf("read"),
                    )
                },
            ),
        )

        assertThrows(ProviderShareException::class.java) {
            ProviderShareCodec.encode(oversized)
        }
    }
}
