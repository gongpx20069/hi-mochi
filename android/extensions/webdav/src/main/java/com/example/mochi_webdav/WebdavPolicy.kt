package com.example.mochi_webdav

import java.net.InetAddress
import java.net.Proxy
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit
import kotlinx.serialization.Serializable
import okhttp3.Dns
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient

internal enum class WebdavError {
    INVALID_ARGUMENT, PERMISSION_DENIED, AUTHORIZATION, NOT_FOUND, CONFLICT,
    LIMIT_EXCEEDED, UNSUPPORTED, NETWORK, TIMEOUT, CANCELLED, STORAGE, SERVER_RESPONSE,
}

internal class WebdavException(
    val code: WebdavError,
    val httpStatus: Int? = null,
) : Exception(code.name)

@Serializable
internal data class WebdavConfig(
    val endpoint: String,
    val root: String,
    val username: String,
    val password: String,
    val allowLan: Boolean = false,
    val revision: String,
) {
    override fun toString(): String = "WebdavConfig(redacted)"
}

internal fun requireWebdav(condition: Boolean, error: WebdavError = WebdavError.INVALID_ARGUMENT) {
    if (!condition) throw WebdavException(error)
}

internal class WebdavLocation(config: WebdavConfig) {
    val rootUrl: HttpUrl

    init {
        val endpoint = config.endpoint.toHttpUrlOrNull() ?: throw WebdavException(WebdavError.INVALID_ARGUMENT)
        requireWebdav(endpoint.isHttps && endpoint.username.isEmpty() && endpoint.password.isEmpty() &&
            endpoint.query == null && endpoint.fragment == null)
        endpoint.pathSegments.filter { it.isNotEmpty() }.forEach(::validateSegment)
        val base = endpoint.newBuilder()
        if (!endpoint.encodedPath.endsWith('/')) base.addPathSegment("")
        segments(config.root).forEach(base::addPathSegment)
        rootUrl = base.addPathSegment("").build()
        requireWebdav(config.username.isNotBlank() && config.username.length <= 256 && ':' !in config.username &&
            config.username.none { it.isISOControl() })
        requireWebdav(config.password.isNotEmpty() && config.password.length <= 4_096 &&
            config.password.none { it.isISOControl() })
    }

    fun url(path: String, directory: Boolean = false): HttpUrl {
        val builder = rootUrl.newBuilder()
        segments(path).forEach(builder::addPathSegment)
        if (directory) builder.addPathSegment("")
        return builder.build()
    }

    fun relative(href: String): String {
        requireWebdav(href.length <= 8_192, WebdavError.SERVER_RESPONSE)
        val url = rootUrl.resolve(href) ?: throw WebdavException(WebdavError.SERVER_RESPONSE)
        requireWebdav(url.scheme == rootUrl.scheme && url.host == rootUrl.host && url.port == rootUrl.port &&
            url.username.isEmpty() && url.password.isEmpty() && url.query == null && url.fragment == null,
            WebdavError.SERVER_RESPONSE)
        val root = rootUrl.pathSegments.dropLast(1)
        val path = url.pathSegments.let { if (it.lastOrNull() == "") it.dropLast(1) else it }
        requireWebdav(path.take(root.size) == root, WebdavError.SERVER_RESPONSE)
        path.forEach(::validateSegment)
        return path.drop(root.size).joinToString("/").also {
            requireWebdav(it.length <= 2_048, WebdavError.SERVER_RESPONSE)
        }
    }

    companion object {
        fun segments(path: String): List<String> {
            requireWebdav(path.length <= 2_048 && !path.contains("://") && !path.startsWith("//"))
            return path.trim('/').takeIf { it.isNotEmpty() }?.split('/')?.onEach(::validateSegment).orEmpty()
        }

        private fun validateSegment(value: String) {
            requireWebdav(value.isNotEmpty() && value != "." && value != ".." &&
                value.none { it.isISOControl() || it == '/' || it == '\\' } &&
                !Regex("(?i)%(?:2e|2f|5c|00|25)").containsMatchIn(value))
        }
    }
}

internal fun webdavAddressAllowed(address: InetAddress, allowLan: Boolean): Boolean {
    if (address.isAnyLocalAddress || address.isLoopbackAddress || address.isLinkLocalAddress || address.isMulticastAddress) return false
    val bytes = address.address.map { it.toInt() and 255 }
    if (bytes.size == 4) {
        val a = bytes[0]
        val b = bytes[1]
        if (a == 0 || a == 127 || a >= 224 || a == 169 && b == 254 ||
            a == 192 && b == 0 || a == 198 && b in 18..19 ||
            a == 198 && b == 51 && bytes[2] == 100 || a == 203 && b == 0 && bytes[2] == 113) return false
        val private = a == 10 || a == 172 && b in 16..31 || a == 192 && b == 168 || a == 100 && b in 64..127
        return !private || allowLan
    }
    if (bytes.size != 16) return false
    if (bytes[0] and 254 == 252) return allowLan
    // Only global unicast; exclude documentation, transition and embedded-IPv4 ranges.
    return bytes[0] in 32..63 && !(bytes[0] == 32 && bytes[1] == 2) &&
        !(bytes[0] == 32 && bytes[1] == 1 && (bytes[2] < 2 || bytes[2] == 13 && bytes[3] == 184))
}

internal fun webdavHttpClient(config: WebdavConfig): OkHttpClient {
    val location = WebdavLocation(config)
    val host = location.rootUrl.host
    // OkHttp skips Dns.lookup for IP literals, so enforce the same policy here.
    if (':' in host || host.matches(Regex("[0-9.]+"))) {
        requireWebdav(webdavAddressAllowed(InetAddress.getByName(host), config.allowLan), WebdavError.PERMISSION_DENIED)
    }
    return OkHttpClient.Builder()
        .proxy(Proxy.NO_PROXY)
        .dns(Dns { hostname ->
            if (hostname != location.rootUrl.host) throw UnknownHostException("WebDAV host is outside the configured connection.")
            val addresses = Dns.SYSTEM.lookup(hostname)
            if (addresses.isEmpty() || addresses.any { !webdavAddressAllowed(it, config.allowLan) }) {
                throw UnknownHostException("WebDAV address is not permitted. Check the local-network setting.")
            }
            addresses
        })
        .followRedirects(false)
        .followSslRedirects(false)
        .retryOnConnectionFailure(false)
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .callTimeout(55, TimeUnit.SECONDS)
        .build()
}
