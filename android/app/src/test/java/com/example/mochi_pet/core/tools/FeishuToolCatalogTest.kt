package com.example.mochi_pet.core.tools

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.stringPreferencesKey
import com.example.mochi_pet.core.agent.tool.ToolExecutionContext
import com.example.mochi_pet.core.mcp.FEISHU_SERVER_ID
import com.example.mochi_pet.core.mcp.McpAuthenticationException
import com.example.mochi_pet.core.mcp.McpRemoteTool
import com.example.mochi_pet.core.mcp.McpServerRuntime
import com.example.mochi_pet.core.mcp.McpStreamableHttpClient
import com.example.mochi_pet.core.model.MochiSurface
import com.example.mochi_pet.core.settings.ApiKeyCipher
import com.example.mochi_pet.core.settings.EncryptedSecret
import java.io.File
import java.time.LocalDate
import java.util.Base64
import kotlin.io.path.createTempDirectory
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class FeishuToolCatalogTest {
    private val directory = createTempDirectory("mochi-feishu-").toFile()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val store = PreferenceDataStoreFactory.create(scope = scope) { File(directory, "tools.preferences_pb") }
    private var clock = 1_000_000L
    private val oauth = FakeOAuth()
    private val mcp = FakeMcp()
    private val repo = DataStoreToolCatalogRepository(
        dataStore = store, secretCipher = FixtureCipher, mcpClient = mcp,
        feishuOAuthClient = oauth, nowMillis = { clock },
    )
    private val credentials = FeishuAppCredentials("cli_fixture", "synthetic-app-secret")
    private val context = ToolExecutionContext(LocalDate.of(2026, 1, 1), MochiSurface.Face)

    @After fun close() {
        scope.cancel()
        directory.deleteRecursively()
    }

    private suspend fun connect() = repo.authorizeFeishu(credentials) {}

    @Test fun `five defaults only encrypted credentials read-only subset and no sharing`() = runBlocking {
        assertFalse(repo.loadSummary().servers.single { it.id == FEISHU_SERVER_ID }.connected)
        val summary = connect()
        val server = summary.servers.single { it.id == FEISHU_SERVER_ID }
        assertTrue(server.connected && server.enabled)
        assertEquals(FEISHU_DOCUMENT_TOOLS, server.tools.map { it.remoteName }.toSet())
        assertTrue(server.tools.all { it.enabled })
        assertEquals(setOf("Feishu MCP"), summary.skillReadiness(setOf("feishu_search_doc")).requirements.keys)
        assertEquals(setOf("feishu_search_doc", "feishu_fetch_doc", "feishu_list_docs"),
            repo.loadEnabledReadOnlyMcpTools().map { it.name }.toSet())
        val raw = store.data.first()[stringPreferencesKey("tools.catalog")].orEmpty()
        listOf(credentials.appSecret, "synthetic-access", "synthetic-refresh").forEach { assertFalse(raw.contains(it)) }
        assertEquals(SharedToolProviders(), repo.exportSharedTools(ToolShareSelection()))
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { repo.exportSharedTools(ToolShareSelection(manualMcpServerIds = setOf(FEISHU_SERVER_ID))) }
        }
        assertTrue(repo.loadSummary().servers.single { it.id == FEISHU_SERVER_ID }.connected)
    }

    @Test fun `disabled tools and provider invalidate captured registries even after reenable`() = runBlocking {
        connect()
        val tool = repo.loadEnabledMcpTools().first { it.name == "feishu_update_doc" }
        repo.setMcpToolEnabled(FEISHU_SERVER_ID, "update-doc", false)
        repo.setMcpToolEnabled(FEISHU_SERVER_ID, "update-doc", true)
        assertEquals("error", tool.execute(buildJsonObject {}, context).status)
        val newer = repo.loadEnabledMcpTools().first()
        repo.setServerEnabled(FEISHU_SERVER_ID, false)
        repo.setServerEnabled(FEISHU_SERVER_ID, true)
        assertEquals("error", newer.execute(buildJsonObject {}, context).status)
        assertEquals(0, mcp.calls)
        val fresh = repo.loadEnabledMcpTools().first()
        assertEquals("ok", fresh.execute(buildJsonObject {}, context).status)
        assertEquals(setOf(fresh.name.removePrefix("feishu_").replace('_', '-')), mcp.lastRuntime!!.feishuAllowedTools)
    }

    @Test fun `concurrent expiry refreshes once persists rotation and honors later disconnect`() = runBlocking {
        connect()
        clock += 3_600_000
        val tools = repo.loadEnabledMcpTools()
        tools.map { tool -> async { tool.execute(buildJsonObject {}, context) } }.awaitAll().forEach {
            assertEquals("ok", it.status)
        }
        assertEquals(1, oauth.refreshes)
        assertEquals("synthetic-rotated-access", mcp.lastRuntime!!.accessToken)
        clock += 3_600_000
        assertEquals("ok", tools.first().execute(buildJsonObject {}, context).status)
        assertEquals("synthetic-rotated-refresh", oauth.lastRefresh)
        repo.disconnectFeishu()
        assertEquals("error", tools.first().execute(buildJsonObject {}, context).status)
        val raw = store.data.first()[stringPreferencesKey("tools.catalog")].orEmpty()
        assertFalse(raw.contains("cli_fixture"))
        assertFalse(repo.loadSummary().servers.single { it.id == FEISHU_SERVER_ID }.connected)
    }

    @Test fun `unknown refresh outcome disconnects and never replays single-use refresh token`() = runBlocking {
        connect()
        clock += 3_600_000
        oauth.failRefresh = true
        val tool = repo.loadEnabledMcpTools().first()
        assertEquals("error", tool.execute(buildJsonObject {}, context).status)
        assertEquals("error", tool.execute(buildJsonObject {}, context).status)
        assertEquals(1, oauth.refreshes)
        assertFalse(repo.loadSummary().servers.single { it.id == FEISHU_SERVER_ID }.connected)
    }

    @Test fun `disconnect while rotation is in flight cannot restore a connection`() = runBlocking {
        connect()
        clock += 3_600_000
        oauth.refreshRelease = CompletableDeferred()
        val tool = repo.loadEnabledMcpTools().first()
        val result = async { tool.execute(buildJsonObject {}, context) }
        oauth.refreshStarted.await()
        repo.disconnectFeishu()
        oauth.refreshRelease!!.complete(Unit)
        assertEquals("error", result.await().status)
        assertFalse(repo.loadSummary().servers.single { it.id == FEISHU_SERVER_ID }.connected)
    }

    @Test fun `incomplete discovery never claims successful setup`() {
        mcp.names = setOf("fetch-doc")
        assertThrows(McpAuthenticationException::class.java) { runBlocking { connect() } }
        runBlocking { assertFalse(repo.loadSummary().servers.single { it.id == FEISHU_SERVER_ID }.connected) }
    }

    @Test fun `disabling during rotation preserves renewed credentials without executing revoked tool`() = runBlocking {
        connect()
        clock += 3_600_000
        oauth.refreshRelease = CompletableDeferred()
        val tool = repo.loadEnabledMcpTools().first()
        val result = async { tool.execute(buildJsonObject {}, context) }
        oauth.refreshStarted.await()
        repo.setServerEnabled(FEISHU_SERVER_ID, false)
        oauth.refreshRelease!!.complete(Unit)
        assertEquals("error", result.await().status)
        val summary = repo.loadSummary().servers.single { it.id == FEISHU_SERVER_ID }
        assertTrue(summary.connected)
        assertFalse(summary.enabled)
        repo.setServerEnabled(FEISHU_SERVER_ID, true)
        assertEquals("ok", repo.loadEnabledMcpTools().first().execute(buildJsonObject {}, context).status)
        assertEquals(1, oauth.refreshes)
    }

    private class FakeOAuth : FeishuOAuthClient() {
        var refreshes = 0
        var lastRefresh: String? = null
        var failRefresh = false
        val refreshStarted = CompletableDeferred<Unit>()
        var refreshRelease: CompletableDeferred<Unit>? = null
        val authorizeStarted = CompletableDeferred<Unit>()
        var authorizeRelease: CompletableDeferred<Unit>? = null
        override suspend fun authorize(credentials: FeishuAppCredentials, openAuthorization: suspend (String) -> Unit): FeishuTokenResponse {
            authorizeStarted.complete(Unit)
            authorizeRelease?.await()
            return feishuFixtureToken()
        }
        override suspend fun refresh(credentials: FeishuAppCredentials, refreshToken: String): FeishuTokenResponse {
            refreshes++
            lastRefresh = refreshToken
            refreshStarted.complete(Unit)
            refreshRelease?.await()
            if (failRefresh) throw McpAuthenticationException(FEISHU_NETWORK_ERROR)
            return feishuFixtureToken("synthetic-rotated-access", "synthetic-rotated-refresh")
        }
    }

    @Test fun `late authorization cannot undo disconnect`() = runBlocking {
        oauth.authorizeRelease = CompletableDeferred()
        val result = async { runCatching { connect() } }
        withTimeout(5_000) { oauth.authorizeStarted.await() }
        repo.disconnectFeishu()
        oauth.authorizeRelease!!.complete(Unit)
        assertTrue(result.await().exceptionOrNull() is IllegalStateException)
        assertFalse(repo.loadSummary().servers.single { it.id == FEISHU_SERVER_ID }.connected)
    }

    @Test fun `cancelled refresh clears reserved credentials without retry`() = runBlocking {
        connect()
        clock += 3_600_000
        oauth.refreshRelease = CompletableDeferred()
        val tool = repo.loadEnabledMcpTools().first()
        val result = async { tool.execute(buildJsonObject {}, context) }
        withTimeout(5_000) { oauth.refreshStarted.await() }
        withTimeout(5_000) { result.cancelAndJoin() }
        assertEquals("error", tool.execute(buildJsonObject {}, context).status)
        assertFalse(repo.loadSummary().servers.single { it.id == FEISHU_SERVER_ID }.connected)
        assertEquals(1, oauth.refreshes)
    }

    private class FakeMcp : McpStreamableHttpClient() {
        var calls = 0
        var names = FEISHU_DOCUMENT_TOOLS + setOf("search-user", "add-comments", "invented-delete")
        var lastRuntime: McpServerRuntime? = null
        override suspend fun listTools(server: McpServerRuntime) = names.map { McpRemoteTool(it) }
        override suspend fun callTool(server: McpServerRuntime, toolName: String, arguments: JsonObject): JsonObject {
            calls++
            lastRuntime = server
            return buildJsonObject {}
        }
    }

    private object FixtureCipher : ApiKeyCipher {
        override fun encrypt(plaintext: String) =
            EncryptedSecret(Base64.getEncoder().encodeToString(plaintext.toByteArray()), "fixture")
        override fun decrypt(secret: EncryptedSecret) =
            String(Base64.getDecoder().decode(secret.ciphertext))
    }
}
