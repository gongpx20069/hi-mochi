package com.example.mochi_mijia

import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request

class MiotCloudClient(
    client: OkHttpClient = OkHttpClient(),
) {
    private val client = client.newBuilder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(40, TimeUnit.SECONDS)
        .writeTimeout(40, TimeUnit.SECONDS)
        .build()
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun post(
        session: MijiaSession,
        path: String,
        data: JsonObject,
    ): JsonObject = withContext(Dispatchers.IO) {
        val compactData = data.toString()
        val nonce = MiotCrypto.nonce()
        val signedNonce = MiotCrypto.signedNonce(
            session.ssecurity,
            nonce,
        )
        val normalizedPath = "/${path.trimStart('/')}"
        val signature = MiotCrypto.signature(
            path = normalizedPath,
            signedNonce = signedNonce,
            nonce = nonce,
            data = compactData,
        )
        val url = baseUrl(session.region)
            .newBuilder()
            .addPathSegments(path.trimStart('/'))
            .build()
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .header("x-xiaomi-protocal-flag-cli", "PROTOCAL-HTTP2")
            .header(
                "Cookie",
                "PassportDeviceId=${session.deviceId};" +
                    "userId=${session.userId};" +
                    "serviceToken=${session.serviceToken}",
            )
            .post(
                FormBody.Builder()
                    .add("_nonce", nonce)
                    .add("data", compactData)
                    .add("signature", signature)
                    .build(),
            )
            .build()
        val response = try {
            client.newCall(request).awaitResponse()
        } catch (error: IOException) {
            throw MijiaProviderException("Mi Home network request failed.", error)
        }
        response.use {
            if (!it.isSuccessful) {
                if (it.code == 401 || it.code == 403) {
                    throw MijiaAuthorizationExpiredException()
                }
                throw MijiaProviderException(
                    "Mi Home request failed with HTTP ${it.code}.",
                )
            }
            val body = it.body.string()
            val parsed = try {
                json.parseToJsonElement(body).jsonObject
            } catch (error: IllegalArgumentException) {
                throw MijiaProviderException(
                    "Mi Home returned an invalid response.",
                    error,
                )
            }
            val code = parsed["code"]?.jsonPrimitive?.content?.toIntOrNull()
            if (code != null && code != 0) {
                if (code in AUTHORIZATION_CODES) {
                    throw MijiaAuthorizationExpiredException()
                }
                throw MijiaProviderException(
                    parsed.stringOrNull("message")
                        ?: "Mi Home returned error $code.",
                )
            }
            parsed
        }
    }

    private fun baseUrl(region: String?): okhttp3.HttpUrl {
        val host = if (region.isNullOrBlank() || region == "cn") {
            "api.io.mi.com"
        } else {
            "$region.api.io.mi.com"
        }
        return "https://$host/app/".toHttpUrl()
    }

    private companion object {
        val AUTHORIZATION_CODES = setOf(-3, -6, -10001, -10002)
        const val USER_AGENT =
            "Android-7.1.1-1.0.7-ONEPLUS A3010-136-" +
                "AB56E7B4A2-4"
    }
}
