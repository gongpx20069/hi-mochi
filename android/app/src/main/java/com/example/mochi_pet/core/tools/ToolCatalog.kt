package com.example.mochi_pet.core.tools

import android.util.Base64
import androidx.core.net.toUri
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.example.mochi_pet.core.agent.tool.AgentTool
import com.example.mochi_pet.core.extensions.MijiaExtensionSnapshot
import com.example.mochi_pet.core.extensions.MochiExtensionClient
import com.example.mochi_pet.core.extensions.UnavailableMijiaExtensionClient
import com.example.mochi_pet.core.maps.AmapCredentials
import com.example.mochi_pet.core.mcp.McpAgentTool
import com.example.mochi_pet.core.mcp.McpRemoteTool
import com.example.mochi_pet.core.mcp.McpServerRuntime
import com.example.mochi_pet.core.mcp.McpStreamableHttpClient
import com.example.mochi_pet.core.mcp.NOTION_MCP_ENDPOINT
import com.example.mochi_pet.core.mcp.NOTION_SERVER_ID
import com.example.mochi_pet.core.mcp.TENCENT_DOCS_MCP_ENDPOINT
import com.example.mochi_pet.core.mcp.TENCENT_DOCS_SERVER_ID
import com.example.mochi_pet.core.mcp.mcpToolAlias
import com.example.mochi_pet.core.settings.ApiKeyCipher
import com.example.mochi_pet.core.settings.EncryptedSecret
import com.example.mochi_pet.core.skills.SkillReadiness
import com.example.mochi_pet.core.web.PublicWebUrlPolicy
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.FormBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.HttpUrl.Companion.toHttpUrl

data class BuiltInToolDescriptor(
    val name: String,
    val displayName: String,
    val description: String,
    val defaultEnabled: Boolean,
)

data class ToolCatalogSummary(
    val builtInTools: List<BuiltInToolSummary> = emptyList(),
    val amap: AmapProviderSummary = AmapProviderSummary(),
    val agentBrowser: AgentBrowserProviderSummary = AgentBrowserProviderSummary(),
    val mijia: MijiaProviderSummary = MijiaProviderSummary(),
    val servers: List<McpServerSummary> = emptyList(),
    val isLoading: Boolean = false,
    val feedback: String? = null,
)

data class AmapProviderSummary(
    val connected: Boolean = false,
    val enabled: Boolean = false,
    val tools: List<BuiltInToolSummary> = emptyList(),
)

data class AgentBrowserProviderSummary(
    val enabled: Boolean = true,
    val tools: List<BuiltInToolSummary> = emptyList(),
)

data class MijiaProviderSummary(
    val installed: Boolean = false,
    val trusted: Boolean = false,
    val connected: Boolean = false,
    val enabled: Boolean = false,
    val status: String = "disconnected",
    val detail: String? = null,
    val versionName: String? = null,
    val accountLabel: String? = null,
    val selectedHomeCount: Int = 0,
    val selectedDeviceCount: Int = 0,
    val configurationPackage: String? = null,
    val configurationActivity: String? = null,
    val tools: List<MijiaToolSummary> = emptyList(),
)

data class MijiaToolSummary(
    val name: String,
    val description: String,
    val riskLevel: String,
    val enabled: Boolean,
    val displayName: String = name,
)

data class BuiltInToolSummary(
    val name: String,
    val displayName: String,
    val description: String,
    val enabled: Boolean,
)

data class McpServerSummary(
    val id: String,
    val name: String,
    val endpoint: String,
    val builtIn: Boolean,
    val enabled: Boolean,
    val connected: Boolean,
    val authMode: McpAuthMode,
    val tools: List<McpToolSummary>,
)

data class McpToolSummary(
    val remoteName: String,
    val alias: String,
    val description: String,
    val enabled: Boolean,
)

fun ToolCatalogSummary.readyToolNames(): Set<String> =
    buildSet {
        builtInTools.filterToReadyNames(this)
        if (agentBrowser.enabled) {
            agentBrowser.tools.filterToReadyNames(this)
        }
        if (amap.connected && amap.enabled) {
            amap.tools.filterToReadyNames(this)
        }
        if (mijia.connected && mijia.enabled) {
            mijia.tools.filter { it.enabled }.mapTo(this) { it.name }
        }
        servers.filter { it.connected && it.enabled }.forEach { server ->
            server.tools.filter { it.enabled }.mapTo(this) { it.alias }
        }
    }

fun ToolCatalogSummary.skillReadiness(
    requiredToolNames: Set<String>,
): SkillReadiness {
    val readyToolNames = readyToolNames()
    return SkillReadiness(
        requiredTools = requiredToolNames,
        readyTools = requiredToolNames.intersect(readyToolNames),
        requirements = requiredToolNames
            .groupBy { skillRequirementName(it) }
            .mapValues { (_, tools) -> tools.toSet() },
    )
}

private fun ToolCatalogSummary.skillRequirementName(
    toolName: String,
): String =
    when {
        toolName.startsWith("browser_") -> "Agent Browser"
        toolName.startsWith("amap_") -> "Amap Maps"
        toolName.startsWith("mijia_") -> "Mi Home extension"
        toolName.startsWith("notion_") ->
            servers.firstOrNull { it.id == NOTION_SERVER_ID }?.name
                ?: "Notion MCP"
        toolName.startsWith("tencent_docs_") ->
            servers.firstOrNull { it.id == TENCENT_DOCS_SERVER_ID }?.name
                ?: "Tencent Docs MCP"
        else -> servers.firstNotNullOfOrNull { server ->
            server.tools
                .firstOrNull { it.alias == toolName }
                ?.let { server.name }
        } ?: builtInTools
            .firstOrNull { it.name == toolName }
            ?.displayName
            ?: toolName
    }

private fun List<BuiltInToolSummary>.filterToReadyNames(
    destination: MutableSet<String>,
) {
    filter { it.enabled }.mapTo(destination) { it.name }
}

data class ManualMcpServerInput(
    val name: String,
    val endpoint: String,
    val bearerToken: String?,
)

enum class McpAuthMode {
    NONE,
    BEARER,
    TOKEN,
    OAUTH,
}

interface ToolCatalogRepository {
    suspend fun loadSummary(): ToolCatalogSummary

    suspend fun setBuiltInEnabled(
        name: String,
        enabled: Boolean,
    ): ToolCatalogSummary

    suspend fun beginNotionAuthorization(): String

    suspend fun completeNotionAuthorization(callbackUri: String): ToolCatalogSummary

    suspend fun disconnectNotion(): ToolCatalogSummary

    suspend fun configureTencentDocs(token: String): ToolCatalogSummary

    suspend fun disconnectTencentDocs(): ToolCatalogSummary

    suspend fun configureAmap(
        webServiceKey: String,
        securityKey: String,
    ): ToolCatalogSummary

    suspend fun disconnectAmap(): ToolCatalogSummary

    suspend fun setAmapEnabled(enabled: Boolean): ToolCatalogSummary

    suspend fun setAgentBrowserEnabled(enabled: Boolean): ToolCatalogSummary

    suspend fun setMijiaEnabled(enabled: Boolean): ToolCatalogSummary

    suspend fun setMijiaToolEnabled(
        name: String,
        enabled: Boolean,
    ): ToolCatalogSummary

    suspend fun disconnectMijia(): ToolCatalogSummary

