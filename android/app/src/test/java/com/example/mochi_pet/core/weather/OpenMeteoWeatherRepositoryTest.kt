package com.example.mochi_pet.core.weather

import com.example.mochi_pet.core.location.DeviceLocation
import com.example.mochi_pet.core.location.DeviceLocationProvider
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Test

class OpenMeteoWeatherRepositoryTest {
    @Test
    fun `loads current conditions using reduced precision coordinates`() =
        runBlocking {
            val server = MockWebServer()
            server.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .setBody(
                        """
                        {
                          "timezone": "Asia/Shanghai",
                          "current": {
                            "time": "2026-08-02T00:45",
                            "temperature_2m": 29.4,
                            "relative_humidity_2m": 74,
                            "apparent_temperature": 33.1,
                            "weather_code": 2
                          }
                        }
                        """.trimIndent(),
                    ),
            )
            server.start()
            try {
                val repository = OpenMeteoWeatherRepository(
                    locationProvider = DeviceLocationProvider {
                        DeviceLocation(31.230416, 121.473701)
                    },
                    forecastUrl = server.url("/v1/forecast").toString(),
                )

                val weather = repository.currentWeather()

                assertEquals(29.4, weather.temperatureC, 0.0)
                assertEquals(33.1, weather.apparentTemperatureC, 0.0)
                assertEquals(74, weather.humidityPercent)
                assertEquals("Partly cloudy", weather.condition)
                val request = server.takeRequest()
                assertEquals("31.23", request.requestUrl?.queryParameter("latitude"))
                assertEquals("121.47", request.requestUrl?.queryParameter("longitude"))
                assertEquals(
                    "auto",
                    request.requestUrl?.queryParameter("timezone"),
                )
            } finally {
                server.close()
            }
        }
}
