package com.example.mochi_mijia

import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request

data class MiotSpecification(
    val type: String,
    val properties: List<MiotProperty>,
    val actions: List<MiotAction>,
)

data class MiotProperty(
    val serviceId: Int,
    val propertyId: Int,
    val name: String,
    val format: String,
    val readable: Boolean,
    val writable: Boolean,
    val range: List<Double>?,
    val allowedValues: Set<String>,
) {
    val reference = MiotPropertyReference(serviceId, propertyId)

    fun validateValue(value: JsonElement): JsonElement {
        val primitive = value as? JsonPrimitive
            ?: throw IllegalArgumentException("Property value must be scalar.")
        when (format) {
            "bool" -> require(primitive.booleanOrNull != null) {
                "Property value must be true or false."
            }
            "float", "int8", "int16", "int32", "int64",
            "uint8", "uint16", "uint32", "uint64",
            -> {
                val number = primitive.doubleOrNull
                    ?: throw IllegalArgumentException(
                        "Property value must be numeric.",
                    )
                range?.let { values ->
                    require(values.size >= 2 && number in values[0]..values[1]) {
                        "Property value is outside the device range."
                    }
                    if (values.size >= 3 && values[2] > 0) {
                        val steps = (number - values[0]) / values[2]
                        require(kotlin.math.abs(steps - steps.toLong()) < 0.000001) {
                            "Property value does not match the device step."
                        }
                    }
                }
            }
            "string" -> require(primitive.isString) {
                "Property value must be text."
            }
        }
        if (allowedValues.isNotEmpty()) {
            require(primitive.content in allowedValues) {
                "Property value is not supported by this device."
            }
        }
        return value
    }
}

data class MiotAction(
    val serviceId: Int,
    val actionId: Int,
    val name: String,
    val inputPropertyIds: List<Int>,
) {
    val reference = MiotActionReference(serviceId, actionId)
}

data class SemanticCapabilities(
    val stateProperties: Map<String, MiotProperty>,
    val writableProperties: Map<String, MiotProperty>,
    val actions: Map<String, MiotAction>,
) {
    val operationNames: List<String>
        get() = (writableProperties.keys + actions.keys).distinct().sorted()
}

class MiotSpecClient(
    client: OkHttpClient = OkHttpClient(),
) {
    private val client = client.newBuilder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()
    private val json = Json { ignoreUnknownKeys = true }
    private val cache = ConcurrentHashMap<String, MiotSpecification>()

    suspend fun get(type: String): MiotSpecification =
        cache[type] ?: withContext(Dispatchers.IO) {
            cache[type] ?: fetch(type).also { cache[type] = it }
        }

    private suspend fun fetch(type: String): MiotSpecification {
        val url = "https://miot-spec.org/miot-spec-v2/instance".toHttpUrl()
            .newBuilder()
            .addQueryParameter("type", type)
            .build()
        val response = try {
            client.newCall(
                Request.Builder().url(url).get().build(),
            ).awaitResponse()
        } catch (error: IOException) {
            throw MijiaProviderException(
                "MIoT specification request failed.",
                error,
            )
        }
        response.use {
            if (!it.isSuccessful) {
                throw MijiaProviderException(
                    "MIoT specification request failed with HTTP ${it.code}.",
                )
            }
            val root = try {
                json.parseToJsonElement(it.body.string()).jsonObject
            } catch (error: IllegalArgumentException) {
                throw MijiaProviderException(
                    "MIoT specification response is invalid.",
                    error,
                )
            }
            val instance = root["type"]?.let { root }
                ?: root["result"]?.jsonObject
                ?: root
            return parseSpecification(type, instance)
        }
    }

    private fun parseSpecification(
        type: String,
        root: JsonObject,
    ): MiotSpecification {
        val properties = mutableListOf<MiotProperty>()
        val actions = mutableListOf<MiotAction>()
        root.arrayOrEmpty("services").forEach { serviceValue ->
            val service = serviceValue.jsonObject
            val serviceId = service["iid"]?.jsonPrimitive?.intOrNull
                ?: return@forEach
            service.arrayOrEmpty("properties").forEach propertyLoop@{ value ->
                val property = value.jsonObject
                val propertyId = property["iid"]?.jsonPrimitive?.intOrNull
                    ?: return@propertyLoop
                val access = property.arrayOrEmpty("access")
                    .mapNotNull { it.jsonPrimitive.contentOrNull }
                    .toSet()
                properties += MiotProperty(
                    serviceId = serviceId,
                    propertyId = propertyId,
                    name = urnName(
                        property["type"]?.jsonPrimitive?.contentOrNull,
                    ),
                    format = property["format"]?.jsonPrimitive?.contentOrNull
                        .orEmpty(),
                    readable = "read" in access,
                    writable = "write" in access,
                    range = property["value-range"]?.jsonArray
                        ?.mapNotNull { it.jsonPrimitive.doubleOrNull },
                    allowedValues = property["value-list"]?.jsonArray
                        ?.mapNotNull { enumValue ->
                            enumValue.jsonObject["value"]
                                ?.jsonPrimitive?.contentOrNull
                        }?.toSet().orEmpty(),
                )
            }
            service.arrayOrEmpty("actions").forEach actionLoop@{ value ->
                val action = value.jsonObject
                val actionId = action["iid"]?.jsonPrimitive?.intOrNull
                    ?: return@actionLoop
                actions += MiotAction(
                    serviceId = serviceId,
                    actionId = actionId,
                    name = urnName(
                        action["type"]?.jsonPrimitive?.contentOrNull,
                    ),
                    inputPropertyIds = action.arrayOrEmpty("in")
                        .mapNotNull { it.jsonPrimitive.intOrNull },
                )
            }
        }
        return MiotSpecification(type, properties, actions)
    }
}

