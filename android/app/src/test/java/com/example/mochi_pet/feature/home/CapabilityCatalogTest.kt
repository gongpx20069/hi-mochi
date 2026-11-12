package com.example.mochi_pet.feature.home

import com.example.mochi_pet.core.mcp.TENCENT_DOCS_SERVER_ID
import com.example.mochi_pet.core.rest.RestConnection
import com.example.mochi_pet.core.rest.RestConnectionSummary
import com.example.mochi_pet.core.rest.RestToolDefinition
import com.example.mochi_pet.core.rest.restToolAlias
import com.example.mochi_pet.core.skills.MochiSkill
import com.example.mochi_pet.core.skills.SkillOrigin
import com.example.mochi_pet.core.tools.AgentBrowserProviderSummary
import com.example.mochi_pet.core.tools.BuiltInToolSummary
import com.example.mochi_pet.core.tools.McpAuthMode
import com.example.mochi_pet.core.tools.McpServerSummary
import com.example.mochi_pet.core.tools.McpToolSummary
import com.example.mochi_pet.core.tools.ToolCatalogSummary
import java.time.Instant
import org.junit.Assert.*
import org.junit.Test

class CapabilityCatalogTest {
    @Test fun `cross service requirements and optional tools retain their own destinations`() {
        val catalog = ToolCatalogSummary(
            builtInTools = listOf(BuiltInToolSummary("get_current_location", "Current Location", "", false)),
        )
        val dependencies = catalog.capabilityDependencies(capabilitySkill("builtin:travel-planning"))
        assertEquals(setOf("amap", "browser"), dependencies.filterNot { it.optional }.map { it.group?.id }.toSet())
        assertEquals("builtin:get_current_location", dependencies.single { "get_current_location" in it.tools }.group?.id)
        assertTrue(dependencies.single { "get_current_location" in it.tools }.optional)
        assertTrue(dependencies.single { "request_user_assistance" in it.tools }.ready)
        assertFalse(dependencies.filterNot { it.optional }.any { it.ready })
    }

    @Test fun `document skill needs any tool and only losing its last ready tool pauses it`() {
        val skill = capabilitySkill("builtin:tencent-docs-knowledge", enabled = true)
        val server = McpServerSummary(TENCENT_DOCS_SERVER_ID, "Tencent Docs MCP", "https://example.com", true, true, true,
            McpAuthMode.TOKEN, listOf(McpToolSummary("read", "tencent_docs_read", "", true), McpToolSummary("write", "tencent_docs_write", "", true)))
        val catalog = ToolCatalogSummary(servers = listOf(server))
        val dependency = catalog.capabilityDependencies(skill).single()
        assertTrue(dependency.anyTool && dependency.ready)
        assertEquals("mcp:$TENCENT_DOCS_SERVER_ID", dependency.group?.id)
        assertTrue(catalog.skillsAffectedByDisabling(listOf(skill), setOf("tencent_docs_read")).isEmpty())
        assertEquals(listOf(skill), catalog.skillsAffectedByDisabling(listOf(skill), setOf("tencent_docs_read", "tencent_docs_write")))
        val disconnected = catalog.copy(servers = listOf(server.copy(connected = false)))
        assertFalse(disconnected.capabilityDependencies(skill).single().ready)
        assertNotNull(disconnected.capabilityDependencies(skill).single().group)
    }

    @Test fun `standalone REST service remains listed and resolves imported skill aliases`() {
        val connection = RestConnection(id = "fixture", name = "Weather API", tools = listOf(RestToolDefinition(id = "weather")))
        val alias = restToolAlias(connection.id, "weather")
        val catalog = ToolCatalogSummary(restConnections = listOf(RestConnectionSummary(connection, false)))
        assertEquals("Weather API", catalog.capabilityGroups().single { it.id == "rest:fixture" }.name)
        val skill = capabilitySkill("custom", content = "---\nallowed-tools: $alias\n---\nRead weather.")
        assertEquals("rest:fixture", catalog.capabilityDependencies(skill).single().group?.id)
        val unknown = capabilitySkill("unknown", content = "---\nallowed-tools: missing_tool\n---\nRead.")
        assertNull(catalog.capabilityDependencies(unknown).single().group)
        assertFalse(catalog.capabilityDependencies(unknown).single().ready)
    }

    @Test fun `optional browser interactions do not pause read only browser skill`() {
        val skill = capabilitySkill("builtin:browser", enabled = true)
        val catalog = ToolCatalogSummary(agentBrowser = AgentBrowserProviderSummary(tools =
            listOf("browser_read", "browser_navigate", "browser_scroll", "browser_click").map { BuiltInToolSummary(it, it, "", true) }))
        assertTrue(catalog.skillsAffectedByDisabling(listOf(skill), setOf("browser_click")).isEmpty())
        assertEquals(listOf(skill), catalog.skillsAffectedByDisabling(listOf(skill), setOf("browser_read")))
        assertEquals("Enabled", capabilitySkillStatus(capabilitySkill("builtin:app-assistant", enabled = true), ToolCatalogSummary(), false))
    }
}

internal fun capabilitySkill(
    id: String,
    name: String = id,
    enabled: Boolean = false,
    content: String = "# Fixture",
): MochiSkill = MochiSkill(
    id, name, "Synthetic capability", content, if (id.startsWith("builtin:")) SkillOrigin.BUILT_IN else SkillOrigin.MARKET,
    "fixture", "", upstreamDigest = "", localDigest = "", enabled = enabled, modified = false, updateAvailable = false,
    installedAt = Instant.EPOCH, updatedAt = Instant.EPOCH,
)
