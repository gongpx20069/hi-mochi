package com.example.mochi_mijia

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

class MiotRepository(
    private val sessionStore: MijiaSessionStore,
    private val cloudClient: MiotCloudClient,
    private val passportQrClient: PassportQrClient,
) {
    suspend fun ensureRegion(): MijiaSession {
        val session = requireSession()
        if (!session.region.isNullOrBlank()) return session
        for (region in REGIONS) {
            val candidate = session.copy(region = region)
            val homes = fetchHomes(candidate)
            if (homes.isNotEmpty()) {
                sessionStore.save(candidate)
                return candidate
            }
        }
        throw MijiaProviderException(
            "No Mi Home region with homes was found. Retry after adding a home.",
        )
    }

    suspend fun homesAndDevices(): Pair<List<MijiaHome>, List<MijiaDevice>> {
        val session = ensureRegion()
        val homes = fetchHomes(session)
        val devices = homes.flatMap { home ->
            fetchDevices(session, home)
        }.distinctBy(MijiaDevice::id)
        return homes to devices
    }

    suspend fun selectedDevices(): List<MijiaDevice> {
        val session = ensureRegion()
        val selected = session.selectedDeviceIds
        if (selected.isEmpty()) return emptyList()
        return homesAndDevices().second.filter { it.id in selected }
    }

    suspend fun saveSelectedDevices(deviceIds: Set<String>) {
        val supportedIds = homesAndDevices().second
            .filter { it.category in SUPPORTED_MIJIA_CATEGORIES }
            .mapTo(mutableSetOf(), MijiaDevice::id)
        require(deviceIds.all { it in supportedIds }) {
            "Selection contains an unsupported Mi Home device."
        }
        val selectedDevices = homesAndDevices().second.filter {
            it.id in deviceIds
        }
        sessionStore.update {
            it.copy(
                selectedHomeIds = selectedDevices.mapTo(mutableSetOf()) {
                    device -> device.homeId
                },
                selectedDeviceIds = deviceIds,
            )
        }
    }

    suspend fun requireSelectedDevice(deviceId: String): MijiaDevice =
        selectedDevices().firstOrNull { it.id == deviceId }
            ?: throw MijiaNotFoundException(
                "The selected Mi Home device was not found.",
            )

    suspend fun getProperties(
        device: MijiaDevice,
        properties: List<MiotPropertyReference>,
    ): Map<MiotPropertyReference, JsonElement?> {
        if (properties.isEmpty()) return emptyMap()
        val session = ensureRegion()
        val response = post(
            session = session,
            path = "miotspec/prop/get",
            data = buildJsonObject {
                put(
                    "params",
                    JsonArray(
                        properties.map { property ->
                            buildJsonObject {
                                put("did", device.id)
                                put("siid", property.serviceId)
                                put("piid", property.propertyId)
                            }
                        },
                    ),
                )
            },
        )
        val results = response.resultArray()
        return properties.mapIndexed { index, property ->
            val item = results.getOrNull(index)?.jsonObject
                ?: throw MijiaProviderException(
                    "Mi Home omitted a property result.",
                )
            val code = item["code"]?.jsonPrimitive?.content?.toIntOrNull() ?: -1
            property to if (code == 0) item["value"] else null
        }.toMap()
    }

    suspend fun setProperty(
        device: MijiaDevice,
        property: MiotPropertyReference,
        value: JsonElement,
    ) {
        val response = post(
            session = ensureRegion(),
            path = "miotspec/prop/set",
            data = buildJsonObject {
                put(
                    "params",
                    JsonArray(
                        listOf(
                            buildJsonObject {
                                put("did", device.id)
                                put("siid", property.serviceId)
                                put("piid", property.propertyId)
                                put("value", value)
                            },
                        ),
                    ),
                )
            },
        )
        requireAcceptedItem(response)
    }

    suspend fun runAction(
        device: MijiaDevice,
        action: MiotActionReference,
        input: List<JsonElement> = emptyList(),
    ) {
        val response = post(
            session = ensureRegion(),
            path = "miotspec/action",
            data = buildJsonObject {
                put(
                    "params",
                    buildJsonObject {
                        put("did", device.id)
                        put("siid", action.serviceId)
                        put("aiid", action.actionId)
                        put("in", JsonArray(input))
                    },
                )
            },
        )
        val code = response["result"]?.jsonObject
            ?.get("code")?.jsonPrimitive?.content?.toIntOrNull()
            ?: response["code"]?.jsonPrimitive?.content?.toIntOrNull()
            ?: -1
        if (code != 0 && code != 1) {
            throw MijiaProviderException(
                "Mi Home rejected the device action ($code).",
            )
        }
    }

    suspend fun listScenes(): List<MijiaScene> {
        val session = ensureRegion()
        return fetchHomes(session)
            .filter { it.id in session.selectedHomeIds }
            .flatMap { home ->
                val response = post(
                    session = session,
                    path =
                        "appgateway/miot/appsceneservice/" +
                            "AppSceneService/GetSceneList",
                    data = buildJsonObject {
                        put("home_id", home.id)
                    },
                )
                val scenes = response["result"]?.jsonObject
                    ?.arrayOrEmpty("scene_info_list")
                    .orEmpty()
                scenes.mapNotNull { value ->
                    val item = value.jsonObject
                    val enabled = item.booleanValue("enable")
                        ?: item.booleanValue("enabled")
                        ?: true
                    val manual = item.arrayOrEmpty("triggers").any { trigger ->
                        trigger.jsonObject.stringValue("src") == "user"
                    } || item.stringValue("trigger_type") == "user"
                    if (!enabled || !manual) return@mapNotNull null
                    val id = item.stringValue("scene_id")
                        ?: item.stringValue("id")
                        ?: return@mapNotNull null
                    MijiaScene(
                        id = id,
                        name = item.stringValue("name") ?: "Mi Home scene",
                        homeId = home.id,
                        ownerId = home.ownerId,
                    )
                }
            }.distinctBy(MijiaScene::id)
    }

    suspend fun runScene(scene: MijiaScene) {
        val session = ensureRegion()
        require(scene.homeId in session.selectedHomeIds) {
            "The selected Mi Home scene is outside the selected homes."
        }
        val response = post(
            session = session,
            path =
                "appgateway/miot/appsceneservice/" +
                    "AppSceneService/NewRunScene",
            data = buildJsonObject {
                put("scene_id", scene.id)
                put("scene_type", 2)
                put("trigger_key", "user.click")
                put("home_id", scene.homeId)
                put("owner_uid", scene.ownerId)
                put("phone_id", "null")
            },
        )
        val code = response["code"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0
        if (code != 0) {
            throw MijiaProviderException(
                "Mi Home rejected the scene command ($code).",
            )
        }
    }

    private suspend fun fetchHomes(session: MijiaSession): List<MijiaHome> {
        val response = post(
            session = session,
            path = "v2/homeroom/gethome",
            data = buildJsonObject {
                put("app_ver", 7)
                put("fetch_share", true)
                put("fetch_share_dev", true)
                put("fg", false)
                put("limit", 300)
            },
        )
        val homeList = response["result"]?.jsonObject
            ?.arrayOrEmpty("homelist")
            .orEmpty()
        return homeList.mapNotNull { element ->
            val item = element.jsonObject
            val id = item.stringValue("id") ?: return@mapNotNull null
            val ownerId = item.stringValue("uid")
                ?: item.stringValue("home_owner")
                ?: session.userId
            MijiaHome(
                id = id,
                ownerId = ownerId,
                name = item.stringValue("name") ?: "Mi Home",
                rooms = item.arrayOrEmpty("roomlist").mapNotNull { roomValue ->
                    val room = roomValue.jsonObject
                    val roomId = room.stringValue("id")
                        ?: return@mapNotNull null
                    MijiaRoom(
                        id = roomId,
                        name = room.stringValue("name") ?: "Room",
                        deviceIds = room.arrayOrEmpty("dids")
                            .mapNotNull { it.jsonPrimitive.contentOrNull }
                            .toSet(),
                    )
                },
            )
        }
    }

    private suspend fun fetchDevices(
        session: MijiaSession,
        home: MijiaHome,
    ): List<MijiaDevice> {
        val response = post(
            session = session,
            path = "v2/home/home_device_list",
            data = buildJsonObject {
                put("home_owner", home.ownerId)
                put("home_id", home.id)
                put("limit", 200)
            },
        )
        val result = response["result"]?.jsonObject ?: return emptyList()
        val values = result["device_info"].asObjectValuesOrArray()
        return values.mapNotNull { element ->
            val item = element.jsonObject
            val id = item.stringValue("did") ?: return@mapNotNull null
            val room = home.rooms.firstOrNull { id in it.deviceIds }
            val model = item.stringValue("model").orEmpty()
            val specification = item.stringValue("spec_type")
                ?: item.stringValue("specType")
                ?: item.stringValue("urn")
            MijiaDevice(
                id = id,
                name = item.stringValue("name") ?: model.ifBlank { "Device" },
                model = model,
                specificationType = specification,
                online = item.booleanValue("isOnline")
                    ?: item.booleanValue("online")
                    ?: false,
                homeId = home.id,
                homeName = home.name,
                roomId = room?.id,
                roomName = room?.name,
                category = classifyMijiaDevice(specification, model),
            )
        }
    }

    private fun requireAcceptedItem(response: JsonObject) {
        val code = response.resultArray().firstOrNull()?.jsonObject
            ?.get("code")?.jsonPrimitive?.content?.toIntOrNull() ?: -1
        if (code != 0 && code != 1) {
            throw MijiaProviderException(
                "Mi Home rejected the property update ($code).",
            )
        }
    }

    private suspend fun requireSession(): MijiaSession =
        sessionStore.load()
            ?: throw MijiaAuthorizationException("Connect Mi Home first.")

    private suspend fun post(
        session: MijiaSession,
        path: String,
        data: JsonObject,
    ): JsonObject =
        try {
            cloudClient.post(session, path, data)
        } catch (error: MijiaAuthorizationExpiredException) {
            cloudClient.post(
                passportQrClient.refresh(session),
                path,
                data,
            )
        }

    private companion object {
        val REGIONS = listOf("cn", "de", "sg", "us", "ru", "i2", "tw")
    }
}

data class MiotPropertyReference(
    val serviceId: Int,
    val propertyId: Int,
)

data class MiotActionReference(
    val serviceId: Int,
    val actionId: Int,
)

data class MijiaScene(
    val id: String,
    val name: String,
    val homeId: String,
    val ownerId: String,
)

private fun JsonObject.resultArray(): JsonArray =
    this["result"]?.let { result ->
        when (result) {
            is JsonArray -> result
            is JsonObject -> result["result"]?.jsonArray ?: JsonArray(emptyList())
            else -> JsonArray(emptyList())
        }
    } ?: JsonArray(emptyList())

private fun JsonObject.arrayOrEmpty(name: String): JsonArray =
    this[name] as? JsonArray ?: JsonArray(emptyList())

private fun JsonElement?.asObjectValuesOrArray(): List<JsonElement> =
    when (this) {
        is JsonArray -> this
        is JsonObject -> values.toList()
        else -> emptyList()
    }

private fun JsonObject.stringValue(name: String): String? =
    this[name]?.jsonPrimitive?.contentOrNull?.takeIf { it != "null" }

private fun JsonObject.booleanValue(name: String): Boolean? =
    this[name]?.jsonPrimitive?.booleanOrNull
