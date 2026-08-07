package com.example.mochi_pet.core.mcp

import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

const val DIANPING_SERVER_ID = "dianping"
const val DIANPING_MCP_ENDPOINT = "https://poiopen.dianping.com/router"

@Serializable
data class DianpingCredentials(
    val appKey: String,
    val appSecret: String,
    val searchSession: String,
    val detailSession: String,
)

val DIANPING_MCP_TOOLS = listOf(
    McpRemoteTool(
        name = "search_poi",
        description =
            "Search authorized Dianping POIs by keyword, city, category, " +
                "coordinates, or radius.",
        inputSchema = objectSchema(
            properties = buildJsonObject {
                put("keyword", stringSchema("Search keyword"))
                put("city", stringSchema("Authorized city name"))
                put("latitude", numberSchema("GCJ-02 latitude"))
                put("longitude", numberSchema("GCJ-02 longitude"))
                put("radius", integerSchema("Radius in meters, maximum 5000"))
                put(
                    "mall",
                    integerSchema("Set to 1 to search shopping malls"),
                )
                put(
                    "categories",
                    stringSchema("Comma-separated Dianping categories"),
                )
                put("page", integerSchema("Page number from 1 to 10"))
                put("limit", integerSchema("Maximum results to return"))
            },
        ),
    ),
    McpRemoteTool(
        name = "get_poi",
        description =
            "Read an authorized Dianping POI by its openshopid, including " +
                "official H5 and app handoff links when available.",
        inputSchema = objectSchema(
            properties = buildJsonObject {
                put("openshopid", stringSchema("Dianping open POI ID"))
            },
            required = listOf("openshopid"),
        ),
    ),
)

