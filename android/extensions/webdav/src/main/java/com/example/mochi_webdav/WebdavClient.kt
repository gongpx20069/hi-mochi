package com.example.mochi_webdav

import java.io.File
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.StringReader
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.Serializable
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Credentials
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserException
import org.xmlpull.v1.XmlPullParserFactory

internal const val MAX_TRANSFER_BYTES = 32L * 1_024 * 1_024
internal const val MAX_TEXT_BYTES = 32 * 1_024
private const val MAX_LISTING_BYTES = 2L * 1_024 * 1_024

@Serializable
internal data class DavEntry(
    val path: String,
    val directory: Boolean,
    val bytes: Long? = null,
    val etag: String? = null,
    val contentType: String? = null,
)

@Serializable
internal data class DavListing(val entries: List<DavEntry>, val truncated: Boolean)

internal data class DavText(val text: String, val etag: String?)

internal class WebdavClient(
    private val config: WebdavConfig,
    private val client: OkHttpClient = webdavHttpClient(config),
) {
    val location = WebdavLocation(config)

    suspend fun list(path: String): DavListing {
        val entries = properties(path, "1")
        val current = WebdavLocation.segments(path).joinToString("/")
        requireWebdav(entries.any { it.path == current && it.directory }, WebdavError.SERVER_RESPONSE)
        return DavListing(
            entries.filter { it.path != current },
            location.rootUrl.host == "dav.jianguoyun.com" && entries.size >= 750,
        )
    }

    suspend fun stat(path: String): DavEntry =
        properties(path, "0").singleOrNull { it.path == WebdavLocation.segments(path).joinToString("/") }
            ?: throw WebdavException(WebdavError.SERVER_RESPONSE)

    private suspend fun properties(path: String, depth: String): List<DavEntry> {
        fun builder(directory: Boolean) = request("PROPFIND", path,
            PROPERTIES.toRequestBody("application/xml; charset=utf-8".toMediaType()))
            .url(location.url(path, directory)).header("Depth", depth)
        val original = builder(depth == "1" || path.endsWith('/')).build()
        var slashRedirect = false
        fun parse(response: Response): List<DavEntry> {
            expect(response, setOf(207))
            return parseDavListing(readBounded(response, MAX_LISTING_BYTES).toString(Charsets.UTF_8), location, path)
        }
        val entries = exchange(original) { response ->
            if (response.code in setOf(301, 308)) {
                val target = response.header("Location")?.let(original.url::resolve)
                requireWebdav(target == location.url(path, true) && target != original.url, WebdavError.SERVER_RESPONSE)
                slashRedirect = true
                emptyList()
            } else parse(response)
        }
        // Only canonicalize this exact collection's trailing slash, once, for a read-only PROPFIND.
        return if (slashRedirect) exchange(builder(true).build(), consume = ::parse) else entries
    }

    suspend fun read(path: String): DavText = exchange(request("GET", path).build()) { response ->
        expect(response, setOf(200))
        val bytes = readBounded(response, MAX_TEXT_BYTES.toLong())
        val decoder = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        val text = try {
            decoder.decode(ByteBuffer.wrap(bytes)).toString()
        } catch (_: java.nio.charset.CharacterCodingException) {
            throw WebdavException(WebdavError.UNSUPPORTED)
        }
        requireWebdav(text.none { it == '\u0000' || it.isISOControl() && it !in "\n\r\t" }, WebdavError.UNSUPPORTED)
        DavText(text, response.header("ETag"))
    }

    suspend fun write(path: String, text: String, etag: String?): Int {
        requireWebdav(text.toByteArray(Charsets.UTF_8).size <= MAX_TEXT_BYTES, WebdavError.LIMIT_EXCEEDED)
        return put(path, text.toRequestBody("text/plain; charset=utf-8".toMediaType()), etag)
    }

    suspend fun upload(path: String, file: File, etag: String?): Int {
        requireWebdav(file.length() <= MAX_TRANSFER_BYTES, WebdavError.LIMIT_EXCEEDED)
        return put(path, file.asRequestBody("application/octet-stream".toMediaType()), etag)
    }

    private suspend fun put(path: String, body: RequestBody, etag: String?): Int {
        nonRoot(path)
        val builder = request("PUT", path, body)
        if (etag == null) builder.header("If-None-Match", "*") else builder.header("If-Match", strongEtag(etag))
        return exchange(builder.build()) { expect(it, setOf(200, 201, 204)); it.code }
    }

    suspend fun mkdir(path: String): Int {
        nonRoot(path)
        return exchange(request("MKCOL", path, EMPTY_BODY).url(location.url(path, true)).header("If-None-Match", "*").build()) {
            expect(it, setOf(201)); it.code
        }
    }

    suspend fun delete(path: String, etag: String, directory: Boolean = false): Int {
        nonRoot(path)
        return exchange(request("DELETE", path).url(location.url(path, directory)).header("If-Match", strongEtag(etag)).build()) {
            // A 207 can describe partial deletion; never report it as full success.
            expect(it, setOf(200, 204)); it.code
        }
    }

    suspend fun moveOrCopy(path: String, destination: String, etag: String, copy: Boolean, directory: Boolean = false): Int {
        nonRoot(path)
        nonRoot(destination)
        val sourceParts = WebdavLocation.segments(path)
        val destinationParts = WebdavLocation.segments(destination)
        requireWebdav(destinationParts.take(sourceParts.size) != sourceParts &&
            sourceParts.take(destinationParts.size) != destinationParts)
        val request = request(if (copy) "COPY" else "MOVE", path)
            .url(location.url(path, directory))
            .header("Destination", location.url(destination, directory).toString())
            .header("Overwrite", "F")
            .header("If-Match", strongEtag(etag))
            .header("Depth", "infinity").build()
        return exchange(request) { expect(it, setOf(201, 204)); it.code }
    }

    suspend fun download(path: String, destination: File, maximum: Long = MAX_TRANSFER_BYTES): Long {
        var completed = false
        try {
            return exchange(request("GET", path).build(), discard = {
                if (destination.exists() && !destination.delete()) android.util.Log.w("MochiWebdav", "Cancelled transfer cleanup failed.")
            }) { response ->
                expect(response, setOf(200))
                val body = response.body
                requireWebdav(body.contentLength() <= maximum, WebdavError.LIMIT_EXCEEDED)
                var total = 0L
                body.byteStream().use { input ->
                    destination.outputStream().use { output ->
                        val buffer = ByteArray(64 * 1_024)
                        while (true) {
                            val count = input.read(buffer)
                            if (count < 0) break
                            total += count
                            requireWebdav(total <= maximum, WebdavError.LIMIT_EXCEEDED)
                            output.write(buffer, 0, count)
                        }
                    }
                }
                total
            }.also { completed = true }
        } finally {
            if (!completed && destination.exists() && !destination.delete()) {
                android.util.Log.w("MochiWebdav", "Incomplete transfer cleanup failed.")
            }
        }
    }

    private fun request(method: String, path: String, body: RequestBody? = null): Request.Builder {
        // retryOnConnectionFailure(false) alone does not prevent HTTP 503 follow-ups.
        val requestBody = if (method in setOf("PUT", "MKCOL", "DELETE", "MOVE", "COPY")) {
            val source = body ?: EMPTY_BODY
            object : RequestBody() {
                override fun contentType() = source.contentType()
                override fun contentLength() = source.contentLength()
                override fun writeTo(sink: okio.BufferedSink) = source.writeTo(sink)
                override fun isOneShot() = true
            }
        } else body
        return Request.Builder().url(location.url(path)).method(method, requestBody)
            .header("Authorization", Credentials.basic(config.username, config.password, Charsets.UTF_8))
            .header("Accept", "*/*")
    }

    // Keep cancellation attached until body consumption finishes, not merely until headers arrive.
    private suspend fun <T> exchange(request: Request, discard: () -> Unit = {}, consume: (Response) -> T): T =
        suspendCancellableCoroutine { continuation ->
            val call = client.newCall(request)
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (continuation.isActive) continuation.resumeWithException(WebdavException(WebdavError.NETWORK))
                }

                override fun onResponse(call: Call, response: Response) {
                    if (!continuation.isActive) {
                        response.close()
                        return
                    }
                    try {
                        val result = response.use(consume)
                        if (continuation.isActive) continuation.resume(result) { _, _, _ -> discard() }
                        else discard()
                    } catch (error: WebdavException) {
                        discard()
                        if (continuation.isActive) continuation.resumeWithException(error)
                    } catch (_: IOException) {
                        discard()
                        if (continuation.isActive) continuation.resumeWithException(WebdavException(WebdavError.NETWORK))
                    }
                }
            })
        }

    companion object {
        private val EMPTY_BODY = ByteArray(0).toRequestBody()
        private val PROPERTIES = """<?xml version="1.0"?><d:propfind xmlns:d="DAV:"><d:prop><d:resourcetype/><d:getcontentlength/><d:getetag/><d:getcontenttype/></d:prop></d:propfind>"""

        private fun nonRoot(path: String) = requireWebdav(WebdavLocation.segments(path).isNotEmpty())

        fun strongEtag(value: String): String {
            requireWebdav(value.length in 2..256 && value.startsWith('"') && value.endsWith('"') &&
                value.substring(1, value.length - 1).all { it.code in 33..126 && it != '"' })
            return value
        }

        private fun expect(response: Response, statuses: Set<Int>) {
            if (response.code in statuses) return
            val error = when (response.code) {
                401, 403 -> WebdavError.AUTHORIZATION
                404 -> WebdavError.NOT_FOUND
                409, 412, 423 -> WebdavError.CONFLICT
                405, 501 -> WebdavError.UNSUPPORTED
                413, 429, 507 -> WebdavError.LIMIT_EXCEEDED
                else -> WebdavError.SERVER_RESPONSE
            }
            throw WebdavException(error, response.code)
        }

        private fun readBounded(response: Response, maximum: Long): ByteArray {
            val body = response.body
            requireWebdav(body.contentLength() <= maximum, WebdavError.LIMIT_EXCEEDED)
            val output = ByteArrayOutputStream()
            body.byteStream().use { input ->
                val buffer = ByteArray(8_192)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    requireWebdav(output.size().toLong() + count <= maximum, WebdavError.LIMIT_EXCEEDED)
                    output.write(buffer, 0, count)
                }
            }
            return output.toByteArray()
        }
    }
}

