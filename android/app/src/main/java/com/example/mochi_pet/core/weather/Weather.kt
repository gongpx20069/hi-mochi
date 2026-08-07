package com.example.mochi_pet.core.weather

import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.round
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

data class GeoPoint(
    val latitude: Double,
    val longitude: Double,
)

data class CurrentWeather(
    val temperatureC: Double,
    val apparentTemperatureC: Double,
    val humidityPercent: Int,
    val weatherCode: Int,
    val observedAt: String,
    val timezone: String,
) {
    val condition: String
        get() = weatherCode.toCondition()
}

fun interface DeviceLocationProvider {
    suspend fun currentLocation(): GeoPoint
}

fun interface WeatherRepository {
    suspend fun currentWeather(): CurrentWeather
}

open class WeatherException(message: String, cause: Throwable? = null) :
    Exception(message, cause)

class LocationPermissionDeniedException :
    WeatherException("Location permission is required for local weather")

class OpenMeteoWeatherRepository(
    private val locationProvider: DeviceLocationProvider,
    private val client: OkHttpClient = OkHttpClient(),
    private val forecastUrl: String = FORECAST_URL,
    private val nowMillis: () -> Long = System::currentTimeMillis,
) : WeatherRepository {
    private val json = Json { ignoreUnknownKeys = true }
    private val mutex = Mutex()
    private var cached: CachedWeather? = null

    override suspend fun currentWeather(): CurrentWeather = mutex.withLock {
        val now = nowMillis()
        cached?.takeIf { now - it.loadedAtMillis < CACHE_MILLIS }
            ?.let { return@withLock it.weather }

        val point = locationProvider.currentLocation()
        val url = forecastUrl.toHttpUrl().newBuilder()
            .addQueryParameter(
                "latitude",
                point.latitude.weatherCoordinate().toString(),
            )
            .addQueryParameter(
                "longitude",
                point.longitude.weatherCoordinate().toString(),
            )
            .addQueryParameter(
                "current",
                "temperature_2m,relative_humidity_2m," +
                    "apparent_temperature,weather_code",
            )
            .addQueryParameter("timezone", "auto")
            .build()
        val body = get(url.toString())
        val response = try {
            json.decodeFromString(OpenMeteoResponse.serializer(), body)
        } catch (error: SerializationException) {
            throw WeatherException("Weather service returned invalid data", error)
        }
        val weather = CurrentWeather(
            temperatureC = response.current.temperature,
            apparentTemperatureC = response.current.apparentTemperature,
            humidityPercent = response.current.humidity,
            weatherCode = response.current.weatherCode,
            observedAt = response.current.time,
            timezone = response.timezone,
        )
        cached = CachedWeather(weather, now)
        weather
    }

    private suspend fun get(url: String): String =
        suspendCancellableCoroutine { continuation ->
            val call = client.newCall(
                Request.Builder()
                    .url(url)
                    .get()
                    .build(),
            )
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(
                object : Callback {
                    override fun onFailure(
                        call: Call,
                        e: IOException,
                    ) {
                        if (continuation.isActive) {
                            continuation.resumeWithException(
                                WeatherException(
                                    "Could not reach the weather service",
                                    e,
                                ),
                            )
                        }
                    }

                    override fun onResponse(
                        call: Call,
                        response: Response,
                    ) {
                        response.use {
                            if (!it.isSuccessful) {
                                continuation.resumeWithException(
                                    WeatherException(
                                        "Weather service returned HTTP ${it.code}",
                                    ),
                                )
                                return
                            }
                            val body = it.body.string()
                            if (body.length > MAX_RESPONSE_CHARS) {
                                continuation.resumeWithException(
                                    WeatherException(
                                        "Weather service response was too large",
                                    ),
                                )
                            } else {
                                continuation.resume(body)
                            }
                        }
                    }
                },
            )
        }

    private data class CachedWeather(
        val weather: CurrentWeather,
        val loadedAtMillis: Long,
    )

    private companion object {
        const val FORECAST_URL = "https://api.open-meteo.com/v1/forecast"
        const val MAX_RESPONSE_CHARS = 64_000
        val CACHE_MILLIS = TimeUnit.MINUTES.toMillis(10)
    }
}

@Serializable
private data class OpenMeteoResponse(
    val timezone: String,
    val current: OpenMeteoCurrent,
)

@Serializable
private data class OpenMeteoCurrent(
    val time: String,
    @SerialName("temperature_2m")
    val temperature: Double,
    @SerialName("relative_humidity_2m")
    val humidity: Int,
    @SerialName("apparent_temperature")
    val apparentTemperature: Double,
    @SerialName("weather_code")
    val weatherCode: Int,
)

private fun Int.toCondition(): String =
    when (this) {
        0 -> "Clear"
        1 -> "Mostly clear"
        2 -> "Partly cloudy"
        3 -> "Overcast"
        45, 48 -> "Foggy"
        51, 53, 55, 56, 57 -> "Drizzle"
        61, 63, 65, 66, 67 -> "Rain"
        71, 73, 75, 77 -> "Snow"
        80, 81, 82 -> "Rain showers"
        85, 86 -> "Snow showers"
        95, 96, 99 -> "Thunderstorm"
        else -> "Mixed conditions"
    }

private fun Double.weatherCoordinate(): Double = round(this * 100.0) / 100.0
