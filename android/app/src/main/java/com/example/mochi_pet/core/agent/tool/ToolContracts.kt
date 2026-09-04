package com.example.mochi_pet.core.agent.tool

import com.example.mochi_pet.core.database.PlannerNotFoundException
import com.example.mochi_pet.core.schedule.AgentScheduleNotFoundException
import com.example.mochi_pet.core.model.MochiSurface
import java.time.DateTimeException
import java.time.LocalDate
import java.util.Locale
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

@Serializable
data class ToolResultEnvelope(
    val status: String,
    val code: String? = null,
    val message: String? = null,
    val data: JsonElement? = null,
    @Transient
    val modelImages: List<ModelImageAttachment> = emptyList(),
) {
    companion object {
        fun success(
            data: JsonElement? = null,
            modelImages: List<ModelImageAttachment> = emptyList(),
        ): ToolResultEnvelope =
            ToolResultEnvelope(
                status = "ok",
                data = data,
                modelImages = modelImages,
            )

        fun error(
            code: ToolErrorCode,
            message: String,
        ): ToolResultEnvelope =
            ToolResultEnvelope(
                status = "error",
                code = code.name,
                message = message,
            )
    }

}

data class ModelImageAttachment(
    val mimeType: String,
    val bytes: ByteArray,
)

class ModelImageRelay {
    private var image: ModelImageAttachment? = null
    private var handedToSubagent = false

    @Synchronized
    fun offer(candidate: ModelImageAttachment) {
        if (image == null) {
            image = candidate
        }
    }

    @Synchronized
    fun takeForSubagent(): ModelImageAttachment? {
        if (handedToSubagent) {
            return null
        }
        val available = image ?: return null
        handedToSubagent = true
        image = null
        return available
    }
}

enum class ToolErrorCode {
    INVALID_ARGS,
    UNKNOWN_TOOL,
    NOT_FOUND,
    STALE_REF,
    CONFLICT,
    PERMISSION_DENIED,
    CANCELLED,
    PROVIDER_ERROR,
    TIMEOUT,
    INTERNAL_ERROR,
}

data class ToolExecutionContext(
    val currentDate: LocalDate,
    val currentSurface: MochiSurface,
    val modelImageInputAllowed: Boolean = false,
    val modelImageRelay: ModelImageRelay = ModelImageRelay(),
)

fun allowsCameraEventImageInput(query: String): Boolean {
    val normalized = query.lowercase(Locale.ROOT)
    val cameraTerms = listOf(
        "camera",
        "doorbell",
        "security cam",
        "door cam",
        "摄像头",
        "门铃",
        "监控",
    )
    val directImageTerms = listOf(
        "image",
        "photo",
        "picture",
        "snapshot",
        "footage",
        "图片",
        "照片",
        "截图",
        "画面",
    )
    val eventTerms = listOf(
        "event",
        "what happened",
        "事件",
        "发生了什么",
    )
    val recencyTerms = listOf(
        "latest",
        "newest",
        "most recent",
        "最新",
        "最近",
    )
    val viewTerms = listOf(
        "show",
        "view",
        "see",
        "describe",
        "analyze",
        "what happened",
        "查看",
        "看看",
        "描述",
        "分析",
        "发生了什么",
    )
    val settingsTerms = listOf(
        "setting",
        "configuration",
        "notification",
        "enabled",
        "disabled",
        "status",
        "设置",
        "配置",
        "通知",
        "启用",
        "关闭",
        "状态",
    )
    val hasCamera = cameraTerms.any(normalized::contains)
    val hasDirectImage = directImageTerms.any(normalized::contains)
    val hasEvent = eventTerms.any(normalized::contains)
    val hasRecency = recencyTerms.any(normalized::contains)
    val hasViewRequest = viewTerms.any(normalized::contains)
    val isSettingsRequest = settingsTerms.any(normalized::contains)
    return !isSettingsRequest &&
        hasCamera &&
        hasViewRequest &&
        (hasDirectImage || (hasEvent && hasRecency))
}

