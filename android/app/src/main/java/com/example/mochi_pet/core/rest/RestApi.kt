package com.example.mochi_pet.core.rest

import com.example.mochi_pet.core.agent.tool.AgentTool
import com.example.mochi_pet.core.agent.tool.ToolErrorCode
import com.example.mochi_pet.core.agent.tool.ToolExecutionContext
import com.example.mochi_pet.core.agent.tool.ToolResultEnvelope
import com.example.mochi_pet.core.agent.tool.functionToolSchema
import java.util.UUID
import java.net.InetAddress
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.put
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

@Serializable
enum class RestAuth { NONE, BEARER, HEADER, AUTHORIZATION }

@Serializable
enum class RestMethod { GET, POST, PUT, PATCH, DELETE }

@Serializable
enum class RestParameterLocation { QUERY, PATH, BODY }

@Serializable
enum class RestValueType { STRING, NUMBER, BOOLEAN }

@Serializable
data class RestParameter(
    val name: String,
    val description: String = "",
    val location: RestParameterLocation = RestParameterLocation.QUERY,
    val type: RestValueType = RestValueType.STRING,
    val required: Boolean = true,
    val defaultValue: JsonElement? = null,
)

@Serializable
data class RestOutputField(
    val pointer: String,
    val name: String,
    val description: String = "",
    val type: RestValueType = RestValueType.STRING,
)

@Serializable
data class RestToolDefinition(
    val id: String = UUID.randomUUID().toString(),
    val name: String = "",
    val description: String = "",
    val method: RestMethod = RestMethod.GET,
    val path: String = "/",
    val parameters: List<RestParameter> = emptyList(),
    val outputs: List<RestOutputField> = emptyList(),
    val body: JsonObject = JsonObject(emptyMap()),
    val successPointer: String? = null,
    val successValue: JsonElement? = null,
    val requiresConfirmation: Boolean = true,
    val enabled: Boolean = false,
)

@Serializable
data class RestConnection(
    val id: String = UUID.randomUUID().toString(),
    val name: String = "",
    val baseUrl: String = "",
    val auth: RestAuth = RestAuth.BEARER,
    val headerName: String = "X-API-Key",
    val enabled: Boolean = false,
    val revision: String = "",
    val tools: List<RestToolDefinition> = emptyList(),
)

data class RestConnectionSummary(val connection: RestConnection, val hasSecret: Boolean)

// Secret text is transient input, never part of a serializable definition.
class RestConnectionInput(val connection: RestConnection, val secret: String = "")

sealed interface RestApiChange {
    class SaveConnection(val input: RestConnectionInput) : RestApiChange
    data class SaveTool(val connectionId: String, val revision: String, val tool: RestToolDefinition) : RestApiChange
    data class SetEnabled(val connectionId: String, val toolId: String?, val enabled: Boolean) : RestApiChange
    data class Delete(val connectionId: String, val toolId: String? = null) : RestApiChange
}

data class RestFieldSample(val pointer: String, val value: JsonPrimitive, val type: RestValueType)
data class RestTestResult(val httpStatus: Int, val fields: List<RestFieldSample>, val output: JsonObject?)

class RestApiException(val code: ToolErrorCode, message: String) : Exception(message)

data class RestRequest(val url: HttpUrl, val method: RestMethod, val body: JsonObject?)
data class RestResponse(val status: Int, val body: JsonElement)

fun interface RestTransport {
    suspend fun execute(request: RestRequest, auth: RestAuth, headerName: String, secret: String): RestResponse
}

fun restToolAlias(connectionId: String, toolId: String): String =
    "rest_${UUID.nameUUIDFromBytes("$connectionId:$toolId".toByteArray(Charsets.UTF_8)).toString().replace("-", "")}"

fun validateRestConnection(connection: RestConnection): HttpUrl {
    require(connection.id.matches(Regex("[a-f0-9-]{36}"))) { "Invalid REST connection ID." }
    require(connection.name.isNotBlank() && connection.name.length <= 80) { "Enter a connection name (up to 80 characters)." }
    val url = connection.baseUrl.toHttpUrlOrNull()
    require(url != null && url.scheme == "https" && url.port == 443 &&
        url.username.isEmpty() && url.password.isEmpty() && url.query == null &&
        url.fragment == null && url.encodedPath == "/"
    ) { "Use a public HTTPS service address without a path, query, or credentials." }
    require(url.host != "localhost" && !url.host.endsWith(".localhost") &&
        !url.host.endsWith(".local")
    ) { "Local network REST endpoints are not supported." }
    if (url.host.contains(':') || url.host.matches(Regex("[0-9.]+"))) {
        require(isPublicRestAddress(InetAddress.getByName(url.host))) { "Local network REST endpoints are not supported." }
    }
    if (connection.auth == RestAuth.HEADER) {
        require(connection.headerName.matches(Regex("[A-Za-z][A-Za-z0-9-]{0,63}")) &&
            connection.headerName.lowercase() !in setOf(
                "host", "content-length", "content-type", "connection", "transfer-encoding",
                "cookie", "proxy-authorization", "authorization", "accept", "accept-encoding",
            )
        ) { "Choose a dedicated API key header, such as X-API-Key." }
    }
    return url
}

