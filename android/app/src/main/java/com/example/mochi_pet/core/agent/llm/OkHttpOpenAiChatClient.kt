package com.example.mochi_pet.core.agent.llm

import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response

class OkHttpOpenAiChatClient(
    private val baseClient: OkHttpClient = OkHttpClient(),
) : OpenAiChatClient {
    private val json = Json {
        encodeDefaults = true
        explicitNulls = false
        ignoreUnknownKeys = true
    }

    override suspend fun complete(
        config: OpenAiProviderConfig,
        request: OpenAiChatRequest,
    ): OpenAiChatResponse {
        val body = json.encodeToString(
            OpenAiChatRequest.serializer(),
            request.copy(
                model = if (config.providerType == ProviderType.AZURE_OPENAI) {
                    null
                } else {
                    config.model.trim()
                },
            ),
        )
        val requestBuilder = Request.Builder()
            .url(chatCompletionsUrl(config))
            .header("Accept", "application/json")
            .post(body.toRequestBody(JSON_MEDIA_TYPE))
        when (config.providerType) {
            ProviderType.AZURE_OPENAI ->
                requestBuilder.header("api-key", config.apiKey)
            ProviderType.OPENAI,
            ProviderType.CUSTOM,
            -> requestBuilder.header(
                "Authorization",
                "Bearer ${config.apiKey}",
            )
        }
        val client = baseClient.newBuilder()
            .connectTimeout(config.timeoutSeconds, TimeUnit.SECONDS)
            .readTimeout(config.timeoutSeconds, TimeUnit.SECONDS)
            .writeTimeout(config.timeoutSeconds, TimeUnit.SECONDS)
            .callTimeout(config.timeoutSeconds, TimeUnit.SECONDS)
            .build()
        val response = try {
            client.newCall(requestBuilder.build()).await()
        } catch (error: IOException) {
            throw ProviderNetworkException(
                "Provider network request failed",
                error,
            )
        }
        response.use {
            val responseText = it.readBoundedBody(config.maxResponseBytes)
            if (!it.isSuccessful) {
                throw ProviderHttpException(
                    statusCode = it.code,
                    message = providerErrorMessage(
                        statusCode = it.code,
                        responseText = responseText,
                        apiKey = config.apiKey,
                    ),
                )
            }
            val parsed = try {
                json.decodeFromString(
                    OpenAiChatResponse.serializer(),
                    responseText,
                )
            } catch (error: SerializationException) {
                throw ProviderProtocolException(
                    "Provider returned invalid chat completion JSON",
                )
            }
            if (parsed.error != null) {
                throw ProviderProtocolException(
                    parsed.error.message ?: "Provider returned an error payload",
                )
            }
            if (parsed.choices.isEmpty()) {
                throw ProviderProtocolException(
                    "Provider response did not contain any choices",
                )
            }
            return parsed
        }
    }

    private fun chatCompletionsUrl(config: OpenAiProviderConfig): HttpUrl {
        val parsed = config.endpoint.trim().trimEnd('/').toHttpUrlOrNull()
            ?: throw ProviderConfigurationException(
                "Provider endpoint must be an absolute HTTP(S) URL",
            )
        if (parsed.scheme !in setOf("http", "https")) {
            throw ProviderConfigurationException(
                "Provider endpoint must use HTTP or HTTPS",
            )
        }
        if (parsed.username.isNotEmpty() || parsed.password.isNotEmpty()) {
            throw ProviderConfigurationException(
                "Provider endpoint must not contain embedded credentials",
            )
        }
        return when (config.providerType) {
            ProviderType.AZURE_OPENAI -> parsed.newBuilder()
                .encodedPath(
                    parsed.encodedPath.trimEnd('/').ifEmpty { "/" },
                )
                .query(null)
                .addPathSegments("openai/deployments")
                .addPathSegment(config.model.trim())
                .addPathSegments("chat/completions")
                .addQueryParameter("api-version", config.apiVersion.trim())
                .build()
            ProviderType.OPENAI,
            ProviderType.CUSTOM,
            -> if (
                parsed.encodedPath.trimEnd('/').endsWith("/chat/completions")
            ) {
                parsed
            } else {
                parsed.newBuilder()
                    .addPathSegments("chat/completions")
                    .build()
            }
        }
    }

    private fun Response.readBoundedBody(maxBytes: Long): String {
        val responseBody = body
            ?: throw ProviderProtocolException("Provider response body was empty")
        if (responseBody.contentLength() > maxBytes) {
            throw ProviderResponseTooLargeException(
                "Provider response exceeded $maxBytes bytes",
            )
        }
        val source = responseBody.source()
        source.request(maxBytes + 1)
        if (source.buffer.size > maxBytes) {
            throw ProviderResponseTooLargeException(
                "Provider response exceeded $maxBytes bytes",
            )
        }
        return source.readUtf8()
    }

    private fun providerErrorMessage(
        statusCode: Int,
        responseText: String,
        apiKey: String,
    ): String {
        val detail = runCatching {
            json.decodeFromString(
                OpenAiChatResponse.serializer(),
                responseText,
            ).error?.message
        }.getOrNull()
        return detail
            ?.replace(apiKey, REDACTED_VALUE)
            ?.take(MAX_ERROR_MESSAGE_CHARS)
            ?: "Provider request failed with HTTP $statusCode"
    }

    private suspend fun Call.await(): Response =
        suspendCancellableCoroutine { continuation ->
            continuation.invokeOnCancellation { cancel() }
            enqueue(
                object : Callback {
                    override fun onFailure(
                        call: Call,
                        e: IOException,
                    ) {
                        if (!continuation.isCancelled) {
                            continuation.resumeWithException(e)
                        }
                    }

                    override fun onResponse(
                        call: Call,
                        response: Response,
                    ) {
                        if (continuation.isCancelled) {
                            response.close()
                        } else {
                            continuation.resume(response)
                        }
                    }
                },
            )
        }

    private companion object {
        val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
        const val MAX_ERROR_MESSAGE_CHARS = 500
        const val REDACTED_VALUE = "[REDACTED]"
    }
}
