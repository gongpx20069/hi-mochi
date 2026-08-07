package com.example.mochi_pet.core.skills

import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

data class MarketSkillSummary(
    val id: String,
    val skillId: String,
    val name: String,
    val installs: Long,
    val source: String,
    val installWindow: InstallWindow = InstallWindow.ALL_TIME,
)

enum class InstallWindow {
    ALL_TIME,
    LAST_24_HOURS,
}

data class DownloadedSkill(
    val marketId: String,
    val name: String,
    val description: String,
    val content: String,
    val source: String,
    val sourceUrl: String,
    val version: String?,
    val digest: String,
)

interface SkillMarketClient {
    suspend fun search(query: String): List<MarketSkillSummary>

    suspend fun popular(): List<MarketSkillSummary>

    suspend fun download(summary: MarketSkillSummary): DownloadedSkill

    suspend fun downloadInstalled(skill: MochiSkill): DownloadedSkill
}

class SkillsShClient(
    private val client: OkHttpClient = OkHttpClient(),
    private val searchUrl: String = SEARCH_URL,
    private val trendingUrl: String = TRENDING_URL,
    private val gitHubApiBaseUrl: String = GITHUB_API_BASE_URL,
    private val rawGitHubBaseUrl: String = RAW_GITHUB_BASE_URL,
) : SkillMarketClient {
    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun search(query: String): List<MarketSkillSummary> {
        val normalized = query.trim()
        require(normalized.isNotEmpty()) { "Search query must not be empty" }
        val url = searchUrl.toHttpUrl().newBuilder()
            .addQueryParameter("q", normalized)
            .addQueryParameter("limit", SEARCH_LIMIT.toString())
            .build()
        val response = get(url.toString(), MAX_SEARCH_BYTES)
        return decode<SearchResponse>(response).skills.map {
            MarketSkillSummary(
                id = it.id,
                skillId = it.skillId,
                name = it.name,
                installs = it.installs,
                source = it.source,
            )
        }
    }

    override suspend fun popular(): List<MarketSkillSummary> {
        val trending = parseTrending(
            get(trendingUrl, MAX_TRENDING_BYTES),
        )
        return if (trending.isEmpty()) {
            search(POPULAR_FALLBACK_QUERY).sortedByDescending {
                it.installs
            }
        } else {
            trending
        }
    }

    override suspend fun download(
        summary: MarketSkillSummary,
    ): DownloadedSkill {
        val sourceUrl = if (summary.source.contains('/')) {
            resolveGitHubSkill(summary.source, summary.skillId)
        } else {
            resolveWellKnownSkill(summary.source, summary.skillId)
        }
        return downloadFromUrl(
            marketId = summary.id,
            fallbackName = summary.name,
            source = summary.source,
            sourceUrl = sourceUrl,
        )
    }

    override suspend fun downloadInstalled(skill: MochiSkill): DownloadedSkill =
        downloadFromUrl(
            marketId = skill.id,
            fallbackName = skill.name,
            source = skill.source,
            sourceUrl = skill.sourceUrl,
        )

    private suspend fun downloadFromUrl(
        marketId: String,
        fallbackName: String,
        source: String,
        sourceUrl: String,
    ): DownloadedSkill {
        val content = get(sourceUrl, MAX_SKILL_BYTES).trim()
        val metadata = parseFrontmatter(content)
        return DownloadedSkill(
            marketId = marketId,
            name = metadata["name"] ?: fallbackName,
            description = metadata["description"]
                ?.trim('"')
                ?: "Installed from skills.sh",
            content = content,
            source = source,
            sourceUrl = sourceUrl,
            version = metadata["version"],
            digest = sha256(content),
        )
    }

    private suspend fun resolveGitHubSkill(
        source: String,
        skillId: String,
    ): String {
        val parts = source.split('/')
        require(parts.size == 2) { "Unsupported skills.sh source" }
        val repositoryApi =
            "${gitHubApiBaseUrl.trimEnd('/')}/repos/${parts[0]}/${parts[1]}"
        val repository = decode<GitHubRepository>(
            get(repositoryApi, MAX_METADATA_BYTES),
        )
        val treeApi =
            "$repositoryApi/git/trees/${repository.defaultBranch}?recursive=1"
        val tree = decode<GitHubTree>(
            get(treeApi, MAX_TREE_BYTES),
        )
        val candidates = tree.tree
            .asSequence()
            .filter { it.type == "blob" && it.path.endsWith("/SKILL.md") }
            .filter {
                it.path.substringBeforeLast('/')
                    .substringAfterLast('/') == skillId
            }
            .map(GitHubTreeItem::path)
            .toList()
        val path = candidates.minByOrNull(String::length)
            ?: throw SkillMarketException(
                "skills.sh source did not contain $skillId/SKILL.md",
            )
        return "${rawGitHubBaseUrl.trimEnd('/')}/${parts[0]}/${parts[1]}/" +
            "${repository.defaultBranch}/$path"
    }

    private suspend fun resolveWellKnownSkill(
        source: String,
        skillId: String,
    ): String {
        for (path in WELL_KNOWN_PATHS) {
            try {
                val index = decode<WellKnownIndex>(
                    get("https://$source$path", MAX_METADATA_BYTES),
                )
                return index.skills.firstOrNull { it.name == skillId }?.url
                    ?: continue
            } catch (error: SkillMarketException) {
                continue
            }
        }
        throw SkillMarketException(
            "skills.sh source does not expose Agent Skills discovery",
        )
    }

    private suspend fun get(
        url: String,
        maxBytes: Long,
    ): String {
        val request = Request.Builder()
            .url(url)
            .header("Accept", "application/json, text/markdown, text/plain")
            .header("User-Agent", "Mochi-Android")
            .get()
            .build()
        val response = try {
            client.newCall(request).await()
        } catch (error: IOException) {
            throw SkillMarketException("Skill market network request failed")
        }

        response.use {
            if (!it.isSuccessful) {
                throw SkillMarketException(
                    "Skill market request failed with HTTP ${it.code}",
                )
            }
            val body = it.body
                ?: throw SkillMarketException("Skill market response was empty")
            if (body.contentLength() > maxBytes) {
                throw SkillMarketException("Skill market response was too large")
            }
            val source = body.source()
            source.request(maxBytes + 1)
            if (source.buffer.size > maxBytes) {
                throw SkillMarketException("Skill market response was too large")
            }
            return source.readUtf8()
        }
    }

    private inline fun <reified T> decode(content: String): T =
        try {
            json.decodeFromString<T>(content)
        } catch (error: SerializationException) {
            throw SkillMarketException("Skill market returned invalid metadata")
        }

    private fun parseTrending(html: String): List<MarketSkillSummary> =
        TRENDING_SKILL_PATTERN.findAll(html)
            .map { match ->
                val path = match.groupValues[1]
                MarketSkillSummary(
                    id = path,
                    skillId = path.substringAfterLast('/'),
                    name = match.groupValues[2],
                    installs = parseCompactCount(match.groupValues[4]),
                    source = match.groupValues[3],
                    installWindow = InstallWindow.LAST_24_HOURS,
                )
            }
            .filter { it.installs > 0 }
            .take(SEARCH_LIMIT)
            .toList()

    private suspend fun Call.await(): Response =
        suspendCancellableCoroutine { continuation ->
            continuation.invokeOnCancellation { cancel() }
            enqueue(
                object : Callback {
                    override fun onFailure(
                        call: Call,
                        e: IOException,
                    ) {
                        if (continuation.isActive) {
                            continuation.resumeWithException(e)
                        }
                    }

                    override fun onResponse(
                        call: Call,
                        response: Response,
                    ) {
                        if (continuation.isActive) {
                            continuation.resume(response)
                        } else {
                            response.close()
                        }
                    }
                },
            )
        }
}