internal fun parseDavListing(xml: String, location: WebdavLocation, requestedPath: String): List<DavEntry> {
    requireWebdav(!xml.contains("<!DOCTYPE", true) && !xml.contains("<!ENTITY", true), WebdavError.SERVER_RESPONSE)
    try {
        val parser = XmlPullParserFactory.newInstance().apply { isNamespaceAware = true }.newPullParser()
        parser.setInput(StringReader(xml))
        val entries = mutableListOf<DavEntry>()
        var href: String? = null
        var props = mutableMapOf<String, String>()
        var successful = mutableMapOf<String, String>()
        var inProps = false
        var status: String? = null
        var responseStatus: String? = null
        var sawRoot = false
        var closedRoot = false
        val requested = WebdavLocation.segments(requestedPath).joinToString("/")
        while (parser.eventType != XmlPullParser.END_DOCUMENT) {
            requireWebdav(parser.depth <= 16, WebdavError.SERVER_RESPONSE)
            if (parser.eventType == XmlPullParser.START_TAG && parser.depth == 1) {
                requireWebdav(!sawRoot && parser.namespace == "DAV:" && parser.name == "multistatus",
                    WebdavError.SERVER_RESPONSE)
            }
            if (parser.eventType == XmlPullParser.START_TAG && parser.namespace == "DAV:") {
                when (parser.name) {
                    "multistatus" -> sawRoot = true
                    "response" -> { href = null; successful = mutableMapOf(); responseStatus = null }
                    "propstat" -> { inProps = true; props = mutableMapOf(); status = null }
                    "href" -> { href = parser.nextText(); requireWebdav(href.length <= 8_192, WebdavError.SERVER_RESPONSE) }
                    "status" -> if (inProps) status = parser.nextText() else responseStatus = parser.nextText()
                    "resourcetype" -> if (inProps) props["directory"] = "false"
                    "collection" -> if (inProps) props["directory"] = "true"
                    "getcontentlength", "getetag", "getcontenttype" -> if (inProps) {
                        val key = parser.name
                        val value = parser.nextText()
                        requireWebdav(value.length <= 512, WebdavError.SERVER_RESPONSE)
                        props[key] = value
                    }
                }
            } else if (parser.eventType == XmlPullParser.END_TAG && parser.namespace == "DAV:") {
                when (parser.name) {
                    "multistatus" -> if (parser.depth == 1) closedRoot = true
                    "propstat" -> { if (status?.matches(Regex("HTTP/\\S+ 200(?: .*)?")) == true) successful.putAll(props); inProps = false }
                    "response" -> {
                        requireWebdav(responseStatus == null && href != null && "directory" in successful, WebdavError.SERVER_RESPONSE)
                        val path = location.relative(requireNotNull(href))
                        requireWebdav(path == requested || path.substringBeforeLast('/', "") == requested, WebdavError.SERVER_RESPONSE)
                        requireWebdav(entries.none { it.path == path } && entries.size < 1_000, WebdavError.LIMIT_EXCEEDED)
                        val size = successful["getcontentlength"]?.toLongOrNull()
                        requireWebdav(size == null || size >= 0, WebdavError.SERVER_RESPONSE)
                        entries += DavEntry(path, successful["directory"] == "true", size,
                            successful["getetag"]?.takeIf { it.isNotBlank() }, successful["getcontenttype"])
                    }
                }
            }
            parser.next()
        }
        requireWebdav(sawRoot && closedRoot, WebdavError.SERVER_RESPONSE)
        return entries
    } catch (_: XmlPullParserException) {
        throw WebdavException(WebdavError.SERVER_RESPONSE)
    } catch (_: IOException) {
        throw WebdavException(WebdavError.SERVER_RESPONSE)
    }
}
