package com.example.mochi_pet.core.tools

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.example.mochi_pet.core.maps.AmapCredentials
import com.example.mochi_pet.core.mcp.McpRemoteTool
import com.example.mochi_pet.core.mcp.McpServerRuntime
import com.example.mochi_pet.core.mcp.NOTION_SERVER_ID
import com.example.mochi_pet.core.mcp.McpStreamableHttpClient
import com.example.mochi_pet.core.mcp.TENCENT_DOCS_SERVER_ID
import com.example.mochi_pet.core.settings.ApiKeyCipher
import com.example.mochi_pet.core.settings.EncryptedSecret
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ToolCatalogTest {
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
            mijia = MijiaProviderSummary(
                connected = true,
                enabled = true,
                tools = listOf(
                    MijiaToolSummary("mijia_on", "", "read", true),
                    MijiaToolSummary("mijia_off", "", "read", false),
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
