package com.example.mochi_pet.core.memory

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.mochi_pet.core.agent.tool.ToolExecutionContext
import com.example.mochi_pet.core.agent.tool.ToolInputException
import com.example.mochi_pet.core.database.MochiDatabase
import com.example.mochi_pet.core.database.entity.AgentMemoryEntity
import com.example.mochi_pet.core.database.entity.DailyJournalEntity
import com.example.mochi_pet.core.model.MochiSurface
import com.example.mochi_pet.core.skills.RoomSkillRepository
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class MemoryRecallTest {
    private lateinit var db: MochiDatabase
    private lateinit var store: RoomMemoryRecallStore
    private val context = ToolExecutionContext(LocalDate.of(2026, 11, 11), MochiSurface.Conversation)
    private val day = "2026-11-09"
    private val base = Instant.parse("2026-11-09T12:00:00Z").toEpochMilli()

    @Before fun setup() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), MochiDatabase::class.java).build()
        store = RoomMemoryRecallStore(db, Clock.fixed(Instant.parse("2026-11-11T00:00:00Z"), ZoneOffset.UTC)) {
            ZoneId.of("Asia/Shanghai")
        }
    }
    @After fun close() { db.close() }

    @Test fun `search includes recent matches and filters time before limiting with explicit continuation`() = runBlocking {
        db.agentMemoryDao().insertAll(listOf(
            memory("old", "old", "杭州旅行", base - 86_400_000),
            memory("a", "turn", "确定杭州旅行", base),
            memory("b", "turn", "杭州旅行已安排", base + 1),
            memory("new", "new", "杭州旅行", base + 86_400_000),
            memory("boundary-start", "boundary", "boundary", Instant.parse("2026-11-08T16:00:00Z").toEpochMilli()),
            memory("boundary-end", "boundary-end", "boundary", Instant.parse("2026-11-09T16:00:00Z").toEpochMilli()),
        ))
        val request = RecallRequest("search", "conversations", "杭州旅行", day, "2026-11-10", limit = 1)
        val first = store.search(request)
        assertEquals("conversation:b", first.records.single().ref)
        assertEquals(1, first.nextOffset)
        val second = store.search(request.copy(offset = first.nextOffset!!))
        assertEquals("conversation:a", second.records.single().ref)
        assertNull(second.nextOffset)
        assertTrue(store.search(request.copy(query = "不存在")).records.isEmpty())
        assertEquals("any_term", store.search(request.copy(query = "杭州 航班")).matching)
        assertEquals("conversation:boundary-start", store.search(request.copy(query = "boundary")).records.single().ref)
    }

    @Test fun `journal publication replacement and deletion synchronize FTS and retain frozen day windows`() = runBlocking {
        val dao = db.journalDao()
        dao.setEnabled(true, day, 1)
        dao.insert(DailyJournalEntity(day, "America/New_York", "WAITING", "", "", null, 1, null))
        dao.claim(day, "first", 2)
        dao.publish(day, "first", "READY", "旅行", "决定去杭州", 3, 2, 0, 0)
        val search = RecallRequest("search", query = "杭州")
        val entry = store.search(search).records.single()
        assertEquals("2026-11-09T05:00:00Z", entry.windowStart)
        assertEquals("2026-11-10T05:00:00Z", entry.windowEnd)
        dao.retry(day, 4)
        dao.claim(day, "second", 5)
        dao.finish(day, "second", "FAILED", "旅行", "决定去杭州", "network", 6)
        assertEquals(entry.ref, store.search(search).records.single().ref)
        dao.retry(day, 7)
        dao.claim(day, "third", 8)
        dao.publish(day, "third", "READY", "旅行", "改去苏州", 9, 2, 0, 0)
        assertTrue(store.search(search).records.isEmpty())
        val changed = runCatching { store.read(RecallRequest("read", ref = entry.ref)) }.exceptionOrNull()
        assertTrue(changed is RecallUnavailable && changed.changed)
        assertEquals(1, store.search(search.copy(query = "苏州")).records.size)
        dao.delete(day, 10)
        assertTrue(store.search(search.copy(query = "苏州")).records.isEmpty())
        assertEquals(0, dao.publish(day, "third", "READY", "旅行", "杭州", 11, 2, 0, 0))
        assertTrue(store.search(search).records.isEmpty())
    }

    @Test fun `reads complete turns and adjacent turns while preserving origin and lossless text continuation`() = runBlocking {
        val long = "a".repeat(5999) + "😀" + "travel ".repeat(300)
        val rows = listOf(
            memory("a", "before", "Earlier user", base),
            memory("b", "before", "Earlier answer", base + 1),
            memory("c", "selected", long, base + 2),
            memory("d", "selected", "Saved result", base + 3).copy(sourceRunId = "scheduled-run"),
            memory("e", "after", "Later user", base + 4),
            memory("f", "after", "Later answer", base + 5),
        )
        db.agentMemoryDao().insertAll(rows)
        var request = RecallRequest("read", ref = "conversation:c")
        val collected = mutableListOf<RecallRecord>()
        do {
            val page = store.read(request)
            assertTrue(page.records.sumOf { it.text.length } <= 6000)
            collected += page.records
            request = request.copy(offset = page.nextOffset ?: 0)
        } while (page.nextOffset != null)
        assertEquals(rows.joinToString("") { it.content }, collected.joinToString("") { it.text })
        assertEquals("scheduled_task_copy", collected.single { it.ref == "conversation:d" }.origin)
        assertEquals("scheduled-run", collected.single { it.ref == "conversation:d" }.sourceRunId)
        val page = store.read(request.copy(neighbors = 0))
        assertEquals(5999, page.nextOffset)
        assertFalse(page.records.single().text.last().isHighSurrogate())
        assertEquals(setOf("conversation:c", "conversation:d"),
            store.read(request.copy(neighbors = 0, offset = requireNotNull(page.nextOffset))).records.map { it.ref }.toSet())
    }

    @Test fun `tool and skill share settings and discard in flight data after disable and reenable`() = runBlocking {
        val dao = db.journalDao()
        val skills = RoomSkillRepository(db.skillDao(), journalEnabled = { store.access().enabled })
        assertEquals(MEMORY_RECALL_SKILL, skills.listSkills().first().id)
        assertNull(skills.loadEnabledSkill("memory-recall"))
        val args = buildJsonObject { put("operation", "search"); put("query", "杭州") }
        assertEquals("PERMISSION_DENIED", RecallMemoryTool(store).execute(args, context).code)
        dao.setEnabled(true, day, 1)
        assertNotNull(skills.loadEnabledSkill("memory-recall"))
        assertTrue(skills.listEnabledMetadata(emptySet()).none { it.name == "memory-recall" })
        assertTrue(skills.listEnabledMetadata(setOf(RECALL_MEMORY_TOOL)).any { it.name == "memory-recall" })
        assertTrue(runCatching { skills.setEnabled(MEMORY_RECALL_SKILL, false) }.isFailure)
        val revoking = object : MemoryRecallStore by store {
            override suspend fun search(request: RecallRequest): RecallResult {
                val result = store.search(request)
                dao.setEnabled(false, day, 2)
                dao.setEnabled(true, day, 3)
                return result
            }
        }
        val result = RecallMemoryTool(revoking).execute(args, context)
        assertEquals("PERMISSION_DENIED", result.code)
        assertNull(result.data)
        for (invalid in listOf(
            buildJsonObject { put("operation", "search") },
            buildJsonObject { put("operation", "search"); put("query", "杭州"); put("limit", 11) },
            buildJsonObject { put("operation", "search"); put("query", "杭州"); put("source", "unknown") },
            buildJsonObject { put("operation", "read"); put("ref", "conversation:x"); put("query", "ignored") },
        )) assertTrue(runCatching { RecallMemoryTool(store).execute(invalid, context) }.exceptionOrNull() is ToolInputException)
    }

    private fun memory(id: String, turn: String, text: String, time: Long) =
        AgentMemoryEntity(id, turn, if (id in setOf("b", "d", "f")) "assistant" else "user",
            if (id in setOf("b", "d", "f")) "assistant_msg" else "user_msg",
            text, MemoryLexicalSearch.searchableText(text), time)
}