    suspend fun loadAmapCredentials(): AmapCredentials?

    suspend fun addManualServer(input: ManualMcpServerInput): ToolCatalogSummary

    suspend fun removeManualServer(id: String): ToolCatalogSummary

    suspend fun setServerEnabled(
        id: String,
        enabled: Boolean,
    ): ToolCatalogSummary

    suspend fun setMcpToolEnabled(
        serverId: String,
        remoteName: String,
        enabled: Boolean,
    ): ToolCatalogSummary

    suspend fun isBuiltInEnabled(name: String): Boolean

    suspend fun loadEnabledMcpTools(): List<AgentTool>

    suspend fun loadEnabledReadOnlyMcpTools(): List<AgentTool>

    suspend fun loadEnabledExtensionTools(): List<AgentTool>
}

class DataStoreToolCatalogRepository(
    private val dataStore: DataStore<Preferences>,
    private val secretCipher: ApiKeyCipher,
    private val mcpClient: McpStreamableHttpClient,
    private val oauthClient: McpOAuthClient = McpOAuthClient(),
    private val nowMillis: () -> Long = System::currentTimeMillis,
    private val extensionClient: MochiExtensionClient =
        UnavailableMijiaExtensionClient,
) : ToolCatalogRepository {
    private val json = Json {
        encodeDefaults = true
        ignoreUnknownKeys = true
        explicitNulls = false
    }

    override suspend fun loadSummary(): ToolCatalogSummary {
        repairTruncatedTencentDocsCatalog()
        updateCatalog { it }
        return loadCatalog().toSummary(extensionClient.snapshot())
    }

    override suspend fun setBuiltInEnabled(
        name: String,
        enabled: Boolean,
    ): ToolCatalogSummary {
        require(BUILT_IN_TOOLS.any { it.name == name }) {
            "Unknown built-in tool"
        }
        updateCatalog { catalog ->
            catalog.copy(
                builtInEnabled = catalog.builtInEnabled + (name to enabled),
            )
        }
        return loadSummary()
    }

    override suspend fun beginNotionAuthorization(): String {
        val metadata = oauthClient.discover(NOTION_MCP_ENDPOINT)
        val client = oauthClient.register(
            metadata = metadata,
            redirectUri = NOTION_REDIRECT_URI,
        )
        val verifier = randomUrlSafe(32)
        val state = randomUrlSafe(32)
        val pending = PendingOAuthRecord(
            state = state,
            verifier = encrypt(verifier),
            clientId = client.clientId,
            clientSecret = client.clientSecret?.let(::encrypt),
            authorizationEndpoint = metadata.authorizationEndpoint,
            tokenEndpoint = metadata.tokenEndpoint,
            createdAtEpochMillis = nowMillis(),
        )
        updateCatalog { catalog ->
            catalog.withBuiltInServers().copy(pendingNotionOAuth = pending)
        }
        return oauthClient.authorizationUrl(
            authorizationEndpoint = metadata.authorizationEndpoint,
            clientId = client.clientId,
            redirectUri = NOTION_REDIRECT_URI,
            state = state,
            codeChallenge = sha256UrlSafe(verifier),
            resource = NOTION_MCP_RESOURCE,
            scope = metadata.scopes.firstOrNull() ?: "default",
        )
    }

    override suspend fun completeNotionAuthorization(
        callbackUri: String,
    ): ToolCatalogSummary {
        val uri = callbackUri.toUri()
        require(
            uri.scheme == "mochi" &&
                uri.host == "oauth" &&
                uri.path == "/notion",
        ) {
            "Notion authorization callback is invalid"
        }
        val error = uri.getQueryParameter("error")
        if (error != null) {
            throw IllegalArgumentException(
                uri.getQueryParameter("error_description")
                    ?: "Notion authorization was cancelled",
            )
        }
        val code = uri.getQueryParameter("code")
            ?: throw IllegalArgumentException(
                "Notion authorization code is missing",
            )
        val state = uri.getQueryParameter("state")
            ?: throw IllegalArgumentException(
                "Notion authorization state is missing",
            )
        val catalog = loadCatalog().withBuiltInServers()
        val pending = catalog.pendingNotionOAuth
            ?: throw IllegalArgumentException(
                "No Notion authorization request is pending",
            )
        require(pending.state == state) {
            "Notion authorization state did not match"
        }
        require(
            nowMillis() - pending.createdAtEpochMillis <= OAUTH_PENDING_MAX_AGE_MS,
        ) {
            "Notion authorization request expired"
        }
        val tokens = oauthClient.exchange(
            tokenEndpoint = pending.tokenEndpoint,
            code = code,
            codeVerifier = decrypt(pending.verifier),
            clientId = pending.clientId,
            clientSecret = pending.clientSecret?.let(::decrypt),
            redirectUri = NOTION_REDIRECT_URI,
            resource = NOTION_MCP_RESOURCE,
        )
        val runtime = McpServerRuntime(
            id = NOTION_SERVER_ID,
            name = "Notion",
            endpoint = NOTION_MCP_ENDPOINT,
            accessToken = tokens.accessToken,
        )
        val tools = mcpClient.listTools(runtime)
        val record = catalog.servers.first { it.id == NOTION_SERVER_ID }
        val enabledNames = tools.map(McpRemoteTool::name)
            .filter { it in DEFAULT_NOTION_TOOLS }
            .toSet()
        updateCatalog {
            catalog.copy(
                pendingNotionOAuth = null,
                servers = catalog.servers.map { server ->
                    if (server.id == NOTION_SERVER_ID) {
                        record.copy(
                            enabled = true,
                            accessToken = encrypt(tokens.accessToken),
                            refreshToken =
                                tokens.refreshToken?.let(::encrypt),
                            tokenExpiresAtEpochMillis =
                                tokens.expiresInSeconds?.let {
                                    nowMillis() + it * 1_000L
                                },
                            oauthClientId = pending.clientId,
                            oauthClientSecret = pending.clientSecret,
                            oauthTokenEndpoint = pending.tokenEndpoint,
                            toolDefaultsVersion =
                                BUILT_IN_TOOL_DEFAULTS_VERSION,
                            tools = tools.map { tool ->
                                PersistedMcpTool(
                                    definition = tool,
                                    enabled = tool.name in enabledNames,
                                )
                            },
                        )
                    } else {
                        server
                    }
                },
            )
        }
        return loadSummary()
    }

    override suspend fun disconnectNotion(): ToolCatalogSummary {
        updateCatalog { existing ->
            val catalog = existing.withBuiltInServers()
            catalog.copy(
                pendingNotionOAuth = null,
                servers = catalog.servers.map { server ->
                    if (server.id == NOTION_SERVER_ID) {
                        server.copy(
                            enabled = false,
                            accessToken = null,
                            refreshToken = null,
                            tokenExpiresAtEpochMillis = null,
                            oauthClientId = null,
                            oauthClientSecret = null,
                            oauthTokenEndpoint = null,
                            tools = emptyList(),
                        )
                    } else {
                        server
                    }
                },
            )
        }
        return loadSummary()
    }

    override suspend fun configureTencentDocs(
        token: String,
    ): ToolCatalogSummary {
        val normalized = token.trim()
        require(normalized.isNotEmpty()) {
            "Tencent Docs token is required"
        }
        require(normalized.length <= MAX_MCP_TOKEN_CHARS) {
            "Tencent Docs token is too long"
        }
        val runtime = McpServerRuntime(
            id = TENCENT_DOCS_SERVER_ID,
            name = "Tencent Docs",
            endpoint = TENCENT_DOCS_MCP_ENDPOINT,
            accessToken = normalized,
            authorizationHeader = normalized,
        )
        val tools = selectTencentDocsTools(mcpClient.listTools(runtime))
        val enabledNames = tools.map(McpRemoteTool::name)
            .filter { it in DEFAULT_TENCENT_DOCS_TOOLS }
            .toSet()
        updateCatalog { existing ->
            val catalog = existing.withBuiltInServers()
            catalog.copy(
                servers = catalog.servers.map { server ->
                    if (server.id == TENCENT_DOCS_SERVER_ID) {
                        server.copy(
                            enabled = true,
                            accessToken = encrypt(normalized),
                            toolDefaultsVersion =
                                BUILT_IN_TOOL_DEFAULTS_VERSION,
                            tools = tools.map { tool ->
                                PersistedMcpTool(
                                    definition =
                                        tool.withTencentEnglishDescription(),
                                    enabled =
                                        tool.name in DEFAULT_TENCENT_DOCS_TOOLS,
                                )
                            },
                        )
                    } else {
                        server
                    }
                },
            )
        }
        return loadSummary()
    }

    override suspend fun disconnectTencentDocs(): ToolCatalogSummary {
        updateCatalog { existing ->
            val catalog = existing.withBuiltInServers()
            catalog.copy(
                servers = catalog.servers.map { server ->
                    if (server.id == TENCENT_DOCS_SERVER_ID) {
                        server.copy(
                            enabled = false,
                            accessToken = null,
                            tools = emptyList(),
                        )
                    } else {
                        server
                    }
                },
            )
        }
        return loadSummary()
    }

    override suspend fun configureAmap(
        webServiceKey: String,
        securityKey: String,
    ): ToolCatalogSummary {
        val credentials = AmapCredentials(
            webServiceKey = webServiceKey.trim(),
            securityKey = securityKey.trim().ifEmpty { null },
        )
        require(credentials.webServiceKey.isNotEmpty()) {
            "Amap Web Service Key is required"
        }
        require(
            credentials.webServiceKey.length <= MAX_MAP_CREDENTIAL_CHARS &&
                (
                    credentials.securityKey?.length
                        ?: 0
                ) <= MAX_MAP_CREDENTIAL_CHARS,
        ) {
            "Amap credentials are too long"
        }
        updateCatalog { catalog ->
            catalog.copy(
                amapCredentials = encrypt(json.encodeToString(credentials)),
                amapEnabled = true,
            )
        }
        return loadSummary()
    }

    override suspend fun disconnectAmap(): ToolCatalogSummary {
        updateCatalog { catalog ->
            catalog.copy(
                amapCredentials = null,
                amapEnabled = false,
            )
        }
        return loadSummary()
    }

    override suspend fun setAmapEnabled(
        enabled: Boolean,
    ): ToolCatalogSummary {
        updateCatalog { catalog ->
            if (enabled) {
                require(catalog.amapCredentials != null) {
                    "Configure Amap before enabling it"
                }
            }
            catalog.copy(amapEnabled = enabled)
        }
        return loadSummary()
    }

    override suspend fun loadAmapCredentials(): AmapCredentials? {
        val catalog = loadCatalog()
        if (!catalog.amapEnabled) {
            return null
        }
        val raw = catalog.amapCredentials?.let(::decrypt) ?: return null
        return try {
            json.decodeFromString<AmapCredentials>(raw)
        } catch (error: SerializationException) {
            throw IllegalStateException("Stored Amap credentials are invalid", error)
        }
    }

    override suspend fun setAgentBrowserEnabled(
        enabled: Boolean,
    ): ToolCatalogSummary {
        updateCatalog { catalog ->
            catalog.copy(agentBrowserEnabled = enabled)
        }
        return loadSummary()
    }

    override suspend fun setMijiaEnabled(enabled: Boolean): ToolCatalogSummary {
        if (enabled) {
            require(extensionClient.snapshot().connected) {
                "Connect Mi Home before enabling its Tools"
            }
        }
        updateCatalog { catalog ->
            catalog.copy(mijiaEnabled = enabled)
        }
        return loadSummary()
    }

    override suspend fun setMijiaToolEnabled(
        name: String,
        enabled: Boolean,
    ): ToolCatalogSummary {
        val snapshot = extensionClient.snapshot()
        require(snapshot.tools.any { it.name == name }) {
            "Unknown Mi Home Tool"
        }
        updateCatalog { catalog ->
            catalog.copy(
                mijiaToolsEnabled = catalog.mijiaToolsEnabled +
                    (name to enabled),
            )
        }
        return loadSummary()
    }

    override suspend fun disconnectMijia(): ToolCatalogSummary {
        extensionClient.disconnect()
        updateCatalog { catalog ->
            catalog.copy(mijiaEnabled = false)
        }
        return loadSummary()
    }

    override suspend fun addManualServer(
        input: ManualMcpServerInput,
    ): ToolCatalogSummary {
        val name = input.name.trim()
        require(name.isNotEmpty()) { "MCP server name is required" }
        require(name.length <= MAX_SERVER_NAME_CHARS) {
            "MCP server name is too long"
        }
        val endpoint = PublicWebUrlPolicy.validate(input.endpoint)
            .toString()
        val token = input.bearerToken?.trim()?.takeIf(String::isNotEmpty)
        val id = "manual-${UUID.randomUUID()}"
        val runtime = McpServerRuntime(
            id = id,
            name = name,
            endpoint = endpoint,
            accessToken = token,
        )
        val tools = mcpClient.listTools(runtime)
        updateCatalog { catalog ->
            catalog.copy(
                servers = catalog.withBuiltInServers().servers +
                    PersistedMcpServer(
                        id = id,
                        name = name,
                        endpoint = endpoint,
                        builtIn = false,
                        enabled = false,
                        authMode = if (token == null) {
                            McpAuthMode.NONE
                        } else {
                            McpAuthMode.BEARER
                        },
                        accessToken = token?.let(::encrypt),
                        tools = tools.map {
                            PersistedMcpTool(
                                definition = it,
                                enabled = false,
                            )
                        },
                    ),
            )
        }
        return loadSummary()
    }

    override suspend fun removeManualServer(id: String): ToolCatalogSummary {
        require(
            id != NOTION_SERVER_ID &&
                id != TENCENT_DOCS_SERVER_ID,
        ) {
            "Built-in MCP servers cannot be removed"
        }
        updateCatalog { catalog ->
            catalog.copy(servers = catalog.servers.filterNot { it.id == id })
        }
        return loadSummary()
    }

    override suspend fun setServerEnabled(
        id: String,
        enabled: Boolean,
    ): ToolCatalogSummary {
        updateCatalog { existing ->
            val catalog = existing.withBuiltInServers()
            catalog.copy(
                servers = catalog.servers.map { server ->
                    if (server.id == id) {
                        if (enabled) {
                            require(server.isConnected()) {
                                "Configure authorization before enabling this server"
                            }
                        }
                        server.copy(enabled = enabled)
                    } else {
                        server
                    }
                },
            )
        }
        return loadSummary()
    }

    override suspend fun setMcpToolEnabled(
        serverId: String,
        remoteName: String,
        enabled: Boolean,
    ): ToolCatalogSummary {
        updateCatalog { existing ->
            val catalog = existing.withBuiltInServers()
            catalog.copy(
                servers = catalog.servers.map { server ->
                    if (server.id == serverId) {
                        server.copy(
                            tools = server.tools.map { tool ->
                                if (tool.definition.name == remoteName) {
                                    tool.copy(enabled = enabled)
                                } else {
                                    tool
                                }
                            },
                        )
                    } else {
                        server
                    }
                },
            )
        }
        return loadSummary()
    }

    override suspend fun isBuiltInEnabled(name: String): Boolean {
        val descriptor = BUILT_IN_TOOLS.firstOrNull { it.name == name }
            ?: return false
        val catalog = loadCatalog()
        if (descriptor.isAgentBrowserTool() && !catalog.agentBrowserEnabled) {
            return false
        }
        return catalog.builtInEnabled[name] ?: descriptor.defaultEnabled
    }

    override suspend fun loadEnabledMcpTools(): List<AgentTool> {
        val catalog = loadCatalog().withBuiltInServers()
        return catalog.loadEnabledMcpTools { _, _ ->
            true
        }
    }

    override suspend fun loadEnabledReadOnlyMcpTools(): List<AgentTool> {
        val catalog = loadCatalog().withBuiltInServers()
        return catalog.loadEnabledMcpTools { server, tool ->
            tool.definition.name in when (server.id) {
                NOTION_SERVER_ID -> READ_ONLY_NOTION_TOOLS
                TENCENT_DOCS_SERVER_ID -> READ_ONLY_TENCENT_DOCS_TOOLS
                else -> emptySet()
            }
        }
    }

    override suspend fun loadEnabledExtensionTools(): List<AgentTool> {
        val catalog = loadCatalog()
        if (!catalog.mijiaEnabled) return emptyList()
        val snapshot = extensionClient.snapshot()
        if (!snapshot.connected) return emptyList()
        return snapshot.tools
            .filter { definition ->
                catalog.mijiaToolsEnabled[definition.name]
                    ?: definition.defaultEnabled
            }
            .map(extensionClient::agentTool)
    }

    private fun PersistedToolCatalog.loadEnabledMcpTools(
        include: (PersistedMcpServer, PersistedMcpTool) -> Boolean,
    ): List<AgentTool> =
        servers
            .filter { it.enabled && it.isConnected() }
            .flatMap { server ->
                server.tools
                    .filter(PersistedMcpTool::enabled)
                    .filter { tool -> include(server, tool) }
                    .map { tool ->
                    McpAgentTool(
                        name = mcpToolAlias(
                            serverId = server.id,
                            remoteName = tool.definition.name,
                        ),
                        remoteTool = tool.definition,
                        server = { runtimeServer(server.id) },
                        client = mcpClient,
                    )
                }
            }

    private suspend fun repairTruncatedTencentDocsCatalog() {
        val catalog = loadCatalog().withBuiltInServers()
        val server = catalog.servers.firstOrNull {
                it.id == TENCENT_DOCS_SERVER_ID
            } ?: return
        if (
            !server.isConnected() ||
                server.tools.size < LEGACY_MAX_DISCOVERED_TOOLS ||
                server.hasTencentDocsReadTools()
        ) {
            return
        }
        val token = server.accessToken?.let(::decrypt) ?: return
        val discovered = runCatching {
            mcpClient.listTools(
                McpServerRuntime(
                    id = server.id,
                    name = server.name,
                    endpoint = server.endpoint,
                    accessToken = token,
                    authorizationHeader = token,
                ),
            ).let(::selectTencentDocsTools)
        }.getOrNull() ?: return
        updateCatalog { existing ->
            val current = existing.withBuiltInServers()
            current.copy(
                servers = current.servers.map { item ->
                    if (item.id == TENCENT_DOCS_SERVER_ID) {
                        item.copy(
                            tools = discovered.map { tool ->
                                PersistedMcpTool(
                                    definition =
                                        tool.withTencentEnglishDescription(),
                                    enabled =
                                        tool.name in DEFAULT_TENCENT_DOCS_TOOLS,
                                )
                            },
                        )
                    } else {
                        item
                    }
                },
            )
        }
    }

    private suspend fun runtimeServer(id: String): McpServerRuntime? {
        var catalog = loadCatalog().withBuiltInServers()
        var server = catalog.servers.firstOrNull { it.id == id }
            ?: return null
        if (!server.enabled || !server.isConnected()) {
            return null
        }
        if (
            server.authMode == McpAuthMode.OAUTH &&
            server.tokenExpiresAtEpochMillis != null &&
            server.tokenExpiresAtEpochMillis <=
                nowMillis() + TOKEN_REFRESH_WINDOW_MS
        ) {
            val refreshToken = server.refreshToken?.let(::decrypt)
                ?: return null
            val refreshed = try {
                oauthClient.refresh(
                    tokenEndpoint = server.oauthTokenEndpoint
                        ?: return null,
                    refreshToken = refreshToken,
                    clientId = server.oauthClientId ?: return null,
                    clientSecret = server.oauthClientSecret?.let(::decrypt),
                    resource = NOTION_MCP_RESOURCE,
                )
            } catch (error: OAuthRequestException) {
                if (error.oauthError != "invalid_grant") {
                    throw error
                }
                clearExpiredOAuthServer(id)
                return null
            }
            updateCatalog { current ->
                current.copy(
                    servers = current.servers.map {
                        if (it.id == id) {
                            it.copy(
                                accessToken = encrypt(refreshed.accessToken),
                                refreshToken = refreshed.refreshToken
                                    ?.let(::encrypt)
                                    ?: it.refreshToken,
                                tokenExpiresAtEpochMillis =
                                    refreshed.expiresInSeconds?.let { seconds ->
                                        nowMillis() + seconds * 1_000L
                                    },
                            )
                        } else {
                            it
                        }
                    },
                )
            }
            catalog = loadCatalog().withBuiltInServers()
            server = catalog.servers.first { it.id == id }
        }
        val token = server.accessToken?.let(::decrypt)
        return McpServerRuntime(
            id = server.id,
            name = server.name,
            endpoint = server.endpoint,
            accessToken = token,
            authorizationHeader = token
                ?.let { value ->
                    if (server.authMode == McpAuthMode.TOKEN) {
                        value
                    } else {
                        "Bearer $value"
                    }
                },
        )
    }

    private suspend fun clearExpiredOAuthServer(id: String) {
        updateCatalog { current ->
            current.copy(
                servers = current.servers.map { server ->
                    if (server.id == id) {
                        server.copy(
                            enabled = false,
                            accessToken = null,
                            refreshToken = null,
                            tokenExpiresAtEpochMillis = null,
                            oauthClientId = null,
                            oauthClientSecret = null,
                            oauthTokenEndpoint = null,
                        )
                    } else {
                        server
                    }
                },
            )
        }
    }

    private suspend fun loadCatalog(): PersistedToolCatalog {
        val raw = dataStore.data.first()[CATALOG]
        if (raw.isNullOrBlank()) {
            return PersistedToolCatalog().withBuiltInServers()
        }
        return try {
            json.decodeFromString<PersistedToolCatalog>(raw)
                .withBuiltInServers()
        } catch (error: SerializationException) {
            throw IllegalStateException("Stored Tool configuration is invalid", error)
        }
    }

    private suspend fun updateCatalog(
        transform: (PersistedToolCatalog) -> PersistedToolCatalog,
    ) {
        dataStore.edit { preferences ->
            val current = preferences[CATALOG]
                ?.let {
                    runCatching {
                        json.decodeFromString<PersistedToolCatalog>(it)
                    }.getOrNull()
                }
                ?.withBuiltInServers()
                ?: PersistedToolCatalog().withBuiltInServers()
            preferences[CATALOG] = json.encodeToString(transform(current))
        }
    }

    private fun PersistedToolCatalog.toSummary(
        extension: MijiaExtensionSnapshot,
    ): ToolCatalogSummary =
        ToolCatalogSummary(
            builtInTools = BUILT_IN_TOOLS
                .filterNot {
                    it.isAmapTool() || it.isAgentBrowserTool()
                }
                .map { descriptor ->
                    toBuiltInToolSummary(descriptor)
                },
            amap = AmapProviderSummary(
                connected = amapCredentials != null,
                enabled = amapEnabled && amapCredentials != null,
                tools = BUILT_IN_TOOLS
                    .filter(BuiltInToolDescriptor::isAmapTool)
                    .map { descriptor ->
                        toBuiltInToolSummary(descriptor)
                    },
            ),
            agentBrowser = AgentBrowserProviderSummary(
                enabled = agentBrowserEnabled,
                tools = BUILT_IN_TOOLS
                    .filter(BuiltInToolDescriptor::isAgentBrowserTool)
                    .map { descriptor ->
                        toBuiltInToolSummary(descriptor)
                    },
            ),
            mijia = MijiaProviderSummary(
                installed = extension.installed,
                trusted = extension.trusted,
                connected = extension.connected,
                enabled = mijiaEnabled && extension.connected,
                status = extension.connectionState.status,
                detail = extension.detail ?: extension.connectionState.detail,
                versionName = extension.metadata?.versionName,
                accountLabel = extension.connectionState.accountLabel,
                selectedHomeCount =
                    extension.connectionState.selectedHomeCount,
                selectedDeviceCount =
                    extension.connectionState.selectedDeviceCount,
                configurationPackage =
                    extension.configurationTarget?.packageName,
                configurationActivity =
                    extension.configurationTarget?.className,
                tools = extension.tools.map { definition ->
                    MijiaToolSummary(
                        name = definition.name,
                        description = definition.description,
                        riskLevel = definition.riskLevel,
                        enabled = mijiaToolsEnabled[definition.name]
                            ?: definition.defaultEnabled,
                        displayName = mijiaToolDisplayName(definition.name),
                    )
                },
            ),
            servers = withBuiltInServers().servers.map { server ->
                McpServerSummary(
                    id = server.id,
                    name = server.name,
                    endpoint = server.endpoint,
                    builtIn = server.builtIn,
                    enabled = server.enabled,
                    connected = server.isConnected(),
                    authMode = server.authMode,
                    tools = server.tools.map { tool ->
                        McpToolSummary(
                            remoteName = tool.definition.name,
                            alias = mcpToolAlias(
                                serverId = server.id,
                                remoteName = tool.definition.name,
                            ),
                            description = tool.definition.description,
                            enabled = tool.enabled,
                        )
                    },
                )
            },
        )

    private fun PersistedToolCatalog.withBuiltInServers(): PersistedToolCatalog {
        val retainedServers = servers.filterNot {
            it.id == LEGACY_DIANPING_SERVER_ID
        }
        val retainedBuiltIns = builtInEnabled.filterKeys { name ->
            !name.startsWith("baidu_map_") &&
                !name.startsWith("dianping_")
        }
        val existingIds = retainedServers.mapTo(
            mutableSetOf(),
            PersistedMcpServer::id,
        )
        val missing = listOf(
            PersistedMcpServer(
                id = NOTION_SERVER_ID,
                name = "Notion MCP",
                endpoint = NOTION_MCP_ENDPOINT,
                builtIn = true,
                enabled = false,
                authMode = McpAuthMode.OAUTH,
            ),
            PersistedMcpServer(
                id = TENCENT_DOCS_SERVER_ID,
                name = "Tencent Docs MCP",
                endpoint = TENCENT_DOCS_MCP_ENDPOINT,
                builtIn = true,
                enabled = false,
                authMode = McpAuthMode.TOKEN,
            ),
        ).filterNot { it.id in existingIds }
        val normalizedServers = retainedServers.map { server ->
            server.withBuiltInToolDefaults()
        }
        if (
            missing.isEmpty() &&
            normalizedServers == servers &&
            retainedBuiltIns == builtInEnabled
        ) {
            return this
        }
        return copy(
            builtInEnabled = retainedBuiltIns,
            servers = missing + normalizedServers,
        )
    }

    private fun PersistedMcpServer.withBuiltInToolDefaults():
        PersistedMcpServer {
        val defaults = when (id) {
            NOTION_SERVER_ID -> DEFAULT_NOTION_TOOLS
            TENCENT_DOCS_SERVER_ID -> DEFAULT_TENCENT_DOCS_TOOLS
            else -> return this
        }
        val shouldApplyDefaults =
            isConnected() &&
                toolDefaultsVersion < BUILT_IN_TOOL_DEFAULTS_VERSION
        val normalizedTools = tools.map { tool ->
            tool.copy(
                definition = if (id == TENCENT_DOCS_SERVER_ID) {
                    tool.definition.withTencentEnglishDescription()
                } else {
                    tool.definition
                },
                enabled = if (shouldApplyDefaults) {
                    tool.definition.name in defaults
                } else {
                    tool.enabled
                },
            )
        }
        return copy(
            tools = normalizedTools,
            toolDefaultsVersion = if (shouldApplyDefaults) {
                BUILT_IN_TOOL_DEFAULTS_VERSION
            } else {
                toolDefaultsVersion
            },
        )
    }

    private fun PersistedMcpServer.isConnected(): Boolean =
        when (authMode) {
            McpAuthMode.NONE -> true
            McpAuthMode.BEARER,
            McpAuthMode.TOKEN,
            McpAuthMode.OAUTH,
            -> accessToken != null
        }

    private fun PersistedMcpServer.hasTencentDocsReadTools(): Boolean {
        val names = tools.mapTo(mutableSetOf()) { it.definition.name }
        return "query_space_node" in names &&
            "get_content" in names &&
            TENCENT_DOCS_SEARCH_TOOLS.any(names::contains)
    }

    private fun encrypt(value: String): StoredSecret =
        secretCipher.encrypt(value).let {
            StoredSecret(ciphertext = it.ciphertext, iv = it.iv)
        }

    private fun decrypt(value: StoredSecret): String =
        secretCipher.decrypt(
            EncryptedSecret(
                ciphertext = value.ciphertext,
                iv = value.iv,
            ),
        )

    private fun mijiaToolDisplayName(name: String): String =
        when (name) {
            "mijia_list_devices" -> "List Mi Home Devices"
            "mijia_get_device_state" -> "Read Mi Home Device State"
            "mijia_control_device" -> "Control Mi Home Device"
            "mijia_control_television" -> "Control Mi Home Television"
            "mijia_configure_camera" -> "Configure Mi Home Camera"
            "mijia_get_latest_camera_event_image" ->
                "Get Latest Camera Event Image"
            "mijia_list_scenes" -> "List Mi Home Scenes"
            "mijia_run_scene" -> "Run Mi Home Scene"
            else -> name
        }

    private fun PersistedToolCatalog.toBuiltInToolSummary(
        descriptor: BuiltInToolDescriptor,
    ): BuiltInToolSummary =
        BuiltInToolSummary(
            name = descriptor.name,
            displayName = descriptor.displayName,
            description = descriptor.description,
            enabled = builtInEnabled[descriptor.name]
                ?: descriptor.defaultEnabled,
        )

    private companion object {
        val CATALOG = stringPreferencesKey("tools.catalog")
    }
}