fun validateRestTool(tool: RestToolDefinition, requireOutputs: Boolean = true) {
    require(tool.id.matches(Regex("[a-f0-9-]{36}"))) { "Invalid REST tool ID." }
    require(tool.name.isNotBlank() && tool.name.length <= 80) { "Enter a tool name (up to 80 characters)." }
    require(tool.description.isNotBlank() && tool.description.length <= 1_000) { "Describe when Mochi should use this tool (up to 1000 characters)." }
    require(tool.path.startsWith("/") && !tool.path.startsWith("//") &&
        tool.path.length <= 1_000 && tool.path.none { it == '?' || it == '#' || it == '\\' || it.isWhitespace() }
    ) { "Enter an absolute API path; configure query parameters separately." }
    require(tool.path.split("/").none { segment ->
        val normalized = segment.replace("%2e", ".", ignoreCase = true)
        normalized == "." || normalized == ".."
    }) { "Relative path segments are not allowed." }
    require(tool.parameters.size <= 20 && tool.outputs.size <= 32) { "Too many REST parameters or output fields." }
    require(tool.parameters.map { it.name }.distinct().size == tool.parameters.size &&
        tool.parameters.all { it.name.matches(Regex("[A-Za-z_][A-Za-z0-9_]{0,63}")) && it.name != "confirmed" }
    ) { "Parameter names must be unique identifiers; confirmed is reserved." }
    tool.parameters.forEach { parameter ->
        require(parameter.description.length <= 500) { "Parameter description is too long." }
        require(parameter.location != RestParameterLocation.PATH || parameter.required) { "Path parameters must be required." }
        parameter.defaultValue?.let { require(restValueMatches(it, parameter.type)) { "A parameter default has the wrong type." } }
    }
    val placeholders = Regex("\\{([A-Za-z_][A-Za-z0-9_]*)}").findAll(tool.path).map { it.groupValues[1] }.toSet()
    require(placeholders == tool.parameters.filter { it.location == RestParameterLocation.PATH }.map { it.name }.toSet() &&
        !Regex("\\{([A-Za-z_][A-Za-z0-9_]*)}").replace(tool.path, "").any { it == '{' || it == '}' }
    ) { "Every path placeholder needs a matching path parameter." }
    require(tool.method != RestMethod.GET ||
        (tool.body.isEmpty() && tool.parameters.none { it.location == RestParameterLocation.BODY })
    ) { "GET requests cannot have a JSON body." }
    require(tool.body.toString().length <= 16_000) { "JSON body is too large." }
    require(tool.method == RestMethod.GET || tool.requiresConfirmation) { "Write requests require confirmation." }
    require(!requireOutputs || tool.outputs.isNotEmpty()) { "Select at least one response field." }
    require(tool.outputs.map { it.name }.distinct().size == tool.outputs.size &&
        tool.outputs.all { it.name.matches(Regex("[A-Za-z_][A-Za-z0-9_]{0,63}")) && it.description.length <= 500 }
    ) { "Output names must be unique identifiers." }
    tool.outputs.forEach { validatePointer(it.pointer) }
    tool.successPointer?.let {
        validatePointer(it)
        require(tool.successValue != null) { "Enter the expected success value as JSON." }
    }
}

private fun validatePointer(pointer: String) {
    require(pointer.length <= 1_000 && (pointer.isEmpty() || pointer.startsWith("/")) &&
        !Regex("~(?![01])").containsMatchIn(pointer)
    ) { "Use a JSON pointer such as /data/temperature." }
}

fun restValueMatches(value: JsonElement, type: RestValueType): Boolean {
    val primitive = value as? JsonPrimitive ?: return false
    if (primitive == JsonNull) return false
    return when (type) {
        RestValueType.STRING -> primitive.isString && primitive.content.length <= 8_000
        RestValueType.NUMBER -> !primitive.isString && primitive.doubleOrNull?.isFinite() == true
        RestValueType.BOOLEAN -> !primitive.isString && primitive.booleanOrNull != null
    }
}

fun restPointer(root: JsonElement, pointer: String): JsonElement? {
    if (pointer.isEmpty()) return root
    return pointer.substring(1).split("/").fold<String, JsonElement?>(root) { value, part ->
        val key = part.replace("~1", "/").replace("~0", "~")
        when (value) {
            is JsonObject -> value[key]
            is JsonArray -> key.toIntOrNull()?.let { value.getOrNull(it) }
            else -> null
        }
    }
}