class SkillMarketException(message: String) : Exception(message)

private fun parseFrontmatter(content: String): Map<String, String> {
    if (!content.startsWith("---")) {
        return emptyMap()
    }
    val end = content.indexOf("\n---", startIndex = 3)
    if (end < 0) {
        return emptyMap()
    }
    return content.substring(3, end)
        .lineSequence()
        .mapNotNull { line ->
            val separator = line.indexOf(':')
            if (separator <= 0) {
                null
            } else {
                line.substring(0, separator).trim() to
                    line.substring(separator + 1).trim()
            }
        }
        .toMap()
}

@Serializable
private data class SearchResponse(
    val skills: List<SearchItem> = emptyList(),
)

@Serializable
private data class SearchItem(
    val id: String,
    @SerialName("skillId")
    val skillId: String,
    val name: String,
    val installs: Long = 0,
    val source: String,
)

@Serializable
private data class GitHubRepository(
    @SerialName("default_branch")
    val defaultBranch: String,
)

@Serializable
private data class GitHubTree(
    val tree: List<GitHubTreeItem> = emptyList(),
)

@Serializable
private data class GitHubTreeItem(
    val path: String,
    val type: String,
)

@Serializable
private data class WellKnownIndex(
    val skills: List<WellKnownSkill> = emptyList(),
)

@Serializable
private data class WellKnownSkill(
    val name: String,
    val url: String,
)

private const val SEARCH_URL = "https://skills.sh/api/search"
private const val TRENDING_URL = "https://skills.sh/trending"
private const val GITHUB_API_BASE_URL = "https://api.github.com"
private const val RAW_GITHUB_BASE_URL = "https://raw.githubusercontent.com"
private const val SEARCH_LIMIT = 30
private const val POPULAR_FALLBACK_QUERY = "agent"
private const val MAX_TRENDING_BYTES = 2L * 1024 * 1024
private const val MAX_SEARCH_BYTES = 512L * 1024
private const val MAX_METADATA_BYTES = 2L * 1024 * 1024
private const val MAX_TREE_BYTES = 8L * 1024 * 1024
private const val MAX_SKILL_BYTES = 256L * 1024
private val WELL_KNOWN_PATHS = listOf(
    "/.well-known/agent-skills/index.json",
    "/.well-known/skills/index.json",
)
private val TRENDING_SKILL_PATTERN = Regex(
    """<a class="group grid[^"]*" href="/([^"]+)">.*?""" +
        """<h3[^>]*>([^<]+)</h3>.*?<p[^>]*>([^<]+)</p>.*?""" +
        """<span class="font-mono text-sm text-foreground">([^<]+)</span>""",
    setOf(RegexOption.DOT_MATCHES_ALL),
)

private fun parseCompactCount(value: String): Long {
    val normalized = value.trim().uppercase()
    val multiplier = when {
        normalized.endsWith("K") -> 1_000
        normalized.endsWith("M") -> 1_000_000
        else -> 1
    }
    return normalized
        .removeSuffix("K")
        .removeSuffix("M")
        .toDoubleOrNull()
        ?.times(multiplier)
        ?.toLong()
        ?: 0
}
