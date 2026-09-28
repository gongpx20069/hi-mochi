package com.example.mochi_pet.core.tools

import com.example.mochi_pet.core.mcp.McpAuthenticationException
import com.example.mochi_pet.core.mcp.FEISHU_MCP_TOOLS
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.SocketTimeoutException
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import okhttp3.Call
import okhttp3.Callback
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okio.buffer
import okio.source

const val FEISHU_REDIRECT_URI = "http://127.0.0.1:43827/oauth/feishu"
const val FEISHU_CONSOLE_URL = "https://open.feishu.cn/app"
const val FEISHU_SETUP_URL =
    "https://open.feishu.cn/document/mcp_open_tools/developers-call-remote-mcp-server"
const val FEISHU_RECONNECT = "Feishu authorization expired or interrupted. Reconnect in Tools."
const val FEISHU_TIMEOUT = "Feishu authorization timed out. Return to Tools and try again."
const val FEISHU_NETWORK_ERROR = "Feishu authorization network request failed. Reconnect in Tools."

val FEISHU_DOCUMENT_TOOLS = FEISHU_MCP_TOOLS
val FEISHU_READ_ONLY_TOOLS = setOf("search-doc", "fetch-doc", "list-docs")
val FEISHU_SCOPES = setOf(
    "offline_access",
    "search:docs:read",
    "wiki:wiki:readonly",
    "docx:document:readonly",
    "task:task:read",
    "im:chat:read",
    "docx:document:create",
    "wiki:node:read",
    "wiki:node:create",
    "docs:document.media:upload",
    "board:whiteboard:node:create",
    "docx:document:write_only",
)

class FeishuAppCredentials(val appId: String, val appSecret: String) {
    init {
        require(appId.matches(Regex("cli_[A-Za-z0-9]+")) && appSecret.isNotBlank() &&
            appSecret.length <= 4096 && appSecret.none(Char::isWhitespace)) {
            "Enter the Feishu App ID and App Secret from Credentials & Basic Info."
        }
    }
}

@Serializable
class FeishuTokenResponse(
    val code: Int = -1,
    @SerialName("access_token") val accessToken: String = "",
    @SerialName("refresh_token") val refreshToken: String = "",
    @SerialName("expires_in") val expiresIn: Long = 0,
    @SerialName("refresh_token_expires_in") val refreshExpiresIn: Long = 0,
    @SerialName("token_type") val tokenType: String = "",
    val scope: String = "",
)

open class FeishuOAuthClient(client: OkHttpClient = OkHttpClient()) {
    private val client = client.newBuilder()
        .callTimeout(25, TimeUnit.SECONDS)
        .followRedirects(false)
        .followSslRedirects(false)
        .retryOnConnectionFailure(false)
        .build()
    private val json = Json { ignoreUnknownKeys = true }

    open suspend fun authorize(
        credentials: FeishuAppCredentials,
        openAuthorization: suspend (String) -> Unit,
    ): FeishuTokenResponse = withTimeout(5 * 60_000L) {
        FeishuLoopbackCallback().use { callback ->
            val state = randomSecret()
            val verifier = randomSecret()
            openAuthorization(authorizationUrl(credentials.appId, state, verifier))
            exchange(credentials, callback.awaitCode(state), verifier)
        }
    }

    internal fun authorizationUrl(appId: String, state: String, verifier: String): String =
        "https://accounts.feishu.cn/open-apis/authen/v1/authorize".toHttpUrl().newBuilder()
            .addQueryParameter("client_id", appId)
            .addQueryParameter("response_type", "code")
            .addQueryParameter("redirect_uri", FEISHU_REDIRECT_URI)
            .addQueryParameter("scope", FEISHU_SCOPES.joinToString(" "))
            .addQueryParameter("state", state)
            .addQueryParameter("code_challenge", Base64.getUrlEncoder().withoutPadding().encodeToString(
                MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII)),
            ))
            .addQueryParameter("code_challenge_method", "S256")
            .addQueryParameter("prompt", "consent")
            .build().toString()

    internal suspend fun exchange(
        credentials: FeishuAppCredentials,
        code: String,
        verifier: String,
    ): FeishuTokenResponse = request(credentials, mapOf(
        "grant_type" to "authorization_code",
        "code" to code,
        "redirect_uri" to FEISHU_REDIRECT_URI,
        "code_verifier" to verifier,
    ))

    open suspend fun refresh(
        credentials: FeishuAppCredentials,
        refreshToken: String,
    ): FeishuTokenResponse = request(credentials, mapOf(
        "grant_type" to "refresh_token",
        "refresh_token" to refreshToken,
    ))

    private suspend fun request(
        credentials: FeishuAppCredentials,
        fields: Map<String, String>,
    ): FeishuTokenResponse {
        val form = FormBody.Builder()
            .add("client_id", credentials.appId)
            .add("client_secret", credentials.appSecret)
            .apply { fields.forEach { (key, value) -> add(key, value) } }.build()
        val request = Request.Builder().url("https://accounts.feishu.cn/oauth/v3/token")
            .header("Accept", "application/json").post(form).build()
        val raw = suspendCancellableCoroutine<String> { continuation ->
            val call = client.newCall(request)
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    continuation.resumeWithException(McpAuthenticationException(FEISHU_NETWORK_ERROR))
                }

                override fun onResponse(call: Call, response: Response) {
                    try {
                        response.use {
                            val source = it.body.source()
                            source.request(65_537)
                            if (source.buffer.size > 65_536) {
                                throw McpAuthenticationException("Feishu returned an oversized authorization response.")
                            }
                            val body = source.readUtf8()
                            if (it.code in 300..399) {
                                throw McpAuthenticationException("Feishu authorization redirect was rejected.")
                            }
                            if (!it.isSuccessful) {
                                // Never surface provider descriptions: they can contain submitted credentials.
                                val code = try {
                                    json.decodeFromString<FeishuTokenResponse>(body).code
                                } catch (_: SerializationException) {
                                    -1
                                }
                                throw McpAuthenticationException(feishuAuthorizationError(code))
                            }
                            continuation.resume(body)
                        }
                    } catch (_: IOException) {
                        continuation.resumeWithException(McpAuthenticationException(FEISHU_NETWORK_ERROR))
                    } catch (error: McpAuthenticationException) {
                        continuation.resumeWithException(error)
                    }
                }
            })
        }
        return try {
            json.decodeFromString<FeishuTokenResponse>(raw).also(::validateFeishuToken)
        } catch (_: SerializationException) {
            throw McpAuthenticationException("Feishu returned an invalid authorization response.")
        }
    }

    private fun randomSecret(): String =
        ByteArray(32).also(SecureRandom()::nextBytes)
            .let { Base64.getUrlEncoder().withoutPadding().encodeToString(it) }
}

