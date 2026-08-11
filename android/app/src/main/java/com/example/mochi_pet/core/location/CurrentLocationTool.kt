package com.example.mochi_pet.core.location

import com.example.mochi_pet.core.agent.tool.AgentTool
import com.example.mochi_pet.core.agent.tool.ToolErrorCode
import com.example.mochi_pet.core.agent.tool.ToolExecutionContext
import com.example.mochi_pet.core.agent.tool.ToolResultEnvelope
import com.example.mochi_pet.core.agent.tool.functionToolSchema
import java.time.Clock
import java.time.Instant
import kotlin.math.pow
import kotlin.math.roundToLong
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

class CurrentLocationTool(
    private val locationProvider: DeviceLocationProvider,
    private val clock: Clock = Clock.systemUTC(),
) : AgentTool {
    override val name: String = "get_current_location"

    override val schema: JsonObject = functionToolSchema(
        name = name,
        description =
            "Get the device's current permission-gated location. Use only " +
                "when the user asks for their current position or clearly " +
                "requests a location-dependent action such as nearby search " +
                "or routing.",
        properties = buildJsonObject {},
        required = emptyList(),
    )

    override suspend fun execute(
        arguments: JsonObject,
        context: ToolExecutionContext,
    ): ToolResultEnvelope =
        try {
            val location = locationProvider.currentLocation()
            val gcj02 = ChinaCoordinateConverter.wgs84ToGcj02(
                latitude = location.latitude,
                longitude = location.longitude,
            )
            ToolResultEnvelope.success(
                buildJsonObject {
                    put(
                        "wgs84",
                        coordinateJson(
                            latitude = location.latitude,
                            longitude = location.longitude,
                            coordinateSystem = "WGS-84",
                        ),
                    )
                    put(
                        "gcj02",
                        gcj02?.let { coordinate ->
                            coordinateJson(
                                latitude = coordinate.latitude,
                                longitude = coordinate.longitude,
                                coordinateSystem = "GCJ-02",
                            )
                        } ?: JsonNull,
                    )
                    location.accuracyMeters?.let {
                        put("accuracy_m", it.roundTo(1))
                    }
                    location.capturedAtEpochMillis?.let { capturedAt ->
                        put(
                            "captured_at",
                            Instant.ofEpochMilli(capturedAt).toString(),
                        )
                        put(
                            "age_seconds",
                            (
                                clock.millis() - capturedAt
                            ).coerceAtLeast(0) / 1_000,
                        )
                    }
                    location.provider?.let { put("provider", it) }
                },
            )
        } catch (error: DeviceLocationException) {
            error.toToolResultEnvelope()
        }

    private fun coordinateJson(
        latitude: Double,
        longitude: Double,
        coordinateSystem: String,
    ): JsonObject =
        buildJsonObject {
            put("latitude", latitude.roundTo(COORDINATE_DECIMALS))
            put("longitude", longitude.roundTo(COORDINATE_DECIMALS))
            put("coordinate_system", coordinateSystem)
        }

    private fun Double.roundTo(decimals: Int): Double {
        val factor = 10.0.pow(decimals)
        return (this * factor).roundToLong() / factor
    }

    private companion object {
        const val COORDINATE_DECIMALS = 6
    }
}

internal fun DeviceLocationException.toToolResultEnvelope(): ToolResultEnvelope =
    when (this) {
        is LocationPermissionDeniedException ->
            ToolResultEnvelope.error(
                ToolErrorCode.PERMISSION_DENIED,
                message ?: "Location permission was denied",
            )
        is LocationRequestTimeoutException ->
            ToolResultEnvelope.error(
                ToolErrorCode.TIMEOUT,
                message ?: "Current location request timed out",
            )
        else ->
            ToolResultEnvelope.error(
                ToolErrorCode.PROVIDER_ERROR,
                message ?: "Current location is unavailable",
            )
    }
