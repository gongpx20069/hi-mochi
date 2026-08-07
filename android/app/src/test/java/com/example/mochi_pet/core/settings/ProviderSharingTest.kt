package com.example.mochi_pet.core.settings

import com.example.mochi_pet.core.agent.llm.ProviderType
import org.junit.Assert.assertEquals
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
    )

    @Test
    fun `round trip keeps provider secrets out of visible link`() {
        val link = ProviderShareCodec.encode(bundle)

        assertTrue(link.startsWith("mochi://provider/import#v1."))
        assertEquals(bundle, ProviderShareCodec.decode(link))
        assertTrue("llm-secret" !in link)
        assertTrue("speech-secret" !in link)
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
}