internal fun validateFeishuToken(token: FeishuTokenResponse) {
    if (token.code != 0) throw McpAuthenticationException(feishuAuthorizationError(token.code))
    if (token.accessToken.isBlank() || token.refreshToken.isBlank() ||
        token.expiresIn !in 1..31_536_000 || token.refreshExpiresIn !in 1..31_536_000 ||
        token.tokenType != "Bearer" ||
        listOf(token.accessToken, token.refreshToken).any { value ->
            value.length > 4096 || value.any(Char::isWhitespace)
        }
    ) {
        throw McpAuthenticationException("Feishu did not return usable tokens. Enable offline_access and authorize again.")
    }
    if (!token.scope.split(' ').toSet().containsAll(FEISHU_SCOPES)) {
        throw McpAuthenticationException("Feishu permissions are incomplete. Enable every listed user permission, publish the app, and authorize again.")
    }
}

private fun feishuAuthorizationError(code: Int): String = when (code) {
    20002 -> "Feishu App ID or App Secret is invalid. Check Credentials & Basic Info."
    20010 -> "Your Feishu account cannot use this app. Publish it and add yourself to its availability."
    else -> "Feishu authorization failed. Check app credentials, permissions, availability, and redirect URL."
}

internal class FeishuLoopbackCallback(port: Int = 43827) : AutoCloseable {
    private val socket = ServerSocket().apply {
        try {
            reuseAddress = true
            bind(InetSocketAddress(InetAddress.getByName("127.0.0.1"), port), 4)
            soTimeout = 500
        } catch (error: IOException) {
            close()
            throw McpAuthenticationException("Feishu callback port is busy. Close the other authorization attempt and retry.")
        }
    }
    internal val localPort: Int get() = socket.localPort

    suspend fun awaitCode(state: String): String = withContext(Dispatchers.IO) {
        var acceptedCode: String? = null
        while (acceptedCode == null) {
            currentCoroutineContext().ensureActive()
            val client = try {
                socket.accept()
            } catch (_: SocketTimeoutException) {
                continue
            }
            client.use {
                it.soTimeout = 1_000
                try {
                    val source = it.source().buffer()
                    source.timeout().deadline(2, TimeUnit.SECONDS)
                    val line = source.readUtf8LineStrict(8192).split(' ')
                    val headers = mutableListOf<String>()
                    var remaining = 16_384L
                    while (true) {
                        val header = source.readUtf8LineStrict(remaining)
                        remaining -= header.toByteArray().size + 2
                        if (header.isEmpty()) break
                        require(remaining > 0)
                        headers.add(header)
                    }
                    val url = if (line.size == 3 && line[0] == "GET" &&
                        line[1].startsWith("/oauth/feishu?") && line[2] == "HTTP/1.1"
                    ) "http://127.0.0.1:$localPort${line[1]}".toHttpUrl() else null
                    val valid = url != null && url.encodedPath == "/oauth/feishu" &&
                        headers.filter { h -> h.startsWith("Host:", ignoreCase = true) }
                            .singleOrNull()?.equals("Host: 127.0.0.1:$localPort", ignoreCase = true) == true &&
                        url.queryParameterValues("state") == listOf(state)
                    val code = url?.queryParameterValues("code")?.singleOrNull()?.takeIf(String::isNotBlank)
                    val denied = url?.queryParameter("error") != null
                    val accepted = valid && (code != null || denied)
                    val message = if (accepted) {
                        "Authorization response received. Return to Mochi to see the connection result."
                    } else {
                        "Invalid authorization callback. Return to Mochi and try again."
                    }
                    val bytes = message.toByteArray(Charsets.UTF_8)
                    it.getOutputStream().write(
                        ("HTTP/1.1 ${if (accepted) "200 OK" else "400 Bad Request"}\r\n" +
                            "Content-Type: text/plain; charset=utf-8\r\nCache-Control: no-store\r\n" +
                            "Referrer-Policy: no-referrer\r\nConnection: close\r\nContent-Length: ${bytes.size}\r\n\r\n")
                            .toByteArray(Charsets.US_ASCII) + bytes,
                    )
                    if (valid && denied) {
                        throw McpAuthenticationException("Feishu authorization was declined. Return to Tools to retry.")
                    }
                    if (accepted && code != null) acceptedCode = code
                } catch (_: IOException) {
                    // Incomplete browser/unsolicited local requests do not consume this authorization.
                } catch (_: IllegalArgumentException) {
                    // Reject malformed HTTP without reflecting an untrusted request into the page.
                }
            }
        }
        checkNotNull(acceptedCode)
    }

    override fun close() = socket.close()
}
