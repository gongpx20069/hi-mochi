package com.example.mochi_pet.core.agent.llm

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put

internal object ResponsesAdapter {
    private const val MAX_CONTINUATION_CHARS = 4_000_000

    fun encode(json: Json, config: OpenAiProviderConfig, request: OpenAiChatRequest): String {
        if (request.messages.sumOf { it.responsesOutput?.toString()?.length?.toLong() ?: 0L } > MAX_CONTINUATION_CHARS) {
            throw ProviderResponseTooLargeException("Responses continuation exceeded the 4000000-character limit")
        }
        return json.encodeToString(buildJsonObject {
            put("model", config.model.trim())
            put("store", false)
            put("include", buildJsonArray { add(JsonPrimitive("reasoning.encrypted_content")) })
            put("input", buildJsonArray {
                request.messages.forEach { message ->
                    val output = message.responsesOutput
                    if (output != null) {
                        if (message.role != "assistant") invalid("continuation role")
                        output.forEach { add(it) }
                    } else if (message.role == "tool") {
                        add(buildJsonObject {
                            put("type", "function_call_output")
                            put("call_id", message.toolCallId?.takeIf { it.isNotBlank() } ?: invalid("tool call ID"))
                            put("output", message.content ?: invalid("tool output"))
                        })
                    } else {
                        if (message.role !in setOf("system", "developer", "user", "assistant")) invalid("message role")
                        if (message.content != null || message.contentParts != null) {
                            add(buildJsonObject {
                                put("role", message.role)
                                if (message.role == "assistant") {
                                    put("phase", if (message.toolCalls.isNullOrEmpty()) "final_answer" else "commentary")
                                }
                                put("content", message.contentParts?.let { parts ->
                                    buildJsonArray {
                                        parts.forEach { part ->
                                            add(buildJsonObject {
                                                when (part.type) {
                                                    "text" -> {
                                                        put("type", "input_text")
                                                        put("text", part.text ?: invalid("text input"))
                                                    }
                                                    "image_url" -> {
                                                        val image = part.imageUrl ?: invalid("image input")
                                                        put("type", "input_image")
                                                        put("image_url", image.url)
                                                        put("detail", image.detail)
                                                    }
                                                    else -> invalid("input content type")
                                                }
                                            })
                                        }
                                    }
                                } ?: JsonPrimitive(message.content))
                            })
                        }
                        message.toolCalls.orEmpty().forEach { call ->
                            if (call.type != "function") invalid("tool type")
                            add(buildJsonObject {
                                put("type", "function_call")
                                put("call_id", call.id)
                                put("name", call.function.name)
                                put("arguments", call.function.arguments)
                            })
                        }
                    }
                }
            })
            put("tools", buildJsonArray {
                request.tools.forEach { tool ->
                    if (tool.string("type") != "function") invalid("tool definition")
                    val function = tool["function"] as? JsonObject ?: invalid("function definition")
                    add(buildJsonObject {
                        function.forEach { (key, value) -> put(key, value) }
                        put("type", "function")
                        // Responses otherwise normalizes optional Chat parameters into a strict schema.
                        if ("strict" !in function) put("strict", false)
                    })
                }
            })
        })
    }

    fun decode(json: Json, text: String): OpenAiChatResponse {
        val response = json.decodeFromString<ResponsesEnvelope>(text)
        if (response.error != null) throw ProviderProtocolException("Provider returned a Responses error")
        when (response.status) {
            "completed" -> Unit
            "incomplete" -> throw ProviderProtocolException("Provider Responses output is incomplete; check the model output limit")
            else -> throw ProviderProtocolException("Provider Responses request did not complete")
        }
        if (response.output.toString().length > MAX_CONTINUATION_CHARS) {
            throw ProviderResponseTooLargeException("Responses continuation exceeded the 4000000-character limit")
        }
        val texts = mutableListOf<String>()
        val calls = mutableListOf<OpenAiToolCall>()
        response.output.forEach { element ->
            val item = element as? JsonObject ?: invalid("output item")
            val status = item.optionalString("status")
            if (status != null && status != "completed") invalid("output item status")
            when (item.string("type")) {
                "reasoning" -> Unit
                "function_call" -> calls += OpenAiToolCall(
                    id = item.string("call_id").ifBlank { invalid("tool call ID") },
                    function = OpenAiFunctionCall(
                        name = item.string("name").ifBlank { invalid("tool name") },
                        arguments = item.string("arguments"),
                    ),
                )
                "message" -> {
                    if (item.string("role") != "assistant") invalid("output role")
                    val content = item["content"] as? JsonArray ?: invalid("output content")
                    content.forEach { part ->
                        val value = part as? JsonObject ?: invalid("output content part")
                        when (value.string("type")) {
                            "output_text" -> {
                                val outputText = value.string("text")
                                if (item.optionalString("phase") != "commentary") texts += outputText
                            }
                            "refusal" -> throw ProviderProtocolException("Provider declined the Responses request")
                            else -> invalid("output content type")
                        }
                    }
                }
                else -> invalid("output item type")
            }
        }
        if (calls.map { it.id }.distinct().size != calls.size) invalid("duplicate tool call IDs")
        val content = texts.joinToString("\n").takeIf { it.isNotBlank() }
        if (content == null && calls.isEmpty()) invalid("empty output")
        return OpenAiChatResponse(choices = listOf(OpenAiChoice(OpenAiChatMessage(
            role = "assistant",
            content = content,
            toolCalls = calls.takeIf { it.isNotEmpty() },
            responsesOutput = response.output,
        ))))
    }

    private fun JsonObject.string(key: String): String =
        (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: invalid(key)

    private fun JsonObject.optionalString(key: String): String? {
        val value: JsonElement = this[key] ?: return null
        if (value is JsonPrimitive && value.contentOrNull == null) return null
        return string(key)
    }

    private fun invalid(field: String): Nothing =
        throw ProviderProtocolException("Invalid Responses $field")

    @Serializable
    private data class ResponsesEnvelope(
        val status: String,
        val output: JsonArray = JsonArray(emptyList()),
        val error: OpenAiError? = null,
    )
}
