package com.example.mochi_pet.core.update

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import java.io.IOException
import java.util.Locale
import org.junit.Test

class AppUpdateTest {
    @Test
    fun `each extension resolves its own universal APK even when the app is already current`() = runBlocking {
        val names = mapOf(
            ExtensionApk.MIJIA to "Mochi-Mijia-Extension-v1.0.0.apk",
            ExtensionApk.TERMUX to "Mochi-Termux-Extension-v1.0.0.apk",
            ExtensionApk.WEBDAV to "Mochi-WebDAV-Extension-v1.0.0.apk",
        )
        val base = "https://github.com/$UPDATE_RELEASE_REPOSITORY/releases/download/v1.0.0/"
        val response = release(
            names.values.map { asset(it, url = base + it) } + asset("Mochi-v1.0.0-universal.apk"),
            tag = "v1.0.0",
        )
        val client = clientFor(response)
        names.forEach { (kind, name) ->
            val download = client.extensionDownload(kind)
            assertEquals(base + name, download.url)
            assertFalse(download.prerelease)
        }
    }

    @Test
    fun `AgentLink resolves the newest public APK including its existing prerelease channel`() = runBlocking {
        val apk = "https://github.com/$AGENTLINK_RELEASE_REPOSITORY/releases/download/0.0.45/agentlink-0.0.45.apk"
        var requested = ""
        val client = clientFor(JsonArray(listOf(
            release(emptyList(), draft = true),
            release(listOf(asset("agentlink-0.0.45.apk", url = apk)), tag = "0.0.45", prerelease = true),
            release(emptyList(), tag = "0.0.44"),
        ))) { requested = it }
        val download = client.extensionDownload(ExtensionApk.AGENTLINK)
        assertEquals(apk, download.url)
        assertTrue(download.prerelease)
        assertEquals("https://api.github.com/repos/$AGENTLINK_RELEASE_REPOSITORY/releases?per_page=10", requested)
    }

    @Test
    fun `missing extension APK and nonstable Mochi releases fail rather than opening a release page`() {
        for (response in listOf(
            release(emptyList()),
            release(listOf(asset("Mochi-Mijia-Extension-v9.0.0.apk")), prerelease = true),
            release(listOf(asset("Mochi-Mijia-Extension-v9.0.0.apk")), draft = true),
        )) {
            assertThrows(IOException::class.java) {
                runBlocking { clientFor(response).extensionDownload(ExtensionApk.MIJIA) }
            }
        }
        assertThrows(IOException::class.java) {
            runBlocking {
                clientFor(JsonArray(listOf(release(emptyList(), prerelease = true))))
                    .extensionDownload(ExtensionApk.AGENTLINK)
            }
        }
    }

    @Test
    fun `APK selection follows device ABI preference and excludes extension packages`() {
        val assets = listOf(
            asset("Mochi-Mijia-Extension-v9.0.0.apk"),
            asset("Mochi-v9.0.0-armeabi-v7a.apk"),
            asset("Mochi-v9.0.0-x86_64.apk"),
            asset("Mochi-v9.0.0-arm64-v8a.apk"),
        )
        assertEquals(
            downloadUrl("Mochi-v9.0.0-arm64-v8a.apk"),
            checkAssets(assets, listOf("arm64-v8a", "armeabi-v7a")).apkUrl,
        )
        assertEquals(
            downloadUrl("Mochi-v9.0.0-x86_64.apk"),
            checkAssets(assets, listOf("x86_64", "x86")).apkUrl,
        )
    }

    @Test
    fun `compatible secondary ABI precedes universal and incompatible APKs are never selected`() {
        val assets = listOf(
            asset("Mochi-v9.0.0-universal.apk"),
            asset("Mochi-v9.0.0-armeabi-v7a.apk"),
        )
        assertEquals(
            downloadUrl("Mochi-v9.0.0-armeabi-v7a.apk"),
            checkAssets(assets, listOf("arm64-v8a", "armeabi-v7a")).apkUrl,
        )
        assertEquals(
            downloadUrl("Mochi-v9.0.0-universal.apk"),
            checkAssets(assets, listOf("x86_64")).apkUrl,
        )
        assertNull(checkAssets(assets.drop(1), listOf("x86_64")).apkUrl)
        val withoutAssets = checkAssets(emptyList(), emptyList())
        assertNull(withoutAssets.apkUrl)
        assertEquals("https://github.com/$UPDATE_RELEASE_REPOSITORY/releases/tag/v9.0.0", withoutAssets.releaseUrl)
    }

    @Test
    fun `APK links must belong to this exact published asset and be ready to download`() {
        val name = "Mochi-v9.0.0-arm64-v8a.apk"
        val invalidAssets = listOf(
            asset(name, url = "https://downloads.example.com/$name"),
            asset(name, url = downloadUrl(name).replace("v9.0.0/", "v8.0.0/")),
            asset(name, url = downloadUrl(name).replace("https:", "http:")),
            asset(name, state = "starter"),
            asset(name, size = 0),
            asset("Mochi-Termux-Extension-v9.0.0.apk"),
        )
        invalidAssets.forEach { assertNull(checkAssets(listOf(it), listOf("arm64-v8a")).apkUrl) }
    }

