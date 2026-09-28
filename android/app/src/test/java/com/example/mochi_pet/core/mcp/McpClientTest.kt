package com.example.mochi_pet.core.mcp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.MockResponse
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.TimeoutCancellationException
import org.junit.Test

class McpClientTest {
    @Test fun `Feishu discovery cancels stalled body and rejects redirects without replay`() = runBlocking {
        MockWebServer().use { http ->
            val client = McpStreamableHttpClient(OkHttpClient.Builder().addInterceptor { chain ->
                chain.proceed(chain.request().newBuilder().url(http.url("/mcp")).build())
            }.build())
            val server = McpServerRuntime(FEISHU_SERVER_ID, "Feishu", FEISHU_MCP_ENDPOINT,
                "synthetic-uat", feishuAllowedTools = setOf("fetch-doc"))
            http.enqueue(MockResponse().setBody("""{"jsonrpc":"2.0","id":1,"result":{}}""")
                .setBodyDelay(3, TimeUnit.SECONDS))
            val started = System.nanoTime()
            assertThrows(TimeoutCancellationException::class.java) {
                runBlocking { withTimeout(500) { client.listTools(server) } }
            }
            assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started) < 2_000)
            assertEquals(1, http.requestCount)
            http.enqueue(MockResponse().setResponseCode(302).setHeader("Location", http.url("/unexpected")))
            assertThrows(McpException::class.java) { runBlocking { client.listTools(server) } }
            assertEquals(2, http.requestCount)
        }
    }

    @Test fun `Feishu uses scoped UAT headers for initialization notification and calls`() = runBlocking {
        val methods = mutableListOf<String>()
        val client = McpStreamableHttpClient(OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            assertEquals(FEISHU_MCP_ENDPOINT, request.url.toString())
            assertNull(request.header("Authorization"))
            assertEquals("synthetic-uat", request.header("X-Lark-MCP-UAT"))
            assertEquals("fetch-doc", request.header("X-Lark-MCP-Allowed-Tools"))
            val payload = Json.parseToJsonElement(Buffer().also { request.body!!.writeTo(it) }.readUtf8()).jsonObject
            val method = payload["method"]!!.jsonPrimitive.content
            methods.add(method)
            val body = if (method == "tools/call") """{"isError":true,"content":[{"type":"text","text":"fixture tool failed"}]}""" else "{}"
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1)
                .code(if (method.startsWith("notifications/")) 202 else 200).message("fixture")
                .body("""{"jsonrpc":"2.0","id":${payload["id"] ?: "null"},"result":$body}"""
                    .toResponseBody("application/json".toMediaType())).build()
        }.build())
        val server = McpServerRuntime(FEISHU_SERVER_ID, "Feishu", FEISHU_MCP_ENDPOINT,
            "synthetic-uat", feishuAllowedTools = setOf("fetch-doc"))
        assertThrows(McpException::class.java) {
            runBlocking { client.callTool(server, "fetch-doc", buildJsonObject {}) }
        }
        assertEquals(listOf("initialize", "notifications/initialized", "tools/call"), methods)
        assertEquals("feishu_fetch_doc", mcpToolAlias(FEISHU_SERVER_ID, "fetch-doc"))
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { client.listTools(server.copy(endpoint = "https://example.com/mcp")) }
        }
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { client.callTool(server, "create-doc", buildJsonObject {}) }
        }
        assertEquals(3, methods.size)
    }

    @Test
    fun `notion aliases match readable official tool names`() {
        assertEquals(
            "notion_search",
            mcpToolAlias(NOTION_SERVER_ID, "notion-search"),
        )
    }

    @Test
    fun `Tencent Docs aliases preserve readable dotted tool names`() {
        assertEquals(
            "tencent_docs_smartcanvas_update_element",
            mcpToolAlias(
                TENCENT_DOCS_SERVER_ID,
                "smartcanvas.update_element",
            ),
        )
    }

    @Test
    fun `manual aliases remain bounded and distinguish long remote names`() {
        val first = mcpToolAlias(
            "manual-server",
            "shared-very-long-tool-name-that-only-differs-at-the-end-a",
        )
        val second = mcpToolAlias(
            "manual-server",
            "shared-very-long-tool-name-that-only-differs-at-the-end-b",
        )

        assertTrue(first.length <= 64)
        assertTrue(first.startsWith("mcp_manual-server_"))
        assertNotEquals(first, second)
    }
}
