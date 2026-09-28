package com.example.mochi_pet.feature.home

import com.example.mochi_pet.core.agent.llm.OpenAiChatClient
import com.example.mochi_pet.core.agent.llm.OpenAiChatMessage
import com.example.mochi_pet.core.agent.llm.OpenAiChatRequest
import com.example.mochi_pet.core.agent.llm.OpenAiChatResponse
import com.example.mochi_pet.core.agent.llm.OpenAiChoice
import com.example.mochi_pet.core.agent.llm.OpenAiProviderConfig
import com.example.mochi_pet.core.agent.llm.ProviderProtocolException
import com.example.mochi_pet.core.diagnostics.CheckStatus
import com.example.mochi_pet.core.settings.ProviderSettingsInput
import com.example.mochi_pet.core.settings.ProviderSettingsRepository
import com.example.mochi_pet.core.settings.ProviderSettingsSummary
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ConfigurationDiagnosticsTest {
    @Test fun `model probe sends only fixed text without tools and never saves settings`() = runTest {
        val client = Client()
        assertEquals(CheckStatus.PASSED, checkModelProvider(Settings(), client).status)
        val request = client.requests.single()
        assertEquals(listOf(OpenAiChatMessage("user", "Reply with OK only.")), request.messages)
        assertTrue(request.tools.isEmpty())
    }

    @Test fun `incomplete configuration never makes a request`() = runTest {
        val client = Client()
        assertEquals(CheckStatus.ATTENTION, checkModelProvider(Settings(false), client).status)
        assertTrue(client.requests.isEmpty())
    }

    @Test fun `a successful HTTP call with no assistant answer is not a passed check`() = runTest {
        val client = Client(OpenAiChatResponse())
        try {
            checkModelProvider(Settings(), client)
            error("Expected unsupported response failure")
        } catch (_: ProviderProtocolException) {
            assertEquals(1, client.requests.size)
        }
    }

    private class Settings(private val ready: Boolean = true) : ProviderSettingsRepository {
        override suspend fun loadSummary() = ProviderSettingsSummary(
            endpoint = "https://example.com", model = "test-model", hasApiKey = ready,
        )
        override suspend fun loadRuntimeConfig() = OpenAiProviderConfig(
            endpoint = "https://example.com", model = "test-model", apiKey = "test-only",
        )
        override suspend fun save(input: ProviderSettingsInput): ProviderSettingsSummary = error("No writes allowed")
        override suspend fun clearApiKey(): ProviderSettingsSummary = error("No writes allowed")
        override suspend fun loadProfiles() = com.example.mochi_pet.core.settings.ProviderProfilesSummary()
        override suspend fun saveProfile(input: com.example.mochi_pet.core.settings.ProviderProfileInput): com.example.mochi_pet.core.settings.ProviderProfilesSummary = error("No writes allowed")
        override suspend fun activateProfile(id: String): com.example.mochi_pet.core.settings.ProviderProfilesSummary = error("No writes allowed")
        override suspend fun deleteProfile(id: String): com.example.mochi_pet.core.settings.ProviderProfilesSummary = error("No writes allowed")
        override suspend fun importProfiles(inputs: List<com.example.mochi_pet.core.settings.ProviderProfileInput>, activeIndex: Int): com.example.mochi_pet.core.settings.ProviderProfilesSummary = error("No writes allowed")
        override suspend fun loadProfileRuntimeConfig(id: String) = loadRuntimeConfig()
    }

    private class Client(
        private val response: OpenAiChatResponse = OpenAiChatResponse(
            choices = listOf(OpenAiChoice(OpenAiChatMessage("assistant", "OK"))),
        ),
    ) : OpenAiChatClient {
        val requests = mutableListOf<OpenAiChatRequest>()
        override suspend fun complete(config: OpenAiProviderConfig, request: OpenAiChatRequest): OpenAiChatResponse {
            requests += request
            return response
        }
    }
}
