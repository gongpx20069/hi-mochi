package com.example.mochi_pet.core.maps

import com.example.mochi_pet.core.agent.tool.AgentTool
import com.example.mochi_pet.core.agent.tool.ToolErrorCode
import com.example.mochi_pet.core.agent.tool.ToolExecutionContext
import com.example.mochi_pet.core.agent.tool.ToolInputException
import com.example.mochi_pet.core.agent.tool.ToolResultEnvelope
import com.example.mochi_pet.core.agent.tool.functionToolSchema
import com.example.mochi_pet.core.agent.tool.optionalInt
import com.example.mochi_pet.core.agent.tool.optionalString
import com.example.mochi_pet.core.agent.tool.requiredEnum
import com.example.mochi_pet.core.agent.tool.requiredString
import java.io.IOException
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request

@Serializable
data class AmapCredentials(
    val webServiceKey: String,
    val securityKey: String? = null,
)

class AmapMapsException(message: String, cause: Throwable? = null) :
    Exception(message, cause)

class AmapMapsClient(
    client: OkHttpClient = OkHttpClient(),
    private val baseUrl: HttpUrl = AMAP_WEB_SERVICE_BASE_URL.toHttpUrl(),
) {
    private val client = client.newBuilder()
        .connectTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .readTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .build()
    private val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
    }

    internal suspend fun call(
        endpoint: AmapEndpoint,
        credentials: AmapCredentials,
        parameters: Map<String, String>,
    ): JsonObject = withContext(Dispatchers.IO) {
        val key = credentials.webServiceKey.trim()
        require(key.isNotEmpty()) { "Amap Web Service Key is required" }
        val signedParameters = buildMap {
            putAll(parameters.filterValues(String::isNotBlank))
            put("key", key)
        }
        val url = baseUrl.newBuilder()
            .apply {
                endpoint.pathSegments.forEach(::addPathSegment)
                signedParameters.forEach { (name, value) ->
                    addQueryParameter(name, value)
                }
                credentials.securityKey
                    ?.trim()
                    ?.takeIf(String::isNotEmpty)
                    ?.let { securityKey ->
                        addQueryParameter(
                            "sig",
                            amapSign(signedParameters, securityKey),
                        )
                    }
            }
            .build()
        val request = Request.Builder()
            .url(url)
            .header("Accept", "application/json")
            .header("User-Agent", "Mochi-Android/1.0")
            .get()
            .build()
        val raw = try {
            client.newCall(request).execute().use { response ->
                val body = response.body
                    ?: throw AmapMapsException(
                        "Amap returned an empty response",
                    )
                if (body.contentLength() > MAX_RESPONSE_BYTES) {
                    throw AmapMapsException(
                        "Amap response exceeded the size limit",
                    )
                }
                val source = body.source()
                source.request(MAX_RESPONSE_BYTES + 1)
                if (source.buffer.size > MAX_RESPONSE_BYTES) {
                    throw AmapMapsException(
                        "Amap response exceeded the size limit",
                    )
                }
                if (!response.isSuccessful) {
                    throw AmapMapsException(
                        "Amap request failed with HTTP ${response.code}",
                    )
                }
                source.readUtf8()
            }
        } catch (error: AmapMapsException) {
            throw error
        } catch (error: IOException) {
            throw AmapMapsException("Amap network request failed", error)
        }
        val response = try {
            json.parseToJsonElement(raw).jsonObject
        } catch (error: SerializationException) {
            throw AmapMapsException("Amap returned invalid JSON", error)
        } catch (error: IllegalArgumentException) {
            throw AmapMapsException("Amap returned invalid JSON", error)
        }
        if (response.string("status") != "1") {
            throw AmapMapsException(
                response.string("info") ?: "Amap request failed",
            )
        }
        buildJsonObject {
            put("provider", "Amap")
            response.forEach { (name, value) -> put(name, value) }
        }
    }
}

