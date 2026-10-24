package com.example.mochi_pet.core.agent.llm

import android.os.SystemClock
import android.util.Log
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.mochi_pet.MochiApplication
import com.example.mochi_pet.core.agent.AgentOrchestrator
import com.example.mochi_pet.core.agent.AgentRunRequest
import com.example.mochi_pet.core.agent.tool.AgentTool
import com.example.mochi_pet.core.agent.tool.ToolExecutionContext
import com.example.mochi_pet.core.agent.tool.ToolRegistry
import com.example.mochi_pet.core.agent.tool.ToolResultEnvelope
import com.example.mochi_pet.core.agent.tool.functionToolSchema
import com.example.mochi_pet.core.model.MochiSurface
import com.example.mochi_pet.core.navigation.NavigationPolicy
import com.example.mochi_pet.core.settings.ProviderProfileInput
import com.example.mochi_pet.core.settings.ProviderSettingsInput
import java.time.LocalDate
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AzureResponsesDiagnosticTest {
    @Test fun syntheticToolLoopAndOptionalProtocolSelection() = runBlocking<Unit> {
        val arguments = InstrumentationRegistry.getArguments()
        assumeTrue(arguments.getString("mochiResponsesDiagnostic") == "true")
        val host = requireNotNull(arguments.getString("mochiAzureResourceHost"))
        val deployment = requireNotNull(arguments.getString("mochiAzureDeployment"))
        val app = ApplicationProvider.getApplicationContext<MochiApplication>()
        val repo = app.providerSettingsRepository
        val before = repo.loadProfiles()
        val speechBefore = app.speechSettingsRepository.loadProfiles()
        val active = checkNotNull(before.active)
        val original = repo.loadRuntimeConfig()
        check(original.endpoint.toHttpUrl().host == host && original.model == deployment) {
            "Active connection does not match the explicitly authorized Azure deployment"
        }
        val config = OpenAiProviderConfig(
            providerType = original.providerType, endpoint = original.endpoint,
            apiKey = original.apiKey, model = original.model, apiVersion = original.apiVersion,
            timeoutSeconds = original.timeoutSeconds, maxResponseBytes = original.maxResponseBytes,
            imageInputEnabled = original.imageInputEnabled, apiProtocol = ApiProtocol.RESPONSES,
        ).forForegroundRequest()
        var toolCalls = 0
        val tool = object : AgentTool {
            override val name = "mochi_protocol_fixture"
            override val schema = functionToolSchema(
                name = name, description = "Return a synthetic protocol check result without any external action.",
                properties = buildJsonObject {
                    put("value", buildJsonObject { put("type", "string") })
                }, required = listOf("value"),
            )
            override suspend fun execute(arguments: JsonObject, context: ToolExecutionContext): ToolResultEnvelope {
                check(++toolCalls == 1) { "Fixture must not be replayed" }
                check(arguments["value"]?.jsonPrimitive?.content == "fixture")
                return ToolResultEnvelope.success(buildJsonObject { put("answer", "responses-ok") })
            }
        }
        val transport = OkHttpOpenAiChatClient()
        val times = mutableListOf<Long>()
        val client = object : OpenAiChatClient {
            override suspend fun complete(config: OpenAiProviderConfig, request: OpenAiChatRequest): OpenAiChatResponse {
                val started = SystemClock.elapsedRealtime()
                return transport.complete(config, request).also { times += SystemClock.elapsedRealtime() - started }
            }
        }
        val agent = AgentOrchestrator(
            chatClient = client, toolRegistry = ToolRegistry(listOf(tool)),
            navigationPolicy = NavigationPolicy(), uiDirectiveSink = { error("Fixture must not navigate") },
            maxToolRounds = 1,
        )
        val reply = withTimeout(90_000) {
            agent.run(AgentRunRequest(
                provider = config, currentEmotion = "neutral",
                query = "Call mochi_protocol_fixture exactly once with value fixture. After its result, return the required JSON with reply exactly responses-ok and emotion neutral. Do not navigate.",
                context = ToolExecutionContext(LocalDate.of(2026, 10, 23), MochiSurface.Conversation),
            ))
        }
        assertEquals(1, toolCalls)
        assertEquals("responses-ok", reply.reply)
        assertTrue("At least two model calls must complete", times.size >= 2)
        assertTrue("Connections changed during diagnostic", before == repo.loadProfiles())
        assertTrue("Speech configuration changed", speechBefore == app.speechSettingsRepository.loadProfiles())
        if (arguments.getString("mochiEnableResponses") == "true") {
            val settings = active.settings
            val result = repo.saveProfile(ProviderProfileInput(
                id = active.id, name = active.name, preset = active.preset,
                settings = ProviderSettingsInput(
                    providerType = settings.providerType, endpoint = settings.endpoint, model = settings.model,
                    apiVersion = settings.apiVersion, timeoutSeconds = settings.timeoutSeconds,
                    maxResponseBytes = settings.maxResponseBytes, imageInputEnabled = settings.imageInputEnabled,
                    apiProtocol = ApiProtocol.RESPONSES,
                ),
            ))
            val expected = before.copy(profiles = before.profiles.map {
                if (it.id == active.id) it.copy(settings = it.settings.copy(apiProtocol = ApiProtocol.RESPONSES)) else it
            })
            assertTrue("Only the authorized protocol may change", expected == result)
            assertTrue("Existing key must be retained", original.apiKey == repo.loadRuntimeConfig().apiKey)
        }
        Log.i("MochiResponses", "fixture_complete requests=${times.size} tools=$toolCalls request_ms=${times.joinToString(",")}")
    }
}