class McpOAuthClient(
    client: OkHttpClient = OkHttpClient(),
) {
    private val client = client.newBuilder()
        .connectTimeout(OAUTH_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .readTimeout(OAUTH_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .writeTimeout(OAUTH_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .build()
    private val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
    }

    suspend fun discover(resourceUrl: String): OAuthMetadata =
        withContext(Dispatchers.IO) {
            val resource = PublicWebUrlPolicy.validate(resourceUrl)
            val resourcePath = resource.encodedPath
                .takeUnless { it == "/" }
                .orEmpty()
            val resourceMetadataUrl = resource.newBuilder()
                .encodedPath(
                    "/.well-known/oauth-protected-resource$resourcePath",
                )
                .query(null)
                .build()
            val protectedResource = get<ProtectedResourceMetadata>(
                resourceMetadataUrl.toString(),
            )
            val authorizationServer =
                protectedResource.authorizationServers.firstOrNull()
                    ?: throw IllegalArgumentException(
                        "MCP server did not advertise an authorization server",
                    )
            val metadataUrl = PublicWebUrlPolicy.validate(authorizationServer)
                .newBuilder()
                .encodedPath("/.well-known/oauth-authorization-server")
                .query(null)
                .build()
            val metadata = get<AuthorizationServerMetadata>(
                metadataUrl.toString(),
            )
            OAuthMetadata(
                authorizationEndpoint = metadata.authorizationEndpoint,
                tokenEndpoint = metadata.tokenEndpoint,
                registrationEndpoint = metadata.registrationEndpoint
                    ?: throw IllegalArgumentException(
                        "MCP authorization server does not support registration",
                    ),
                scopes = metadata.scopesSupported,
            )
        }

    suspend fun register(
        metadata: OAuthMetadata,
        redirectUri: String,
    ): OAuthClientRegistration = withContext(Dispatchers.IO) {
        val payload = json.encodeToString(
            RegistrationRequest(
                clientName = "Mochi Android",
                clientUri = "https://github.com/gongpx20069/hi-mochi",
                redirectUris = listOf(redirectUri),
            ),
        )
        postJson<OAuthClientRegistration>(
            metadata.registrationEndpoint,
            payload,
        )
    }

    fun authorizationUrl(
        authorizationEndpoint: String,
        clientId: String,
        redirectUri: String,
        state: String,
        codeChallenge: String,
        resource: String,
        scope: String,
    ): String = authorizationEndpoint.toHttpUrl().newBuilder()
        .addQueryParameter("response_type", "code")
        .addQueryParameter("client_id", clientId)
        .addQueryParameter("redirect_uri", redirectUri)
        .addQueryParameter("scope", scope)
        .addQueryParameter("state", state)
        .addQueryParameter("code_challenge", codeChallenge)
        .addQueryParameter("code_challenge_method", "S256")
        .addQueryParameter("resource", resource)
        .addQueryParameter("prompt", "consent")
        .build()
        .toString()

    suspend fun exchange(
        tokenEndpoint: String,
        code: String,
        codeVerifier: String,
        clientId: String,
        clientSecret: String?,
        redirectUri: String,
        resource: String,
    ): OAuthTokenResponse = token(
        tokenEndpoint = tokenEndpoint,
        fields = mapOf(
            "grant_type" to "authorization_code",
            "code" to code,
            "client_id" to clientId,
            "redirect_uri" to redirectUri,
            "code_verifier" to codeVerifier,
            "resource" to resource,
        ) + clientSecretField(clientSecret),
    )

    suspend fun refresh(
        tokenEndpoint: String,
        refreshToken: String,
        clientId: String,
        clientSecret: String?,
        resource: String,
    ): OAuthTokenResponse = token(
        tokenEndpoint = tokenEndpoint,
        fields = mapOf(
            "grant_type" to "refresh_token",
            "refresh_token" to refreshToken,
            "client_id" to clientId,
            "resource" to resource,
        ) + clientSecretField(clientSecret),
    )

    private suspend fun token(
        tokenEndpoint: String,
        fields: Map<String, String>,
    ): OAuthTokenResponse = withContext(Dispatchers.IO) {
        val form = FormBody.Builder().apply {
            fields.forEach { (name, value) -> add(name, value) }
        }.build()
        val request = Request.Builder()
            .url(PublicWebUrlPolicy.validate(tokenEndpoint))
            .post(form)
            .header("Accept", "application/json")
            .header("User-Agent", "Mochi-Android/1.0")
            .build()
        execute<OAuthTokenResponse>(request)
    }

    private inline fun <reified T> get(url: String): T {
        val request = Request.Builder()
            .url(PublicWebUrlPolicy.validate(url))
            .get()
            .header("Accept", "application/json")
            .build()
        return execute(request)
    }

    private inline fun <reified T> postJson(
        url: String,
        payload: String,
    ): T {
        val request = Request.Builder()
            .url(PublicWebUrlPolicy.validate(url))
            .post(payload.toRequestBody(JSON_MEDIA_TYPE))
            .header("Accept", "application/json")
            .build()
        return execute(request)
    }

    private inline fun <reified T> execute(request: Request): T {
        try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    val errorBody = response.body
                    if (errorBody.contentLength() > MAX_OAUTH_RESPONSE_BYTES) {
                        throw IllegalArgumentException(
                            "OAuth response was too large",
                        )
                    }
                    val source = errorBody.source()
                    source.request(MAX_OAUTH_RESPONSE_BYTES + 1)
                    require(source.buffer.size <= MAX_OAUTH_RESPONSE_BYTES) {
                        "OAuth response was too large"
                    }
                    val raw = source.readUtf8()
                    val oauthError = runCatching {
                        json.decodeFromString<OAuthErrorResponse>(raw)
                    }.getOrNull()
                    throw OAuthRequestException(
                        statusCode = response.code,
                        oauthError = oauthError?.error,
                        message = oauthError?.errorDescription
                            ?: "OAuth request failed with HTTP ${response.code}",
                    )
                }
                val body = response.body
                    ?: throw IllegalArgumentException(
                        "OAuth response body was empty",
                    )
                if (body.contentLength() > MAX_OAUTH_RESPONSE_BYTES) {
                    throw IllegalArgumentException(
                        "OAuth response was too large",
                    )
                }
                val source = body.source()
                source.request(MAX_OAUTH_RESPONSE_BYTES + 1)
                require(source.buffer.size <= MAX_OAUTH_RESPONSE_BYTES) {
                    "OAuth response was too large"
                }
                return json.decodeFromString(source.readUtf8())
            }
        } catch (error: IOException) {
            throw IllegalArgumentException(
                "OAuth network request failed",
                error,
            )
        }
    }

    private fun clientSecretField(secret: String?): Map<String, String> =
        secret?.let { mapOf("client_secret" to it) }.orEmpty()
}

