package com.example.mochi_pet.core.tools

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.example.mochi_extension.ExtensionAttachmentDescriptor
import com.example.mochi_extension.ExtensionConnectionState
import com.example.mochi_extension.ExtensionConnectionStatus
import com.example.mochi_extension.ExtensionToolDefinition
import com.example.mochi_pet.core.agent.tool.AgentTool
import com.example.mochi_pet.core.agent.tool.ToolExecutionContext
import com.example.mochi_pet.core.agent.tool.ToolResultEnvelope
import com.example.mochi_pet.core.extensions.ExtensionImageAttachment
import com.example.mochi_pet.core.extensions.ExtensionToolScope
import com.example.mochi_pet.core.extensions.MochiExtensionClient
import com.example.mochi_pet.core.extensions.MochiExtensionSnapshot
import com.example.mochi_pet.core.extensions.OpenedExtensionAttachment
import com.example.mochi_pet.core.extensions.TermuxRuntimeState
import com.example.mochi_pet.core.extensions.TrustedExtension
import com.example.mochi_pet.core.model.MochiSurface
import com.example.mochi_pet.core.maps.AmapCredentials
import com.example.mochi_pet.core.mcp.McpRemoteTool
import com.example.mochi_pet.core.mcp.McpServerRuntime
import com.example.mochi_pet.core.mcp.NOTION_SERVER_ID
import com.example.mochi_pet.core.mcp.McpStreamableHttpClient
import com.example.mochi_pet.core.mcp.TENCENT_DOCS_SERVER_ID
import com.example.mochi_pet.core.settings.ApiKeyCipher
import com.example.mochi_pet.core.settings.EncryptedSecret
import java.io.File
import java.time.LocalDate
import kotlin.io.path.createTempDirectory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ToolCatalogTest {
    @Test
    fun `Termux Skill requires connection and both tool switches`() {
        val summary = ToolCatalogSummary(termux = ExtensionProviderSummary(
            connected = true, enabled = true,
            tools = listOf(ExtensionToolSummary("termux_exec", "", "sensitive", true),
                ExtensionToolSummary("termux_task", "", "sensitive", false)),
        ))
        assertEquals(setOf("termux_exec"), summary.readyToolNames())
        assertTrue(summary.copy(termux = summary.termux.copy(connected = false)).readyToolNames().isEmpty())
        assertTrue(summary.copy(termux = summary.termux.copy(enabled = false)).readyToolNames().isEmpty())
        assertEquals(setOf("Termux extension"),
            summary.skillReadiness(setOf("termux_exec", "termux_task")).requirements.keys)
    }

    private val termuxArguments = buildJsonObject { put("command", "printf hello") }
    private val termuxContext = ToolExecutionContext(LocalDate.of(2026, 1, 1), MochiSurface.Face)
    private val backgroundScopes = listOf(ExtensionToolScope.SCHEDULED, ExtensionToolScope.SUBAGENT)

    private fun termuxRepository(
        termux: RecordingExtensionClient,
        runtime: TermuxRuntimeState,
    ) = DataStoreToolCatalogRepository(
        dataStore = dataStore,
        secretCipher = PlaintextCipher,
        mcpClient = client,
        termuxClient = termux,
        termuxRuntime = runtime,
        extensionClient = RecordingExtensionClient(TrustedExtension.MIJIA),
    )

    @Test
    fun `legacy background flags no longer restrict enabled Termux and background registries exclude Mi Home`() = runBlocking {
        val extension = RecordingExtensionClient(TrustedExtension.TERMUX)
        val runtime = TermuxRuntimeState()
        for (legacy in listOf("", ""","termuxBackgroundEnabled":false""", ""","termuxBackgroundEnabled":true""")) {
            dataStore.edit {
                it[stringPreferencesKey("tools.catalog")] = """{"termuxEnabled":true,"mijiaEnabled":true$legacy}"""
            }
            val restored = termuxRepository(extension, runtime)
            assertTrue(restored.loadSummary().termux.enabled)
            assertFalse(dataStore.data.first()[stringPreferencesKey("tools.catalog")].orEmpty().contains("termuxBackgroundEnabled"))
            assertEquals(
                setOf("termux_exec", "termux_task", "mijia_list_devices"),
                restored.loadEnabledExtensionTools().map { it.name }.toSet(),
            )
            backgroundScopes.forEach { scope ->
                val tools = restored.loadEnabledExtensionTools(scope)
                assertEquals(setOf("termux_exec", "termux_task"), tools.map { it.name }.toSet())
                tools.forEach { tool ->
                    assertEquals("ok", withTimeout(5_000) { tool.execute(termuxArguments, termuxContext) }.status)
                    assertEquals(scope, extension.executions.last())
                }
            }
        }
        assertEquals(12, extension.executions.size)
        assertEquals("task-12", runtime.tasks.value.first().id)
    }

    @Test
    fun `foreground tools execute immediately without confirmation`() = runBlocking {
        val extension = RecordingExtensionClient(TrustedExtension.TERMUX)
        val catalog = termuxRepository(extension, TermuxRuntimeState())
        catalog.setTermuxEnabled(true)
        val tool = catalog.loadEnabledExtensionTools().first { it.name == "termux_exec" }
        repeat(2) {
            assertEquals("ok", withTimeout(5_000) { tool.execute(termuxArguments, termuxContext) }.status)
        }
        assertEquals(listOf(ExtensionToolScope.FOREGROUND_MAIN, ExtensionToolScope.FOREGROUND_MAIN), extension.executions)
    }

    @Test
    fun `provider disable revokes all old registries even after reenable`() = runBlocking {
        val extension = RecordingExtensionClient(TrustedExtension.TERMUX)
        val catalog = termuxRepository(extension, TermuxRuntimeState())
        catalog.setTermuxEnabled(true)
        val allScopes = listOf(ExtensionToolScope.FOREGROUND_MAIN) + backgroundScopes
        val staleTools = allScopes.map { scope ->
            catalog.loadEnabledExtensionTools(scope).first { it.name == "termux_exec" }
        }
        catalog.setTermuxEnabled(false)
        allScopes.forEach { assertTrue(catalog.loadEnabledExtensionTools(it).isEmpty()) }
        catalog.setTermuxEnabled(true)
        staleTools.forEach {
            assertEquals("PERMISSION_DENIED", it.execute(termuxArguments, termuxContext).code)
        }
        assertTrue(extension.executions.isEmpty())
        assertEquals("ok", catalog.loadEnabledExtensionTools(ExtensionToolScope.SUBAGENT)
            .first { it.name == "termux_exec" }.execute(termuxArguments, termuxContext).status)
    }

    @Test
    fun `provider tool and connection switches remain authoritative for background shell`() = runBlocking {
        val extension = RecordingExtensionClient(TrustedExtension.TERMUX)
        val catalog = termuxRepository(extension, TermuxRuntimeState())
        catalog.setTermuxEnabled(true)
        val stale = catalog.loadEnabledExtensionTools(ExtensionToolScope.SCHEDULED).first()
        catalog.setTermuxToolEnabled("termux_exec", false)
        backgroundScopes.forEach { scope ->
            assertEquals(listOf("termux_task"), catalog.loadEnabledExtensionTools(scope).map { it.name })
        }
        assertEquals("PERMISSION_DENIED", stale.execute(termuxArguments, termuxContext).code)
        catalog.setTermuxEnabled(false)
        backgroundScopes.forEach { assertTrue(catalog.loadEnabledExtensionTools(it).isEmpty()) }
        catalog.setTermuxEnabled(true)
        backgroundScopes.forEach {
            assertEquals(listOf("termux_task"), catalog.loadEnabledExtensionTools(it).map { tool -> tool.name })
        }
        extension.connected = false
        backgroundScopes.forEach { assertTrue(catalog.loadEnabledExtensionTools(it).isEmpty()) }
        extension.connected = true
        catalog.disconnectTermux()
        assertFalse(catalog.loadSummary().termux.enabled)
        assertTrue(extension.executions.isEmpty())
    }

    @Test
    fun `Termux is disabled by default and disconnected provider cannot be enabled`() = runBlocking {
        val extension = RecordingExtensionClient(TrustedExtension.TERMUX)
        val catalog = termuxRepository(extension, TermuxRuntimeState())
        assertFalse(catalog.loadSummary().termux.enabled)
        (listOf(ExtensionToolScope.FOREGROUND_MAIN) + backgroundScopes).forEach {
            assertTrue(catalog.loadEnabledExtensionTools(it).isEmpty())
        }
        catalog.setTermuxEnabled(true)
        extension.connected = false
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { catalog.setTermuxEnabled(true) }
        }
        assertFalse(catalog.setTermuxEnabled(false).termux.enabled)
    }

    private lateinit var directory: File
    private lateinit var scope: CoroutineScope
    private lateinit var client: RecordingMcpClient
    private lateinit var dataStore: DataStore<Preferences>
    private lateinit var repository: DataStoreToolCatalogRepository

    @Before
    fun setUp() {
        directory = createTempDirectory("mochi-tools-").toFile()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        client = RecordingMcpClient()
        dataStore = PreferenceDataStoreFactory.create(scope = scope) {
                File(directory, "tools.preferences_pb")
            }
        repository = DataStoreToolCatalogRepository(
            dataStore = dataStore,
            secretCipher = PlaintextCipher,
            mcpClient = client,
        )
    }

    private class RecordingExtensionClient(private val identity: TrustedExtension) : MochiExtensionClient {
        override val attachmentEvents = emptyFlow<ExtensionImageAttachment>()
        var connected = true
        val executions = mutableListOf<ExtensionToolScope>()
        private val tools = (if (identity == TrustedExtension.TERMUX) {
            listOf("termux_exec", "termux_task")
        } else {
            listOf("mijia_list_devices")
        }).map { ExtensionToolDefinition(it, "", """{"type":"object"}""", "sensitive", true) }

        override suspend fun snapshot() = MochiExtensionSnapshot(
            installed = true, trusted = true, identity = identity, tools = tools,
            connectionState = ExtensionConnectionState(
                if (connected) ExtensionConnectionStatus.CONNECTED else ExtensionConnectionStatus.DISCONNECTED,
                null, null, 0, 0,
            ),
        )

        override fun agentTool(definition: ExtensionToolDefinition, scope: ExtensionToolScope) = object : AgentTool {
            override val name = definition.name
            override val schema = JsonObject(emptyMap())
            override suspend fun execute(arguments: JsonObject, context: ToolExecutionContext): ToolResultEnvelope {
                executions += scope
                return ToolResultEnvelope.success(buildJsonObject {
                    put("task_id", "task-${executions.size}")
                    put("state", "submitted")
                })
            }
        }

        override suspend fun disconnect() { connected = false }
        override suspend fun openAttachment(descriptor: ExtensionAttachmentDescriptor): OpenedExtensionAttachment =
            error("No attachments in this test")
    }

    @After
    fun tearDown() {
        scope.cancel()
        directory.deleteRecursively()
    }

    @Test
    fun `ready Tool names require provider and individual switches`() {
        val summary = ToolCatalogSummary(
            builtInTools = listOf(
                BuiltInToolSummary("native_on", "On", "", true),
                BuiltInToolSummary("native_off", "Off", "", false),
            ),
            mijia = ExtensionProviderSummary(
                connected = true,
                enabled = true,
                tools = listOf(
                    ExtensionToolSummary("mijia_on", "", "read", true),
                    ExtensionToolSummary("mijia_off", "", "read", false),
                ),
            ),
        )

        assertEquals(
            setOf("native_on", "mijia_on"),
            summary.readyToolNames(),
        )
        assertFalse(
            summary.copy(
                mijia = summary.mijia.copy(enabled = false),
            ).readyToolNames().contains("mijia_on"),
        )
    }

    @Test
    fun `Skill readiness aggregates required Tools by provider`() {
        val summary = ToolCatalogSummary(
            agentBrowser = AgentBrowserProviderSummary(
                enabled = true,
                tools = listOf(
                    BuiltInToolSummary(
                        "browser_read",
                        "Read browser page",
                        "",
                        true,
                    ),
                    BuiltInToolSummary(
                        "browser_navigate",
                        "Navigate browser",
                        "",
                        false,
                    ),
                ),
            ),
            servers = listOf(
                McpServerSummary(
                    id = TENCENT_DOCS_SERVER_ID,
                    name = "Tencent Docs MCP",
                    endpoint = "https://docs.qq.com/openapi/mcp",
                    builtIn = true,
                    enabled = true,
                    connected = true,
                    authMode = McpAuthMode.TOKEN,
                    tools = listOf(
                        McpToolSummary(
                            remoteName = "query_space_node",
                            alias = "tencent_docs_query_space_node",
                            description = "",
                            enabled = true,
                        ),
                        McpToolSummary(
                            remoteName = "get_content",
                            alias = "tencent_docs_get_content",
                            description = "",
                            enabled = false,
                        ),
                    ),
                ),
                McpServerSummary(
                    id = NOTION_SERVER_ID,
                    name = "Notion MCP",
                    endpoint = "https://mcp.notion.com/mcp",
                    builtIn = true,
                    enabled = false,
                    connected = false,
                    authMode = McpAuthMode.OAUTH,
                    tools = emptyList(),
                ),
            ),
        )

        val readiness = summary.skillReadiness(
            setOf(
                "browser_read",
                "browser_navigate",
                "tencent_docs_query_space_node",
                "tencent_docs_get_content",
                "notion_search",
            ),
        )

        assertEquals(
            setOf("Agent Browser", "Notion MCP", "Tencent Docs MCP"),
            readiness.requirements.keys,
        )
        assertEquals(
            setOf("Agent Browser", "Notion MCP", "Tencent Docs MCP"),
            readiness.missingRequirements,
        )
        assertFalse(readiness.isReady)
        assertTrue(
            summary.copy(
                agentBrowser = summary.agentBrowser.copy(
                    tools = summary.agentBrowser.tools.map {
                        it.copy(enabled = true)
                    },
                ),
                servers = summary.servers.map { server ->
                    when (server.id) {
                        TENCENT_DOCS_SERVER_ID -> server.copy(
                            tools = server.tools.map {
                                it.copy(enabled = true)
                            },
                        )
                        NOTION_SERVER_ID -> server.copy(
                            enabled = true,
                            connected = true,
                            tools = listOf(
                                McpToolSummary(
                                    remoteName = "search",
                                    alias = "notion_search",
                                    description = "",
                                    enabled = true,
                                ),
                            ),
                        )
                        else -> server
                    }
                },
            ).skillReadiness(readiness.requiredTools).isReady,
        )
    }

    @Test
    fun `Tencent Docs token discovers and selects knowledge tools`() =
        runBlocking {
            val initial = repository.loadSummary().servers.first {
                it.id == TENCENT_DOCS_SERVER_ID
            }

            assertFalse(initial.connected)
            assertFalse(initial.enabled)

            val configured = repository.configureTencentDocs("personal-token")
            val server = configured.servers.first {
                it.id == TENCENT_DOCS_SERVER_ID
            }

            assertEquals("personal-token", client.runtime?.authorizationHeader)
            assertTrue(server.connected)
            assertTrue(server.enabled)
            assertTrue(
                server.tools.first {
                    it.remoteName == "query_space_node"
                }.enabled,
            )
            assertTrue(
                server.tools.first {
                    it.remoteName == "search_space_file"
                }.enabled,
            )
            assertFalse(
                server.tools.first {
                    it.remoteName == "smartcanvas.update_element"
                }.enabled,
            )
            assertFalse(
                server.tools.first {
                    it.remoteName == "delete_space_node"
                }.enabled,
            )
            assertEquals(
                "List files and folders in a Tencent Docs workspace.",
                server.tools.first {
                    it.remoteName == "query_space_node"
                }.description,
            )

            val disconnected = repository.disconnectTencentDocs().servers
                .first { it.id == TENCENT_DOCS_SERVER_ID }
            assertFalse(disconnected.connected)
            assertFalse(disconnected.enabled)
        }

    @Test
    fun `Tencent Docs catalog keeps only the 32 most important tools`() =
        runBlocking {
            client.tools = buildList {
                repeat(40) { index ->
                    add(McpRemoteTool("minor_operation_$index"))
                }
                add(McpRemoteTool("get_content"))
                add(McpRemoteTool("manage.search_file"))
                add(McpRemoteTool("query_space_node"))
            }

            val server = repository.configureTencentDocs("personal-token")
                .servers.first { it.id == TENCENT_DOCS_SERVER_ID }

            assertEquals(32, server.tools.size)
            assertTrue(
                server.tools.any {
                    it.remoteName == "manage.search_file" && it.enabled
                },
            )
            assertTrue(
                server.tools.any {
                    it.remoteName == "query_space_node" && it.enabled
                },
            )
            assertTrue(
                server.tools.any {
                    it.remoteName == "get_content" && it.enabled
                },
            )
        }

    @Test
    fun `Amap credentials configure and disable provider`() = runBlocking {
        val connected = repository.configureAmap(
            webServiceKey = "map-key",
            securityKey = "security-key",
        )

        assertTrue(connected.amap.connected)
        assertTrue(connected.amap.enabled)
        assertEquals(6, connected.amap.tools.size)
        assertTrue(
            connected.amap.tools.all {
                it.name.startsWith("amap_")
            },
        )
        assertFalse(
            connected.builtInTools.any {
                it.name.startsWith("amap_")
            },
        )
        assertEquals(
            AmapCredentials("map-key", "security-key"),
            repository.loadAmapCredentials(),
        )

        val disabled = repository.setAmapEnabled(false)
        assertTrue(disabled.amap.connected)
        assertFalse(disabled.amap.enabled)
        assertEquals(null, repository.loadAmapCredentials())

        val disconnected = repository.disconnectAmap()
        assertFalse(disconnected.amap.connected)
    }

    @Test
    fun `selected Tool connections export secrets and enabled Tools`() =
        runBlocking {
            repository.configureAmap("map-key", "security-key")
            repository.configureTencentDocs("personal-token")
            val manual = repository.addManualServer(
                ManualMcpServerInput(
                    name = "Example MCP",
                    endpoint = "https://example.com/mcp",
                    bearerToken = "mcp-token",
                ),
            ).servers.first { !it.builtIn }
            repository.setMcpToolEnabled(
                serverId = manual.id,
                remoteName = "get_content",
                enabled = true,
            )

            val shared = repository.exportSharedTools(
                ToolShareSelection(
                    includeAmap = true,
                    includeTencentDocs = true,
                    manualMcpServerIds = setOf(manual.id),
                ),
            )

            assertEquals("map-key", shared.amap?.credentials?.webServiceKey)
            assertEquals("personal-token", shared.tencentDocs?.token)
            assertEquals(
                setOf("get_content", "query_space_node", "search_space_file"),
                shared.tencentDocs?.enabledToolNames,
            )
            assertEquals(
                "mcp-token",
                shared.manualMcpServers.single().bearerToken,
            )
            assertEquals(
                setOf("get_content"),
                shared.manualMcpServers.single().enabledToolNames,
            )
        }

    @Test
    fun `imported Tool connections are enabled with selected Tools`() =
        runBlocking {
            val prepared = repository.prepareSharedTools(
                SharedToolProviders(
                    amap = SharedAmapProvider(
                        credentials = AmapCredentials("map-key", null),
                        enabledToolNames = setOf("amap_weather"),
                    ),
                    tencentDocs = SharedTencentDocsProvider(
                        token = "personal-token",
                        enabledToolNames = setOf("get_content"),
                    ),
                    manualMcpServers = listOf(
                        SharedManualMcpServer(
                            name = "Example MCP",
                            endpoint = "https://example.com/mcp",
                            bearerToken = "mcp-token",
                            enabledToolNames = setOf("get_content"),
                        ),
                    ),
                ),
            )
            val imported = repository.applySharedTools(prepared)

            assertTrue(imported.amap.connected)
            assertTrue(imported.amap.enabled)
            assertEquals(
                setOf("amap_weather"),
                imported.amap.tools
                    .filter(BuiltInToolSummary::enabled)
                    .mapTo(mutableSetOf(), BuiltInToolSummary::name),
            )
            val tencent = imported.servers.first {
                it.id == TENCENT_DOCS_SERVER_ID
            }
            assertTrue(tencent.enabled)
            assertEquals(
                setOf("get_content"),
                tencent.tools
                    .filter(McpToolSummary::enabled)
                    .mapTo(mutableSetOf(), McpToolSummary::remoteName),
            )
            val manual = imported.servers.single { !it.builtIn }
            assertTrue(manual.enabled)
            assertEquals(
                setOf("get_content"),
                manual.tools
                    .filter(McpToolSummary::enabled)
                    .mapTo(mutableSetOf(), McpToolSummary::remoteName),
            )
        }

    @Test
    fun `legacy map and Dianping settings are removed`() = runBlocking {
        val catalogKey = stringPreferencesKey("tools.catalog")
        dataStore.edit { preferences ->
            preferences[catalogKey] =
                """
                {
                  "builtInEnabled": {
                    "baidu_map_place": false,
                    "dianping_search_poi": true,
                    "browser_read": false
                  },
                  "baiduMapToken": {
                    "ciphertext": "legacy",
                    "iv": "legacy"
                  },
                  "baiduMapEnabled": true,
                  "servers": [
                    {
                      "id": "dianping",
                      "name": "Dianping MCP",
                      "endpoint": "https://poiopen.dianping.com/router",
                      "builtIn": true,
                      "enabled": true,
                      "authMode": "TOKEN"
                    }
                  ]
                }
                """.trimIndent()
        }

        val summary = repository.loadSummary()
        val persisted = dataStore.data.first()[catalogKey].orEmpty()

        assertFalse(summary.servers.any { it.id == "dianping" })
        assertFalse(persisted.contains("baiduMap"))
        assertFalse(persisted.contains("dianping"))
        assertFalse(repository.isBuiltInEnabled("browser_read"))
    }

    @Test
    fun `Agent Browser groups five tools behind provider switch`() =
        runBlocking {
            val initial = repository.loadSummary()

            assertTrue(initial.agentBrowser.enabled)
            assertEquals(
                setOf(
                    "browser_read",
                    "browser_navigate",
                    "browser_click",
                    "browser_input",
                    "browser_scroll",
                ),
                initial.agentBrowser.tools.mapTo(mutableSetOf()) { it.name },
            )
            assertFalse(
                initial.builtInTools.any { it.name.startsWith("browser_") },
            )
            assertTrue(repository.isBuiltInEnabled("browser_navigate"))

            val disabled = repository.setAgentBrowserEnabled(false)

            assertFalse(disabled.agentBrowser.enabled)
            assertFalse(repository.isBuiltInEnabled("browser_navigate"))
        }

    @Test
    fun `subagent MCP catalog exposes only enabled read only tools`() =
        runBlocking {
            client.tools = listOf(
                McpRemoteTool("query_space_node"),
                McpRemoteTool(
                    name = "custom_read",
                    readOnlyHint = true,
                ),
                McpRemoteTool("smartcanvas.update_element"),
            )
            repository.configureTencentDocs("personal-token")
            val tools = repository.loadEnabledReadOnlyMcpTools()
                .mapTo(mutableSetOf()) { it.name }

            assertTrue(tools.any { it.endsWith("query_space_node") })
            assertFalse(tools.any { it.endsWith("custom_read") })
            assertFalse(tools.any { it.endsWith("update_element") })
        }
}

private class RecordingMcpClient : McpStreamableHttpClient() {
    var runtime: McpServerRuntime? = null
    var tools: List<McpRemoteTool>? = null

    override suspend fun listTools(
        server: McpServerRuntime,
    ): List<McpRemoteTool> {
        runtime = server
        return tools ?: listOf(
            McpRemoteTool(
                name = "query_space_node",
                description = "\u67e5\u8be2\u7a7a\u95f4\u8282\u70b9",
            ),
            McpRemoteTool("search_space_file"),
            McpRemoteTool("get_content"),
            McpRemoteTool("smartcanvas.update_element"),
            McpRemoteTool("delete_space_node"),
        )
    }
}

private object PlaintextCipher : ApiKeyCipher {
    override fun encrypt(plaintext: String): EncryptedSecret =
        EncryptedSecret(ciphertext = plaintext, iv = "test")

    override fun decrypt(secret: EncryptedSecret): String = secret.ciphertext
}
