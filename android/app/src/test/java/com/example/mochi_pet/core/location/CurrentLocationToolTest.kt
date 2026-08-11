package com.example.mochi_pet.core.location

import com.example.mochi_pet.core.agent.tool.ToolExecutionContext
import com.example.mochi_pet.core.model.MochiSurface
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CurrentLocationToolTest {
    private val context = ToolExecutionContext(
        currentDate = LocalDate.of(2026, 8, 11),
        currentSurface = MochiSurface.Face,
    )

    @Test
    fun `returns WGS84 and locally converted GCJ02 coordinates`() =
        runBlocking {
            val capturedAt = Instant.parse("2026-08-11T10:00:00Z")
            val tool = CurrentLocationTool(
                locationProvider = DeviceLocationProvider {
                    DeviceLocation(
                        latitude = 31.230416,
                        longitude = 121.473701,
                        accuracyMeters = 12.34,
                        capturedAtEpochMillis = capturedAt.toEpochMilli(),
                        provider = "network",
                    )
                },
                clock = Clock.fixed(
                    capturedAt.plusSeconds(45),
                    ZoneOffset.UTC,
                ),
            )

            val result = tool.execute(buildJsonObject {}, context)

            assertEquals("ok", result.status)
            assertNotNull(result.data)
            val data = result.data!!.jsonObject
            val wgs84 = data.getValue("wgs84").jsonObject
            assertEquals(
                31.230416,
                wgs84.getValue("latitude").jsonPrimitive.double,
                0.0,
            )
            assertEquals(
                "WGS-84",
                wgs84.getValue("coordinate_system").jsonPrimitive.content,
            )
            val gcj02 = data.getValue("gcj02").jsonObject
            assertEquals(
                31.228474,
                gcj02.getValue("latitude").jsonPrimitive.double,
                0.00001,
            )
            assertEquals(
                121.478224,
                gcj02.getValue("longitude").jsonPrimitive.double,
                0.00001,
            )
            assertEquals(
                "GCJ-02",
                gcj02.getValue("coordinate_system").jsonPrimitive.content,
            )
            assertEquals(
                12.3,
                data.getValue("accuracy_m").jsonPrimitive.double,
                0.0,
            )
            assertEquals(
                capturedAt.toString(),
                data.getValue("captured_at").jsonPrimitive.content,
            )
            assertEquals(
                45,
                data.getValue("age_seconds").jsonPrimitive.long,
            )
            assertEquals(
                "network",
                data.getValue("provider").jsonPrimitive.content,
            )
        }

    @Test
    fun `omits GCJ02 conversion outside China`() = runBlocking {
        val tool = CurrentLocationTool(
            locationProvider = DeviceLocationProvider {
                DeviceLocation(
                    latitude = 51.5074,
                    longitude = -0.1278,
                )
            },
        )

        val result = tool.execute(buildJsonObject {}, context)

        assertEquals(JsonNull, result.data?.jsonObject?.get("gcj02"))
    }

    @Test
    fun `maps location failures to typed Tool errors`() = runBlocking {
        val cases = listOf(
            LocationPermissionDeniedException() to "PERMISSION_DENIED",
            LocationRequestTimeoutException() to "TIMEOUT",
            LocationUnavailableException("Location is disabled") to
                "PROVIDER_ERROR",
        )

        cases.forEach { (error, expectedCode) ->
            val tool = CurrentLocationTool(
                locationProvider = DeviceLocationProvider { throw error },
            )

            val result = tool.execute(buildJsonObject {}, context)

            assertEquals("error", result.status)
            assertEquals(expectedCode, result.code)
            assertTrue(result.message.orEmpty().isNotBlank())
        }
    }
}
