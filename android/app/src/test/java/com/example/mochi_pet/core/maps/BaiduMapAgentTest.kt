package com.example.mochi_pet.core.maps

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class BaiduMapAgentTest {
    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `client sends bearer token and bounded query parameters`() =
        runBlocking {
            server.enqueue(
                MockResponse()
                    .setHeader("Content-Type", "application/json")
                    .setBody("""{"status":0,"result":{"name":"Cafe"}}"""),
            )
            val client = BaiduMapAgentClient(
                client = OkHttpClient(),
                baseUrl = server.url("/agent_plan/v1/"),
            )

            val result = client.call(
                operation = "place",
                token = "test-token",
                parameters = mapOf(
                    "user_raw_request" to "Find a quiet cafe",
                    "region" to "Beijing",
                ),
            )
            val request = server.takeRequest()

            assertEquals("Bearer test-token", request.getHeader("Authorization"))
            assertEquals("/agent_plan/v1/place", request.requestUrl?.encodedPath)
            assertEquals(
                "Find a quiet cafe",
                request.requestUrl?.queryParameter("user_raw_request"),
            )
            assertEquals("0", result["status"].toString())
        }

    @Test
    fun `five tools are exposed with stable names`() {
        val names = baiduMapAgentTools(
            client = BaiduMapAgentClient(
                baseUrl = server.url("/agent_plan/v1/"),
            ),
            token = "test-token",
        ).map { it.name }

        assertEquals(5, names.size)
        assertTrue("baidu_map_place" in names)
        assertTrue("baidu_map_direction" in names)
        assertTrue("baidu_map_geocoding" in names)
        assertTrue("baidu_map_reverse_geocoding" in names)
        assertTrue("baidu_map_weather" in names)
    }
}
