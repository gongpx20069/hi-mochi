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
)

class AppUpdateClient(
    client: OkHttpClient = OkHttpClient(),
) {
    private val client = client.newBuilder()
        .connectTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .readTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .build()
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun check(): AppUpdate? = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(LATEST_RELEASE_API)
            .header("Accept", "application/vnd.github+json")
            .header("User-Agent", "Mochi-Android/${BuildConfig.VERSION_NAME}")
            .get()
            .build()
        val release = try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    return@withContext null
                }
                val body = response.body
                if (body.contentLength() > MAX_RESPONSE_BYTES) {
                    return@withContext null
                }
                val source = body.source()
                source.request(MAX_RESPONSE_BYTES + 1)
                if (source.buffer.size > MAX_RESPONSE_BYTES) {
                    return@withContext null
                }
                json.decodeFromString<GitHubRelease>(source.readUtf8())
            }
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
        AppUpdate(
            version = latest.toString(),
            releaseUrl = release.htmlUrl,
            notes = release.body.trim().take(MAX_NOTES_CHARS),
        )
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
)

internal const val UPDATE_RELEASE_REPOSITORY = "gongpx20069/hi-mochi"
internal const val LATEST_RELEASE_API =
    "https://api.github.com/repos/" +
        "$UPDATE_RELEASE_REPOSITORY/releases/latest"
private const val TIMEOUT_SECONDS = 10L
private const val MAX_RESPONSE_BYTES = 256L * 1024L
private const val MAX_NOTES_CHARS = 2_000
