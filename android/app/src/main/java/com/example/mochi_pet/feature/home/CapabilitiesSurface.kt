package com.example.mochi_pet.feature.home

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.mochi_pet.core.skills.MochiSkill
import com.example.mochi_pet.core.tools.skillReadiness

@Composable
internal fun CapabilityCatalogSurface(
    skills: SkillsUiState,
    tools: ToolsUiState,
    initialSkillsOnly: Boolean = false,
    onExplore: () -> Unit,
    onCheckUpdates: () -> Unit,
    skillContent: @Composable (MochiSkill) -> Unit,
    toolContent: @Composable (String, @Composable () -> Unit) -> Unit,
) {
    var query by rememberSaveable { mutableStateOf("") }
    var filter by rememberSaveable { mutableStateOf(if (initialSkillsOnly) "Skills" else "All") }
    var routes by rememberSaveable { mutableStateOf(listOf<String>()) }
    val listState = rememberLazyListState()
    val catalog = tools.catalog
    val groups = catalog.capabilityGroups()
    val loading = tools.isLoading || skills.isLoading
    fun open(route: String) {
        val existing = routes.indexOf(route)
        routes = if (existing >= 0) routes.take(existing + 1) else routes + route
    }
    fun back() { routes = routes.dropLast(1) }
    BackHandler(routes.isNotEmpty(), ::back)
    val route = routes.lastOrNull()
    val selectedSkill = if (route?.startsWith("skill/") == true) skills.skills.firstOrNull { it.id == route.removePrefix("skill/") } else null
    val selectedGroup = if (route?.startsWith("tool/") == true) groups.firstOrNull { it.id == route.removePrefix("tool/") } else null

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.fillMaxSize()) {
            if (route != null) {
                TextButton(onClick = ::back, modifier = Modifier.testTag("capabilities-back")) { Text("Back to capabilities") }
                if (selectedSkill != null) {
                    LazyColumn(Modifier.weight(1f).testTag("capability-detail"), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        item { skillContent(selectedSkill) }
                        if (loading) item { Text("Checking") }
                        else {
                            val dependencies = catalog.capabilityDependencies(selectedSkill)
                            if (dependencies.isNotEmpty()) item {
                                Text("Tools and permissions", fontWeight = FontWeight.Bold)
                                Text("Tool permissions are shared. Enabling a Skill never grants access automatically.",
                                    style = MaterialTheme.typography.bodySmall)
                            }
                            items(dependencies, key = { "${it.optional}:${it.group?.id ?: it.name}" }) { dependency ->
                                val status = when {
                                    dependency.optional -> "Optional"
                                    dependency.ready -> "Ready"
                                    else -> "Needs setup"
                                }
                                CapabilityRow(
                                    name = dependency.name,
                                    kind = if (dependency.anyTool) "At least one enabled tool" else if (dependency.optional) "Does not block this Skill" else "All required tools",
                                    status = status,
                                    description = if (dependency.group != null) {
                                        if (dependency.ready) "Review shared permissions" else "Configure without leaving this Skill"
                                    } else if (dependency.ready) "Provided by Mochi"
                                    else if (dependency.optional) "Optional tool is not available."
                                    else "Required tool is not registered. Add its service or repair the Skill declaration.",
                                    tag = "dependency-${dependency.group?.id ?: dependency.name}",
                                    isSkill = false,
                                    onClick = dependency.group?.let { group -> { open("tool/${group.id}") } },
                                )
                            }
                        }
                    }
                } else if (selectedGroup != null || route == "add") {
                    val related = selectedGroup?.let { group ->
                        skills.skills.filter { skill -> catalog.capabilityDependencies(skill).any { it.group?.id == group.id } }
                    }.orEmpty()
                    key(route) {
                        toolContent(selectedGroup?.id ?: "add") {
                            if (related.isNotEmpty()) {
                                Text("Related Skills", fontWeight = FontWeight.Bold)
                                Text("Turning off required tools pauses dependent Skills; their saved switches are kept. Optional tools only remove the extra capability.",
                                    style = MaterialTheme.typography.bodySmall)
                                related.forEach { skill ->
                                    val required = catalog.capabilityDependencies(skill).any { it.group?.id == selectedGroup?.id && !it.optional }
                                    CapabilityRow(skill.name, if (required) "Required dependency" else "Optional enhancement",
                                        capabilitySkillStatus(skill, catalog, loading), skill.description, "related-${skill.id}",
                                        isSkill = true,
                                        onClick = { open("skill/${skill.id}") })
                                }
                            }
                        }
                    }
                } else {
                    Text(if (loading) "Checking" else "This capability is no longer available.")
                }
            } else {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("Capabilities", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                    TextButton(onClick = { open("add") }, modifier = Modifier.testTag("add-capability")) { Text("Add tool") }
                }
                OutlinedTextField(
                    value = query, onValueChange = { query = it }, singleLine = true,
                    label = { Text("Search skills, tools, or services") },
                    modifier = Modifier.fillMaxWidth().testTag("capability-search"),
                )
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("All", "Skills", "Tools and services", "Needs setup").forEach { label ->
                        FilterChip(selected = filter == label, onClick = { filter = label },
                            label = { Text(label) }, modifier = Modifier.testTag("capability-filter-$label"))
                    }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    TextButton(onClick = onExplore) { Text("Discover Skills") }
                    TextButton(onClick = onCheckUpdates, enabled = !skills.isLoading) { Text("Check for updates") }
                }
                skills.feedback?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                tools.feedback?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                if (loading) CircularProgressIndicator(Modifier.padding(12.dp))
                val search = query.trim()
                fun matches(vararg texts: String) = texts.any {
                    it.contains(search, ignoreCase = true) || localizeUiText(it).contains(search, ignoreCase = true)
                }
                val visibleSkills = skills.skills.filter {
                    filter != "Tools and services" &&
                        (filter != "Needs setup" || (!loading && it.id != "builtin:memory-recall" && !catalog.skillReadiness(it).isReady)) &&
                        matches(it.name, it.description, it.source)
                }.sortedBy { it.id != "builtin:memory-recall" }
                val visibleGroups = groups.filter {
                    filter != "Skills" &&
                        (filter != "Needs setup" || (!tools.isLoading && it.needsSetup)) &&
                        matches(it.name, it.description, *it.tools.toTypedArray())
                }
                LazyColumn(Modifier.weight(1f).testTag("capability-list"), state = listState, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    items(visibleSkills, key = { "skill/${it.id}" }) { skill ->
                        CapabilityRow(skill.name, "Skill", capabilitySkillStatus(skill, catalog, loading), skill.description,
                            "capability-skill-${skill.id}", isSkill = true, onClick = { open("skill/${skill.id}") })
                    }
                    items(visibleGroups, key = { "tool/${it.id}" }) { group ->
                        CapabilityRow(group.name, if (group.id.startsWith("builtin:")) "Built-in tool" else "Tools and services",
                            if (tools.isLoading) "Checking" else group.status, group.description,
                            "capability-tool-${group.id}", isSkill = false, onClick = { open("tool/${group.id}") })
                    }
                    if (!loading && visibleSkills.isEmpty() && visibleGroups.isEmpty()) item { Text("No matching capabilities.") }
                    item { Spacer(Modifier.height(8.dp)) }
                }
            }
        }
    }
}

@Composable
private fun CapabilityRow(
    name: String,
    kind: String,
    status: String,
    description: String,
    tag: String,
    isSkill: Boolean,
    onClick: (() -> Unit)?,
) {
    val colors = MaterialTheme.colorScheme
    val accent = if (isSkill) colors.secondary else colors.primary
    Surface(
        modifier = Modifier.fillMaxWidth().testTag(tag).then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier),
        shape = MaterialTheme.shapes.large,
        color = accent.copy(alpha = 0.14f).compositeOver(colors.surfaceContainer),
        contentColor = colors.onSurface,
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row {
                Text(name, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                if (onClick != null) ToolGlyphIcon(ToolGlyph.CHEVRON)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(kind, style = MaterialTheme.typography.labelSmall, color = accent, modifier = Modifier.weight(1f))
                Text(status, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.weight(1f))
            }
            Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