@Serializable
private data class PersistedToolCatalog(
    val builtInEnabled: Map<String, Boolean> = emptyMap(),
    val amapCredentials: StoredSecret? = null,
    val amapEnabled: Boolean = false,
    val agentBrowserEnabled: Boolean = true,
    val mijiaEnabled: Boolean = false,
    val mijiaToolsEnabled: Map<String, Boolean> = emptyMap(),
    val servers: List<PersistedMcpServer> = emptyList(),
    val pendingNotionOAuth: PendingOAuthRecord? = null,
)

@Serializable
private data class PersistedMcpServer(
    val id: String,
    val name: String,
    val endpoint: String,
    val builtIn: Boolean,
    val enabled: Boolean,
    val authMode: McpAuthMode,
    val accessToken: StoredSecret? = null,
    val refreshToken: StoredSecret? = null,
    val tokenExpiresAtEpochMillis: Long? = null,
    val oauthClientId: String? = null,
    val oauthClientSecret: StoredSecret? = null,
    val oauthTokenEndpoint: String? = null,
    val toolDefaultsVersion: Int = 0,
    val tools: List<PersistedMcpTool> = emptyList(),
)

@Serializable
private data class PersistedMcpTool(
    val definition: McpRemoteTool,
    val enabled: Boolean,
)

