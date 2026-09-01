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
import java.time.Instant
import java.time.LocalDate
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
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
        assertEquals(12, skills.size)
        assertEquals(
            listOf("Web Search"),
            skills.filter { it.id.contains("web-search") }.map { it.name },
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
            assertEquals(true, initial.first { it.name == "Amap Maps" }.enabled)
            assertEquals(
                true,
                initial.first { it.name == "Merchant Discovery" }.enabled,
            )
            assertEquals(
                true,
                initial.filter {
                    it.origin == SkillOrigin.BUILT_IN &&
                        !it.name.endsWith("Knowledge")
                }.all { it.enabled },
            )

            repository.setEnabled("builtin:notion-knowledge", true)
            repository.setEnabled("builtin:tencent-docs-knowledge", true)
            repository.setEnabled("builtin:web-search", false)
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
                updated.first { it.name == "Web Search" }.enabled,
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
                skills.any { it.name == "web-search" },
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
                buildJsonObject { put("skill_name", "web-search") },
                context,
            )
            assertEquals("ok", loaded.status)
            assertTrue(loaded.data.toString().contains("name: web-search"))

            val productSearch = tool.execute(
                buildJsonObject {
                    put("skill_name", "product-search")
                },
                context,
            )
            assertEquals("ok", productSearch.status)
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

            repository.setEnabled("builtin:web-search", false)
            val disabled = tool.execute(
                buildJsonObject { put("skill_name", "web-search") },
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