object SemanticCapabilityReducer {
    fun reduce(
        category: MijiaDeviceCategory,
        specification: MiotSpecification,
    ): SemanticCapabilities {
        val readableNames = when (category) {
            MijiaDeviceCategory.SCALE ->
                setOf("battery-level", "battery", "fault")
            MijiaDeviceCategory.SENSOR -> SENSOR_STATE_NAMES
            MijiaDeviceCategory.CAMERA -> CAMERA_STATE_NAMES
            else -> COMMON_STATE_NAMES
        }
        val writableNames = when (category) {
            MijiaDeviceCategory.LIGHT ->
                setOf("on", "brightness", "color-temperature")
            MijiaDeviceCategory.SWITCH,
            MijiaDeviceCategory.PLUG,
            -> setOf("on")
            MijiaDeviceCategory.FAN ->
                setOf("on", "mode", "fan-level")
            MijiaDeviceCategory.AIR_CONDITIONER ->
                setOf("on", "mode", "target-temperature", "fan-level")
            MijiaDeviceCategory.AIR_PURIFIER ->
                setOf("on", "mode", "fan-level")
            MijiaDeviceCategory.HUMIDIFIER ->
                setOf("on", "mode", "target-humidity", "fan-level")
            MijiaDeviceCategory.CURTAIN -> setOf("target-position")
            MijiaDeviceCategory.TELEVISION ->
                setOf("on", "volume", "mute", "input-control")
            MijiaDeviceCategory.CAMERA -> CAMERA_WRITABLE_NAMES
            else -> emptySet()
        }
        val actionNames = when (category) {
            MijiaDeviceCategory.CURTAIN ->
                setOf("open", "close", "pause", "stop")
            MijiaDeviceCategory.TELEVISION -> TV_ACTION_NAMES
            else -> emptySet()
        }
        val state = specification.properties
            .filter { it.readable && (it.name in readableNames || it.name in writableNames) }
            .associateBy { semanticOperation(it.name) }
        val writable = specification.properties
            .filter { it.writable && it.name in writableNames }
            .associateBy { semanticOperation(it.name) }
        val actions = specification.actions
            .filter { it.name in actionNames && it.inputPropertyIds.isEmpty() }
            .associateBy { semanticOperation(it.name) }
        return SemanticCapabilities(state, writable, actions)
    }

    private fun semanticOperation(name: String): String =
        when (name) {
            "on" -> "power"
            "color-temperature" -> "color_temperature"
            "fan-level" -> "fan_level"
            "target-temperature" -> "target_temperature"
            "target-humidity" -> "target_humidity"
            "target-position" -> "position"
            "input-control" -> "input"
            else -> name.replace('-', '_')
        }

    private val COMMON_STATE_NAMES = setOf(
        "on",
        "mode",
        "brightness",
        "color-temperature",
        "fan-level",
        "target-temperature",
        "temperature",
        "relative-humidity",
        "target-humidity",
        "pm2.5-density",
        "air-quality",
        "target-position",
        "current-position",
        "battery-level",
        "fault",
        "volume",
        "mute",
        "input-control",
    )
    private val SENSOR_STATE_NAMES = setOf(
        "temperature",
        "relative-humidity",
        "pm2.5-density",
        "air-quality",
        "contact-state",
        "motion-state",
        "battery-level",
        "battery",
    )
    private val CAMERA_STATE_NAMES = setOf(
        "on",
        "status",
        "indicator-light",
        "night-shot",
        "image-rollover",
        "wdr-mode",
        "motion-detection",
        "motion-tracking",
        "recording-mode",
        "battery-level",
    )
    private val CAMERA_WRITABLE_NAMES = setOf(
        "on",
        "indicator-light",
        "night-shot",
        "image-rollover",
        "wdr-mode",
        "motion-detection",
        "motion-tracking",
        "recording-mode",
    )
    private val TV_ACTION_NAMES = setOf(
        "turn-on",
        "turn-off",
        "home",
        "menu",
        "settings",
        "back",
        "up",
        "down",
        "left",
        "right",
        "ok",
        "enter",
        "play",
        "pause",
    )
}

private fun urnName(value: String?): String {
    val parts = value.orEmpty().split(':')
    return parts.getOrNull(3).orEmpty()
}

private fun JsonObject.arrayOrEmpty(name: String): JsonArray =
    this[name] as? JsonArray ?: JsonArray(emptyList())