@Serializable
private data class StoredSecret(
    val ciphertext: String,
    val iv: String,
)

@Serializable
private data class PendingOAuthRecord(
    val state: String,
    val verifier: StoredSecret,
    val clientId: String,
    val clientSecret: StoredSecret? = null,
    val authorizationEndpoint: String,
    val tokenEndpoint: String,
    val createdAtEpochMillis: Long,
)

data class OAuthMetadata(
    val authorizationEndpoint: String,
    val tokenEndpoint: String,
    val registrationEndpoint: String,
    val scopes: List<String>,
)

@Serializable
data class OAuthClientRegistration(
    @kotlinx.serialization.SerialName("client_id")
    val clientId: String,
    @kotlinx.serialization.SerialName("client_secret")
    val clientSecret: String? = null,
)

@Serializable
data class OAuthTokenResponse(
    @kotlinx.serialization.SerialName("access_token")
    val accessToken: String,
    @kotlinx.serialization.SerialName("refresh_token")
    val refreshToken: String? = null,
    @kotlinx.serialization.SerialName("expires_in")
    val expiresInSeconds: Long? = null,
)

class OAuthRequestException(
    val statusCode: Int,
    val oauthError: String?,
    message: String,
) : IllegalArgumentException(message)

@Serializable
private data class OAuthErrorResponse(
    val error: String,
    @kotlinx.serialization.SerialName("error_description")
    val errorDescription: String? = null,
)

