package com.example.mochi_pet.core.maps

import com.example.mochi_pet.core.agent.tool.AgentTool
import com.example.mochi_pet.core.agent.tool.ToolErrorCode
import com.example.mochi_pet.core.agent.tool.ToolExecutionContext
import com.example.mochi_pet.core.agent.tool.ToolInputException
import com.example.mochi_pet.core.agent.tool.ToolResultEnvelope
import com.example.mochi_pet.core.agent.tool.functionToolSchema
import com.example.mochi_pet.core.agent.tool.optionalString
import com.example.mochi_pet.core.agent.tool.requiredString
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request

class BaiduMapAgentException(message: String, cause: Throwable? = null) :
    Exception(message, cause)

class BaiduMapAgentClient(
    client: OkHttpClient = OkHttpClient(),
    private val baseUrl: HttpUrl = BAIDU_MAP_AGENT_BASE_URL.toHttpUrl(),
) {
    private val client = client.newBuilder()
        .connectTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .readTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .build()
    private val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
    }

    suspend fun call(
        operation: String,
        token: String,
        parameters: Map<String, String>,
    ): JsonObject = withContext(Dispatchers.IO) {
        require(operation in ALLOWED_OPERATIONS) {
            "Unsupported Baidu Map operation"
        }
        val normalizedToken = token.trim()
        require(normalizedToken.isNotEmpty()) {
            "Baidu Map Agent Plan token is required"
        }
        val url = baseUrl.newBuilder()
            .addPathSegment(operation)
            .apply {
                parameters.forEach { (name, value) ->
                    addQueryParameter(name, value)
                }
            }
            .build()
        val request = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $normalizedToken")
            .header("Accept", "application/json")
            .get()
            .build()
        try {
            client.newCall(request).execute().use { response ->
                val body = response.body
                    ?: throw BaiduMapAgentException(
                        "Baidu Map returned an empty response",
                    )
                if (body.contentLength() > MAX_RESPONSE_BYTES) {
                    throw BaiduMapAgentException(
                        "Baidu Map response exceeded the size limit",
                    )
                }
                val text = body.string()
                if (text.length > MAX_RESPONSE_CHARS) {
                    throw BaiduMapAgentException(
                        "Baidu Map response exceeded the size limit",
                    )
                }
                if (!response.isSuccessful) {
                    throw BaiduMapAgentException(
                        "Baidu Map request failed with HTTP ${response.code}",
                    )
                }
                try {
                    json.parseToJsonElement(text).jsonObject
                } catch (error: SerializationException) {
                    throw BaiduMapAgentException(
                        "Baidu Map returned invalid JSON",
                        error,
                    )
                } catch (error: IllegalArgumentException) {
                    throw BaiduMapAgentException(
                        "Baidu Map returned invalid JSON",
                        error,
                    )
                }
            }
        } catch (error: IOException) {
            throw BaiduMapAgentException(
                "Baidu Map network request failed",
                error,
            )
        }
    }
}

