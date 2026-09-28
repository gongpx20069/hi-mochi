package com.example.mochi_mijia

import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OkHttpAwaitTest {
    @Test fun `verification deadline cancels a stalled response body without replay`() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("{}").setBodyDelay(3, TimeUnit.SECONDS))
            val call = OkHttpClient().newCall(Request.Builder().url(server.url("/")).build())
            val property = MiotProperty(2, 1, "on", "bool", true, true, null, emptySet())
            val start = System.nanoTime()
            val result = verifyDeviceControl(property, JsonPrimitive(false), read = {
                call.awaitBufferedResponse().use { MiotPropertyResult(0, JsonPrimitive(false)) }
            }, timeoutMillis = 500)
            val elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start)
            assertEquals("state_read_timeout", result.reason)
            assertTrue("Readback took $elapsed ms", elapsed < 2_000)
            assertTrue(call.isCanceled())
            assertEquals(1, server.requestCount)
        }
    }

    @Test fun `buffered response preserves body and authorization headers`() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(401).setHeader("Set-Cookie", "test=value").setBody("error"))
            OkHttpClient().newCall(Request.Builder().url(server.url("/")).build())
                .awaitBufferedResponse().use {
                    assertEquals(401, it.code)
                    assertEquals("test=value", it.header("Set-Cookie"))
                    assertEquals("error", it.body.string())
                }
        }
    }
}
