package com.example.mochi_pet.core.web

import java.io.IOException
import java.net.InetAddress
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl

open class WebContentException(message: String, cause: Throwable? = null) :
    Exception(message, cause)

class WebAccessDeniedException(message: String) :
    WebContentException(message)

object PublicWebUrlPolicy {
    fun validate(rawUrl: String): HttpUrl {
        val url = rawUrl.trim().toHttpUrl()
        if (url.scheme != "https") {
            throw WebAccessDeniedException("Only HTTPS web URLs are allowed")
        }
        if (url.username.isNotEmpty() || url.password.isNotEmpty()) {
            throw WebAccessDeniedException(
                "Web URLs must not contain credentials",
            )
        }
        if (url.port != 80 && url.port != 443) {
            throw WebAccessDeniedException(
                "Only standard web ports are allowed",
            )
        }
        val host = url.host.lowercase()
        if (
            host == "localhost" ||
            host.endsWith(".localhost") ||
            host.endsWith(".local")
        ) {
            throw WebAccessDeniedException("Local network URLs are not allowed")
        }
        val addresses = try {
            InetAddress.getAllByName(host)
        } catch (error: IOException) {
            throw WebContentException("Could not resolve the web host", error)
        }
        if (addresses.isEmpty() || addresses.any(InetAddress::isPrivateTarget)) {
            throw WebAccessDeniedException(
                "Private or local network URLs are not allowed",
            )
        }
        return url
    }
}

private fun InetAddress.isPrivateTarget(): Boolean {
    if (
        isAnyLocalAddress ||
        isLoopbackAddress ||
        isLinkLocalAddress ||
        isSiteLocalAddress ||
        isMulticastAddress
    ) {
        return true
    }
    val bytes = address
    return bytes.size == 16 && (bytes[0].toInt() and 0xFE) == 0xFC
}
