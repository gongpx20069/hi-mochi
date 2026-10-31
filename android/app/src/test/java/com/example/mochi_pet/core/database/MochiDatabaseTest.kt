package com.example.mochi_pet.core.database

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.mochi_pet.core.agent.tool.ToolExecutionContext
import com.example.mochi_pet.core.database.entity.CalendarEventEntity
import com.example.mochi_pet.core.database.entity.SkillEntity
import com.example.mochi_pet.core.database.entity.TodoEntity
import com.example.mochi_pet.core.model.MochiSurface
import com.example.mochi_pet.core.skills.DownloadedSkill
import com.example.mochi_pet.core.skills.LoadSkillTool
import com.example.mochi_pet.core.skills.RoomSkillRepository
import com.example.mochi_pet.core.skills.SkillOrigin
import com.example.mochi_pet.core.skills.requiredToolNames
import com.example.mochi_pet.core.skills.readiness
import com.example.mochi_pet.core.tools.ToolCatalogSummary
import com.example.mochi_pet.core.tools.McpServerSummary
import com.example.mochi_pet.core.tools.McpToolSummary
import com.example.mochi_pet.core.tools.McpAuthMode
import com.example.mochi_pet.core.tools.skillReadiness
import java.time.Instant
import java.time.LocalDate
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
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
        assertEquals(17, skills.size)
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
        val repository = RoomSkillRepository(database.skillDao())
        val context = ToolExecutionContext(LocalDate.of(2026, 11, 1), MochiSurface.Conversation)
        for (initial in repository.listSkills().filter { it.requiredToolNames.isNotEmpty() }) {
            val skill = repository.setEnabled(initial.id, true)
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
            assertEquals(listOf("termux"), repository.listEnabledMetadata(termux.requiredToolNames).map { it.name })
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
            assertTrue(
                repository.listEnabledMetadata(emptySet()).isEmpty(),
            )
            assertEquals(
                listOf("notion-knowledge"),
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
                listOf("us-stock-analysis"),
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
    fun `browser loads in read-only roles and declares optional tools only when available`() = runBlocking<Unit> {
        val repository = RoomSkillRepository(database.skillDao())
        val reads = setOf("browser_read", "browser_navigate", "browser_scroll")
        val context = ToolExecutionContext(
            currentDate = LocalDate.of(2026, 10, 23),
            currentSurface = MochiSurface.Conversation,
        )
        assertEquals(
            listOf("browser"),
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
            assertTrue(document.contains("currently has no saved-account picker"))
            assertTrue(document.contains("Cache improves loading, not information freshness"))
        }
        val missing = reads - "browser_read"
        assertTrue(repository.listEnabledMetadata(missing).isEmpty())
        assertEquals(
            "error",
            LoadSkillTool(repository, missing).execute(
                buildJsonObject { put("skill_name", "browser") },
                context,
            ).status,
        )
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
