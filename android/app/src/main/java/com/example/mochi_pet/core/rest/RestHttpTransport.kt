package com.example.mochi_pet.core.rest

import com.example.mochi_pet.core.agent.tool.ToolErrorCode
import java.io.IOException
import java.net.InetAddress
import java.net.Proxy
import java.net.SocketTimeoutException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Dns
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import kotlin.coroutines.resumeWithException

internal fun isPublicRestAddress(address: InetAddress): Boolean {
    if (address.isAnyLocalAddress || address.isLoopbackAddress || address.isLinkLocalAddress ||
        address.isSiteLocalAddress || address.isMulticastAddress
    ) return false
    val bytes = address.address.map { it.toInt() and 255 }
    return if (bytes.size == 4) {
        bytes[0] !in setOf(0, 127) && bytes[0] < 224 &&
            !(bytes[0] == 100 && bytes[1] in 64..127) &&
            !(bytes[0] == 198 && bytes[1] in 18..19)
    } else {
        bytes[0] in 0x20..0x3f && !(bytes[0] == 0x20 && bytes[1] == 0x02)
    }
}

internal fun restHttpClient(addressAllowed: (InetAddress) -> Boolean = ::isPublicRestAddress): OkHttpClient =
    OkHttpClient.Builder()
        .proxy(Proxy.NO_PROXY)
        .dns { host ->
            Dns.SYSTEM.lookup(host).also { addresses ->
                if (addresses.isEmpty() || addresses.any { !addressAllowed(it) }) {
                    throw IOException("REST address is not public")
                }
            }
        }
        .followRedirects(false)
        .followSslRedirects(false)
        .retryOnConnectionFailure(false)
        .addNetworkInterceptor { chain ->
            val address = chain.connection()?.route()?.socketAddress?.address
            if (address == null || !addressAllowed(address)) throw IOException("REST address is not public")
            val dispatched = chain.request().tag(AtomicBoolean::class.java)
                ?: throw IOException("REST dispatch state missing")
            if (!dispatched.compareAndSet(false, true)) throw IOException("REST requests cannot be retried")
            chain.proceed(chain.request())
        }
        .callTimeout(20, TimeUnit.SECONDS)
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

class RestHttpTransport internal constructor(private val client: OkHttpClient) : RestTransport {
    constructor() : this(restHttpClient())

    override suspend fun execute(
        request: RestRequest,
        auth: RestAuth,
        headerName: String,
        secret: String,
    ): RestResponse = withContext(Dispatchers.IO) {
        val builder = Request.Builder().url(request.url).header("Accept", "application/json")
            .tag(AtomicBoolean::class.java, AtomicBoolean(false))
            .method(request.method.name, request.body?.toString()?.toRequestBody("application/json".toMediaType()))
        when (auth) {
            RestAuth.NONE -> Unit
            RestAuth.BEARER -> builder.header("Authorization", "Bearer $secret")
            RestAuth.AUTHORIZATION -> builder.header("Authorization", secret)
            RestAuth.HEADER -> builder.header(headerName, secret)
        }
        try {
            val call = client.newCall(builder.build())
            suspendCancellableCoroutine { continuation ->
                continuation.invokeOnCancellation { call.cancel() }
                call.enqueue(object : Callback {
                    override fun onFailure(call: Call, e: IOException) {
                        continuation.resumeWithException(
                            RestApiException(
                                if (e is SocketTimeoutException || e is java.io.InterruptedIOException) ToolErrorCode.TIMEOUT else ToolErrorCode.PROVIDER_ERROR,
                                "REST request failed or timed out. If it changes data, its outcome may be unknown; do not retry automatically.",
                            ),
                        )
                    }

                    override fun onResponse(call: Call, response: Response) {
                        try {
                            val result = response.use {
                                if (!it.isSuccessful) throw RestApiException(
                                    if (it.code == 401 || it.code == 403) ToolErrorCode.PERMISSION_DENIED else ToolErrorCode.PROVIDER_ERROR,
                                    "REST HTTP ${it.code}. Check authorization, limits, and the endpoint; redirects are not followed.",
                                )
                                val body = it.body ?: throw RestApiException(ToolErrorCode.PROVIDER_ERROR, "REST response has no JSON body.")
                                val source = body.source()
                                if (source.request(262_145) || source.buffer.size > 262_144) {
                                    throw RestApiException(ToolErrorCode.PROVIDER_ERROR, "REST response exceeds 256 KiB.")
                                }
                                val text = source.readUtf8()
                                requireRestJsonDepth(text)
                                RestResponse(it.code, Json.parseToJsonElement(text))
                            }

                            continuation.resumeWith(Result.success(result))
                        } catch (error: RestApiException) {
                            continuation.resumeWithException(error)
                        } catch (error: IOException) {
                            continuation.resumeWithException(RestApiException(ToolErrorCode.PROVIDER_ERROR, "Could not read the REST response; the action may already have run."))
                        } catch (error: SerializationException) {
                            continuation.resumeWithException(RestApiException(ToolErrorCode.PROVIDER_ERROR, "REST response is not valid JSON."))
                        }
                    }
                })
            }
        } catch (error: IOException) {
            throw RestApiException(ToolErrorCode.PROVIDER_ERROR, "Could not send the REST request.")
        }
    }
}

internal fun requireRestJsonDepth(text: String) {
    var depth = 0
    var quoted = false
    var escaped = false
    for (character in text) {
        if (quoted) {
            if (escaped) escaped = false
            else if (character == '\\') escaped = true
            else if (character == '"') quoted = false
        } else when (character) {
            '"' -> quoted = true
            '{', '[' -> {
                depth++
                if (depth > 64) throw RestApiException(ToolErrorCode.PROVIDER_ERROR, "REST JSON nesting exceeds 64 levels.")
            }
            '}', ']' -> depth--
        }
    }
}