fun amapMapTools(
    client: AmapMapsClient,
    credentials: AmapCredentials,
): List<AgentTool> = listOf(
    AmapTool(
        name = "amap_search_poi",
        description =
            "Search Amap places or nearby merchants with optional city, " +
                "category, distance, and sort constraints.",
        properties = buildJsonObject {
            put("keyword", stringProperty("Place or merchant search terms"))
            put("city", stringProperty("Optional city or region"))
            put("latitude", numberProperty("Trusted GCJ-02 latitude"))
            put("longitude", numberProperty("Trusted GCJ-02 longitude"))
            put("radius", integerProperty("Search radius in meters"))
            put("types", stringProperty("Optional Amap POI type codes"))
            put(
                "sort",
                enumProperty(
                    "Nearby result order",
                    "distance",
                    "weight",
                ),
            )
            put("page", integerProperty("Page number"))
            put("limit", integerProperty("Results per page"))
        },
        required = emptyList(),
    ) { arguments ->
        val keyword = arguments.boundedString("keyword", MAX_KEYWORD_CHARS)
        val city = arguments.boundedString("city", MAX_CITY_CHARS)
        val types = arguments.boundedString("types", MAX_TYPES_CHARS)
        val coordinates = arguments.coordinates()
        val page = arguments.optionalInt("page") ?: 1
        val limit = arguments.optionalInt("limit") ?: 10
        require(page in 1..MAX_PAGE) { "page must be between 1 and $MAX_PAGE" }
        require(limit in 1..MAX_PAGE_SIZE) {
            "limit must be between 1 and $MAX_PAGE_SIZE"
        }
        val common = buildMap {
            keyword?.let { put("keywords", it) }
            types?.let { put("types", it) }
            city?.let {
                put("region", it)
                put("city_limit", "true")
            }
            put("show_fields", POI_DETAIL_FIELDS)
            put("page_num", page.toString())
            put("page_size", limit.toString())
        }
        if (coordinates == null) {
            require(keyword != null || types != null) {
                "keyword or types is required without coordinates"
            }
            client.call(
                endpoint = AmapEndpoint.PLACE_TEXT,
                credentials = credentials,
                parameters = common,
            )
        } else {
            val radius = arguments.optionalInt("radius") ?: DEFAULT_RADIUS_METERS
            require(radius in 0..MAX_RADIUS_METERS) {
                "radius must be between 0 and $MAX_RADIUS_METERS"
            }
            val sort = arguments.optionalString("sort") ?: "distance"
            require(sort in POI_SORT_VALUES) {
                "sort must be distance or weight"
            }
            client.call(
                endpoint = AmapEndpoint.PLACE_AROUND,
                credentials = credentials,
                parameters = common + mapOf(
                    "location" to coordinates.asLngLat(),
                    "radius" to radius.toString(),
                    "sortrule" to sort,
                ),
            )
        }
    },
    AmapTool(
        name = "amap_get_poi",
        description =
            "Read Amap place or merchant details including available rating, " +
                "average cost, hours, phone, tags, and photos.",
        properties = buildJsonObject {
            put("poi_id", stringProperty("Amap POI ID from search results"))
        },
        required = listOf("poi_id"),
    ) { arguments ->
        client.call(
            endpoint = AmapEndpoint.PLACE_DETAIL,
            credentials = credentials,
            parameters = mapOf(
                "id" to arguments.requiredString("poi_id")
                    .requireLength("poi_id", MAX_POI_ID_CHARS),
                "show_fields" to POI_DETAIL_FIELDS,
            ),
        )
    },
    AmapTool(
        name = "amap_geocoding",
        description = "Convert a complete address to Amap GCJ-02 coordinates.",
        properties = buildJsonObject {
            put("address", stringProperty("Complete address"))
            put("city", stringProperty("Optional city or region"))
        },
        required = listOf("address"),
    ) { arguments ->
        client.call(
            endpoint = AmapEndpoint.GEOCODING,
            credentials = credentials,
            parameters = buildMap {
                put(
                    "address",
                    arguments.requiredString("address")
                        .requireLength("address", MAX_ADDRESS_CHARS),
                )
                arguments.boundedString("city", MAX_CITY_CHARS)
                    ?.let { put("city", it) }
            },
        )
    },
    AmapTool(
        name = "amap_reverse_geocoding",
        description = "Convert trusted GCJ-02 coordinates to an address.",
        properties = buildJsonObject {
            put("latitude", numberProperty("Trusted GCJ-02 latitude"))
            put("longitude", numberProperty("Trusted GCJ-02 longitude"))
            put("radius", integerProperty("Optional search radius in meters"))
        },
        required = listOf("latitude", "longitude"),
    ) { arguments ->
        val radius = arguments.optionalInt("radius") ?: DEFAULT_REGEOCODE_RADIUS
        require(radius in 0..MAX_REGEOCODE_RADIUS) {
            "radius must be between 0 and $MAX_REGEOCODE_RADIUS"
        }
        client.call(
            endpoint = AmapEndpoint.REVERSE_GEOCODING,
            credentials = credentials,
            parameters = mapOf(
                "location" to arguments.requiredCoordinates().asLngLat(),
                "radius" to radius.toString(),
                "extensions" to "all",
            ),
        )
    },
    AmapTool(
        name = "amap_direction",
        description =
            "Plan an Amap driving, walking, cycling, or transit route between " +
                "trusted GCJ-02 coordinates.",
        properties = buildJsonObject {
            put(
                "mode",
                enumProperty(
                    "Route mode",
                    "driving",
                    "walking",
                    "cycling",
                    "transit",
                ),
            )
            put("origin_latitude", numberProperty("Origin GCJ-02 latitude"))
            put("origin_longitude", numberProperty("Origin GCJ-02 longitude"))
            put(
                "destination_latitude",
                numberProperty("Destination GCJ-02 latitude"),
            )
            put(
                "destination_longitude",
                numberProperty("Destination GCJ-02 longitude"),
            )
            put("origin_city_code", stringProperty("Transit origin citycode"))
            put(
                "destination_city_code",
                stringProperty("Transit destination citycode"),
            )
        },
        required = listOf(
            "mode",
            "origin_latitude",
            "origin_longitude",
            "destination_latitude",
            "destination_longitude",
        ),
    ) { arguments ->
        val mode = arguments.requiredEnum<AmapRouteMode>("mode")
        val origin = arguments.requiredCoordinates("origin_")
        val destination = arguments.requiredCoordinates("destination_")
        val originCityCode = arguments.boundedString(
            "origin_city_code",
            MAX_CITY_CHARS,
        )
        val destinationCityCode = arguments.boundedString(
            "destination_city_code",
            MAX_CITY_CHARS,
        )
        if (mode == AmapRouteMode.TRANSIT) {
            require(originCityCode != null && destinationCityCode != null) {
                "origin_city_code and destination_city_code are required for transit"
            }
        }
        client.call(
            endpoint = mode.endpoint,
            credentials = credentials,
            parameters = buildMap {
                put("origin", origin.asLngLat())
                put("destination", destination.asLngLat())
                originCityCode?.let { put("city1", it) }
                destinationCityCode?.let { put("city2", it) }
            },
        )
    },
    AmapTool(
        name = "amap_weather",
        description = "Read Amap weather forecasts for an adcode.",
        properties = buildJsonObject {
            put("adcode", stringProperty("Administrative region code"))
        },
        required = listOf("adcode"),
    ) { arguments ->
        client.call(
            endpoint = AmapEndpoint.WEATHER,
            credentials = credentials,
            parameters = mapOf(
                "city" to arguments.requiredString("adcode")
                    .requireLength("adcode", MAX_CITY_CHARS),
                "extensions" to "all",
            ),
        )
    },
)

