package com.example.mochi_pet.core.rest

import com.example.mochi_pet.core.agent.tool.ToolExecutionContext
import com.example.mochi_pet.core.model.MochiSurface
import java.net.InetAddress
import java.time.LocalDate
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.*
import org.junit.Test

class RestApiTest {
    private val connection = RestConnection(name = "Fixture", baseUrl = "https://api.example.com", auth = RestAuth.NONE)
    private val tool = RestToolDefinition(
        name = "Temperature", description = "Read room temperature", path = "/v1/rooms/{room}",
        parameters = listOf(
            RestParameter("room", location = RestParameterLocation.PATH),
            RestParameter("unit", required = false, defaultValue = JsonPrimitive("celsius")),
        ),
        outputs = listOf(RestOutputField("/data/value", "temperature", "Temperature in Celsius", RestValueType.NUMBER)),
    )

    @Test fun `typed paths query defaults and bodies stay on configured origin`() {
        val request = prepareRestRequest(connection, tool, json("""{"room":"a/b ?#%"}"""))
        assertEquals("api.example.com", request.url.host)
        assertEquals("/v1/rooms/a%2Fb%20%3F%23%25", request.url.encodedPath)
        assertEquals("celsius", request.url.queryParameter("unit"))
        assertNull(request.body)
        val write = tool.copy(method = RestMethod.POST, parameters = tool.parameters +
            RestParameter("count", location = RestParameterLocation.BODY, type = RestValueType.NUMBER),
            body = json("""{"fixed":true}"""))
        assertEquals(json("""{"fixed":true,"count":2}"""), prepareRestRequest(connection, write, json("""{"room":"a","count":2}""")).body)
        assertThrows(IllegalArgumentException::class.java) { prepareRestRequest(connection, write, json("""{"room":"a","count":"2"}""")) }
        assertThrows(IllegalArgumentException::class.java) { prepareRestRequest(connection, tool, json("""{"room":".."}""")) }
        assertThrows(IllegalArgumentException::class.java) { prepareRestRequest(connection, tool, json("""{"room":"a","unknown":1}""")) }
    }

    @Test fun `connections reject local literal addresses alternate origins and reserved headers`() {
        for (url in listOf("http://api.example.com", "https://api.example.com:8443", "https://user:pass@api.example.com",
            "https://api.example.com/path", "https://api.example.com?key=a", "https://localhost", "https://home.local",
            "https://127.0.0.1", "https://10.0.0.1", "https://[::1]", "https://[fc00::1]")) {
            assertThrows(url, IllegalArgumentException::class.java) { validateRestConnection(connection.copy(baseUrl = url)) }
        }
        for (header in listOf("Host", "Authorization", "Cookie", "Content-Length", "X-Key\r\nInjected")) {
            assertThrows(IllegalArgumentException::class.java) { validateRestConnection(connection.copy(auth = RestAuth.HEADER, headerName = header)) }
        }
        validateRestConnection(connection.copy(auth = RestAuth.HEADER, headerName = "X-API-Key"))
    }

    @Test fun `public address policy covers special IPv4 IPv6 and mapped addresses`() {
        for (ip in listOf("0.0.0.0", "127.0.0.1", "10.0.0.1", "172.16.1.1", "192.168.1.1",
            "169.254.169.254", "100.64.0.1", "198.18.0.1", "224.0.0.1", "240.0.0.1",
            "::", "::1", "fe80::1", "fc00::1", "::ffff:127.0.0.1", "2002:7f00:1::")) {
            assertFalse(ip, isPublicRestAddress(InetAddress.getByName(ip)))
        }
        for (ip in listOf("8.8.8.8", "1.1.1.1", "2606:4700:4700::1111")) {
            assertTrue(ip, isPublicRestAddress(InetAddress.getByName(ip)))
        }
    }

    @Test fun `definitions enforce parameter mapping write confirmation and scalar outputs`() {
        for (invalid in listOf(tool.copy(path = "//other.example/"), tool.copy(path = "/v1/../admin"),
            tool.copy(path = "/v1/%2e%2e/admin"), tool.copy(path = "/v1?x=1"),
            tool.copy(parameters = emptyList()), tool.copy(outputs = emptyList()),
            tool.copy(method = RestMethod.DELETE, requiresConfirmation = false),
            tool.copy(body = json("""{"x":1}""")),
            tool.copy(parameters = tool.parameters + RestParameter("confirmed")),
            tool.copy(parameters = tool.parameters + RestParameter("x", type = RestValueType.NUMBER, defaultValue = JsonPrimitive("wrong"))))) {
            assertThrows(IllegalArgumentException::class.java) { validateRestTool(invalid) }
        }
        val body = Json.parseToJsonElement("""{"code":0,"data":{"value":22},"a/b":{"~key":[true]}}""")
        assertEquals(JsonPrimitive(true), restPointer(body, "/a~1b/~0key/0"))
        assertEquals(json("""{"temperature":22}"""), selectRestOutput(tool.copy(successPointer = "/code", successValue = JsonPrimitive(0)), body))
        assertThrows(RestApiException::class.java) { selectRestOutput(tool.copy(successPointer = "/code", successValue = JsonPrimitive(1)), body) }
        assertThrows(RestApiException::class.java) { selectRestOutput(tool, json("""{"data":{"value":"22"}}""")) }
        assertThrows(RestApiException::class.java) { selectRestOutput(tool, json("{}")) }
    }

    @Test fun `output caps discovery and secret echoes are bounded`() {
        val body = Json.parseToJsonElement("""{"fixture-key":"Bearer fixture-key","nested":["fixture-key",12345]}""")
        assertFalse(redactRestResponse(body, "fixture-key").toString().contains("fixture-key"))
        assertFalse(redactRestResponse(body, "12345").toString().contains("12345"))
        val fields = restResponseFields(JsonObject((1..200).associate { "field$it" to JsonPrimitive(it) }))
        assertEquals(128, fields.size)
        val large = tool.copy(outputs = (1..10).map { RestOutputField("/data", "field$it") })
        assertThrows(RestApiException::class.java) { selectRestOutput(large, JsonObject(mapOf("data" to JsonPrimitive("x".repeat(4_000))))) }
        requireRestJsonDepth("""{"quoted":"${"[".repeat(100)}"}""")
        assertThrows(RestApiException::class.java) { requireRestJsonDepth("[".repeat(65) + "0" + "]".repeat(65)) }
    }

    @Test fun `schema includes meanings defaults and explicit approval without credentials`() = runBlocking {
        var calls = 0
        val agent = RestAgentTool(connection, tool) { calls++; json("""{"temperature":22}""") }
        val context = ToolExecutionContext(LocalDate.of(2026, 1, 1), MochiSurface.Face)
        assertEquals("PERMISSION_DENIED", agent.execute(json("""{"room":"a"}"""), context).code)
        assertEquals(0, calls)
        assertEquals("ok", agent.execute(json("""{"room":"a","confirmed":true}"""), context).status)
        assertEquals(1, calls)
        assertTrue(agent.schema.toString().contains("Temperature in Celsius"))
        assertTrue(agent.schema.toString().contains("celsius"))
        assertTrue(agent.name.length <= 64)
        assertEquals(agent.name, restToolAlias(connection.id, tool.id))
        assertNotEquals(agent.name, restToolAlias(connection.id, RestToolDefinition().id))
    }

    private fun json(value: String) = Json.parseToJsonElement(value) as JsonObject
}
