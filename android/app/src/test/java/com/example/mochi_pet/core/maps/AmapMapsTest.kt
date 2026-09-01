package com.example.mochi_pet.core.maps

import com.example.mochi_pet.core.agent.tool.ToolExecutionContext
import com.example.mochi_pet.core.model.MochiSurface
import java.time.LocalDate
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class AmapMapsTest {
    private lateinit var server: MockWebServer
    private lateinit var client: AmapMapsClient

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        client = AmapMapsClient(
            client = OkHttpClient(),
            baseUrl = server.url("/"),
        )
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `client adds key and optional signature`() = runBlocking {
        server.enqueue(amapSuccess("""{"geocodes":[]}"""))

        client.call(
            endpoint = AmapEndpoint.GEOCODING,
            credentials = AmapCredentials(
                webServiceKey = "web-key",
                securityKey = "security-key",
            ),
            parameters = mapOf("address" to "Shanghai"),
        )
        val request = server.takeRequest()

        assertEquals("/v3/geocode/geo", request.requestUrl?.encodedPath)
        assertEquals("web-key", request.requestUrl?.queryParameter("key"))
        assertEquals(
            amapSign(
                mapOf(
                    "address" to "Shanghai",
                    "key" to "web-key",
                ),
                "security-key",
            ),
            request.requestUrl?.queryParameter("sig"),
        )
    }

    @Test
    fun `six tools are exposed with stable names`() {
        val names = amapMapTools(
            client = client,
            credentials = AmapCredentials("web-key"),
        ).map { it.name }

        assertEquals(
            setOf(
                "amap_search_poi",
                "amap_get_poi",
                "amap_geocoding",
                "amap_reverse_geocoding",
                "amap_direction",
                "amap_weather",
            ),
            names.toSet(),
        )
    }

    @Test
    fun `nearby search uses trusted coordinate order and detail fields`() =
        runBlocking {
            server.enqueue(amapSuccess("""{"pois":[]}"""))
            val tool = amapMapTools(
                client = client,
                credentials = AmapCredentials("web-key"),
            ).first { it.name == "amap_search_poi" }

            val result = tool.execute(
                arguments = buildJsonObject {
                    put("keyword", "coffee")
                    put("latitude", 31.2304)
                    put("longitude", 121.4737)
                    put("radius", 3000)
                },
                context = context,
            )
            val request = server.takeRequest()

            assertEquals("ok", result.status)
            assertEquals("/v5/place/around", request.requestUrl?.encodedPath)
            assertEquals(
                "121.4737,31.2304",
                request.requestUrl?.queryParameter("location"),
            )
            assertEquals(
                "business,photos",
                request.requestUrl?.queryParameter("show_fields"),
            )
            assertEquals(
                "Amap",
                result.data?.jsonObject?.get("provider")
                    ?.toString()
                    ?.trim('"'),
            )
        }

    @Test
    fun `search rejects incomplete coordinates`() = runBlocking {
        val tool = amapMapTools(
            client = client,
            credentials = AmapCredentials("web-key"),
        ).first { it.name == "amap_search_poi" }

        val result = tool.execute(
            arguments = buildJsonObject {
                put("keyword", "coffee")
                put("latitude", 31.2304)
            },
            context = context,
        )

        assertEquals("error", result.status)
        assertEquals("INVALID_ARGS", result.code)
    }

    @Test
    fun `provider failure is explicit`() = runBlocking {
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody(
                    """{"status":"0","info":"INVALID_USER_KEY","infocode":"10001"}""",
                ),
        )
        val tool = amapMapTools(
            client = client,
            credentials = AmapCredentials("bad-key"),
        ).first { it.name == "amap_weather" }

        val result = tool.execute(
            arguments = buildJsonObject { put("adcode", "310000") },
            context = context,
        )

        assertEquals("error", result.status)
        assertEquals("PROVIDER_ERROR", result.code)
        assertTrue(result.message.orEmpty().contains("INVALID_USER_KEY"))
    }

    private fun amapSuccess(content: String): MockResponse =
        MockResponse()
            .setHeader("Content-Type", "application/json")
            .setBody("""{"status":"1","info":"OK",${
                content.removePrefix("{").removeSuffix("}")
            }}""")

    private val context = ToolExecutionContext(
        currentDate = LocalDate.of(2026, 9, 1),
        currentSurface = MochiSurface.Conversation,
    )
}