fun baiduMapAgentTools(
    client: BaiduMapAgentClient,
    token: String,
): List<AgentTool> = listOf(
    BaiduMapTool(
        name = "baidu_map_place",
        operation = "place",
        description =
            "Semantically search Baidu Maps for places matching the user's " +
                "complete constraints.",
        properties = buildJsonObject {
            put("user_raw_request", stringProperty("Complete original request"))
            put("region", stringProperty("City or region"))
            put("center", stringProperty("Optional GCJ-02 lat,lng center"))
            put("sort", stringProperty("relevance or distance"))
        },
        required = listOf("user_raw_request", "region"),
        parameterNames =
            listOf("user_raw_request", "region", "center", "sort"),
        client = client,
        token = token,
    ),
    BaiduMapTool(
        name = "baidu_map_direction",
        operation = "direction",
        description =
            "Plan a driving, walking, cycling, or transit route from a complete " +
                "natural-language request.",
        properties = buildJsonObject {
            put(
                "user_raw_request",
                stringProperty("Complete route request with origin and destination"),
            )
            put("location", stringProperty("Trusted GCJ-02 lat,lng location"))
            put(
                "refer_pois",
                stringProperty("Optional disambiguation POIs from trusted results"),
            )
        },
        required = listOf("user_raw_request", "location"),
        parameterNames =
            listOf("user_raw_request", "location", "refer_pois"),
        client = client,
        token = token,
    ),
    BaiduMapTool(
        name = "baidu_map_geocoding",
        operation = "geocoding",
        description = "Convert a complete address to trusted map coordinates.",
        properties = buildJsonObject {
            put("address", stringProperty("Complete address"))
            put("region", stringProperty("Optional city or region hint"))
        },
        required = listOf("address"),
        parameterNames = listOf("address", "region"),
        client = client,
        token = token,
    ),
    BaiduMapTool(
        name = "baidu_map_reverse_geocoding",
        operation = "reverse_geocoding",
        description = "Convert trusted GCJ-02 coordinates to an address.",
        properties = buildJsonObject {
            put("location", stringProperty("GCJ-02 lat,lng"))
        },
        required = listOf("location"),
        parameterNames = listOf("location"),
        client = client,
        token = token,
    ),
    BaiduMapTool(
        name = "baidu_map_weather",
        operation = "weather",
        description = "Read Baidu Maps weather by region or trusted coordinates.",
        properties = buildJsonObject {
            put("region", stringProperty("Administrative region"))
            put("location", stringProperty("Optional GCJ-02 lat,lng"))
        },
        required = emptyList(),
        parameterNames = listOf("region", "location"),
        validate = { arguments ->
            if (
                arguments.optionalString("region") == null &&
                arguments.optionalString("location") == null
            ) {
                throw ToolInputException(
                    "region or location is required",
                )
            }
        },
        client = client,
        token = token,
    ),
)

private class BaiduMapTool(
    override val name: String,
    private val operation: String,
    description: String,
    properties: JsonObject,
    required: List<String>,
    private val parameterNames: List<String>,
    private val client: BaiduMapAgentClient,
    private val token: String,
    private val validate: (JsonObject) -> Unit = {},
) : AgentTool {
    override val schema: JsonObject = functionToolSchema(
        name = name,
        description = description,
        properties = properties,
        required = required,
    )

    override suspend fun execute(
        arguments: JsonObject,
        context: ToolExecutionContext,
    ): ToolResultEnvelope {
        validate(arguments)
        val parameters = parameterNames.mapNotNull { name ->
            arguments.optionalString(name)?.let { name to it }
        }.toMap()
        return try {
            ToolResultEnvelope.success(
                client.call(
                    operation = operation,
                    token = token,
                    parameters = parameters,
                ),
            )
        } catch (error: IllegalArgumentException) {
            ToolResultEnvelope.error(
                ToolErrorCode.INVALID_ARGS,
                error.message ?: "Invalid Baidu Map request",
            )
        } catch (error: BaiduMapAgentException) {
            ToolResultEnvelope.error(
                ToolErrorCode.PROVIDER_ERROR,
                error.message ?: "Baidu Map request failed",
            )
        }
    }
}

private fun stringProperty(description: String): JsonObject =
    buildJsonObject {
        put("type", "string")
        put("description", description)
    }

const val BAIDU_MAP_AGENT_TOKEN_URL =
    "https://lbs.baidu.com/apiconsole/agentplan"
private const val BAIDU_MAP_AGENT_BASE_URL =
    "https://api.map.baidu.com/agent_plan/v1/"
private const val TIMEOUT_SECONDS = 30L
private const val MAX_RESPONSE_BYTES = 512L * 1024L
private const val MAX_RESPONSE_CHARS = 512_000
private val ALLOWED_OPERATIONS = setOf(
    "place",
    "direction",
    "geocoding",
    "reverse_geocoding",
    "weather",
)
