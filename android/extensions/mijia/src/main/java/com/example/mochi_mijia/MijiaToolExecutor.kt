package com.example.mochi_mijia

import com.example.mochi_extension.ExtensionRiskLevel
import com.example.mochi_extension.ExtensionToolDefinition
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

class MijiaToolExecutor(
    private val repository: MiotRepository,
    private val specificationClient: MiotSpecClient,
    private val cameraEventClient: CameraEventClient,
    private val sessionStore: MijiaSessionStore,
    private val passportQrClient: PassportQrClient,
) {
    suspend fun execute(
        toolName: String,
        arguments: JsonObject,
    ): MijiaToolExecution =
        when (toolName) {
            LIST_DEVICES -> MijiaToolExecution(listDevices())
            GET_DEVICE_STATE -> MijiaToolExecution(
                getDeviceState(
                    arguments.requiredArgumentString("device_id"),
                ),
            )
            CONTROL_DEVICE -> MijiaToolExecution(controlDevice(arguments))
            CONTROL_TELEVISION ->
                MijiaToolExecution(controlTelevision(arguments))
            CONFIGURE_CAMERA -> MijiaToolExecution(configureCamera(arguments))
            GET_CAMERA_EVENT_IMAGE -> latestCameraEvent(arguments)
            LIST_SCENES -> MijiaToolExecution(listScenes())
            RUN_SCENE -> MijiaToolExecution(runScene(arguments))
            else -> throw IllegalArgumentException("Unknown Mi Home Tool.")
        }

    private suspend fun latestCameraEvent(
        arguments: JsonObject,
    ): MijiaToolExecution {
        val device = repository.requireSelectedDevice(
            arguments.requiredArgumentString("device_id"),
        )
        require(device.category == MijiaDeviceCategory.CAMERA) {
            "The selected device is not a camera."
        }
        val session = sessionStore.load()
            ?: throw MijiaAuthorizationException("Connect Mi Home first.")
        val descriptor = try {
            cameraEventClient.latestEvent(session, device)
        } catch (error: MijiaAuthorizationExpiredException) {
            cameraEventClient.latestEvent(
                passportQrClient.refresh(session),
                device,
            )
        }
        return MijiaToolExecution(
            content = buildJsonObject {
                put("device_id", device.id)
                put("name", device.name)
                put("latest_event_image_available", true)
                put(
                    "message",
                    "The newest available camera event image is available " +
                        "as a validated host attachment.",
                )
            },
            attachments = listOf(descriptor),
        )
    }

    private suspend fun listDevices(): JsonObject {
        val devices = repository.selectedDevices()
        return buildJsonObject {
            put(
                "devices",
                buildJsonArray {
                    devices.forEach { device ->
                        add(
                            buildJsonObject {
                                put("device_id", device.id)
                                put("name", device.name)
                                put("category", device.category.wireName)
                                put("model", device.model)
                                put("online", device.online)
                                put("home", device.homeName)
                                device.roomName?.let { put("room", it) }
                                val capabilities = runCatching {
                                    capabilities(device)
                                }

                                capabilities.onSuccess { supported ->
                                    put(
                                        "operations",
                                        JsonArray(supported.operationNames.map(::JsonPrimitive)),
                                    )
                                    put("readable_state", JsonArray(supported.stateProperties.keys.map(::JsonPrimitive)))
                                    if (device.category == MijiaDeviceCategory.TELEVISION) {
                                        put("power_values", buildJsonArray {
                                            if ("power" in supported.writableProperties || "turn_off" in supported.actions) add(JsonPrimitive(false))
                                            if ("power" in supported.writableProperties || "turn_on" in supported.actions) add(JsonPrimitive(true))
                                        })
                                    }
                                }.onFailure { error ->
                                    put("operations", JsonArray(emptyList()))
                                    put(
                                        "capability_error",
                                        error.message
                                            ?: "MIoT specification unavailable",
                                    )
                                }
                            },
                        )
                    }
                },
            )
        }
    }

    private suspend fun getDeviceState(deviceId: String): JsonObject {
        val device = repository.requireSelectedDevice(deviceId)
        val capabilities = capabilities(device)
        if (capabilities.stateProperties.isEmpty()) {
            throw MijiaNotFoundException(
                "This device exposes no supported readable state.",
            )
        }
        val values = repository.getProperties(
            device,
            capabilities.stateProperties.values.map(MiotProperty::reference),
        )
        return buildJsonObject {
            put("device_id", device.id)
            put("name", device.name)
            put("category", device.category.wireName)
            put("online", device.online)
            put("home", device.homeName)
            device.roomName?.let { put("room", it) }
            put(
                "state",
                buildJsonObject {
                    capabilities.stateProperties.forEach { (name, property) ->
                        put(name, values[property.reference]?.value ?: JsonNull)
                    }
                },
            )
            put("state_errors", buildJsonObject {
                capabilities.stateProperties.forEach { (name, property) ->
                    val result = values.getValue(property.reference)
                    if (result.code != 0) put(name, result.code)
                }
            })
        }
    }

    private suspend fun controlDevice(arguments: JsonObject): JsonObject {
        val device = repository.requireSelectedDevice(
            arguments.requiredArgumentString("device_id"),
        )
        require(
            device.category !in setOf(
                MijiaDeviceCategory.SENSOR,
                MijiaDeviceCategory.SCALE,
                MijiaDeviceCategory.CAMERA,
                MijiaDeviceCategory.TELEVISION,
                MijiaDeviceCategory.UNKNOWN,
            ),
        ) {
            "Use a category-specific Tool for this device."
        }
        val operation = arguments.requiredArgumentString("operation")
        return controlAndVerify(device, operation, arguments["value"])
    }

    private suspend fun controlTelevision(arguments: JsonObject): JsonObject {
        val device = repository.requireSelectedDevice(
            arguments.requiredArgumentString("device_id"),
        )
        require(device.category == MijiaDeviceCategory.TELEVISION) {
            "The selected device is not a television."
        }
        val operation = arguments.requiredArgumentString("operation")
        return controlAndVerify(device, operation, arguments["value"])
    }

    private suspend fun configureCamera(arguments: JsonObject): JsonObject {
        require(arguments["confirmed"]?.jsonPrimitive?.booleanOrNull == true) {
            "Camera setting changes require confirmed=true."
        }
        val device = repository.requireSelectedDevice(
            arguments.requiredArgumentString("device_id"),
        )
        require(device.category == MijiaDeviceCategory.CAMERA) {
            "The selected device is not a camera."
        }
        val setting = arguments.requiredArgumentString("setting")
        return controlAndVerify(device, setting, arguments["value"])
    }

    private suspend fun controlAndVerify(
        device: MijiaDevice,
        operation: String,
        value: JsonElement?,
    ): JsonObject {
        val supported = capabilities(device)
        executeOperation(device, supported, operation, value)
        val property = supported.stateProperties[
            if (operation == "position") "current_position" else operation
        ]
        val verification = verifyDeviceControl(property, value, read = { reference ->
            repository.getProperties(device, listOf(reference)).getValue(reference)
        })
        return commandAccepted(device, operation, verification)
    }

    private suspend fun executeOperation(
        device: MijiaDevice,
        capabilities: SemanticCapabilities,
        operation: String,
        value: JsonElement?,
    ) {
        val property = capabilities.writableProperties[operation]
        if (property != null) {
            val requiredValue = value
                ?: throw IllegalArgumentException(
                    "Operation $operation requires a value.",
                )
            repository.setProperty(
                device,
                property.reference,
                property.validateValue(requiredValue),
            )
            return
        }
        if (device.category == MijiaDeviceCategory.TELEVISION && operation == "power") {
            val enabled = value?.jsonPrimitive?.booleanOrNull
                ?: throw IllegalArgumentException(
                    "Television power requires true or false.",
                )
            val action = capabilities.actions[
                if (enabled) "turn_on" else "turn_off"
            ] ?: throw MijiaNotFoundException(
                "This television does not expose cloud power control.",
            )
            repository.runAction(device, action.reference)
            return
        }
        val action = capabilities.actions[operation]
            ?: throw MijiaNotFoundException(
                "This device does not expose operation $operation.",
            )
        repository.runAction(device, action.reference)
    }

    private suspend fun listScenes(): JsonObject =
        buildJsonObject {
            put(
                "scenes",
                buildJsonArray {
                    repository.listScenes().forEach { scene ->
                        add(
                            buildJsonObject {
                                put("scene_id", scene.id)
                                put("name", scene.name)
                                put("home_id", scene.homeId)
                            },
                        )
                    }
                },
            )
        }

    private suspend fun runScene(arguments: JsonObject): JsonObject {
        require(arguments["confirmed"]?.jsonPrimitive?.booleanOrNull == true) {
            "Scene execution requires confirmed=true."
        }
        val sceneId = arguments.requiredArgumentString("scene_id")
        val scene = repository.listScenes().firstOrNull { it.id == sceneId }
            ?: throw MijiaNotFoundException(
                "The selected Mi Home scene was not found.",
            )
        repository.runScene(scene)
        return buildJsonObject {
            put("scene_id", scene.id)
            put("name", scene.name)
            put("command_accepted", true)
            put(
                "message",
                "Mi Home accepted the scene command; device completion " +
                    "was not independently verified.",
            )
        }
    }

    private suspend fun capabilities(
        device: MijiaDevice,
    ): SemanticCapabilities {
        val type = device.specificationType
            ?: throw MijiaNotFoundException(
                "This device has no public MIoT specification.",
            )
        return SemanticCapabilityReducer.reduce(
            device.category,
            specificationClient.get(type),
        )
    }

    private fun commandAccepted(
        device: MijiaDevice,
        operation: String,
        verification: DeviceControlVerification,
    ): JsonObject =
        buildJsonObject {
            put("device_id", device.id)
            put("name", device.name)
            put("operation", operation)
            put("command_accepted", true)
            put("verification", buildJsonObject {
                put("status", verification.status)
                put("reason", verification.reason)
                put("attempts", verification.attempts)
                verification.observed?.let { put("observed", it) }
                verification.errorCode?.let { put("error_code", it) }
            })
            put(
                "message",
                when (verification.status) {
                    "confirmed" -> "Mi Home accepted the command; a subsequent property read matches the requested value."
                    "not_confirmed" -> "Mi Home accepted the command, but subsequent state still differs. Do not claim completion or resend automatically."
                    else -> "Mi Home accepted the command, but state verification is unavailable. Explain the verification reason briefly; do not claim completion, promise a later check, or resend automatically."
                },
            )
        }

    companion object {
        const val LIST_DEVICES = "mijia_list_devices"
        const val GET_DEVICE_STATE = "mijia_get_device_state"
        const val CONTROL_DEVICE = "mijia_control_device"
        const val CONTROL_TELEVISION = "mijia_control_television"
        const val CONFIGURE_CAMERA = "mijia_configure_camera"
        const val GET_CAMERA_EVENT_IMAGE =
            "mijia_get_latest_camera_event_image"
        const val LIST_SCENES = "mijia_list_scenes"
        const val RUN_SCENE = "mijia_run_scene"

        val DEFINITIONS = listOf(
            definition(
                LIST_DEVICES,
                "List user-selected supported Mi Home devices and operations.",
                ExtensionRiskLevel.READ,
                """{"type":"object","properties":{},"additionalProperties":false}""",
            ),
            definition(
                GET_DEVICE_STATE,
                "Read supported state from one selected Mi Home device.",
                ExtensionRiskLevel.READ,
                deviceSchema(),
            ),
            definition(
                CONTROL_DEVICE,
                "Control one selected light, switch, plug, fan, climate, air, or curtain device.",
                ExtensionRiskLevel.WRITE,
                """
                {
                  "type":"object",
                  "properties":{
                    "device_id":{"type":"string"},
                    "operation":{
                      "type":"string",
                      "enum":[
                        "power","brightness","color_temperature","mode",
                        "fan_level","target_temperature","target_humidity",
                        "position","open","close","stop"
                      ]
                    },
                    "value":{}
                  },
                  "required":["device_id","operation"],
                  "additionalProperties":false
                }
                """.trimIndent(),
            ),
            definition(
                CONTROL_TELEVISION,
                "Control one selected television using only declared MIoT capabilities.",
                ExtensionRiskLevel.WRITE,
                """
                {
                  "type":"object",
                  "properties":{
                    "device_id":{"type":"string"},
                    "operation":{
                      "type":"string",
                      "enum":[
                        "power","input","volume","mute","home","menu",
                        "settings","back","up","down","left","right",
                        "ok","enter","play","pause"
                      ]
                    },
                    "value":{}
                  },
                  "required":["device_id","operation"],
                  "additionalProperties":false
                }
                """.trimIndent(),
            ),
            definition(
                CONFIGURE_CAMERA,
                "Change one explicitly confirmed supported camera setting.",
                ExtensionRiskLevel.SENSITIVE,
                """
                {
                  "type":"object",
                  "properties":{
                    "device_id":{"type":"string"},
                    "setting":{
                      "type":"string",
                      "enum":[
                        "power","indicator_light","night_shot",
                        "image_rollover","wdr_mode","motion_detection",
                        "motion_tracking","recording_mode"
                      ]
                    },
                    "value":{},
                    "confirmed":{"type":"boolean","const":true}
                  },
                  "required":["device_id","setting","value","confirmed"],
                  "additionalProperties":false
                }
                """.trimIndent(),
            ),
            definition(
                GET_CAMERA_EVENT_IMAGE,
                "Retrieve the newest available motion or doorbell event image from one selected camera.",
                ExtensionRiskLevel.SENSITIVE,
                deviceSchema(),
            ),
            definition(
                LIST_SCENES,
                "List enabled manually triggered scenes from selected homes.",
                ExtensionRiskLevel.READ,
                """{"type":"object","properties":{},"additionalProperties":false}""",
            ),
            definition(
                RUN_SCENE,
                "Run one exact Mi Home scene after explicit confirmation.",
                ExtensionRiskLevel.SENSITIVE,
                """
                {
                  "type":"object",
                  "properties":{
                    "scene_id":{"type":"string"},
                    "confirmed":{"type":"boolean","const":true}
                  },
                  "required":["scene_id","confirmed"],
                  "additionalProperties":false
                }
                """.trimIndent(),
            ),
        )

        private fun definition(
            name: String,
            description: String,
            risk: String,
            schema: String,
        ) = ExtensionToolDefinition(
            name = name,
            description = description,
            inputSchemaJson = schema,
            riskLevel = risk,
            defaultEnabled = true,
        )

        private fun deviceSchema() =
            """
            {
              "type":"object",
              "properties":{"device_id":{"type":"string"}},
              "required":["device_id"],
              "additionalProperties":false
            }
            """.trimIndent()
    }
}

data class MijiaToolExecution(
    val content: JsonObject,
    val attachments: List<
        com.example.mochi_extension.ExtensionAttachmentDescriptor
        > = emptyList(),
)

private fun JsonObject.requiredArgumentString(name: String): String =
    this[name]?.jsonPrimitive?.content
        ?.takeIf(String::isNotBlank)
        ?: throw IllegalArgumentException("$name is required.")
