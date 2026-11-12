package com.example.mochi_pet.core.update

import com.example.mochi_pet.BuildConfig
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request

data class AppUpdate(
    val version: String,
    val releaseUrl: String,
    val notes: String,
    val apkUrl: String?,
)

enum class ExtensionApk { MIJIA, TERMUX, WEBDAV, AGENTLINK }

data class ApkDownload(val url: String, val prerelease: Boolean)

class AppUpdateClient(
    client: OkHttpClient = OkHttpClient(),
) {
    private val client = client.newBuilder()
        .connectTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .readTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .build()
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun check(supportedAbis: List<String>): AppUpdate? = withContext(Dispatchers.IO) {
        val release = try {
            json.decodeFromString<GitHubRelease>(fetch(LATEST_RELEASE_API))
        } catch (_: IOException) {
            return@withContext null
        } catch (_: SerializationException) {
            return@withContext null
        }
        if (release.draft || release.prerelease) {
            return@withContext null
        }
        val latest = SemanticVersion.parse(release.tagName)
            ?: return@withContext null
        val current = SemanticVersion.parse(BuildConfig.VERSION_NAME)
            ?: return@withContext null
        if (latest <= current) {
            return@withContext null
        }
        val apkUrl = (supportedAbis + "universal").firstNotNullOfOrNull { abi ->
            release.apkUrl(UPDATE_RELEASE_REPOSITORY, "Mochi-v$latest-$abi.apk")
        }
        AppUpdate(
            version = latest.toString(),
            releaseUrl = release.htmlUrl,
            notes = release.body.trim().take(MAX_NOTES_CHARS),
            apkUrl = apkUrl,
        )
    }

    suspend fun extensionDownload(extension: ExtensionApk): ApkDownload = withContext(Dispatchers.IO) {
        val repository = if (extension == ExtensionApk.AGENTLINK) AGENTLINK_RELEASE_REPOSITORY else UPDATE_RELEASE_REPOSITORY
        val release = try {
            if (extension == ExtensionApk.AGENTLINK) {
                json.decodeFromString<List<GitHubRelease>>(
                    fetch("https://api.github.com/repos/$repository/releases?per_page=10"),
                ).firstOrNull { !it.draft } ?: throw IOException("No published AgentLink release is available.")
            } else {
                json.decodeFromString<GitHubRelease>(fetch(LATEST_RELEASE_API))
            }
        } catch (error: SerializationException) {
            throw IOException("Release metadata is invalid.", error)
        }
        if (release.draft || (release.prerelease && extension != ExtensionApk.AGENTLINK)) {
            throw IOException("A stable extension release is not available.")
        }
        val version = SemanticVersion.parse(release.tagName) ?: throw IOException("Release version is invalid.")
        val name = when (extension) {
            ExtensionApk.MIJIA -> "Mochi-Mijia-Extension-v$version.apk"
            ExtensionApk.TERMUX -> "Mochi-Termux-Extension-v$version.apk"
            ExtensionApk.WEBDAV -> "Mochi-WebDAV-Extension-v$version.apk"
            ExtensionApk.AGENTLINK -> "agentlink-$version.apk"
        }
        val url = release.apkUrl(repository, name) ?: throw IOException("The extension APK is not available in this release.")
        ApkDownload(url, release.prerelease)
    }

    private fun fetch(url: String): String {
        val request = Request.Builder()
            .url(url)
            .header("Accept", "application/vnd.github+json")
            .header("User-Agent", "Mochi-Android/${BuildConfig.VERSION_NAME}")
            .get()
            .build()
        return client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("Release request failed (HTTP ${response.code}).")
            val body = response.body
            if (body.contentLength() > MAX_RESPONSE_BYTES) throw IOException("Release metadata is too large.")
            val source = body.source()
            source.request(MAX_RESPONSE_BYTES + 1)
            if (source.buffer.size > MAX_RESPONSE_BYTES) throw IOException("Release metadata is too large.")
            source.readUtf8()
        }
    }
}

internal data class SemanticVersion(
    val major: Int,
    val minor: Int,
    val patch: Int,
) : Comparable<SemanticVersion> {
    override fun compareTo(other: SemanticVersion): Int =
        compareValuesBy(this, other, { it.major }, { it.minor }, { it.patch })

    override fun toString(): String = "$major.$minor.$patch"

    companion object {
        private val PATTERN = Regex("^v?(\\d+)\\.(\\d+)\\.(\\d+)$")

        fun parse(value: String): SemanticVersion? {
            val match = PATTERN.matchEntire(value.trim()) ?: return null
            return SemanticVersion(
                major = match.groupValues[1].toIntOrNull() ?: return null,
                minor = match.groupValues[2].toIntOrNull() ?: return null,
                patch = match.groupValues[3].toIntOrNull() ?: return null,
            )
        }
    }
}

@Serializable
private data class GitHubRelease(
    @SerialName("tag_name")
    val tagName: String,
    @SerialName("html_url")
    val htmlUrl: String,
    val body: String = "",
    val draft: Boolean = false,
    val prerelease: Boolean = false,
    val assets: List<GitHubReleaseAsset> = emptyList(),
) {
    fun apkUrl(repository: String, name: String): String? {
        val expectedUrl = "https://github.com/$repository/releases/download/$tagName/$name"
        return assets.firstOrNull {
            it.name == name && it.state == "uploaded" && it.size > 0 && it.browserDownloadUrl == expectedUrl
        }?.browserDownloadUrl
    }
}

@Serializable
private data class GitHubReleaseAsset(
    val name: String,
    @SerialName("browser_download_url")
    val browserDownloadUrl: String,
    val state: String,
    val size: Long,
)

internal const val UPDATE_RELEASE_REPOSITORY = "gongpx20069/hi-mochi"
internal const val AGENTLINK_RELEASE_REPOSITORY = "gongpx20069/android-agent-link"
internal const val LATEST_RELEASE_API =
    "https://api.github.com/repos/" +
        "$UPDATE_RELEASE_REPOSITORY/releases/latest"
private const val TIMEOUT_SECONDS = 10L
private const val MAX_RESPONSE_BYTES = 256L * 1024L
private const val MAX_NOTES_CHARS = 2_000
