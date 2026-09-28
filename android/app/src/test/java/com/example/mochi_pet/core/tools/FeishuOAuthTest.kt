package com.example.mochi_pet.core.tools

import com.example.mochi_pet.core.mcp.McpAuthenticationException
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.MockResponse
import kotlinx.coroutines.TimeoutCancellationException
import java.util.concurrent.atomic.AtomicReference
import okhttp3.Call
import org.junit.Assert.*
import org.junit.Test

class FeishuOAuthTest {
    private val credentials = FeishuAppCredentials("cli_fixture", "synthetic-app-secret")

    @Test fun `authorization uses official endpoint exact loopback PKCE and offline access`() {
        val url = FeishuOAuthClient().authorizationUrl(credentials.appId, "state", "verifier").toHttpUrl()
        assertEquals("accounts.feishu.cn", url.host)
        assertEquals(FEISHU_REDIRECT_URI, url.queryParameter("redirect_uri"))
        assertEquals("state", url.queryParameter("state"))
        assertEquals("S256", url.queryParameter("code_challenge_method"))
        assertEquals(43, url.queryParameter("code_challenge")!!.length)
        assertEquals(FEISHU_SCOPES, url.queryParameter("scope")!!.split(' ').toSet())
        assertFalse(url.toString().contains(credentials.appSecret))
        assertFalse(url.toString().contains("verifier"))
    }

    @Test fun `exchange sends confidential client secret and verifier only to v3 token endpoint`() = runBlocking {
        val client = FeishuOAuthClient(OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            assertEquals("https://accounts.feishu.cn/oauth/v3/token", request.url.toString())
            val form = request.body as FormBody
            val fields = (0 until form.size).associate { form.name(it) to form.value(it) }
            assertEquals(credentials.appId, fields["client_id"])
            assertEquals(credentials.appSecret, fields["client_secret"])
            assertEquals("authorization_code", fields["grant_type"])
            assertEquals("fixture-code", fields["code"])
            assertEquals("fixture-verifier", fields["code_verifier"])
            assertEquals(FEISHU_REDIRECT_URI, fields["redirect_uri"])
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body(Json.encodeToString(feishuFixtureToken()).toResponseBody("application/json".toMediaType()))
                .build()
        }.build())
        assertEquals("synthetic-access", client.exchange(credentials, "fixture-code", "fixture-verifier").accessToken)
    }

    @Test fun `HTTP200 business failure and cropped grants are rejected without echoing provider secrets`() {
        for ((body, status) in listOf(
            """{"code":20002,"error_description":"synthetic-app-secret"}""" to 200,
            """{"code":20010,"error_description":"synthetic-app-secret"}""" to 400,
            Json.encodeToString(feishuFixtureToken(scope = "offline_access")) to 200,
            "not json" to 200,
            "x".repeat(65_537) to 200,
        )) {
            val client = FeishuOAuthClient(OkHttpClient.Builder().addInterceptor { chain ->
                Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(status).message("fixture")
                    .body(body.toResponseBody("application/json".toMediaType())).build()
            }.build())
            val error = assertThrows(McpAuthenticationException::class.java) {
                runBlocking { client.refresh(credentials, "synthetic-refresh") }
            }
            assertFalse(error.message.orEmpty().contains(credentials.appSecret))
        }
    }

    @Test fun `callback rejects unrelated host path and state without consuming valid response`() = runBlocking {
        withTimeout(5_000) {
            FeishuLoopbackCallback(0).use { callback ->
                val result = async(Dispatchers.IO) { callback.awaitCode("fixture-state") }
                for (path in listOf(
                    "/wrong?state=fixture-state&code=fixture-code",
                    "/oauth/feishu?state=other&code=fixture-code",
                    "/oauth/feishu?state=fixture-state&state=other&code=fixture-code",
                    "/oauth/feishu?state=fixture-state&code=one&code=two",
                )) assertTrue(callbackRequest(callback.localPort, path).startsWith("HTTP/1.1 400"))
                assertTrue(callbackRequest(callback.localPort, "/oauth/feishu?state=fixture-state&code=fixture-code",
                    host = "example.com").startsWith("HTTP/1.1 400"))
                assertFalse(result.isCompleted)
                val response = callbackRequest(callback.localPort, "/oauth/feishu?state=fixture-state&code=fixture-code")
                assertTrue(response.startsWith("HTTP/1.1 200"))
                assertFalse(response.contains("fixture-code"))
                assertEquals("fixture-code", result.await())
            }
        }
    }

    @Test fun `denied callback fails explicitly and cancellation releases fixed port`() {
        assertThrows(McpAuthenticationException::class.java) {
            runBlocking {
                FeishuLoopbackCallback(0).use { callback ->
                    val sender = launch(Dispatchers.IO) {
                        callbackRequest(callback.localPort, "/oauth/feishu?state=s&error=access_denied")
                    }
                    callback.awaitCode("s")
                    sender.join()
                }
            }
        }
        runBlocking {
            val opened = CountDownLatch(1)
            val job = launch(Dispatchers.IO) {
                FeishuOAuthClient().authorize(credentials) { opened.countDown() }
            }
            assertTrue(withContext(Dispatchers.IO) { opened.await(3, TimeUnit.SECONDS) })
            withTimeout(3_000) { job.cancelAndJoin() }
            FeishuLoopbackCallback().close()
        }
    }

    @Test fun `invalid lifetimes token type and missing refresh token are not successful connections`() {
        listOf(
            FeishuTokenResponse(code = 0),
            FeishuTokenResponse(code = 0, accessToken = "a", refreshToken = "r", expiresIn = Long.MAX_VALUE),
        ).forEach { token ->
            assertThrows(McpAuthenticationException::class.java) { validateFeishuToken(token) }
        }

    }

    @Test fun `refresh cancels stalled body promptly and never follows a redirect`() = runBlocking {
        MockWebServer().use { server ->
            val call = AtomicReference<Call>()
            val client = FeishuOAuthClient(OkHttpClient.Builder().addInterceptor { chain ->
                call.set(chain.call())
                chain.proceed(chain.request().newBuilder().url(server.url("/token")).build())
            }.build())
            server.enqueue(MockResponse().setBody(Json.encodeToString(feishuFixtureToken()))
                .setBodyDelay(3, TimeUnit.SECONDS))
            val started = System.nanoTime()
            assertThrows(TimeoutCancellationException::class.java) {
                runBlocking { withTimeout(500) { client.refresh(credentials, "synthetic-refresh") } }
            }
            assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started) < 2_000)
            assertTrue(call.get().isCanceled())
            assertEquals(1, server.requestCount)
            server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", server.url("/unexpected")))
            assertThrows(McpAuthenticationException::class.java) {
                runBlocking { client.refresh(credentials, "synthetic-refresh") }
            }
            assertEquals(2, server.requestCount)
        }
    }

    private suspend fun callbackRequest(port: Int, path: String, host: String = "127.0.0.1:$port"): String =
        withContext(Dispatchers.IO) {
            Socket("127.0.0.1", port).use {
                it.soTimeout = 3_000
                it.getOutputStream().write("GET $path HTTP/1.1\r\nHost: $host\r\n\r\n".toByteArray())
                it.getInputStream().bufferedReader().readText()
            }
        }
}

internal fun feishuFixtureToken(
    access: String = "synthetic-access",
    refresh: String = "synthetic-refresh",
    scope: String = FEISHU_SCOPES.joinToString(" "),
) = FeishuTokenResponse(
    code = 0, accessToken = access, refreshToken = refresh, expiresIn = 3600,
    refreshExpiresIn = 604800, tokenType = "Bearer", scope = scope,
)
