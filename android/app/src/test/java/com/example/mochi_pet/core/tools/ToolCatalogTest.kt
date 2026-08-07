package com.example.mochi_pet.core.tools

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.example.mochi_pet.core.mcp.McpRemoteTool
import com.example.mochi_pet.core.mcp.McpServerRuntime
import com.example.mochi_pet.core.mcp.McpStreamableHttpClient
import com.example.mochi_pet.core.mcp.TENCENT_DOCS_SERVER_ID
import com.example.mochi_pet.core.mcp.DIANPING_SERVER_ID
import com.example.mochi_pet.core.settings.ApiKeyCipher
import com.example.mochi_pet.core.settings.EncryptedSecret
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
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
    private lateinit var repository: DataStoreToolCatalogRepository

    @Before
    fun setUp() {
        directory = createTempDirectory("mochi-tools-").toFile()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        client = RecordingMcpClient()
        repository = DataStoreToolCatalogRepository(
            dataStore = PreferenceDataStoreFactory.create(scope = scope) {
                File(directory, "tools.preferences_pb")
            },
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
    fun `Baidu Map token configures and disables provider`() = runBlocking {
        val connected = repository.configureBaiduMap("map-token")

        assertTrue(connected.baiduMap.connected)
        assertTrue(connected.baiduMap.enabled)
        assertEquals(5, connected.baiduMap.tools.size)
        assertTrue(
            connected.baiduMap.tools.all {
                it.name.startsWith("baidu_map_")
            },
        )
        assertFalse(
            connected.builtInTools.any {
                it.name.startsWith("baidu_map_")
            },
        )
        assertEquals("map-token", repository.loadBaiduMapToken())

        val disabled = repository.setBaiduMapEnabled(false)
        assertTrue(disabled.baiduMap.connected)
        assertFalse(disabled.baiduMap.enabled)
        assertEquals(null, repository.loadBaiduMapToken())

        val disconnected = repository.disconnectBaiduMap()
        assertFalse(disconnected.baiduMap.connected)
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
    fun `Dianping credentials enable read only MCP tools`() = runBlocking {
        val initial = repository.loadSummary().servers.first {
            it.id == DIANPING_SERVER_ID
        }
        assertFalse(initial.connected)
        assertEquals(2, initial.tools.size)

        val configured = repository.configureDianping(
            appKey = "app-key",
            appSecret = "app-secret",
            searchSession = "search-session",
            detailSession = "",
        ).servers.first { it.id == DIANPING_SERVER_ID }

        assertTrue(configured.connected)
        assertTrue(configured.enabled)
        assertEquals(
            setOf("dianping_search_poi", "dianping_get_poi"),
            configured.tools.mapTo(mutableSetOf()) { it.alias },
        )
        assertTrue(configured.tools.all { it.enabled })

        val disconnected = repository.disconnectDianping().servers.first {
            it.id == DIANPING_SERVER_ID
        }
        assertFalse(disconnected.connected)
        assertFalse(disconnected.enabled)
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