private class AmapTool(
    override val name: String,
    description: String,
    properties: JsonObject,
    required: List<String>,
    private val call: suspend (JsonObject) -> JsonObject,
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
    ): ToolResultEnvelope = try {
        ToolResultEnvelope.success(call(arguments))
    } catch (error: ToolInputException) {
        ToolResultEnvelope.error(
            ToolErrorCode.INVALID_ARGS,
            error.message ?: "Invalid Amap request",
        )
    } catch (error: IllegalArgumentException) {
        ToolResultEnvelope.error(
            ToolErrorCode.INVALID_ARGS,
            error.message ?: "Invalid Amap request",
        )
    } catch (error: AmapMapsException) {
        ToolResultEnvelope.error(
            ToolErrorCode.PROVIDER_ERROR,
            error.message ?: "Amap request failed",
        )
    }
}

internal enum class AmapEndpoint(vararg segments: String) {
    PLACE_TEXT("v5", "place", "text"),
    PLACE_AROUND("v5", "place", "around"),
    PLACE_DETAIL("v5", "place", "detail"),
    GEOCODING("v3", "geocode", "geo"),
    REVERSE_GEOCODING("v3", "geocode", "regeo"),
    DIRECTION_DRIVING("v5", "direction", "driving"),
    DIRECTION_WALKING("v5", "direction", "walking"),
    DIRECTION_CYCLING("v5", "direction", "bicycling"),
    DIRECTION_TRANSIT("v5", "direction", "transit", "integrated"),
    WEATHER("v3", "weather", "weatherInfo"),
    ;

