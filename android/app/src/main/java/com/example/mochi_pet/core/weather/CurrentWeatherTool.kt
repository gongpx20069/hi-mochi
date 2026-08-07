package com.example.mochi_pet.core.weather

import com.example.mochi_pet.core.agent.tool.AgentTool
import com.example.mochi_pet.core.agent.tool.ToolErrorCode
import com.example.mochi_pet.core.agent.tool.ToolExecutionContext
import com.example.mochi_pet.core.agent.tool.ToolResultEnvelope
import com.example.mochi_pet.core.agent.tool.functionToolSchema
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

class CurrentWeatherTool(
    private val repository: WeatherRepository,
    private val onLoaded: (CurrentWeather) -> Unit = {},
) : AgentTool {
    override val name: String = "get_current_weather"

    override val schema: JsonObject = functionToolSchema(
        name = name,
        description =
            "Get current weather for the device location, including " +
                "temperature, apparent temperature, and humidity.",
        properties = buildJsonObject {},
        required = emptyList(),
    )

    override suspend fun execute(
        arguments: JsonObject,
        context: ToolExecutionContext,
    ): ToolResultEnvelope =
        try {
            val weather = repository.currentWeather()
            onLoaded(weather)
            ToolResultEnvelope.success(
                buildJsonObject {
                    put("condition", weather.condition)
                    put("temperature_c", weather.temperatureC)
                    put(
                        "apparent_temperature_c",
                        weather.apparentTemperatureC,
                    )
                    put("humidity_percent", weather.humidityPercent)
                    put("observed_at", weather.observedAt)
                    put("timezone", weather.timezone)
                },
            )
        } catch (error: LocationPermissionDeniedException) {
            ToolResultEnvelope.error(
                ToolErrorCode.PERMISSION_DENIED,
                error.message ?: "Location permission was denied",
            )
        } catch (error: WeatherException) {
            ToolResultEnvelope.error(
                ToolErrorCode.PROVIDER_ERROR,
                error.message ?: "Weather service failed",
            )
        }
}
