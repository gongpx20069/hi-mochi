package com.example.mochi_pet.core.agent.llm

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.buildClassSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonEncoder
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

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
    val imageInputEnabled: Boolean = false,
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
data class OpenAiImageUrl(
    val url: String,
    val detail: String = "auto",
)

@Serializable
data class OpenAiChatContentPart(
    val type: String,
    val text: String? = null,
    @SerialName("image_url")
    val imageUrl: OpenAiImageUrl? = null,
)

@Serializable(with = OpenAiChatMessageSerializer::class)
data class OpenAiChatMessage(
    val role: String,
    val content: String? = null,
    val contentParts: List<OpenAiChatContentPart>? = null,
    @SerialName("tool_calls")
    val toolCalls: List<OpenAiToolCall>? = null,
    @SerialName("tool_call_id")
    val toolCallId: String? = null,
    val name: String? = null,
)

object OpenAiChatMessageSerializer : KSerializer<OpenAiChatMessage> {
    override val descriptor: SerialDescriptor =
        buildClassSerialDescriptor("OpenAiChatMessage")

    override fun serialize(
        encoder: Encoder,
        value: OpenAiChatMessage,
    ) {
        require(encoder is JsonEncoder)
        encoder.encodeJsonElement(
            buildJsonObject {
                put("role", value.role)
                when {
                    value.contentParts != null -> put(
                        "content",
                        encoder.json.encodeToJsonElement(
                            kotlinx.serialization.builtins.ListSerializer(
                                OpenAiChatContentPart.serializer(),
                            ),
                            value.contentParts,
                        ),
                    )
                    value.content != null -> put(
                        "content",
                        value.content,
                    )
                    else -> put("content", JsonNull)
                }
                value.toolCalls?.let {
                    put(
                        "tool_calls",
                        encoder.json.encodeToJsonElement(
                            kotlinx.serialization.builtins.ListSerializer(
                                OpenAiToolCall.serializer(),
                            ),
                            it,
                        ),
                    )
                }
                value.toolCallId?.let { put("tool_call_id", it) }
                value.name?.let { put("name", it) }
            },
        )
    }

    override fun deserialize(decoder: Decoder): OpenAiChatMessage {
        require(decoder is JsonDecoder)
        val value = decoder.decodeJsonElement().jsonObject
        val contentValue = value["content"]
        return OpenAiChatMessage(
            role = value.getValue("role").jsonPrimitive.content,
            content = (contentValue as? JsonPrimitive)?.contentOrNull,
            contentParts = (contentValue as? JsonArray)?.let {
                decoder.json.decodeFromJsonElement(
                    kotlinx.serialization.builtins.ListSerializer(
                        OpenAiChatContentPart.serializer(),
                    ),
                    it,
                )
            },
            toolCalls = (value["tool_calls"] as? JsonArray)?.let {
                decoder.json.decodeFromJsonElement(
                    kotlinx.serialization.builtins.ListSerializer(
                        OpenAiToolCall.serializer(),
                    ),
                    it,
                )
            },
            toolCallId = value["tool_call_id"]
                ?.jsonPrimitive?.contentOrNull,
            name = value["name"]?.jsonPrimitive?.contentOrNull,
        )
    }
}

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