    val pathSegments: List<String> = segments.toList()
}

private enum class AmapRouteMode(val endpoint: AmapEndpoint) {
    DRIVING(AmapEndpoint.DIRECTION_DRIVING),
    WALKING(AmapEndpoint.DIRECTION_WALKING),
    CYCLING(AmapEndpoint.DIRECTION_CYCLING),
    TRANSIT(AmapEndpoint.DIRECTION_TRANSIT),
}

private data class Coordinates(
    val latitude: Double,
    val longitude: Double,
) {
    fun asLngLat(): String =
        "${longitude.asCoordinate()},${latitude.asCoordinate()}"
}

private fun JsonObject.coordinates(prefix: String = ""): Coordinates? {
    val latitude = optionalDouble("${prefix}latitude")
    val longitude = optionalDouble("${prefix}longitude")
    require((latitude == null) == (longitude == null)) {
        "${prefix}latitude and ${prefix}longitude must be provided together"
    }
    return latitude?.let { Coordinates(it, longitude!!) }?.validated(prefix)
}

private fun JsonObject.requiredCoordinates(prefix: String = ""): Coordinates =
    coordinates(prefix)
        ?: throw ToolInputException(
            "${prefix}latitude and ${prefix}longitude are required",
        )

private fun Coordinates.validated(prefix: String): Coordinates {
    require(latitude in -90.0..90.0) {
        "${prefix}latitude must be between -90 and 90"
    }
    require(longitude in -180.0..180.0) {
        "${prefix}longitude must be between -180 and 180"
    }
    return this
}

private fun JsonObject.optionalDouble(name: String): Double? {
    val value = this[name] ?: return null
    return value.jsonPrimitive.doubleOrNull
        ?: throw ToolInputException("$name must be a number")
}

private fun JsonObject.boundedString(name: String, maxLength: Int): String? =
    optionalString(name)?.requireLength(name, maxLength)

private fun String.requireLength(name: String, maxLength: Int): String {
    require(length <= maxLength) { "$name is too long" }
    return this
}

private fun Double.asCoordinate(): String =
    String.format(Locale.US, "%.6f", this)
        .trimEnd('0')
        .trimEnd('.')

internal fun amapSign(
    parameters: Map<String, String>,
    securityKey: String,
): String {
    val canonical = parameters.toSortedMap()
        .entries
        .joinToString("&") { (name, value) -> "$name=$value" }
    return MessageDigest.getInstance("MD5")
        .digest("$canonical$securityKey".toByteArray())
        .joinToString(separator = "") { byte ->
            "%02x".format(byte.toInt() and 0xff)
        }
}

private fun JsonObject.string(name: String): String? =
    this[name]?.jsonPrimitive?.content

private fun stringProperty(description: String): JsonObject =
    buildJsonObject {
        put("type", "string")
        put("description", description)
    }

private fun numberProperty(description: String): JsonObject =
    buildJsonObject {
        put("type", "number")
        put("description", description)
    }

private fun integerProperty(description: String): JsonObject =
    buildJsonObject {
        put("type", "integer")
        put("description", description)
    }

private fun enumProperty(
    description: String,
    vararg values: String,
): JsonObject = buildJsonObject {
    put("type", "string")
    put("description", description)
    put(
        "enum",
        buildJsonArray {
            values.forEach { add(JsonPrimitive(it)) }
        },
    )
}

const val AMAP_CONSOLE_URL = "https://console.amap.com/"
private const val AMAP_WEB_SERVICE_BASE_URL = "https://restapi.amap.com/"
private const val POI_DETAIL_FIELDS = "business,photos"
private const val DEFAULT_RADIUS_METERS = 5_000
private const val MAX_RADIUS_METERS = 50_000
private const val DEFAULT_REGEOCODE_RADIUS = 1_000
private const val MAX_REGEOCODE_RADIUS = 3_000
private const val MAX_PAGE = 100
private const val MAX_PAGE_SIZE = 25
private const val MAX_KEYWORD_CHARS = 80
private const val MAX_CITY_CHARS = 64
private const val MAX_TYPES_CHARS = 256
private const val MAX_POI_ID_CHARS = 256
private const val MAX_ADDRESS_CHARS = 256
private const val TIMEOUT_SECONDS = 30L
private const val MAX_RESPONSE_BYTES = 512L * 1024L
private val POI_SORT_VALUES = setOf("distance", "weight")