@Serializable
private data class ProtectedResourceMetadata(
    @kotlinx.serialization.SerialName("authorization_servers")
    val authorizationServers: List<String>,
)

@Serializable
private data class AuthorizationServerMetadata(
    @kotlinx.serialization.SerialName("authorization_endpoint")
    val authorizationEndpoint: String,
    @kotlinx.serialization.SerialName("token_endpoint")
    val tokenEndpoint: String,
    @kotlinx.serialization.SerialName("registration_endpoint")
    val registrationEndpoint: String? = null,
    @kotlinx.serialization.SerialName("scopes_supported")
    val scopesSupported: List<String> = emptyList(),
)

@Serializable
private data class RegistrationRequest(
    @kotlinx.serialization.SerialName("client_name")
    val clientName: String,
    @kotlinx.serialization.SerialName("client_uri")
    val clientUri: String,
    @kotlinx.serialization.SerialName("redirect_uris")
    val redirectUris: List<String>,
    @kotlinx.serialization.SerialName("grant_types")
    val grantTypes: List<String> =
        listOf("authorization_code", "refresh_token"),
    @kotlinx.serialization.SerialName("response_types")
    val responseTypes: List<String> = listOf("code"),
    @kotlinx.serialization.SerialName("token_endpoint_auth_method")
    val tokenEndpointAuthMethod: String = "none",
)

