package com.example.mochi_mijia

import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request

class PassportQrClient(
    private val sessionStore: MijiaSessionStore,
    client: OkHttpClient = OkHttpClient(),
    private val nowMillis: () -> Long = System::currentTimeMillis,
    private val accountBaseUrl: HttpUrl =
        "https://account.xiaomi.com/".toHttpUrl(),
) {
    private val cookieJar = MemoryCookieJar()
    private val client = client.newBuilder()
        .cookieJar(cookieJar)
        .followRedirects(true)
        .followSslRedirects(true)
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .build()
    private val json = Json { ignoreUnknownKeys = true }
    private val refreshMutex = Mutex()

    suspend fun begin(): MijiaQrChallenge = withContext(Dispatchers.IO) {
        cookieJar.clear()
        val url = accountBaseUrl.newBuilder()
            .addPathSegments("longPolling/loginUrl")
            .addQueryParameter("_qrsize", "240")
            .addQueryParameter("qs", "?sid=xiaomiio")
            .addQueryParameter("callback", "https://sts.api.io.mi.com/sts")
            .addQueryParameter("sid", "xiaomiio")
            .addQueryParameter("serviceParam", "")
            .addQueryParameter("_locale", "zh_CN")
            .addQueryParameter("_dc", nowMillis().toString())
            .build()
        val response = execute(url)
        requireCodeZero(response)
        MijiaQrChallenge(
            loginUrl = response.requiredString("loginUrl"),
            longPollUrl = response.requiredString("lp"),
            timeoutSeconds = response.longOrNull("timeout") ?: 120L,
        )
    }

    suspend fun complete(challenge: MijiaQrChallenge): MijiaSession =
        withContext(Dispatchers.IO) {
            execute(challenge.longPollUrl.toHttpUrl())
            val login = execute(
                accountBaseUrl.newBuilder()
                    .addPathSegments("pass/serviceLogin")
                    .addQueryParameter("sid", "xiaomiio")
                    .addQueryParameter("_json", "true")
                    .build(),
            )
            requireCodeZero(login)
            val ssecurity = login.requiredString("ssecurity")
            val location = login.requiredString("location")
            executeRaw(location.toHttpUrl()).close()
            val serviceToken = cookieJar.value("serviceToken")
                ?: throw MijiaAuthorizationException(
                    "Xiaomi did not issue a Mi Home service token.",
                )
            val session = MijiaSession(
                userId = login.requiredString("userId"),
                cUserId = login.stringOrNull("cUserId").orEmpty(),
                passToken = login.requiredString("passToken"),
                ssecurity = ssecurity,
                serviceToken = serviceToken,
                deviceId = sessionStore.getOrCreateDeviceId(),
            )
            sessionStore.save(session)
            session
        }

    suspend fun refresh(expiredSession: MijiaSession): MijiaSession =
        refreshMutex.withLock {
            val current = sessionStore.load()
                ?: throw MijiaAuthorizationExpiredException()
            if (current.serviceToken != expiredSession.serviceToken) {
                return@withLock current
            }
            try {
                withContext(Dispatchers.IO) {
                    cookieJar.clear()
                    val cookieHeader = listOf(
                        "userId=${current.userId}",
                        "passToken=${current.passToken}",
                        "deviceId=${current.deviceId}",
                        "sdkVersion=$SDK_VERSION",
                    ).joinToString(";")
                    val login = try {
                        execute(
                            accountBaseUrl.newBuilder()
                                .addPathSegments("pass/serviceLogin")
                                .addQueryParameter("sid", "xiaomiio")
                                .addQueryParameter("_json", "true")
                                .build(),
                            cookieHeader,
                        ).also(::requireCodeZero)
                    } catch (error: MijiaAuthorizationException) {
                        throw MijiaAuthorizationExpiredException()
                    }
                    executeRaw(login.requiredString("location").toHttpUrl())
                        .close()
                    val serviceToken = cookieJar.value("serviceToken")
                        ?: throw MijiaAuthorizationExpiredException()
                    current.copy(
                        userId = login.stringOrNull("userId")
                            ?: current.userId,
                        cUserId = login.stringOrNull("cUserId")
                            ?: current.cUserId,
                        passToken = login.stringOrNull("passToken")
                            ?: current.passToken,
                        ssecurity = login.stringOrNull("ssecurity")
                            ?: current.ssecurity,
                        serviceToken = serviceToken,
                    ).also { sessionStore.save(it) }
                }
            } catch (error: MijiaAuthorizationExpiredException) {
                sessionStore.markAuthorizationExpired()
                throw error
            }
        }

    private suspend fun execute(url: HttpUrl): JsonObject =
        execute(url, null)

    private suspend fun execute(
        url: HttpUrl,
        cookieHeader: String?,
    ): JsonObject =
        executeRaw(url, cookieHeader).use { response ->
            val body = response.body.string().removePrefix(JSON_PREFIX)
            try {
                json.parseToJsonElement(body).jsonObject
            } catch (error: IllegalArgumentException) {
                throw MijiaProviderException(
                    "Xiaomi returned an invalid login response.",
                    error,
                )
            }
        }

    private suspend fun executeRaw(
        url: HttpUrl,
        cookieHeader: String? = null,
    ): okhttp3.Response =
        try {
            client.newCall(
                Request.Builder()
                    .url(url)
                    .header("User-Agent", USER_AGENT)
                    .apply {
                        cookieHeader?.let { header("Cookie", it) }
                    }
                    .get()
                    .build(),
            ).awaitResponse().also { response ->
                if (!response.isSuccessful) {
                    response.close()
                    throw MijiaProviderException(
                        "Xiaomi login failed with HTTP ${response.code}.",
                    )
                }
            }
        } catch (error: IOException) {
            throw MijiaProviderException("Xiaomi login network request failed.", error)
        }

    private fun requireCodeZero(value: JsonObject) {
        if (value["code"]?.jsonPrimitive?.content != "0") {
            throw MijiaAuthorizationException(
                value.stringOrNull("description")
                    ?: "Xiaomi rejected the QR authorization.",
            )
        }
    }

    private companion object {
        const val JSON_PREFIX = "&&&START&&&"
        const val SDK_VERSION = "4.2.29"
        const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 " +
                "XiaoMi/MiuiBrowser/17.3.2"
    }
}

private class MemoryCookieJar : CookieJar {
    private val cookies = LinkedHashMap<String, Cookie>()

    @Synchronized
    override fun saveFromResponse(
        url: HttpUrl,
        cookies: List<Cookie>,
    ) {
        cookies.forEach { cookie ->
            this.cookies["${cookie.domain}|${cookie.path}|${cookie.name}"] =
                cookie
        }
    }

    @Synchronized
    override fun loadForRequest(url: HttpUrl): List<Cookie> {
        val now = System.currentTimeMillis()
        cookies.entries.removeIf { it.value.expiresAt < now }
        return cookies.values.filter { it.matches(url) }
    }

    @Synchronized
    fun value(name: String): String? =
        cookies.values.lastOrNull { it.name == name }?.value

    @Synchronized
    fun clear() {
        cookies.clear()
    }
}

internal fun JsonObject.stringOrNull(name: String): String? =
    this[name]?.jsonPrimitive?.content?.takeIf { it != "null" }

internal fun JsonObject.requiredString(name: String): String =
    stringOrNull(name)?.takeIf(String::isNotBlank)
        ?: throw MijiaProviderException("Xiaomi response is missing $name.")

internal fun JsonObject.longOrNull(name: String): Long? =
    stringOrNull(name)?.toLongOrNull()