open class DianpingMcpClient(
    client: OkHttpClient = OkHttpClient(),
    private val nowMillis: () -> Long = System::currentTimeMillis,
    private val baseEndpoint: String = DIANPING_MCP_ENDPOINT,
) : McpStreamableHttpClient(client) {
    private val client = client.newBuilder()
        .connectTimeout(DEFAULT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .readTimeout(DEFAULT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .writeTimeout(DEFAULT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .build()
    private val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
    }

    override suspend fun listTools(
        server: McpServerRuntime,
    ): List<McpRemoteTool> {
        requireDianpingServer(server)
        return DIANPING_MCP_TOOLS
    }

    override suspend fun callTool(
        server: McpServerRuntime,
        toolName: String,
        arguments: JsonObject,
    ): JsonObject = withContext(Dispatchers.IO) {
        requireDianpingServer(server)
        val credentials = decodeCredentials(server.accessToken)
        val response = when (toolName) {
            "search_poi" -> searchPoi(credentials, arguments)
            "get_poi" -> getPoi(credentials, arguments)
            else -> throw McpException(
                "Unsupported Dianping MCP tool: $toolName",
            )
        }
        buildJsonObject {
            put("server", server.name)
            put("tool", toolName)
            put("structured_content", response)
            put("text", response.toString().take(MAX_RESULT_TEXT_CHARS))
        }
    }

    private fun searchPoi(
        credentials: DianpingCredentials,
        arguments: JsonObject,
    ): JsonObject {
        val keyword = arguments.string("keyword")
        val city = arguments.string("city")
        val latitude = arguments.number("latitude")
        val longitude = arguments.number("longitude")
        require((latitude == null) == (longitude == null)) {
            "Dianping latitude and longitude must be provided together"
        }
        require(latitude == null || latitude in -90.0..90.0) {
            "Dianping latitude must be between -90 and 90"
        }
        require(longitude == null || longitude in -180.0..180.0) {
            "Dianping longitude must be between -180 and 180"
        }
        val radius = arguments.integer("radius")
        require(radius == null || radius in 1..5_000) {
            "Dianping radius must be between 1 and 5000 meters"
        }
        val page = arguments.integer("page") ?: 1
        require(page in 1..10) {
            "Dianping page must be between 1 and 10"
        }
        val limit = arguments.integer("limit") ?: 25
        require(limit in 1..25) {
            "Dianping limit must be between 1 and 25"
        }
        return call(
            endpoint = "${baseEndpoint.trimEnd('/')}/poisearch/search",
            credentials = credentials,
            session = credentials.searchSession,
            parameters = buildMap {
                keyword?.let { put("keyword", it) }
                city?.let { put("city", it) }
                latitude?.let { put("latitude", it.toString()) }
                longitude?.let { put("longitude", it.toString()) }
                radius?.let { put("radius", it.toString()) }
                arguments.integer("mall")?.let {
                    require(it == 1) {
                        "Dianping mall must be 1 when supplied"
                    }
                    put("mall", "1")
                }
                arguments.string("categories")?.let {
                    put("categories", it)
                }
                put("page", page.toString())
                put("limit", limit.toString())
            },
        )
    }

    private fun getPoi(
        credentials: DianpingCredentials,
        arguments: JsonObject,
    ): JsonObject {
        val openShopId = arguments.string("openshopid")
            ?: throw IllegalArgumentException(
                "Dianping openshopid is required",
            )
        require(openShopId.length <= MAX_OPEN_SHOP_ID_CHARS) {
            "Dianping openshopid is too long"
        }
        return call(
            endpoint = "${baseEndpoint.trimEnd('/')}/poi/getsinglepoi",
            credentials = credentials,
            session = credentials.detailSession,
            parameters = mapOf("openshopid" to openShopId),
        )
    }

    private fun call(
        endpoint: String,
        credentials: DianpingCredentials,
        session: String,
        parameters: Map<String, String>,
    ): JsonObject {
        val timestamp = nowMillis().toString()
        val signed = buildMap {
            put("appkey", credentials.appKey)
            put("session", session)
            put("timestamp", timestamp)
            putAll(parameters.filterValues(String::isNotBlank))
        }
        val payload = buildJsonObject {
            signed.forEach { (name, value) ->
                put(name, value)
            }
            put("sign", dianpingSign(signed, credentials.appSecret))
        }
        val request = Request.Builder()
            .url(endpoint)
            .post(payload.toString().toRequestBody(JSON_MEDIA_TYPE))
            .header("Accept", "application/json")
            .header("User-Agent", "Mochi-Android/1.0")
            .build()
        val raw = try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    throw McpException(
                        "Dianping API failed with HTTP ${response.code}",
                    )
                }
                val body = response.body
                if (body.contentLength() > MAX_RESPONSE_BYTES) {
                    throw McpException("Dianping response was too large")
                }
                val source = body.source()
                source.request(MAX_RESPONSE_BYTES + 1)
                if (source.buffer.size > MAX_RESPONSE_BYTES) {
                    throw McpException("Dianping response was too large")
                }
                source.readUtf8()
            }
        } catch (error: McpException) {
            throw error
        } catch (error: IOException) {
            throw McpException("Dianping network request failed", error)
        }
        val response = try {
            json.parseToJsonElement(raw).jsonObject
        } catch (error: SerializationException) {
            throw McpException("Dianping returned invalid JSON", error)
        } catch (error: IllegalArgumentException) {
            throw McpException("Dianping returned an invalid response", error)
        }
        val success = response["success"]
            ?.jsonPrimitive
            ?.contentOrNull
            ?.toBooleanStrictOrNull()
        val status = response.string("status")
        val succeeded =
            success == true ||
                status.equals("OK", ignoreCase = true) ||
                status.equals("success", ignoreCase = true)
        if (!succeeded) {
            throw McpException(
                response.string("message") ?: "Dianping request failed",
            )
        }
        return response
    }

    private fun decodeCredentials(raw: String?): DianpingCredentials {
        val value = raw
            ?: throw McpAuthenticationException(
                "Dianping credentials are not configured",
            )
        return try {
            json.decodeFromString<DianpingCredentials>(value)
        } catch (error: SerializationException) {
            throw McpAuthenticationException(
                "Stored Dianping credentials are invalid",
            )
        }
    }

    private fun requireDianpingServer(server: McpServerRuntime) {
        require(server.id == DIANPING_SERVER_ID) {
            "Dianping MCP client received another server"
        }
    }

    companion object {
        fun encodeCredentials(credentials: DianpingCredentials): String =
            Json.encodeToString(credentials)

        private const val DEFAULT_TIMEOUT_SECONDS = 20L
        private const val MAX_RESPONSE_BYTES = 512L * 1024L
        private const val MAX_RESULT_TEXT_CHARS = 16_000
        private const val MAX_OPEN_SHOP_ID_CHARS = 256
        private val JSON_MEDIA_TYPE =
            "application/json; charset=utf-8".toMediaType()
    }
}

internal fun dianpingSign(
    parameters: Map<String, String>,
    appSecret: String,
): String {
    val canonical = parameters.entries
        .filter { it.value.isNotBlank() }
        .sortedBy { it.key.lowercase() }
        .joinToString(separator = "") {
            it.key.lowercase() + it.value
        }
    return MessageDigest.getInstance("MD5")
        .digest("$appSecret$canonical$appSecret".toByteArray())
        .joinToString(separator = "") { byte ->
            "%02x".format(byte.toInt() and 0xff)
        }
}

private fun objectSchema(
    properties: JsonObject,
    required: List<String> = emptyList(),
): JsonObject = buildJsonObject {
    put("type", "object")
    put("properties", properties)
    put(
        "required",
        buildJsonArray {
            required.forEach { add(JsonPrimitive(it)) }
        },
    )
    put("additionalProperties", false)
}

private fun stringSchema(description: String): JsonObject =
    buildJsonObject {
        put("type", "string")
        put("description", description)
    }

private fun numberSchema(description: String): JsonObject =
    buildJsonObject {
        put("type", "number")
        put("description", description)
    }

private fun integerSchema(description: String): JsonObject =
    buildJsonObject {
        put("type", "integer")
        put("description", description)
    }

private fun JsonObject.string(name: String): String? =
    this[name]?.jsonPrimitive?.contentOrNull?.trim()?.takeIf(String::isNotEmpty)

private fun JsonObject.number(name: String): Double? =
    this[name]?.jsonPrimitive?.doubleOrNull

private fun JsonObject.integer(name: String): Int? =
    this[name]?.jsonPrimitive?.intOrNull