class ToolInputException(message: String) : IllegalArgumentException(message)

interface AgentTool {
    val name: String
    val schema: JsonObject

    suspend fun execute(
        arguments: JsonObject,
        context: ToolExecutionContext,
    ): ToolResultEnvelope
}

class ToolRegistry(tools: List<AgentTool>) {
    private val toolsByName: Map<String, AgentTool>

    init {
        val duplicateNames = tools.groupBy(AgentTool::name)
            .filterValues { it.size > 1 }
            .keys
        require(duplicateNames.isEmpty()) {
            "Duplicate tool names: ${duplicateNames.sorted().joinToString()}"
        }
        toolsByName = tools.associateBy(AgentTool::name)
    }

    val schemas: List<JsonObject> =
        toolsByName.values.map(AgentTool::schema)

    val names: Set<String> = toolsByName.keys

    suspend fun execute(
        name: String,
        arguments: JsonObject,
        context: ToolExecutionContext,
    ): ToolResultEnvelope {
        val tool = toolsByName[name]
            ?: return ToolResultEnvelope.error(
                ToolErrorCode.UNKNOWN_TOOL,
                "Unknown tool: $name",
            )
        return try {
            tool.execute(arguments, context)
        } catch (error: PlannerNotFoundException) {
            ToolResultEnvelope.error(
                ToolErrorCode.NOT_FOUND,
                error.message ?: "Planner item not found",
            )
        } catch (error: AgentScheduleNotFoundException) {
            ToolResultEnvelope.error(
                ToolErrorCode.NOT_FOUND,
                error.message ?: "Agent schedule not found",
            )
        } catch (error: ToolInputException) {
            ToolResultEnvelope.error(
                ToolErrorCode.INVALID_ARGS,
                error.message ?: "Invalid tool arguments",
            )
        } catch (error: DateTimeException) {
            ToolResultEnvelope.error(
                ToolErrorCode.INVALID_ARGS,
                error.message ?: "Invalid date or time",
            )
        } catch (error: SerializationException) {
            ToolResultEnvelope.error(
                ToolErrorCode.INVALID_ARGS,
                error.message ?: "Invalid JSON arguments",
            )
        } catch (error: IllegalArgumentException) {
            ToolResultEnvelope.error(
                ToolErrorCode.INVALID_ARGS,
                error.message ?: "Invalid tool arguments",
            )
        }
    }

    suspend fun execute(
        name: String,
        argumentsJson: String,
        context: ToolExecutionContext,
    ): ToolResultEnvelope {
        val arguments = try {
            AgentToolJson.format.parseToJsonElement(argumentsJson).jsonObject
        } catch (error: SerializationException) {
            return ToolResultEnvelope.error(
                ToolErrorCode.INVALID_ARGS,
                "Tool arguments must be a JSON object",
            )
        } catch (error: IllegalArgumentException) {
            return ToolResultEnvelope.error(
                ToolErrorCode.INVALID_ARGS,
                "Tool arguments must be a JSON object",
            )
        }
        return execute(name, arguments, context)
    }
}

object AgentToolJson {
    val format: Json = Json {
        encodeDefaults = true
        explicitNulls = false
        ignoreUnknownKeys = false
    }

    fun encode(result: ToolResultEnvelope): String =
        format.encodeToString(ToolResultEnvelope.serializer(), result)
}

fun functionToolSchema(
    name: String,
    description: String,
    properties: JsonObject,
    required: List<String>,
): JsonObject =
    buildJsonObject {
        put("type", "function")
        put(
            "function",
            buildJsonObject {
                put("name", name)
                put("description", description)
                put(
                    "parameters",
                    buildJsonObject {
                        put("type", "object")
                        put("properties", properties)
                        put(
                            "required",
                            kotlinx.serialization.json.JsonArray(
                                required.map {
                                    kotlinx.serialization.json.JsonPrimitive(it)
                                },
                            ),
                        )
                    },
                )
            },
        )
    }
