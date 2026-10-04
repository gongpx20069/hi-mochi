package com.example.mochi_pet.core.rest

import java.util.concurrent.TimeUnit
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import kotlinx.serialization.json.JsonObject
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.Assert.*
import org.junit.Test

class RestHttpTransportTest {
    @Test fun `all auth modes and write methods send exactly one bounded JSON request`() = runBlocking {
        MockWebServer().use { server ->
            val transport = RestHttpTransport(restHttpClient { true })
            for (auth in RestAuth.entries) {
                server.enqueue(MockResponse().setBody("""{"value":22}"""))
                assertEquals(200, transport.execute(request(server), auth, "X-Fixture", "fixture-key").status)
                val sent = server.takeRequest(2, TimeUnit.SECONDS)!!
                assertEquals("POST", sent.method)
                assertEquals("application/json; charset=utf-8", sent.getHeader("Content-Type"))
                assertEquals("application/json", sent.getHeader("Accept"))
                assertEquals(if (auth == RestAuth.BEARER) "Bearer fixture-key" else if (auth == RestAuth.AUTHORIZATION) "fixture-key" else null,
                    sent.getHeader("Authorization"))
                assertEquals(if (auth == RestAuth.HEADER) "fixture-key" else null, sent.getHeader("X-Fixture"))
                assertEquals("{}", sent.body.readUtf8())
            }
            assertEquals(4, server.requestCount)
        }
    }

    @Test fun `redirect and HTTP errors never replay a mutation even with retry after zero`() {
        for (status in listOf(301, 302, 307, 308, 401, 403, 408, 429, 500, 503)) {
            MockWebServer().use { server ->
                server.enqueue(MockResponse().setResponseCode(status).setHeader("Location", server.url("/elsewhere"))
                    .setHeader("Retry-After", "0").setBody("fixture-key"))
                server.enqueue(MockResponse().setBody("""{"replayed":true}"""))
                val error = assertThrows(RestApiException::class.java) {
                    runBlocking { RestHttpTransport(restHttpClient { true }).execute(request(server), RestAuth.BEARER, "", "fixture-key") }
                }
                assertFalse(error.message!!.contains("fixture-key"))
                assertEquals("HTTP $status replayed", 1, server.requestCount)
            }
        }
    }

    @Test fun `invalid oversized compressed and deeply nested responses fail explicitly`() {
        for (body in listOf("not JSON", "x".repeat(262_145), "[".repeat(65) + "0" + "]".repeat(65))) {
            MockWebServer().use { server ->
                server.enqueue(MockResponse().setBody(body))
                assertThrows(RestApiException::class.java) {
                    runBlocking { RestHttpTransport(restHttpClient { true }).execute(request(server), RestAuth.NONE, "", "") }
                }
            }
        }
        MockWebServer().use { server ->
            val buffer = okio.Buffer()
            okio.GzipSink(buffer).use { sink -> sink.write(okio.Buffer().writeUtf8("x".repeat(262_145)), 262_145) }
            server.enqueue(MockResponse().setHeader("Content-Encoding", "gzip").setBody(buffer))
            assertThrows(RestApiException::class.java) {
                runBlocking { RestHttpTransport(restHttpClient { true }).execute(request(server), RestAuth.NONE, "", "") }
            }
        }
    }

    @Test fun `literal loopback is denied by production policy before HTTP dispatch`() {
        MockWebServer().use { server ->
            assertThrows(RestApiException::class.java) {
                runBlocking { RestHttpTransport().execute(request(server), RestAuth.BEARER, "", "fixture-key") }
            }
            assertEquals(0, server.requestCount)
        }
    }

    @Test fun `cancellation stops waiting without automatic retry`() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
            val task = async { RestHttpTransport(restHttpClient { true }).execute(request(server), RestAuth.NONE, "", "") }
            withTimeout(5_000) { while (server.requestCount == 0) yield() }
            withTimeout(2_000) { task.cancelAndJoin() }
            assertTrue(task.isCancelled)
            assertEquals(1, server.requestCount)
        }
    }

    private fun request(server: MockWebServer) =
        RestRequest(server.url("/fixture"), RestMethod.POST, JsonObject(emptyMap()))
}