fun prepareRestRequest(connection: RestConnection, tool: RestToolDefinition, arguments: JsonObject): RestRequest {
    val origin = validateRestConnection(connection)
    validateRestTool(tool, requireOutputs = false)
    require(arguments.keys.all { key -> key == "confirmed" || tool.parameters.any { it.name == key } }) { "Unknown REST argument." }
    var path = tool.path
    val query = mutableListOf<Pair<String, String>>()
    val body = tool.body.toMutableMap()
    for (parameter in tool.parameters) {
        val value = arguments[parameter.name] ?: parameter.defaultValue
        require(value != null || !parameter.required) { "A required REST parameter is missing." }
        if (value == null) continue
        require(restValueMatches(value, parameter.type)) { "A REST parameter has the wrong type." }
        val text = (value as JsonPrimitive).content
        when (parameter.location) {
            RestParameterLocation.QUERY -> query += parameter.name to text
            RestParameterLocation.BODY -> body[parameter.name] = value
            RestParameterLocation.PATH -> {
                require(text.isNotEmpty() && text != "." && text != "..") { "Invalid path parameter." }
                val encoded = HttpUrl.Builder().scheme("https").host("example.com").addPathSegment(text).build().encodedPath.substring(1)
                path = path.replace("{${parameter.name}}", encoded)
            }
        }
    }
    val url = origin.newBuilder().encodedPath(path).apply {
        query.forEach { (name, value) -> addQueryParameter(name, value) }
    }.build()
    require(url.host == origin.host && url.port == origin.port && url.scheme == origin.scheme) { "REST request changed its service address." }
    return RestRequest(url, tool.method, if (tool.method == RestMethod.GET) null else JsonObject(body))
}

fun restResponseFields(body: JsonElement): List<RestFieldSample> {
    val result = mutableListOf<RestFieldSample>()
    fun walk(value: JsonElement, pointer: String, depth: Int) {
        if (depth > 8 || result.size >= 128) return
        when (value) {
            is JsonObject -> value.entries.take(128).forEach { (key, item) ->
                walk(item, "$pointer/${key.replace("~", "~0").replace("/", "~1")}", depth + 1)
            }
            is JsonArray -> value.take(20).forEachIndexed { index, item -> walk(item, "$pointer/$index", depth + 1) }
            is JsonPrimitive -> {
                val type = RestValueType.entries.firstOrNull { restValueMatches(value, it) }
                if (type != null) result += RestFieldSample(pointer, value, type)
            }
        }
    }
    walk(body, "", 0)
    return result
}

fun selectRestOutput(tool: RestToolDefinition, body: JsonElement): JsonObject {
    if (tool.successPointer != null && restPointer(body, tool.successPointer) != tool.successValue) {
        throw RestApiException(ToolErrorCode.PROVIDER_ERROR, "The REST response did not match the configured success value.")
    }
    return JsonObject(tool.outputs.associate { field ->
        val value = restPointer(body, field.pointer)
        if (value == null || !restValueMatches(value, field.type)) {
            throw RestApiException(ToolErrorCode.PROVIDER_ERROR, "A selected response field is missing or has changed type.")
        }
        field.name to value
    }).also {
        if (it.toString().toByteArray(Charsets.UTF_8).size > 32_768) throw RestApiException(ToolErrorCode.PROVIDER_ERROR, "Selected REST output is too large.")
    }
}

fun redactRestResponse(body: JsonElement, secret: String): JsonElement {
    if (secret.isEmpty()) return body
    return when (body) {
        is JsonObject -> JsonObject(body.entries.associate { (key, value) ->
            key.replace(secret, "[redacted]") to redactRestResponse(value, secret)
        })
        is JsonArray -> JsonArray(body.map { redactRestResponse(it, secret) })
        is JsonPrimitive -> if (body.content.contains(secret)) JsonPrimitive(body.content.replace(secret, "[redacted]")) else body
    }
}

class RestAgentTool(
    connection: RestConnection,
    private val definition: RestToolDefinition,
    private val call: suspend (JsonObject) -> JsonObject,
) : AgentTool {
    override val name = restToolAlias(connection.id, definition.id)
    override val schema = functionToolSchema(
        name,
        "${definition.name}: ${definition.description}. Returns: " +
            definition.outputs.joinToString("; ") { "${it.name}: ${it.description}" } +
            if (definition.requiresConfirmation) " Ask the user before executing; set confirmed only after explicit approval." else "",
        buildJsonObject {
            definition.parameters.forEach { parameter ->
                put(parameter.name, buildJsonObject {
                    put("type", parameter.type.name.lowercase())
                    put("description", parameter.description)
                    parameter.defaultValue?.let { put("default", it) }
                })
            }
            if (definition.requiresConfirmation) put("confirmed", buildJsonObject {
                put("type", "boolean")
                put("description", "True only after the user explicitly approves this API action.")
            })
        },
        definition.parameters.filter { it.required && it.defaultValue == null }.map { it.name } +
            if (definition.requiresConfirmation) listOf("confirmed") else emptyList(),
    )

    override suspend fun execute(arguments: JsonObject, context: ToolExecutionContext): ToolResultEnvelope {
        if (definition.requiresConfirmation && arguments["confirmed"] != JsonPrimitive(true)) {
            return ToolResultEnvelope.error(ToolErrorCode.PERMISSION_DENIED, "Confirm this REST API action with the user first.")
        }
        return try {
            ToolResultEnvelope.success(call(arguments))
        } catch (error: RestApiException) {
            ToolResultEnvelope.error(error.code, error.message ?: "REST API request failed.")
        }
    }
}
