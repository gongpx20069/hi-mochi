package com.example.mochi_pet.core.agent.llm

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

enum class ProviderType {
    OPENAI,
    AZURE_OPENAI,
    CUSTOM,
}

class OpenAiProviderConfig(
    val providerType: ProviderType = ProviderType.CUSTOM,
    val endpoint: String,
    val apiKey: String,
    val model: String,
    val apiVersion: String = DEFAULT_AZURE_API_VERSION,
    val timeoutSeconds: Long = 60,
    val maxResponseBytes: Long = 2L * 1024L * 1024L,
) {
    init {
        require(endpoint.isNotBlank()) { "Provider endpoint must not be empty" }
        require(apiKey.isNotBlank()) { "Provider API key must not be empty" }
        require(model.isNotBlank()) { "Provider model must not be empty" }
        if (providerType == ProviderType.AZURE_OPENAI) {
            require(apiVersion.isNotBlank()) {
                "Azure OpenAI API version must not be empty"
            }
        }
        require(timeoutSeconds in 1..300) {
            "Provider timeout must be between 1 and 300 seconds"
        }
        require(maxResponseBytes in 1..10L * 1024L * 1024L) {
            "Provider response limit must be between 1 byte and 10 MiB"
        }
    }
}

@Serializable
data class OpenAiChatMessage(
    val role: String,
    val content: String? = null,
    @SerialName("tool_calls")
    val toolCalls: List<OpenAiToolCall>? = null,
    @SerialName("tool_call_id")
    val toolCallId: String? = null,
    val name: String? = null,
)

@Serializable
data class OpenAiFunctionCall(
    val name: String,
    val arguments: String,
)

@Serializable
data class OpenAiToolCall(
    val id: String,
    val type: String = "function",
    val function: OpenAiFunctionCall,
)

@Serializable
data class OpenAiChatRequest(
    val model: String? = null,
    val messages: List<OpenAiChatMessage>,
    val tools: List<JsonObject> = emptyList(),
)

@Serializable
data class OpenAiChatResponse(
    val choices: List<OpenAiChoice> = emptyList(),
    val error: OpenAiError? = null,
)

@Serializable
data class OpenAiChoice(
    val message: OpenAiChatMessage,
)

@Serializable
data class OpenAiError(
    val message: String? = null,
    val type: String? = null,
    val code: String? = null,
)

interface OpenAiChatClient {
    suspend fun complete(
        config: OpenAiProviderConfig,
        request: OpenAiChatRequest,
    ): OpenAiChatResponse
}

sealed class OpenAiProviderException(
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)

class ProviderConfigurationException(message: String) :
    OpenAiProviderException(message)

class ProviderHttpException(
    val statusCode: Int,
    message: String,
) : OpenAiProviderException(message)

class ProviderProtocolException(message: String) :
    OpenAiProviderException(message)

class ProviderNetworkException(
    message: String,
    cause: Throwable? = null,
) : OpenAiProviderException(message, cause)

class ProviderResponseTooLargeException(message: String) :
    OpenAiProviderException(message)

const val DEFAULT_AZURE_API_VERSION = "2024-10-21"
