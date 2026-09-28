package com.example.mochi_pet.core.settings

import com.example.mochi_pet.core.agent.llm.ProviderType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

enum class ProviderPreset(
    val title: String,
    val protocol: ProviderType,
    val endpoint: String,
    val documentation: String,
) {
    OPENAI("OpenAI", ProviderType.OPENAI, "https://api.openai.com/v1", "https://developers.openai.com/api/docs"),
    AZURE("Azure OpenAI", ProviderType.AZURE_OPENAI, "", "https://learn.microsoft.com/azure/ai-foundry/openai/"),
    DEEPSEEK("DeepSeek", ProviderType.CUSTOM, "https://api.deepseek.com", "https://api-docs.deepseek.com/"),
    KIMI("Kimi", ProviderType.CUSTOM, "https://api.moonshot.cn/v1", "https://platform.kimi.com/docs/get-api-key"),
    GLM("GLM", ProviderType.CUSTOM, "https://open.bigmodel.cn/api/paas/v4", "https://docs.bigmodel.cn/cn/guide/develop/openai/introduction"),
    MINIMAX("MiniMax", ProviderType.CUSTOM, "https://api.minimax.cn/v1", "https://platform.minimax.cn/docs/api-reference/text-openai-api"),
    AGNES("Agnes-AI", ProviderType.CUSTOM, "https://apihub.agnes-ai.com/v1", "https://wiki.agnes-ai.com/en/docs/quickstart"),
    CUSTOM("Custom compatible API", ProviderType.CUSTOM, "", ""),
}

data class ProviderProfileSummary(
    val id: String,
    val name: String,
    val preset: ProviderPreset,
    val settings: ProviderSettingsSummary,
)

data class ProviderProfilesSummary(
    val profiles: List<ProviderProfileSummary> = emptyList(),
    val activeId: String? = null,
) {
    val active: ProviderProfileSummary? get() = profiles.firstOrNull { it.id == activeId }
}

data class ProviderProfileInput(
    val id: String? = null,
    val name: String,
    val preset: ProviderPreset,
    val settings: ProviderSettingsInput,
    val useAfterSave: Boolean = false,
)

/** Serializes configuration changes against Agent startup, not against entire Agent runs. */
class ProviderRunCoordinator {
    private val gate = Mutex()
    private val owners = mutableSetOf<Job>()

    suspend fun register(owner: Job) {
        gate.withLock {
            synchronized(owners) { owners.add(owner) }
            owner.invokeOnCompletion { synchronized(owners) { owners.remove(owner) } }
        }
    }

    suspend fun <T> change(block: suspend (cancelRuns: suspend () -> Unit) -> T): T = gate.withLock {
        block {
            val running = synchronized(owners) { owners.toList() }
            running.forEach { it.cancel(CancellationException("AI connection changed")) }
            running.joinAll()
        }
    }
}