private fun randomUrlSafe(bytes: Int): String =
    ByteArray(bytes).also(SecureRandom()::nextBytes).let {
        Base64.encodeToString(
            it,
            Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING,
        )
    }

private fun sha256UrlSafe(value: String): String =
    MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(StandardCharsets.US_ASCII))
        .let {
            Base64.encodeToString(
                it,
                Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING,
            )
        }

val BUILT_IN_TOOLS = listOf(
    BuiltInToolDescriptor(
        name = "manage_mochi_calendar",
        displayName = "Mochi Calendar",
        description = "Read and update Mochi calendar events.",
        defaultEnabled = true,
    ),
    BuiltInToolDescriptor(
        name = "manage_mochi_todo",
        displayName = "Mochi Todo",
        description = "Read and update Mochi todos.",
        defaultEnabled = true,
    ),
    BuiltInToolDescriptor(
        name = "manage_mochi_schedule",
        displayName = "Agent Schedules",
        description = "Create and manage scheduled Mochi Agent prompts.",
        defaultEnabled = true,
    ),
    BuiltInToolDescriptor(
        name = "get_current_location",
        displayName = "Current Location",
        description = "Read the device location after Android permission.",
        defaultEnabled = true,
    ),
    BuiltInToolDescriptor(
        name = "get_current_weather",
        displayName = "Current Weather",
        description = "Read current local weather with device permission.",
        defaultEnabled = true,
    ),
    BuiltInToolDescriptor(
        name = "browser_read",
        displayName = "Read Page",
        description = "Read the current page as a semantic snapshot.",
        defaultEnabled = true,
    ),
    BuiltInToolDescriptor(
        name = "browser_navigate",
        displayName = "Navigate",
        description = "Open a public HTTPS page in Agent Browser.",
        defaultEnabled = true,
    ),
    BuiltInToolDescriptor(
        name = "browser_click",
        displayName = "Click",
        description = "Activate an element from the latest snapshot.",
        defaultEnabled = true,
    ),
    BuiltInToolDescriptor(
        name = "browser_input",
        displayName = "Input",
        description = "Enter text, select options, or send a key.",
        defaultEnabled = true,
    ),
    BuiltInToolDescriptor(
        name = "browser_scroll",
        displayName = "Scroll",
        description = "Scroll the current browser page.",
        defaultEnabled = true,
    ),
    BuiltInToolDescriptor(
        name = "navigate_mochi_ui",
        displayName = "Mochi Navigation",
        description = "Open trusted native Mochi surfaces.",
        defaultEnabled = true,
    ),
    BuiltInToolDescriptor(
        name = "run_sandboxed_javascript",
        displayName = "JavaScript Sandbox",
        description = "Run bounded pure JavaScript calculations locally.",
        defaultEnabled = true,
    ),
    BuiltInToolDescriptor(
        name = "amap_search_poi",
        displayName = "Amap Place Search",
        description = "Search Amap places and nearby merchants.",
        defaultEnabled = true,
    ),
    BuiltInToolDescriptor(
        name = "amap_get_poi",
        displayName = "Amap Place Details",
        description = "Read merchant details, ratings, cost, hours, and photos.",
        defaultEnabled = true,
    ),
    BuiltInToolDescriptor(
        name = "amap_direction",
        displayName = "Amap Route Planning",
        description = "Plan driving, walking, cycling, or transit routes.",
        defaultEnabled = true,
    ),
    BuiltInToolDescriptor(
        name = "amap_geocoding",
        displayName = "Amap Geocoding",
        description = "Convert complete addresses to map coordinates.",
        defaultEnabled = true,
    ),
    BuiltInToolDescriptor(
        name = "amap_reverse_geocoding",
        displayName = "Amap Reverse Geocoding",
        description = "Convert trusted coordinates to addresses.",
        defaultEnabled = true,
    ),
    BuiltInToolDescriptor(
        name = "amap_weather",
        displayName = "Amap Weather",
        description = "Read weather forecasts for an administrative region.",
        defaultEnabled = true,
    ),
)

