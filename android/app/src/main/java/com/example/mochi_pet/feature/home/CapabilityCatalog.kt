package com.example.mochi_pet.feature.home

import com.example.mochi_pet.core.agentlink.AGENTLINK_TOOLS
import com.example.mochi_pet.core.apps.APP_TOOL_NAMES
import com.example.mochi_pet.core.media.MEDIA_TOOL_NAMES
import com.example.mochi_pet.core.rest.restToolAlias
import com.example.mochi_pet.core.skills.MochiSkill
import com.example.mochi_pet.core.skills.optionalToolNames
import com.example.mochi_pet.core.skills.requiredToolNames
import com.example.mochi_pet.core.tools.ExtensionProviderSummary
import com.example.mochi_pet.core.tools.ToolCatalogSummary
import com.example.mochi_pet.core.tools.readyToolNames
import com.example.mochi_pet.core.tools.skillReadiness

internal data class CapabilityToolGroup(
    val id: String,
    val name: String,
    val description: String,
    val status: String,
    val tools: Set<String>,
    val needsSetup: Boolean = false,
)

internal data class CapabilityDependency(
    val group: CapabilityToolGroup?,
    val name: String,
    val tools: Set<String>,
    val optional: Boolean,
    val anyTool: Boolean,
    val ready: Boolean,
)

internal fun ToolCatalogSummary.capabilityGroups(): List<CapabilityToolGroup> = buildList {
    fun native(id: String, name: String, enabled: Boolean, tools: List<com.example.mochi_pet.core.tools.BuiltInToolSummary>) {
        add(CapabilityToolGroup(id, name, "Built-in tools", if (enabled) "Enabled" else "Disabled",
            tools.map { it.name }.toSet()))
    }
    native("browser", "Agent Browser", agentBrowser.enabled, agentBrowser.tools)
    native("app-control", "App Control", appControl.enabled, appControl.tools)
    native("media", "Media Control", media.enabled, media.tools)
    add(CapabilityToolGroup("amap", "Amap Maps", "Places, routes, and weather",
        if (!amap.connected) "Connection required" else if (amap.enabled) "Connected" else "Connected - not enabled",
        amap.tools.map { it.name }.toSet(), !amap.connected))
    builtInTools.forEach { tool ->
        add(CapabilityToolGroup("builtin:${tool.name}", tool.displayName, tool.description,
            if (tool.name == "recall_memory") "Follows Daily journal" else if (tool.enabled) "Enabled" else "Disabled",
            setOf(tool.name)))
    }
    servers.forEach { server ->
        add(CapabilityToolGroup("mcp:${server.id}", server.name, "MCP service",
            if (!server.connected) "Connection required" else if (server.enabled) "Connected" else "Connected - not enabled",
            server.tools.map { it.alias }.toSet(), !server.connected))
    }
    restConnections.forEach { summary ->
        val connection = summary.connection
        add(CapabilityToolGroup("rest:${connection.id}", connection.name, "REST API",
            if (connection.tools.isEmpty()) "Add API tool" else if (connection.enabled) "Enabled" else "Disabled",
            connection.tools.map { restToolAlias(connection.id, it.id) }.toSet(), connection.tools.isEmpty()))
    }
    fun extension(id: String, name: String, summary: ExtensionProviderSummary) {
        add(CapabilityToolGroup(id, name, "Extension",
            when {
                !summary.installed -> "Install extension"
                !summary.trusted -> "Get trusted extension"
                !summary.connected -> "Connection required"
                !summary.enabled -> "Connected - not enabled"
                else -> "Connected"
            }, summary.tools.map { it.name }.toSet(), !summary.installed || !summary.trusted || !summary.connected))
    }
    extension("mijia", "Mi Home extension", mijia)
    extension("termux", "Termux extension", termux)
    extension("webdav", "WebDAV Files extension", webdav)
    add(CapabilityToolGroup("agentlink", "AgentLink", "Shared computer conversations",
        when {
            !agentLink.installed -> "Install"
            !agentLink.authorized || !agentLink.connected -> "Connection required"
            !agentLink.enabled -> "Connected - not enabled"
            else -> "Connected"
        }, AGENTLINK_TOOLS, !agentLink.installed || !agentLink.authorized || !agentLink.connected))
}

internal fun List<CapabilityToolGroup>.groupForTool(tool: String): CapabilityToolGroup? =
    firstOrNull { tool in it.tools } ?: run {
        val id = when {
            tool.startsWith("browser_") -> "browser"
            tool in APP_TOOL_NAMES -> "app-control"
            tool in MEDIA_TOOL_NAMES -> "media"
            tool.startsWith("amap_") -> "amap"
            tool.startsWith("mijia_") -> "mijia"
            tool.startsWith("termux_") -> "termux"
            tool.startsWith("webdav_") -> "webdav"
            tool.startsWith("agentlink_") -> "agentlink"
            else -> null
        }
        firstOrNull { it.id == id }
    }

internal fun ToolCatalogSummary.capabilityDependencies(skill: MochiSkill): List<CapabilityDependency> {
    val groups = capabilityGroups()
    val ready = readyToolNames() + "request_user_assistance"
    fun dependencies(names: Set<String>, optional: Boolean): List<CapabilityDependency> =
        names.groupBy { groups.groupForTool(it)?.id ?: it }.map { (_, tools) ->
            val group = groups.groupForTool(tools.first())
            CapabilityDependency(group, group?.name ?: tools.first(), tools.toSet(), optional, false, ready.containsAll(tools))
        }
    return dependencies(skill.requiredToolNames, false) +
        skillReadiness(skill).anyToolRequirements.map { (name, tools) ->
            val serverId = when (skill.id) {
                "builtin:notion-knowledge" -> com.example.mochi_pet.core.mcp.NOTION_SERVER_ID
                "builtin:tencent-docs-knowledge" -> com.example.mochi_pet.core.mcp.TENCENT_DOCS_SERVER_ID
                "builtin:feishu-knowledge" -> com.example.mochi_pet.core.mcp.FEISHU_SERVER_ID
                else -> null
            }
            val group = groups.firstOrNull { it.id == "mcp:$serverId" }
            CapabilityDependency(group, group?.name ?: name, tools, false, true, tools.any { it in ready })
        } + dependencies(skill.optionalToolNames, true)
}

internal fun capabilitySkillStatus(skill: MochiSkill, catalog: ToolCatalogSummary, loading: Boolean): String = when {
    skill.id == "builtin:memory-recall" -> "Follows Daily journal"
    loading -> "Checking"
    !catalog.skillReadiness(skill).isReady -> if (skill.enabled) "Suspended" else "Needs setup"
    skill.enabled -> "Enabled"
    else -> "Disabled"
}

internal fun ToolCatalogSummary.skillsAffectedByDisabling(skills: List<MochiSkill>, names: Set<String>): List<MochiSkill> =
    skills.filter { skill ->
        val readiness = skillReadiness(skill)
        skill.enabled && readiness.isReady && (
            readiness.requiredTools.any { it in names } ||
                readiness.anyToolRequirements.values.any { tools -> tools.isNotEmpty() && (tools - names).isEmpty() }
            )
    }
