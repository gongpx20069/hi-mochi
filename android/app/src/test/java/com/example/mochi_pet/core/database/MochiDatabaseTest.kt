package com.example.mochi_pet.core.database

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.mochi_pet.core.agent.tool.ToolExecutionContext
import com.example.mochi_pet.core.agent.tool.AgentTool
import com.example.mochi_pet.core.agent.tool.ToolDirectorySnapshot
import com.example.mochi_pet.core.agent.tool.ToolDiscovery
import com.example.mochi_pet.core.agent.tool.ToolRegistry
import com.example.mochi_pet.core.agent.tool.functionToolSchema
import com.example.mochi_pet.core.database.entity.CalendarEventEntity
import com.example.mochi_pet.core.database.entity.SkillEntity
import com.example.mochi_pet.core.database.entity.TodoEntity
import com.example.mochi_pet.core.model.MochiSurface
import com.example.mochi_pet.core.skills.DownloadedSkill
import com.example.mochi_pet.core.skills.LoadSkillTool
import com.example.mochi_pet.core.skills.RoomSkillRepository
import com.example.mochi_pet.core.skills.SkillOrigin
import com.example.mochi_pet.core.skills.SkillToolDirectory
import com.example.mochi_pet.core.skills.requiredToolNames
import com.example.mochi_pet.core.skills.readiness
import com.example.mochi_pet.core.skills.prepareForModel
import com.example.mochi_pet.core.skills.resolveTools
import com.example.mochi_pet.core.agent.tool.ToolSchemaLanguage
import com.example.mochi_pet.core.tools.ToolCatalogSummary
import com.example.mochi_pet.core.tools.McpServerSummary
import com.example.mochi_pet.core.tools.McpToolSummary
import com.example.mochi_pet.core.tools.McpAuthMode
import com.example.mochi_pet.core.tools.skillReadiness
import java.time.Instant
import java.time.LocalDate
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class MochiDatabaseTest {
    private lateinit var database: MochiDatabase

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(
            context,
            MochiDatabase::class.java,
        ).build()
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun `calendar query returns only events overlapping range`() = runBlocking {
        val dao = database.calendarEventDao()
        dao.upsert(
            calendarEvent(
                id = "inside",
                start = "2026-08-05T01:00:00Z",
                end = "2026-08-05T02:00:00Z",
            ),
        )
        dao.upsert(
            calendarEvent(
                id = "outside",
                start = "2026-08-06T01:00:00Z",
                end = "2026-08-06T02:00:00Z",
            ),
        )

        val events = dao.listOverlapping(
            rangeStartEpochMillis =
                Instant.parse("2026-08-05T00:00:00Z").toEpochMilli(),
            rangeEndEpochMillis =
                Instant.parse("2026-08-06T00:00:00Z").toEpochMilli(),
        )

        assertEquals(listOf("inside"), events.map { it.id })
    }

    @Test
    fun `todo query keeps date boundary and priority order`() = runBlocking {
        val dao = database.todoDao()
        val date = LocalDate.of(2026, 8, 5)
        dao.upsert(todo(id = "normal", date = date, priority = "NORMAL"))
        dao.upsert(todo(id = "high", date = date, priority = "HIGH"))
        dao.upsert(
            todo(
                id = "other-day",
                date = date.plusDays(1),
                priority = "HIGH",
            ),
        )

        val todos = dao.listForDate(date.toEpochDay())

        assertEquals(listOf("high", "normal"), todos.map { it.id })
    }

    @Test
    fun `active todo query carries unfinished prior dates forward`() = runBlocking {
        val dao = database.todoDao()
        val today = LocalDate.of(2026, 8, 5)
        dao.upsert(
            todo(id = "overdue", date = today.minusDays(1), priority = "NORMAL"),
        )
        dao.upsert(todo(id = "today", date = today, priority = "NORMAL"))
        dao.upsert(
            todo(id = "future", date = today.plusDays(1), priority = "NORMAL"),
        )
        dao.upsert(
            todo(
                id = "completed",
                date = today.minusDays(2),
                priority = "NORMAL",
            ).copy(
                status = "COMPLETED",
            ),
        )

        val todos = dao.listActiveThroughDate(today.toEpochDay())

        assertEquals(listOf("overdue", "today"), todos.map { it.id })
    }

    @Test
    fun `Mi Home skill upgrades verification guidance without losing enablement`() = runBlocking {
        val repository = RoomSkillRepository(database.skillDao())
        val id = "builtin:mi-home-smart-home"
        repository.setEnabled(id, true)
        val stored = database.skillDao().listAll().single { it.id == id }
        database.skillDao().upsert(stored.copy(content = "Old acceptance-only instructions"))
        val upgraded = repository.listSkills().single { it.id == id }
        assertEquals(true, upgraded.enabled)
        assertEquals(true, upgraded.content.contains("verification.status"))
        assertEquals(true, upgraded.content.contains("unverified result"))
        assertFalse(upgraded.content.contains("older extension"))
        assertEquals(false, upgraded.content.contains("Old acceptance-only instructions"))
    }

    @Test
    fun `built in Skill instructions and metadata are English without translation`() = runBlocking {
        val repository = RoomSkillRepository(database.skillDao(), journalEnabled = { true })
        val builtIns = repository.listSkills().filter { it.origin == SkillOrigin.BUILT_IN }
        builtIns.filter { it.id != "builtin:memory-recall" }.forEach { repository.setEnabled(it.id, true) }
        val available = builtIns.flatMap { it.requiredToolNames }.toSet() +
            setOf("notion_search", "tencent_docs_get_content", "feishu_search_doc")
        repository.listEnabledMetadata().forEach {
            assertTrue(it.name, ToolSchemaLanguage.isEnglishText(it.description))
        }
        builtIns.forEach { skill ->
            assertTrue(skill.id, ToolSchemaLanguage.isEnglishText(skill.content))
            val name = skill.id.substringAfter("builtin:")
            if (skill.readiness(available).isReady) {
                val result = LoadSkillTool(repository, available).execute(
                    buildJsonObject { put("skill_name", name) },
                    ToolExecutionContext(LocalDate.of(2026, 8, 5), MochiSurface.Conversation),
                )
                assertEquals(name, "ok", result.status)
                assertTrue(name, ToolSchemaLanguage.isEnglishText(result.data!!.jsonObject.getValue("content").jsonPrimitive.content))
            }
        }
    }

    @Test
    fun `market Skill model copies preserve source dependency declarations and recheck revocation`() = runBlocking {
        val repository = RoomSkillRepository(database.skillDao())
        val original = repository.install(DownloadedSkill(
            marketId = "fixture/skills/english-view", name = "english-view",
            description = "读取页面", content = "---\nname: english-view\nallowed-tools: browser_read\n---\n# 读取\n先读取页面。",
            source = "本地", sourceUrl = "https://example.invalid/SKILL.md", version = "1", digest = "fixture",
        ))
        repository.setEnabled(original.id, true)
        val translations = mapOf("读取页面" to "Read a page", "本地" to "Local", "# 读取\n先读取页面。" to "# Read\nRead the page first.")
        val prepare: suspend (String) -> String = { translations[it] ?: ToolSchemaLanguage.requireEnglishText(it) }
        val metadata = repository.listEnabledMetadata(setOf("browser_read")).prepareForModel(prepare)
            .single { it.name == "english-view" }
        assertEquals("Read a page", metadata.description)
        val args = buildJsonObject { put("skill_name", "english-view") }
        val context = ToolExecutionContext(LocalDate.of(2026, 8, 5), MochiSurface.Conversation)
        val result = LoadSkillTool(repository, setOf("browser_read"), prepareText = prepare).execute(args, context)
        val document = result.data!!.jsonObject.getValue("content").jsonPrimitive.content
        assertTrue(document.contains("allowed-tools: browser_read"))
        assertTrue(document.contains("# Read\nRead the page first."))
        assertTrue(ToolSchemaLanguage.isEnglishText(document))
        assertEquals(original.content, repository.loadEnabledSkill("english-view")!!.content)
        assertEquals(original.description, repository.listSkills().single { it.id == original.id }.description)
        val revoked = LoadSkillTool(repository, setOf("browser_read"), prepareText = {
            repository.setEnabled(original.id, false)
            prepare(it)
        }).execute(args, context)
        assertEquals("STALE_REF", revoked.code)
    }

    @Test
    fun `optional Skill tools revoked during translation cannot remain in the returned header`() = runBlocking {
        val repository = RoomSkillRepository(database.skillDao())
        var available = setOf("app_read", "media_read")
        val tool = LoadSkillTool(repository, available, prepareText = {
            available = setOf("app_read")
            it
        }, availableToolNamesProvider = { available })
        val result = tool.execute(
            buildJsonObject { put("skill_name", "app-assistant") },
            ToolExecutionContext(LocalDate.of(2026, 8, 5), MochiSurface.Conversation),
        )
        assertEquals("STALE_REF", result.code)
    }

    @Test
    fun `Skill relation resolution shares readiness and headers across mandatory optional and provider tools`() = runBlocking {
        val repository = RoomSkillRepository(database.skillDao())
        val names = setOf("webdav_list", "webdav_read", "webdav_write", "notion_search", "notion_update_page", "unrelated")
        for ((name, expected) in mapOf(
            "webdav-files" to setOf("webdav_list", "webdav_read", "webdav_write"),
            "notion-knowledge" to setOf("notion_search", "notion_update_page"),
        )) {
            repository.setEnabled("builtin:$name", true)
            val skill = repository.loadEnabledSkill(name)!!
            val resolved = skill.resolveTools(names)
            assertEquals(expected, resolved.toolNames)
            assertTrue(resolved.readiness.isReady)
            assertEquals(resolved.readiness, skill.readiness(names))
            val result = LoadSkillTool(repository, names).execute(
                buildJsonObject { put("skill_name", name) },
                ToolExecutionContext(LocalDate.of(2026, 8, 5), MochiSurface.Conversation),
            )
            val document = result.data!!.jsonObject.getValue("content").jsonPrimitive.content
            assertTrue(document.contains("allowed-tools: ${expected.sorted().joinToString(" ")}"))
            assertFalse(skill.resolveTools(emptySet()).readiness.isReady)
        }
    }

    @Test
    fun `Skill directory uses current actor definitions and skips schemas already supplied`() = runBlocking {
        val repository = RoomSkillRepository(database.skillDao())
        repository.setEnabled("builtin:notion-knowledge", true)
        var enabled = setOf("notion_search", "notion_update_page", "load_skill")
        val load = LoadSkillTool(repository, enabled, availableToolNamesProvider = { enabled })
        fun tool(name: String) = object : AgentTool {
            override val name = name
            override val schema = functionToolSchema(name, "Read or update a Notion page.", buildJsonObject {}, emptyList())
            override suspend fun execute(arguments: JsonObject, context: ToolExecutionContext) =
                error("The directory must not execute a business Tool")
        }
        val registry = ToolRegistry(
            listOf(load, tool("notion_search"), tool("notion_update_page"), tool("notion_disabled"), tool("unrelated")),
            availableNames = { it.intersect(enabled) },
        )
        val discovery = ToolDiscovery(registry)
        val context = ToolExecutionContext(LocalDate.of(2026, 8, 5), MochiSurface.Conversation)
        val supplied = discovery.schemas().mapTo(mutableSetOf(), ToolDiscovery::schemaName)
        suspend fun document(names: Set<String>) = discovery.execute(
            "load_skill", """{"skill_name":"notion-knowledge"}""", context, names,
        ).data!!.jsonObject.getValue("content").jsonPrimitive.content
        val first = document(supplied)
        assertTrue(first.contains("notion_search: Read or update"))
        assertTrue(first.contains("notion_update_page: Read or update"))
        assertFalse(first.contains("notion_disabled"))
        assertFalse(first.substringAfter(SkillToolDirectory.HEADING).contains("unrelated"))
        discovery.execute("tool_search", """{"query":"notion_search","limit":1}""", context, supplied)
        assertTrue(document(supplied).contains("notion_search:"))
        val next = document(discovery.schemas().mapTo(mutableSetOf(), ToolDiscovery::schemaName))
        assertFalse(next.contains("notion_search:"))
        assertTrue(next.contains("notion_update_page:"))
        enabled = enabled - "notion_update_page"
        assertFalse(document(supplied).substringAfter(SkillToolDirectory.HEADING).contains("notion_update_page"))
    }

    @Test
    fun `Skills using only base Tools return no redundant directory`() = runBlocking {
        val repository = RoomSkillRepository(database.skillDao())
        val names = setOf("app_read", "app_input", "media_read")
        val result = LoadSkillTool(repository, names).execute(
            buildJsonObject { put("skill_name", "app-assistant") },
            ToolExecutionContext(
                LocalDate.of(2026, 8, 5), MochiSurface.Conversation,
                toolDirectory = ToolDirectorySnapshot(
                    names.associateWith { "Already available base Tool." }, names,
                ),
            ),
        )
        assertEquals("ok", result.status)
        val data = result.data!!.jsonObject
        assertFalse(data.getValue("content").jsonPrimitive.content.contains(SkillToolDirectory.HEADING))
        assertFalse(data.containsKey("related_tool_count"))
        assertFalse(data.containsKey("next_tool_cursor"))
    }

    @Test
    fun `Skill directory pages are bounded complete and reject changed snapshots`() = runBlocking {
        val repository = RoomSkillRepository(database.skillDao())
        repository.setEnabled("builtin:notion-knowledge", true)
        val descriptions = (1..250).associate { "notion_tool_${it.toString().padStart(3, '0')}" to "Read ".repeat(30) }
        var available = descriptions.keys
        var preparations = 0
        val load = LoadSkillTool(repository, available, prepareText = { preparations++; it }, availableToolNamesProvider = { available })
        val context = ToolExecutionContext(
            LocalDate.of(2026, 8, 5), MochiSurface.Conversation,
            toolDirectory = ToolDirectorySnapshot(descriptions, emptySet()),
        )
        suspend fun page(cursor: String? = null, executionContext: ToolExecutionContext = context) = load.execute(
            buildJsonObject { put("skill_name", "notion-knowledge"); cursor?.let { put("tool_cursor", it) } }, executionContext,
        )
        val first = page().data!!.jsonObject
        val firstCursor = first.getValue("next_tool_cursor").jsonPrimitive.content
        val initialPreparations = preparations
        val seen = mutableListOf<String>()
        var data = first
        while (true) {
            val content = data.getValue("content").jsonPrimitive.content
            val directory = SkillToolDirectory.HEADING + content.substringAfter(SkillToolDirectory.HEADING)
            assertTrue(directory.toByteArray(Charsets.UTF_8).size <= 12 * 1024)
            seen += directory.lines().filter { it.startsWith("notion_tool_") }.map { it.substringBefore(':') }
            val cursor = data["next_tool_cursor"]?.jsonPrimitive?.content ?: break
            data = page(cursor).data!!.jsonObject
            assertTrue(data.getValue("content").jsonPrimitive.content.startsWith(SkillToolDirectory.HEADING))
        }
        assertEquals(descriptions.keys.sorted(), seen)
        assertEquals(initialPreparations, preparations)
        assertEquals("STALE_REF", page(firstCursor, context.copy(toolDirectory =
            ToolDirectorySnapshot(descriptions, setOf(descriptions.keys.first())))).code)
        assertEquals("STALE_REF", page(firstCursor, context.copy(toolDirectory =
            ToolDirectorySnapshot(descriptions + (descriptions.keys.first() to "Changed operation."), emptySet()))).code)
        assertThrows(com.example.mochi_pet.core.agent.tool.ToolInputException::class.java) {
            runBlocking { page("invalid") }
        }
        available = available - descriptions.keys.last()
        assertEquals("STALE_REF", page(firstCursor).code)
    }

    @Test
    fun `duplicate enabled Skill names cannot silently select a different tools scope`() = runBlocking {
        val repository = RoomSkillRepository(database.skillDao())
        for (index in 1..2) {
            val installed = repository.install(DownloadedSkill(
                marketId = "fixture/duplicate-$index", name = "duplicate", description = "Read",
                content = "---\nname: duplicate\nallowed-tools: remote_$index\n---\nRead.",
                source = "fixture", sourceUrl = "https://example.invalid/SKILL.md", version = "1", digest = "fixture-$index",
            ))
            repository.setEnabled(installed.id, true)
        }
        assertThrows(com.example.mochi_pet.core.agent.tool.ToolInputException::class.java) {
            runBlocking { repository.loadEnabledSkill("duplicate") }
        }
        repository.setEnabled("fixture/duplicate-2", false)
        assertEquals(setOf("remote_1"), repository.loadEnabledSkill("duplicate")!!.resolveTools(setOf("remote_1", "remote_2")).toolNames)
    }

    @Test
    fun `named Skill lookup does not read unrelated journal permissions and never caches revocation`() = runBlocking {
        var permissionReads = 0
        var journal = true
        val repository = RoomSkillRepository(database.skillDao(), journalEnabled = {
            permissionReads++
            journal
        })
        repository.setEnabled("builtin:notion-knowledge", true)
        assertTrue(repository.loadEnabledSkill("notion-knowledge") != null)
        assertEquals(0, permissionReads)
        repository.setEnabled("builtin:notion-knowledge", false)
        assertEquals(null, repository.loadEnabledSkill("notion-knowledge"))
        assertEquals(0, permissionReads)
        assertTrue(repository.loadEnabledSkill("memory-recall") != null)
        journal = false
        assertEquals(null, repository.loadEnabledSkill("memory-recall"))
        assertEquals(2, permissionReads)
    }

    @Test
    fun `market skill can be installed and locally edited`() = runBlocking {
        val repository = RoomSkillRepository(database.skillDao())
        repository.install(
            DownloadedSkill(
                marketId = "owner/repo/example",
                name = "example",
                description = "Example skill",
                content = "# Example",
                source = "owner/repo",
                sourceUrl = "https://example.test/SKILL.md",
                version = "1.0.0",
                digest = "upstream",
            ),
        )

        val installed = repository.listSkills().first {
            it.origin == SkillOrigin.MARKET
        }
        val edited = repository.updateContent(
            installed.id,
            "# Locally edited",
        )

        assertEquals(false, installed.enabled)
        assertEquals(true, edited.modified)
        val skills = repository.listSkills()
        assertEquals(19, skills.size)
        assertEquals(
            listOf("Browser"),
            skills.filter { it.id == "builtin:browser" }.map { it.name },
        )
        assertEquals(
            true,
            skills.first { it.name == "Product Search" }.content.contains(
                "https://www.bing.com/search?q=",
            ),
        )
        assertEquals(
            true,
            skills.first { it.name == "Douban Ratings" }.content.contains(
                "https://m.douban.com/home_guide",
            ),
        )
    }

    @Test
    fun `Tencent Skill upgrades workflows and accepts either real search entry point`() = runBlocking {
        val repository = RoomSkillRepository(database.skillDao())
        val id = "builtin:tencent-docs-knowledge"
        repository.setEnabled(id, true)
        val stored = database.skillDao().listAll().single { it.id == id }
        database.skillDao().upsert(stored.copy(content = "Old search-only guidance"))
        val skill = repository.listSkills().single { it.id == id }
        assertTrue(skill.enabled)
        assertTrue("Keep the operational Skill concise", skill.content.length <= 5_500)
        for (instruction in listOf("create_smartcanvas_by_mdx", "content_format=\"markdown\"",
            "smartcanvas_read", "smartcanvas_edit", "INSERT_AFTER", "MDX", "read back",
            "Word:", "Spreadsheet:", "SmartSheet:", "Slides:", "empty file is not completing")) {
            assertTrue(instruction, skill.content.contains(instruction, ignoreCase = true))
        }
        assertTrue(skill.content.contains("remove_type=all"))
        assertTrue(skill.content.contains("not proof of deletion"))
        assertFalse(skill.content.contains("Old search-only guidance"))
        for (search in listOf("tencent_docs_manage_search_file", "tencent_docs_search_space_file")) {
            val available = setOf(search)
            assertTrue(skill.readiness(available).isReady)
            assertTrue(repository.listEnabledMetadata(available).any { it.name == "tencent-docs-knowledge" })
            val arguments = buildJsonObject { put("skill_name", "tencent-docs-knowledge") }
            val context = ToolExecutionContext(LocalDate.of(2026, 1, 1), MochiSurface.Face)
            assertEquals("ok", LoadSkillTool(repository, available).execute(arguments, context).status)
            assertFalse(skill.readiness(available - search).isReady)
            assertFalse(repository.listEnabledMetadata(available - search).any { it.name == "tencent-docs-knowledge" })
            assertEquals("error", LoadSkillTool(repository, available - search).execute(arguments, context).status)
        }
    }

    @Test
    fun `skill loading uses full deferred availability and rechecks revocation`() = runBlocking {
        val repository = RoomSkillRepository(database.skillDao())
        repository.setEnabled("builtin:feishu-knowledge", true)
        var available = setOf("feishu_search_doc")
        val tool = LoadSkillTool(repository, available) { available }
        val discovery = com.example.mochi_pet.core.agent.tool.ToolDiscovery(
            com.example.mochi_pet.core.agent.tool.ToolRegistry(listOf(tool)),
        )
        val names = discovery.schemas().mapTo(mutableSetOf(),
            com.example.mochi_pet.core.agent.tool.ToolDiscovery::schemaName)
        val context = ToolExecutionContext(LocalDate.of(2026, 11, 17), MochiSurface.Face)
        val arguments = """{"skill_name":"feishu-knowledge"}"""
        assertFalse("feishu_search_doc" in names)
        val result = discovery.execute("load_skill", arguments, context, names)
        assertEquals("ok", result.status)
        assertTrue(result.data.toString().contains("feishu_search_doc"))
        available = emptySet()
        assertEquals("PERMISSION_DENIED", discovery.execute("load_skill", arguments, context, names).code)
    }

    @Test
    fun `Feishu skill stays optional and allows any enabled document tool`() = runBlocking {
        val repository = RoomSkillRepository(database.skillDao())
        val id = "builtin:feishu-knowledge"
        val skill = repository.listSkills().single { it.id == id }
        assertFalse(skill.enabled)
        val tools = setOf("feishu_search_doc", "feishu_fetch_doc", "feishu_list_docs",
            "feishu_create_doc", "feishu_update_doc")
        repository.setEnabled(id, true)
        assertTrue(repository.listEnabledMetadata(tools).any { it.name == "feishu-knowledge" })
        tools.forEach { tool ->
            assertTrue(repository.listEnabledMetadata(setOf(tool)).any { it.name == "feishu-knowledge" })
        }
        assertFalse(repository.listEnabledMetadata(emptySet()).any { it.name == "feishu-knowledge" })
        assertTrue(skill.content.contains("fetch the affected content"))
        assertTrue(skill.content.contains("Never repeat create/update"))
    }

    @Test
    fun `document Skills share any-tool gating across UI discovery and loading and expose only actual tools`() = runBlocking {
        val repository = RoomSkillRepository(database.skillDao())
        val context = ToolExecutionContext(LocalDate.of(2026, 11, 1), MochiSurface.Conversation)
        for ((id, tool) in listOf(
            "notion-knowledge" to "notion_update_page",
            "tencent-docs-knowledge" to "tencent_docs_smartcanvas_edit",
            "feishu-knowledge" to "feishu_fetch_doc",
        )) {
            val skill = repository.setEnabled("builtin:$id", true)
            val server = McpServerSummary(
                id = id, name = id, endpoint = "https://example.com/mcp", builtIn = true,
                enabled = true, connected = true, authMode = McpAuthMode.TOKEN,
                tools = listOf(McpToolSummary(tool, tool, "", true)),
            )
            val ready = ToolCatalogSummary(servers = listOf(server))
            assertTrue(ready.skillReadiness(skill).isReady)
            for (unavailable in listOf(
                ready.copy(servers = listOf(server.copy(enabled = false))),
                ready.copy(servers = listOf(server.copy(connected = false))),
                ready.copy(servers = listOf(server.copy(tools = server.tools.map { it.copy(enabled = false) }))),
                ToolCatalogSummary(),
            )) {
                assertFalse(unavailable.skillReadiness(skill).isReady)
            }
            for (available in listOf(setOf(tool), setOf(tool, "browser_read", "manage_mochi_todo"))) {
                assertTrue(repository.listEnabledMetadata(available).any { it.name == id })
                val loaded = LoadSkillTool(repository, available).execute(buildJsonObject { put("skill_name", id) }, context)
                assertEquals("ok", loaded.status)
                val content = checkNotNull(loaded.data).jsonObject.getValue("content").jsonPrimitive.content
                assertEquals("allowed-tools: $tool", content.lineSequence().single { it.startsWith("allowed-tools:") })
            }
            assertFalse(repository.listEnabledMetadata(setOf("browser_read")).any { it.name == id })
            assertEquals("PERMISSION_DENIED", LoadSkillTool(repository, emptySet())
                .execute(buildJsonObject { put("skill_name", id) }, context).code)
            assertTrue(repository.listSkills().single { it.id == skill.id }.enabled)
        }
    }

    @Test
    fun `all other built-in Skills require every mandatory tool and expose available optional capabilities only`() = runBlocking {
        val repository = RoomSkillRepository(database.skillDao(), journalEnabled = { true })
        val context = ToolExecutionContext(LocalDate.of(2026, 11, 1), MochiSurface.Conversation)
        for (initial in repository.listSkills().filter { it.requiredToolNames.isNotEmpty() }) {
            val skill = if (initial.id == "builtin:memory-recall") initial else repository.setEnabled(initial.id, true)
            val name = skill.id.removePrefix("builtin:")
            assertTrue(skill.readiness(skill.requiredToolNames).isReady)
            for (missing in skill.requiredToolNames) {
                val available = skill.requiredToolNames - missing
                assertFalse(skill.readiness(available).isReady)
                assertFalse(ToolCatalogSummary().skillReadiness(skill).isReady)
                assertEquals("PERMISSION_DENIED", LoadSkillTool(repository, available)
                    .execute(buildJsonObject { put("skill_name", name) }, context).code)
            }
            val available = skill.requiredToolNames + setOf("request_user_assistance", "get_current_location", "unrelated_tool")
            val result = LoadSkillTool(repository, available).execute(buildJsonObject { put("skill_name", name) }, context)
            assertEquals("ok", result.status)
            val header = checkNotNull(result.data).jsonObject.getValue("content").jsonPrimitive.content
                .lineSequence().single { it.startsWith("allowed-tools:") }.removePrefix("allowed-tools: ").split(" ").toSet()
            assertTrue(available.containsAll(header))
            assertFalse("unrelated_tool" in header)
            if (name in setOf("product-search", "douban-ratings", "us-stock-analysis", "travel-planning")) {
                assertTrue("request_user_assistance" in header)
            }
        }
    }

    @Test
    fun `built-in prompts contain current workflows rather than retired operations or compatibility history`() = runBlocking {
        val skills = RoomSkillRepository(database.skillDao()).listSkills()
        val retiredGuidance = Regex(
            """\b(legacy|older extension|previous version|deprecated|formerly|search_web|fetch_web_page|dianping_\w*)\b""",
            RegexOption.IGNORE_CASE,
        )
        for (skill in skills) {
            assertFalse(skill.name, retiredGuidance.containsMatchIn(skill.content))
        }
    }

    @Test
    fun `market tool declarations retain multiline dependencies and invalid declarations cannot bypass readiness`() = runBlocking {
        val repository = RoomSkillRepository(database.skillDao())
        val context = ToolExecutionContext(LocalDate.of(2026, 11, 1), MochiSurface.Conversation)
        val declarations = listOf(
            "browser_read browser_scroll",
            "[browser_read, 'browser_scroll']",
            "\n  - browser_read\n  - browser_scroll",
            "\n- browser_read\n- browser_scroll",
            ">-\n  browser_read\n  browser_scroll",
            "|-\n  browser_read\n  browser_scroll",
        )
        for ((index, declaration) in declarations.withIndex()) {
            val name = "fixture-$index"
            val content = "---\nname: $name\ndescription: Fixture\nallowed-tools: $declaration\n---\nRead a page."
            val installed = repository.install(DownloadedSkill(
                "fixture/$name", name, "Fixture", content, "fixture", "", null, "fixture",
            ))
            val skill = repository.setEnabled(installed.id, true)
            assertEquals(setOf("browser_read", "browser_scroll"), skill.requiredToolNames)
            assertFalse(skill.readiness(setOf("browser_read")).isReady)
            assertEquals("PERMISSION_DENIED", LoadSkillTool(repository, setOf("browser_read"))
                .execute(buildJsonObject { put("skill_name", name) }, context).code)
            assertTrue(skill.readiness(skill.requiredToolNames).isReady)
            assertThrows(IllegalArgumentException::class.java) {
                runBlocking { repository.updateContent(skill.id, content.replace(declaration, "{unknown: structure}")) }
            }
            val stored = checkNotNull(database.skillDao().getById(skill.id))
            database.skillDao().upsert(stored.copy(content = content.replace(declaration, "{unknown: structure}")))
            val invalid = repository.listSkills().single { it.id == skill.id }
            assertFalse(ToolCatalogSummary().skillReadiness(invalid).isReady)
            assertTrue(invalid.readiness(emptySet()).declarationError != null)
            assertFalse(repository.listEnabledMetadata().any { it.name == name })
            assertEquals("PERMISSION_DENIED", LoadSkillTool(repository, emptySet())
                .execute(buildJsonObject { put("skill_name", name) }, context).code)
        }
        val assistance = repository.install(DownloadedSkill(
            "fixture/assistance", "assistance", "Fixture",
            "---\nname: assistance\nallowed-tools: request_user_assistance\n---\nAsk the user.",
            "fixture", "", null, "fixture",
        ))
        assertTrue(ToolCatalogSummary().skillReadiness(assistance).isReady)
        assertFalse(assistance.readiness(emptySet()).isReady)
    }

    @Test
    fun `built in skills persist enablement with knowledge providers disabled`() =
        runBlocking {
            val repository = RoomSkillRepository(database.skillDao())
            val initial = repository.listSkills()

            assertEquals(
                false,
                initial.first { it.name == "Notion Knowledge" }.enabled,
            )
            assertEquals(
                false,
                initial.first { it.name == "Tencent Docs Knowledge" }.enabled,
            )
            val travelPlanning = initial.first {
                it.name == "Travel Planning"
            }
            assertEquals(true, travelPlanning.enabled)
            assertTrue(
                travelPlanning.content.contains(
                    "https://kyfw.12306.cn/otn/leftTicket/init",
                ),
            )
            assertTrue(
                travelPlanning.content.contains(
                    "call undocumented 12306 JSON endpoints",
                ),
            )
            assertEquals(
                true,
                initial.first { it.name == "Merchant Discovery" }.enabled,
            )
            val miHome = initial.first {
                it.name == "Mi Home Smart Home"
            }
            assertEquals(false, miHome.enabled)
            assertEquals(
                8,
                miHome.requiredToolNames.size,
            )
            assertFalse(miHome.readiness(emptySet()).isReady)
            assertTrue(
                miHome.readiness(miHome.requiredToolNames).isReady,
            )
            val termux = initial.first { it.id == "builtin:termux" }
            assertFalse(termux.enabled)
            assertEquals(setOf("termux_exec", "termux_task"), termux.requiredToolNames)
            assertFalse(termux.readiness(setOf("termux_exec")).isReady)
            assertTrue(termux.readiness(termux.requiredToolNames).isReady)
            repository.setEnabled("builtin:termux", true)
            assertEquals(listOf("app-assistant", "termux"), repository.listEnabledMetadata(termux.requiredToolNames).map { it.name })
            assertTrue(repository.listEnabledMetadata(setOf("termux_exec")).none { it.name == "termux" })
            assertTrue(termux.content.contains("Scheduled agents and subagents"))
            repository.setEnabled("builtin:termux", false)
            val webdav = initial.first { it.id == "builtin:webdav-files" }
            assertFalse(webdav.enabled)
            repository.setEnabled(webdav.id, true)
            assertTrue(repository.listEnabledMetadata(webdav.requiredToolNames).any { it.name == "webdav-files" })
            assertTrue(repository.listEnabledMetadata(setOf("webdav_list")).none { it.name == "webdav-files" })
            assertTrue(repository.listEnabledMetadata(emptySet()).none { it.name == "webdav-files" })
            val webdavArguments = buildJsonObject { put("skill_name", "webdav-files") }
            val webdavContext = ToolExecutionContext(LocalDate.of(2026, 1, 1), MochiSurface.Face)
            assertEquals("ok", LoadSkillTool(repository, webdav.requiredToolNames).execute(webdavArguments, webdavContext).status)
            assertEquals("PERMISSION_DENIED", LoadSkillTool(repository, emptySet()).execute(webdavArguments, webdavContext).code)
            repository.setEnabled(webdav.id, false)
            assertEquals(
                true,
                initial.filter {
                    it.origin == SkillOrigin.BUILT_IN &&
                        !it.name.endsWith("Knowledge") &&
                        it.name != "Mi Home Smart Home" &&
                        it.name != "AgentLink" &&
                        it.name != "Termux" &&
                        it.name != "Memory Recall" &&
                        it.name != "WebDAV Files"
                }.all { it.enabled },
            )

            repository.setEnabled("builtin:notion-knowledge", true)
            repository.setEnabled("builtin:tencent-docs-knowledge", true)
            repository.setEnabled("builtin:browser", false)
            val updated = repository.listSkills()

            assertEquals(
                true,
                updated.first { it.name == "Notion Knowledge" }.enabled,
            )
            assertEquals(
                true,
                updated.first { it.name == "Tencent Docs Knowledge" }.enabled,
            )
            assertEquals(
                false,
                updated.first { it.name == "Browser" }.enabled,
            )
            val skills = repository.listEnabledMetadata()
            assertEquals(
                true,
                skills.any { it.name == "notion-knowledge" },
            )
            assertEquals(
                true,
                skills.any { it.name == "tencent-docs-knowledge" },
            )
            assertEquals(
                false,
                skills.any { it.name == "browser" },
            )
            assertEquals(
                listOf("app-assistant"),
                repository.listEnabledMetadata(emptySet()).map { it.name },
            )
            assertEquals(
                listOf("app-assistant", "notion-knowledge"),
                repository.listEnabledMetadata(
                    setOf(
                        "notion_search",
                        "notion_fetch",
                        "notion_create_pages",
                        "notion_update_page",
                    ),
                ).map { it.name },
            )
            assertEquals(
                listOf("app-assistant", "us-stock-analysis"),
                repository.listEnabledMetadata(
                    setOf(
                        "browser_read",
                        "browser_navigate",
                        "browser_scroll",
                        "run_sandboxed_javascript",
                    ),
                ).map { it.name },
            )
            val amapTools = setOf(
                "amap_search_poi",
                "amap_direction",
                "amap_geocoding",
                "amap_reverse_geocoding",
                "amap_weather",
            )
            assertFalse(
                repository.listEnabledMetadata(amapTools).any {
                    it.name == "travel-planning"
                },
            )
            assertTrue(
                repository.listEnabledMetadata(
                    amapTools + setOf(
                        "browser_read",
                        "browser_navigate",
                        "browser_click",
                        "browser_input",
                        "browser_scroll",
                    ),
                ).any { it.name == "travel-planning" },
            )
        }

    @Test
    fun `browser upgrade preserves legacy preferences without duplicate skills and new overrides win`() = runBlocking<Unit> {
        val dao = database.skillDao()
        val repository = RoomSkillRepository(dao)
        repository.setEnabled("builtin:browser", false)
        val template = checkNotNull(dao.getById("builtin:browser"))
        dao.delete(template)
        for (enabled in listOf(false, true)) {
            dao.upsert(
                template.copy(
                    id = "builtin:web-search",
                    name = "Web Search",
                    enabled = enabled,
                    content = "Old search-only instructions",
                ),
            )
            val restored = RoomSkillRepository(dao)
            val skills = restored.listSkills()
            val browser = skills.single { it.id == "builtin:browser" }
            assertEquals(enabled, browser.enabled)
            assertFalse(skills.any { it.id == "builtin:web-search" })
            assertFalse(browser.content.contains("Old search-only instructions"))
            assertTrue(browser.description.contains("Xiaohongshu"))
            assertTrue(browser.content.contains("https://www.xiaohongshu.com/"))
            assertTrue(browser.content.contains("authoritative sources"))
            assertTrue(browser.content.contains("do not retry the same blocked search"))
            assertEquals(
                enabled,
                restored.listEnabledMetadata().any { it.name == "browser" },
            )
            assertFalse(restored.listEnabledMetadata().any { it.name == "web-search" })
        }
        repository.setEnabled("builtin:browser", false)
        assertFalse(
            RoomSkillRepository(dao).listSkills()
                .single { it.id == "builtin:browser" }.enabled,
        )
    }

    @Test
    fun `product cart policy refreshes without changing saved enablement`() = runBlocking {
        val dao = database.skillDao()
        val repository = RoomSkillRepository(dao)
        repository.setEnabled("builtin:product-search", true)
        val stored = checkNotNull(dao.getById("builtin:product-search"))
        dao.upsert(stored.copy(content = "Search/read only; never add to cart"))
        val current = checkNotNull(RoomSkillRepository(dao).loadEnabledSkill("product-search"))
        assertTrue(current.content.contains("user's explicit request"))
        assertTrue(current.content.contains("do not retry an unconfirmed add operation"))
        assertTrue(current.content.contains("order, choose addresses or pay"))
        repository.setEnabled("builtin:product-search", false)
        assertEquals(null, RoomSkillRepository(dao).loadEnabledSkill("product-search"))
    }

    @Test
    fun `browser loads in read-only roles and declares optional tools only when available`() = runBlocking<Unit> {
        val repository = RoomSkillRepository(database.skillDao())
        val reads = setOf("browser_read", "browser_navigate", "browser_scroll")
        val context = ToolExecutionContext(
            currentDate = LocalDate.of(2026, 10, 23),
            currentSurface = MochiSurface.Conversation,
        )
        assertEquals(
            listOf("app-assistant", "browser"),
            repository.listEnabledMetadata(reads).map { it.name },
        )
        val foreground = reads +
            setOf("browser_click", "browser_input", "request_user_assistance")
        val registries = listOf(
            reads to reads,
            (reads + "browser_click") to (reads + "browser_click"),
            foreground to foreground,
            (foreground + "manage_mochi_todo") to foreground,
        )
        for ((available, expected) in registries) {
            val result = LoadSkillTool(repository, available).execute(
                buildJsonObject { put("skill_name", "browser") },
                context,
            )
            assertEquals("ok", result.status)
            val document = checkNotNull(result.data).jsonObject
                .getValue("content").jsonPrimitive.content
            val declared = document.lineSequence()
                .first { it.startsWith("allowed-tools: ") }
                .removePrefix("allowed-tools: ").split(" ").toSet()
            assertEquals(expected, declared)
            assertTrue(document.contains("no model-facing tool"))
            assertTrue(document.contains("Handoff's Accounts"))
            assertTrue(document.contains("is not proof of login failure"))
            assertTrue(document.contains("Cache improves loading, not information freshness"))
        }
        val missing = reads - "browser_read"
        assertFalse(repository.listEnabledMetadata(missing).any { it.name == "browser" })
        assertEquals(
            "error",
            LoadSkillTool(repository, missing).execute(
                buildJsonObject { put("skill_name", "browser") },
                context,
            ).status,
        )
    }

    @Test
    fun `app assistant remains available for setup without granting tools and preserves opt out`() = runBlocking {
        val repository = RoomSkillRepository(database.skillDao())
        val context = ToolExecutionContext(LocalDate.of(2026, 11, 4), MochiSurface.Conversation)
        for (available in listOf(emptySet(), setOf("app_read", "app_click", "media_read", "unrelated_tool"))) {
            assertTrue(repository.listEnabledMetadata(available).any { it.name == "app-assistant" })
            val result = LoadSkillTool(repository, available).execute(
                buildJsonObject { put("skill_name", "app-assistant") }, context,
            )
            assertEquals("ok", result.status)
            val content = checkNotNull(result.data).jsonObject.getValue("content").jsonPrimitive.content
            val declared = content.lineSequence().firstOrNull { it.startsWith("allowed-tools: ") }
                ?.removePrefix("allowed-tools: ")?.split(" ")?.toSet().orEmpty()
            assertEquals(available - "unrelated_tool", declared)
            assertTrue(content.contains("Tools > App Control"))
        }
        repository.setEnabled("builtin:app-assistant", false)
        val restored = RoomSkillRepository(database.skillDao())
        assertFalse(restored.listEnabledMetadata().any { it.name == "app-assistant" })
        assertEquals("NOT_FOUND", LoadSkillTool(restored, emptySet()).execute(
            buildJsonObject { put("skill_name", "app-assistant") }, context,
        ).code)
    }

    @Test
    fun `travel planning preserves the legacy Amap skill enablement`() =
        runBlocking {
            database.skillDao().upsert(
                SkillEntity(
                    id = "builtin:amap-maps",
                    name = "Amap Maps",
                    description = "Legacy",
                    content = "Legacy",
                    source = "Mochi",
                    sourceUrl = "",
                    upstreamVersion = null,
                    upstreamDigest = "legacy",
                    localDigest = "legacy",
                    enabled = false,
                    modified = false,
                    updateAvailable = false,
                    installedAtEpochMillis = 0,
                    updatedAtEpochMillis = 0,
                    lastCheckedAtEpochMillis = null,
                ),
            )

            val skills = RoomSkillRepository(database.skillDao()).listSkills()

            assertFalse(skills.first { it.name == "Travel Planning" }.enabled)
            assertFalse(skills.any { it.id == "builtin:amap-maps" })
        }

    @Test
    fun `retired built in skill overrides are removed`() = runBlocking {
        val retired = SkillEntity(
            id = "builtin:dianping-discovery",
            name = "Dianping Discovery",
            description = "Retired",
            content = "Retired",
            source = "Mochi",
            sourceUrl = "",
            upstreamVersion = null,
            upstreamDigest = "retired",
            localDigest = "retired",
            enabled = true,
            modified = false,
            updateAvailable = false,
            installedAtEpochMillis = 0,
            updatedAtEpochMillis = 0,
            lastCheckedAtEpochMillis = null,
        )
        database.skillDao().upsert(retired)

        val skills = RoomSkillRepository(database.skillDao()).listSkills()

        assertFalse(skills.any { it.id == retired.id })
        assertEquals(null, database.skillDao().getById(retired.id))
    }

    @Test
    fun `load skill exposes standard document only while enabled`() =
        runBlocking {
            val repository = RoomSkillRepository(database.skillDao())
            val tool = LoadSkillTool(
                repository = repository,
                availableToolNames = setOf(
                    "browser_read",
                    "browser_navigate",
                    "browser_click",
                    "browser_input",
                    "browser_scroll",
                    "run_sandboxed_javascript",
                    "manage_mochi_schedule",
                ),
            )
            val context = ToolExecutionContext(
                currentDate = LocalDate.of(2026, 8, 5),
                currentSurface = MochiSurface.Conversation,
            )

            val loaded = tool.execute(
                buildJsonObject { put("skill_name", "browser") },
                context,
            )
            assertEquals("ok", loaded.status)
            assertTrue(loaded.data.toString().contains("name: browser"))

            val productSearch = tool.execute(
                buildJsonObject {
                    put("skill_name", "product-search")
                },
                context,
            )
            assertEquals("ok", productSearch.status)
            assertTrue(productSearch.data.toString().contains("For a supplied product URL"))
            assertTrue(
                productSearch.data.toString().contains(
                    "https://www.bing.com/search?q=",
                ),
            )
            assertTrue(
                productSearch.data.toString().contains(
                    "site:mobile.yangkeduo.com/goods.html",
                ),
            )
            val travelTool = LoadSkillTool(
                repository = repository,
                availableToolNames = setOf(
                    "browser_read",
                    "browser_navigate",
                    "browser_click",
                    "browser_input",
                    "browser_scroll",
                    "amap_search_poi",
                    "amap_direction",
                    "amap_geocoding",
                    "amap_reverse_geocoding",
                    "amap_weather",
                ),
            )
            val travelPlanning = travelTool.execute(
                buildJsonObject {
                    put("skill_name", "travel-planning")
                },
                context,
            )
            assertEquals("ok", travelPlanning.status)
            assertTrue(
                travelPlanning.data.toString().contains(
                    "name: travel-planning",
                ),
            )
            assertTrue(
                travelPlanning.data.toString().contains(
                    "browser_input",
                ),
            )
            assertTrue(
                travelPlanning.data.toString().contains(
                    "amap_direction",
                ),
            )
            assertTrue(
                travelPlanning.data.toString().contains(
                    "https://kyfw.12306.cn/otn/leftTicket/init",
                ),
            )
            assertTrue(
                productSearch.data.toString().contains(
                    "at least two",
                ),
            )

            val douban = tool.execute(
                buildJsonObject { put("skill_name", "douban-ratings") },
                context,
            )
            assertEquals("ok", douban.status)
            assertTrue(
                douban.data.toString().contains(
                    "https://m.douban.com/home_guide",
                ),
            )
            assertTrue(
                douban.data.toString().contains(
                    "https://m.douban.com/search/?query=",
                ),
            )
            assertTrue(
                douban.data.toString().contains(
                    "review themes",
                ),
            )

            val stock = tool.execute(
                buildJsonObject { put("skill_name", "us-stock-analysis") },
                context,
            )
            assertEquals("ok", stock.status)
            assertTrue(stock.data.toString().contains("Visit only the requested ticker URLs"))
            assertTrue(stock.data.toString().contains("For a requested full"))
            assertTrue(stock.data.toString().contains("browser_navigate"))
            assertTrue(
                stock.data.toString().contains(
                    "pqa9p2.smartapps.baidu.com/pages/quote/quote",
                ),
            )
            assertTrue(
                stock.data.toString().contains(
                    "AAPL`, `MSFT`, `AMZN`, `GOOGL`, `META`, `NVDA`, and `TSLA",
                ),
            )
            assertTrue(stock.data.toString().contains("support level"))
            assertTrue(stock.data.toString().contains("resistance level"))
            assertTrue(stock.data.toString().contains("bullish/bearish"))

            val schedules = tool.execute(
                buildJsonObject {
                    put("skill_name", "scheduled-automations")
                },
                context,
            )
            assertEquals("ok", schedules.status)
            assertTrue(
                schedules.data.toString().contains("manage_mochi_schedule"),
            )
            assertTrue(
                schedules.data.toString().contains("same provider"),
            )

            repository.setEnabled("builtin:browser", false)
            val disabled = tool.execute(
                buildJsonObject { put("skill_name", "browser") },
                context,
            )
            assertEquals("error", disabled.status)
        }
}

private fun calendarEvent(
    id: String,
    start: String,
    end: String,
): CalendarEventEntity {
    val now = Instant.parse("2026-07-31T10:00:00Z").toEpochMilli()
    return CalendarEventEntity(
        id = id,
        title = id,
        description = null,
        startAtEpochMillis = Instant.parse(start).toEpochMilli(),
        endAtEpochMillis = Instant.parse(end).toEpochMilli(),
        allDay = false,
        timezoneId = "UTC",
        recurrenceRule = null,
        location = null,
        reminderAtEpochMillis = null,
        createdAtEpochMillis = now,
        updatedAtEpochMillis = now,
    )
}

private fun todo(
    id: String,
    date: LocalDate,
    priority: String,
): TodoEntity {
    val now = Instant.parse("2026-07-31T10:00:00Z").toEpochMilli()
    return TodoEntity(
        id = id,
        content = id,
        status = "ACTIVE",
        priority = priority,
        scheduledDateEpochDay = date.toEpochDay(),
        dueAtEpochMillis = null,
        reminderAtEpochMillis = null,
        completedAtEpochMillis = null,
        createdAtEpochMillis = now,
        updatedAtEpochMillis = now,
    )
}
