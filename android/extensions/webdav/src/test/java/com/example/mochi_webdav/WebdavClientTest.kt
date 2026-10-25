package com.example.mochi_webdav

import java.net.InetAddress
import java.util.concurrent.TimeUnit
import kotlin.io.path.createTempFile
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.cancel
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class WebdavClientTest {
    private val server = MockWebServer()
    private lateinit var config: WebdavConfig
    private lateinit var client: WebdavClient

    @Before fun start() {
        val certificate = HeldCertificate.Builder().commonName("WebDAV fixture")
            .addSubjectAlternativeName("localhost").addSubjectAlternativeName("127.0.0.1").build()
        val serverTls = HandshakeCertificates.Builder().heldCertificate(certificate).build()
        val clientTls = HandshakeCertificates.Builder().addTrustedCertificate(certificate.certificate).build()
        server.useHttps(serverTls.sslSocketFactory(), false)
        server.protocols = listOf(okhttp3.Protocol.HTTP_1_1)
        server.start()
        config = WebdavConfig(server.url("/dav/vault/").toString(), "/", "fixture", "fixture", revision = "one")
        client = WebdavClient(config, OkHttpClient.Builder()
            .sslSocketFactory(clientTls.sslSocketFactory(), clientTls.trustManager)
            .followRedirects(false).retryOnConnectionFailure(false).build())
    }

    @After fun stop() { server.shutdown() }

    @Test fun `paths cannot escape origin or configured root`() {
        val location = client.location
        assertEquals("/dav/vault/notes/a%20b.md", location.url("notes/a b.md").encodedPath)
        for (path in listOf("../secret", "a/../secret", "a\\b", "a//b", "%2e%2e/secret", "%252e%252e/secret", "//host/x")) {
            fails(WebdavError.INVALID_ARGUMENT) { location.url(path) }
        }
        for (href in listOf("https://other.example/dav/vault/a", "/dav/elsewhere/a", "/dav/vaultish/a", "/dav/vault/a?token=x")) {
            fails(WebdavError.SERVER_RESPONSE) { location.relative(href) }
        }
        assertEquals("notes/a b.md", location.relative("/dav/vault/notes/a%20b.md"))
    }

    @Test fun `LAN requires opt in and IP literals cannot bypass DNS policy`() {
        for (ip in listOf("127.0.0.1", "169.254.169.254", "0.0.0.0", "::1", "fe80::1", "192.0.2.1", "2001:db8::1")) {
            assertFalse(ip, webdavAddressAllowed(InetAddress.getByName(ip), true))
        }
        for (ip in listOf("10.0.0.1", "192.168.1.2", "172.16.0.2", "100.64.0.1", "fd00::1")) {
            assertFalse(ip, webdavAddressAllowed(InetAddress.getByName(ip), false))
            assertTrue(ip, webdavAddressAllowed(InetAddress.getByName(ip), true))
        }
        fails(WebdavError.PERMISSION_DENIED) { webdavHttpClient(config.copy(endpoint = "https://127.0.0.1/", allowLan = true)) }
        val transport = webdavHttpClient(config.copy(endpoint = "https://192.168.1.2/", allowLan = true))
        assertFalse(transport.followRedirects)
        assertFalse(transport.followSslRedirects)
        assertFalse(transport.retryOnConnectionFailure)
        assertEquals(java.net.Proxy.NO_PROXY, transport.proxy)
        assertFalse(config.toString().contains("fixture"))
    }

    @Test fun `PROPFIND respects namespaces successful properties and directory slashes`() = runBlocking {
        val body = multistatus(entry("/dav/vault/", true) + entry("/dav/vault/a.md", false, "&quot;v1&quot;"))
        server.enqueue(MockResponse().setResponseCode(207).setChunkedBody(body, 7))
        val listing = client.list("")
        assertEquals(listOf("a.md"), listing.entries.map { it.path })
        assertEquals("\"v1\"", listing.entries.single().etag)
        val request = server.takeRequest()
        assertEquals("PROPFIND", request.method)
        assertEquals("1", request.getHeader("Depth"))
        assertEquals("/dav/vault/", request.path)
        assertNotNull(request.getHeader("Authorization"))
        server.enqueue(MockResponse().setResponseCode(301).addHeader("Location", "/dav/vault/folder/"))
        server.enqueue(MockResponse().setResponseCode(207).setBody(multistatus(entry("/dav/vault/folder/", true))))
        assertTrue(client.stat("folder").directory)
        assertEquals("/dav/vault/folder", server.takeRequest().path)
        assertEquals("/dav/vault/folder/", server.takeRequest().path)
    }

    @Test fun `read rejects oversized chunked data binary text and redirects`() = runBlocking {
        server.enqueue(MockResponse().setChunkedBody("hello", 2).addHeader("ETag", "\"v2\""))
        assertEquals(DavText("hello", "\"v2\""), client.read("a.md"))
        server.enqueue(MockResponse().setChunkedBody("x".repeat(MAX_TEXT_BYTES + 1), 19))
        failsSuspend(WebdavError.LIMIT_EXCEEDED) { client.read("a.md") }
        server.enqueue(MockResponse().setBody("a\u0000b"))
        failsSuspend(WebdavError.UNSUPPORTED) { client.read("a.bin") }
        server.enqueue(MockResponse().setResponseCode(302).addHeader("Location", "https://other.example/"))
        failsSuspend(WebdavError.SERVER_RESPONSE) { client.read("a.md") }
        assertEquals(4, server.requestCount)
    }

    @Test fun `writes are conditional and conflicts or partial results are not success`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(201))
        client.write("a.md", "hello", null)
        assertEquals("*", server.takeRequest().getHeader("If-None-Match"))
        server.enqueue(MockResponse().setResponseCode(412))
        failsSuspend(WebdavError.CONFLICT) { client.write("a.md", "changed", "\"old\"") }
        assertEquals("\"old\"", server.takeRequest().getHeader("If-Match"))
        server.enqueue(MockResponse().setResponseCode(201))
        client.moveOrCopy("a.md", "b.md", "\"old\"", false)
        val move = server.takeRequest()
        assertEquals("MOVE", move.method)
        assertEquals("F", move.getHeader("Overwrite"))
        assertEquals(client.location.url("b.md").toString(), move.getHeader("Destination"))
        server.enqueue(MockResponse().setResponseCode(207))
        failsSuspend(WebdavError.SERVER_RESPONSE) { client.delete("b.md", "\"old\"") }
        failsSuspend(WebdavError.INVALID_ARGUMENT) { client.delete("/", "\"old\"") }
        failsSuspend(WebdavError.INVALID_ARGUMENT) { client.write("a.md", "x", "W/\"old\"") }
        assertEquals(4, server.requestCount)
    }

    @Test fun `server retry hints cannot replay a mutation`() = runBlocking {
        server.dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
            override fun dispatch(request: okhttp3.mockwebserver.RecordedRequest) =
                MockResponse().setResponseCode(503).addHeader("Retry-After", "0")
        }
        val source = createTempFile("webdav-upload").toFile()
        try {
            source.writeText("fixture")
            val mutations = listOf<suspend () -> Unit>(
                { client.write("new.md", "fixture", null) },
                { client.mkdir("folder") },
                { client.delete("old.md", "\"old\"") },
                { client.moveOrCopy("old.md", "new.md", "\"old\"", false) },
                { client.moveOrCopy("old.md", "copy.md", "\"old\"", true) },
                { client.upload("binary.bin", source, null) },
            )
            for ((index, mutation) in mutations.withIndex()) {
                failsSuspend(WebdavError.SERVER_RESPONSE, mutation)
                assertEquals(index + 1, server.requestCount)
            }
        } finally {
            source.delete()
        }
    }

    @Test fun `XML entities bad properties and escaped hrefs fail closed`() {
        val invalid = listOf(
            "<!DOCTYPE x [<!ENTITY e SYSTEM 'file:///fixture'>]><x/>",
            multistatus(entry("/dav/elsewhere/secret", false)),
            multistatus(entry("/dav/vault/a", false).replace("200 OK", "403 Forbidden")),
            multistatus(entry("/dav/vault/a", false) + entry("/dav/vault/a", false)),
            "<d:multistatus xmlns:d=\"DAV:\"><d:response>",
        )
        invalid.forEach { xml ->
            assertThrows(WebdavException::class.java) { parseDavListing(xml, client.location, "") }
        }
    }

    @Test fun `binary size failures and mid body cancellation discard partial files`() = runBlocking {
        val target = createTempFile("webdav-fixture").toFile()
        try {
            server.enqueue(MockResponse().setChunkedBody("too large", 2))
            failsSuspend(WebdavError.LIMIT_EXCEEDED) { client.download("a.bin", target, 3) }
            assertFalse(target.exists())
            server.takeRequest()
            server.enqueue(MockResponse().setBody("x".repeat(100_000)).throttleBody(100, 1, TimeUnit.SECONDS))
            val task = async { client.download("b.bin", target) }
            withTimeout(5_000) { while (server.requestCount < 2) delay(10) }
            task.cancel()
            task.join()
            withTimeout(5_000) { while (target.exists()) delay(10) }
            assertFalse(target.exists())
        } finally {
            target.delete()
        }
    }

    @Test fun `ordinary client rejects the untrusted test certificate`() = runBlocking {
        val untrusted = WebdavClient(config, OkHttpClient.Builder().retryOnConnectionFailure(false).build())
        failsSuspend(WebdavError.NETWORK) { untrusted.read("a.md") }
    }

    @Test fun `tool listing pages are bounded and missing confirmation never dispatches a write`() = runBlocking {
        val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO)
        val cipher = object : WebdavCipher {
            private var value = ""
            override fun encrypt(value: String): String { this.value = value; return "opaque-fixture" }
            override fun decrypt(value: String): String = this.value
        }
        val repository = WebdavRepository(androidx.test.core.app.ApplicationProvider.getApplicationContext(), scope, cipher)
        try {
            repository.save(config, null)
            val body = multistatus(entry("/dav/vault/", true) +
                (0..104).joinToString("") { entry("/dav/vault/file-${it.toString().padStart(3, '0')}.md", false) })
            server.enqueue(MockResponse().setResponseCode(207).setBody(body))
            val first = executeWebdav(repository, "webdav_list", buildJsonObject { put("_connection_id", "one") }) { client }
            assertEquals(100, first.getValue("entries").jsonArray.size)
            assertEquals("100", first.getValue("next_offset").jsonPrimitive.content)
            server.enqueue(MockResponse().setResponseCode(207).setBody(body))
            val last = executeWebdav(repository, "webdav_list", buildJsonObject {
                put("_connection_id", "one"); put("offset", 100)
            }) { client }
            assertEquals(5, last.getValue("entries").jsonArray.size)
            assertNull(last["next_offset"])
            failsSuspend(WebdavError.PERMISSION_DENIED) {
                executeWebdav(repository, "webdav_write", buildJsonObject {
                    put("_connection_id", "one"); put("path", "new.md"); put("content", "fixture")
                }) { client }
            }
            assertEquals(2, server.requestCount)
        } finally {
            scope.cancel()
        }
    }

    private fun entry(href: String, folder: Boolean, etag: String = "") =
        """<d:response><d:href>$href</d:href><d:propstat><d:prop><d:resourcetype>${if (folder) "<d:collection/>" else ""}</d:resourcetype><d:getetag>$etag</d:getetag><d:getcontentlength>5</d:getcontentlength></d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response>"""
    private fun multistatus(body: String) = """<d:multistatus xmlns:d="DAV:">$body</d:multistatus>"""
    private fun fails(code: WebdavError, action: () -> Unit) {
        assertEquals(code, assertThrows(WebdavException::class.java, action).code)
    }
    private suspend fun failsSuspend(code: WebdavError, action: suspend () -> Unit) {
        try { action(); fail("Expected $code") } catch (failure: WebdavException) { assertEquals(code, failure.code) }
    }
}