private fun BuiltInToolDescriptor.isAmapTool(): Boolean =
    name.startsWith("amap_")

private fun BuiltInToolDescriptor.isAgentBrowserTool(): Boolean =
    name.startsWith("browser_")

private val DEFAULT_NOTION_TOOLS = setOf(
    "notion-search",
    "notion-fetch",
    "notion-create-pages",
    "notion-update-page",
)
private val READ_ONLY_NOTION_TOOLS = setOf(
    "notion-search",
    "notion-fetch",
)
private val DEFAULT_TENCENT_DOCS_TOOLS = setOf(
    "query_space_node",
    "search_space_file",
    "manage.search_file",
    "get_content",
)
private val READ_ONLY_TENCENT_DOCS_TOOLS = setOf(
    "query_space_node",
    "search_space_file",
    "manage.search_file",
    "get_content",
    "manage.query_folder_meta",
    "manage.get_privilege",
    "slide_get_page_info",
    "slide_find_text",
)
private val TENCENT_DOCS_SEARCH_TOOLS = setOf(
    "search_space_file",
    "manage.search_file",
)
private const val LEGACY_MAX_DISCOVERED_TOOLS = 64
private const val MAX_VISIBLE_TENCENT_DOCS_TOOLS = 32
private val TENCENT_DOCS_TOOL_PRIORITY = listOf(
    "query_space_node",
    "search_space_file",
    "manage.search_file",
    "get_content",
    "manage.query_folder_meta",
    "manage.create_file",
    "manage.export_file",
    "manage.get_privilege",
    "manage.set_privilege",
    "create_smartcanvas_by_markdown",
    "smartcanvas.find",
    "smartcanvas.append_insert_smartcanvas_by_markdown",
    "smartcanvas.update_element",
    "doc.resolve_document_structure",
    "doc.get_last_operable_pos",
    "doc.insert_paragraph_with_text",
    "doc.find_and_replace",
    "doc.insert_image",
    "doc.get_images",
    "doc.get_comments",
    "doc.insert_code_block",
    "doc.compare_documents",
    "sheet.get_sheet_info",
    "sheet.get_cell_data",
    "sheet.operation_sheet",
    "sheet.set_link",
    "sheet.set_freeze",
    "smartsheet.list_fields",
    "smartsheet.update_records",
    "slide_get_page_info",
    "slide_find_text",
    "slide_append_text",
    "ocr.extract",
)
private val TENCENT_DOCS_TOOL_PRIORITY_INDEX =
    TENCENT_DOCS_TOOL_PRIORITY.withIndex().associate { it.value to it.index }
private const val BUILT_IN_TOOL_DEFAULTS_VERSION = 2
private val TENCENT_DOCS_TOOL_DESCRIPTIONS = mapOf(
    "query_space_node" to
        "List files and folders in a Tencent Docs workspace.",
    "search_space_file" to
        "Search Tencent Docs files by title or content.",
    "manage.search_file" to
        "Search Tencent Docs files by title or content.",
    "get_content" to
        "Read the full content of a Tencent Docs document.",
    "create_space_node" to
        "Create a file, folder, document, or link in Tencent Docs.",
    "delete_space_node" to
        "Delete a Tencent Docs file or folder.",
    "create_smartcanvas_by_markdown" to
        "Create a Smart Canvas document from Markdown.",
    "smartcanvas.append_insert_smartcanvas_by_markdown" to
        "Append Markdown content to a Smart Canvas document.",
    "smartcanvas.update_element" to
        "Update an element in a Smart Canvas document.",
    "create_excel_by_markdown" to
        "Create a Tencent Docs spreadsheet from Markdown data.",
    "create_slide_by_markdown" to
        "Create a Tencent Docs presentation from Markdown.",
    "create_mind_by_markdown" to
        "Create a Tencent Docs mind map from Markdown.",
    "create_flowchart_by_mermaid" to
        "Create a Tencent Docs flowchart from Mermaid.",
    "create_word_by_markdown" to
        "Create a Tencent Docs word-processing document from Markdown.",
    "batch_update_sheet_range" to
        "Update a range of cells in a Tencent Docs spreadsheet.",
)

private fun McpRemoteTool.withTencentEnglishDescription(): McpRemoteTool {
    val description = TENCENT_DOCS_TOOL_DESCRIPTIONS[name]
        ?: "Use the Tencent Docs ${name.toEnglishToolLabel()} operation."
    return copy(description = description)
}

private fun selectTencentDocsTools(
    tools: List<McpRemoteTool>,
): List<McpRemoteTool> =
    tools.distinctBy(McpRemoteTool::name)
        .sortedWith(
            compareBy<McpRemoteTool> {
                TENCENT_DOCS_TOOL_PRIORITY_INDEX[it.name] ?: Int.MAX_VALUE
            }.thenByDescending { it.tencentDocsImportance() }
                .thenBy(McpRemoteTool::name),
        )
        .take(MAX_VISIBLE_TENCENT_DOCS_TOOLS)

private fun McpRemoteTool.tencentDocsImportance(): Int {
    val normalized = name.lowercase()
    return when {
        normalized.contains("search") -> 90
        normalized.contains("query") ||
            normalized.contains("list") ||
            normalized.contains("get_") ||
            normalized.contains(".get") -> 80
        normalized.contains("create") -> 70
        normalized.contains("update") ||
            normalized.contains("insert") ||
            normalized.contains("append") -> 60
        normalized.contains("find") -> 50
        normalized.contains("export") -> 40
        else -> 0
    }
}

private fun String.toEnglishToolLabel(): String =
    replace('.', ' ')
        .replace('_', ' ')
        .replace(Regex("\\s+"), " ")
        .trim()
private const val NOTION_REDIRECT_URI = "mochi://oauth/notion"
private const val NOTION_MCP_RESOURCE = "https://mcp.notion.com/mcp"
private const val MAX_SERVER_NAME_CHARS = 64
private const val MAX_MCP_TOKEN_CHARS = 4_096
private const val MAX_MAP_CREDENTIAL_CHARS = 512
private const val LEGACY_DIANPING_SERVER_ID = "dianping"
private const val OAUTH_TIMEOUT_SECONDS = 30L
private const val MAX_OAUTH_RESPONSE_BYTES = 256L * 1024L
private const val OAUTH_PENDING_MAX_AGE_MS = 10L * 60L * 1_000L
private const val TOKEN_REFRESH_WINDOW_MS = 5L * 60L * 1_000L
private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