    @Test
    fun `Chinese system locales use the verified CNB APK and release page`() = runBlocking {
        val name = "Mochi-v9.0.0-arm64-v8a.apk"
        for (languageTag in listOf("zh-CN", "zh-TW", "zh-HK", "zh-Hans-SG")) {
            val requests = mutableListOf<String>()
            val update = checkNotNull(clientFor(
                release(listOf(asset(name), asset("Mochi-v9.0.0-universal.apk"))),
                mirrorStatus = 200,
                mirrorSize = 1024,
            ) { requests += it }.check(listOf("arm64-v8a", "armeabi-v7a"), Locale.forLanguageTag(languageTag)))
            assertEquals("$CNB_RELEASE_ROOT/v9.0.0/$name", update.apkUrl)
            assertEquals("$CNB_RELEASE_PAGE_ROOT/v9.0.0", update.releaseUrl)
            assertEquals(listOf(LATEST_RELEASE_API, update.apkUrl), requests)
        }
    }

    @Test
    fun `non Chinese systems keep GitHub downloads regardless of the app locale`() = runBlocking {
        val previous = Locale.getDefault()
        try {
            Locale.setDefault(Locale.SIMPLIFIED_CHINESE)
            for (languageTag in listOf("en-US", "ja-JP", "fr-FR", "en-CN")) {
                val requests = mutableListOf<String>()
                val name = "Mochi-v9.0.0-universal.apk"
                val update = checkNotNull(clientFor(release(listOf(asset(name)))) { requests += it }
                    .check(listOf("x86"), Locale.forLanguageTag(languageTag)))
                assertEquals(downloadUrl(name), update.apkUrl)
                assertEquals("https://github.com/$UPDATE_RELEASE_REPOSITORY/releases/tag/v9.0.0", update.releaseUrl)
                assertEquals(listOf(LATEST_RELEASE_API), requests)
            }
        } finally {
            Locale.setDefault(previous)
        }
    }

    @Test
    fun `Chinese update waits for the same complete mirrored asset without falling back to GitHub`() = runBlocking {
        val release = release(listOf(asset("Mochi-v9.0.0-universal.apk")))
        for ((status, size) in listOf(404 to 0L, 500 to 1024L, 200 to 0L, 200 to 1023L)) {
            assertNull(clientFor(release, mirrorStatus = status, mirrorSize = size)
                .check(listOf("arm64-v8a"), Locale.CHINESE))
        }
        val update = checkNotNull(clientFor(release, mirrorStatus = 200, mirrorSize = 1024)
            .check(listOf("arm64-v8a"), Locale.CHINESE))
        assertEquals("$CNB_RELEASE_ROOT/v9.0.0/Mochi-v9.0.0-universal.apk", update.apkUrl)
    }

    @Test
    fun `Chinese release page fallback requires a published mirrored checksum manifest`() = runBlocking {
        assertNull(clientFor(release(emptyList())).check(listOf("x86"), Locale.CHINESE))
        val update = checkNotNull(clientFor(
            release(listOf(asset("Mochi-v9.0.0-SHA256SUMS.txt"))),
            mirrorStatus = 200,
            mirrorSize = 1024,
        ).check(listOf("x86"), Locale.CHINESE))
        assertNull(update.apkUrl)
        assertEquals("$CNB_RELEASE_PAGE_ROOT/v9.0.0", update.releaseUrl)
    }

    @Test
    fun `update check targets current Hi Mochi repository`() {
        assertEquals("gongpx20069/hi-mochi", UPDATE_RELEASE_REPOSITORY)
        assertEquals(
            "https://api.github.com/repos/" +
                "gongpx20069/hi-mochi/releases/latest",
            LATEST_RELEASE_API,
        )
    }

    @Test
    fun `semantic versions compare numerically`() {
        assertTrue(
            SemanticVersion.parse("v1.0.10")!! >
                SemanticVersion.parse("1.0.9")!!,
        )
        assertTrue(
            SemanticVersion.parse("2.0.0")!! >
                SemanticVersion.parse("1.99.99")!!,
        )
    }

    @Test
    fun `semantic version accepts release tag`() {
        assertEquals("1.0.1", SemanticVersion.parse("v1.0.1").toString())
        assertNull(SemanticVersion.parse("1.0"))
        assertNull(SemanticVersion.parse("1.0.1-beta"))
    }

    private fun downloadUrl(name: String) =
        "https://github.com/$UPDATE_RELEASE_REPOSITORY/releases/download/v9.0.0/$name"

    private fun asset(name: String, url: String = downloadUrl(name), state: String = "uploaded", size: Long = 1024) =
        buildJsonObject {
            put("name", name)
            put("browser_download_url", url)
            put("state", state)
            put("size", size)
        }

    private fun checkAssets(assets: List<JsonObject>, abis: List<String>): AppUpdate = runBlocking {
        checkNotNull(clientFor(release(assets)).check(abis, Locale.ENGLISH))
    }

    private fun release(assets: List<JsonObject>, tag: String = "v9.0.0", draft: Boolean = false, prerelease: Boolean = false) =
        buildJsonObject {
            put("tag_name", tag)
            put("html_url", "https://github.com/$UPDATE_RELEASE_REPOSITORY/releases/tag/$tag")
            put("draft", draft)
            put("prerelease", prerelease)
            put("assets", JsonArray(assets))
        }

    private fun clientFor(
        response: JsonElement,
        mirrorStatus: Int = 404,
        mirrorSize: Long = 0,
        onRequest: (String) -> Unit = {},
    ): AppUpdateClient {
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            onRequest(chain.request().url.toString())
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                .code(if (chain.request().method == "HEAD") mirrorStatus else 200)
                .header("Content-Length", mirrorSize.toString())
                .message("OK").body(response.toString().toResponseBody()).build()
        }.build()
        return AppUpdateClient(client)
    }
}
