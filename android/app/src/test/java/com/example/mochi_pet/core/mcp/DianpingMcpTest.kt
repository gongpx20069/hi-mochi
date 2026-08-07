package com.example.mochi_pet.core.mcp

import java.security.MessageDigest
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Test

class DianpingMcpTest {
    @Test
    fun `sign lowercases and sorts parameter names`() {
        val actual = dianpingSign(
            parameters = linkedMapOf(
                "b" to "2",
                "AB" to "3",
                "a" to "1",
                "empty" to "",
            ),
            appSecret = "xyz",
        )
        val expected = MessageDigest.getInstance("MD5")
            .digest("xyza1ab3b2xyz".toByteArray())
            .joinToString(separator = "") {
                "%02x".format(it.toInt() and 0xff)
            }

        assertEquals(expected, actual)
    }

    @Test
    fun `search preserves signed strings and uses search session`() =
        runBlocking {
            val server = MockWebServer()
            server.enqueue(
                MockResponse()
                    .setHeader("Content-Type", "application/json")
                    .setBody("""{"status":"OK","records":[]}"""),
            )
            server.start()
            try {
                val client = DianpingMcpClient(
                    nowMillis = { 1_725_000_000_123L },
                    baseEndpoint = server.url("/router").toString(),
                )
                val credentials = DianpingCredentials(
                    appKey = "00123",
                    appSecret = "secret",
                    searchSession = "search-007",
                    detailSession = "detail-008",
                )

                client.callTool(
                    server = runtime(credentials),
                    toolName = "search_poi",
                    arguments = buildJsonObject {
                        put("keyword", "coffee")
                        put("city", "Shanghai")
                    },
                )

                val request = server.takeRequest()
                assertEquals(
                    "/router/poisearch/search",
                    request.requestUrl?.encodedPath,
                )
                val payload = Json.parseToJsonElement(
                    request.body.readUtf8(),
                ).jsonObject
                assertEquals("00123", payload["appkey"]?.jsonPrimitive?.content)
                assertEquals(
                    "search-007",
                    payload["session"]?.jsonPrimitive?.content,
                )
                assertEquals(
                    "1725000000123",
                    payload["timestamp"]?.jsonPrimitive?.content,
                )
                assertEquals(
                    dianpingSign(
                        parameters = mapOf(
                            "appkey" to "00123",
                            "session" to "search-007",
                            "timestamp" to "1725000000123",
                            "keyword" to "coffee",
                            "city" to "Shanghai",
                            "page" to "1",
                            "limit" to "25",
                        ),
                        appSecret = "secret",
                    ),
                    payload["sign"]?.jsonPrimitive?.content,
                )
            } finally {
                server.shutdown()
            }
        }

    @Test
    fun `detail uses its endpoint specific session`() = runBlocking {
        val server = MockWebServer()
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody(
                    """{"status":"success","success":true,"data":{}}""",
                ),
        )
        server.start()
        try {
            val client = DianpingMcpClient(
                nowMillis = { 1_725_000_000_123L },
                baseEndpoint = server.url("/router").toString(),
            )
            val credentials = DianpingCredentials(
                appKey = "key",
                appSecret = "secret",
                searchSession = "search-session",
                detailSession = "detail-session",
            )

            client.callTool(
                server = runtime(credentials),
                toolName = "get_poi",
                arguments = buildJsonObject {
                    put("openshopid", "shop_123")
                },
            )

            val request = server.takeRequest()
            assertEquals(
                "/router/poi/getsinglepoi",
                request.requestUrl?.encodedPath,
            )
            val payload = Json.parseToJsonElement(
                request.body.readUtf8(),
            ).jsonObject
            assertEquals(
                "detail-session",
                payload["session"]?.jsonPrimitive?.content,
            )
        } finally {
            server.shutdown()
        }
    }

    private fun runtime(credentials: DianpingCredentials) = McpServerRuntime(
        id = DIANPING_SERVER_ID,
        name = "Dianping MCP",
        endpoint = DIANPING_MCP_ENDPOINT,
        accessToken = DianpingMcpClient.encodeCredentials(credentials),
        authorizationHeader = null,
    )
}
